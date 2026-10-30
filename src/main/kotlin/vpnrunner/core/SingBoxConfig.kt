package vpnrunner.core

/**
 * Генерирует конфиг sing-box (формат 1.12+):
 *  - TUN перехватывает весь трафик системы;
 *  - рабочие подсети Cisco исключены из TUN — ими занимается openconnect;
 *  - российские сайты и пользовательские DIRECT-правила идут напрямую;
 *  - PROXY-правила и всё остальное — через выбранный сервер (или авто).
 * Из-за рубежа (abroad != null) вместо зарубежных серверов — российские: через них российские сайты
 * и (allViaRu) всё остальное; DNS — только зашифрованный, см. Abroad.
 */
object SingBoxConfig {

    const val CLASH_API = "127.0.0.1:9090"
    /** sing-box-«цепочка» из-за рубежа: через этот прокси (только он) openconnect ходит к серверу Cisco. */
    const val CHAIN_PORT = 2081
    const val CHAIN_CLASH_API = "127.0.0.1:9091"
    const val PROXY_TAG = "proxy"
    const val AUTO_TAG = "auto"
    const val DIRECT_TAG = "direct"

    /** Адрес TUN sing-box и DNS внутри него: этот DNS система получает как основной (см. супервизор на macOS). */
    const val TUN_ADDRESS = "172.19.0.1"
    const val TUN_DNS = "172.19.0.2"

    private val RU_SUFFIXES = listOf("ru", "su", "xn--p1ai" /* .рф */, "xn--d1acj3b" /* .дети */, "moscow", "tatar")

    /** Списки российских сайтов и IP: тег → адрес. */
    val RULE_SETS = linkedMapOf(
        "geosite-ru" to "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ru.srs",
        "geoip-ru" to "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-ru.srs",
    )

    fun tagFor(server: Server) = "${server.subscription} | ${server.name}"

    fun build(
        settings: AppSettings,
        servers: List<Server>,
        cisco: CiscoState?,
        cacheDir: String,
        /** Уже скачанные списки (тег → путь к .srs). Чего нет — sing-box скачает сам через VPN. */
        localRuleSets: Map<String, String> = emptyMap(),
        /** Режим «из-за рубежа»: servers — российские серверы. */
        abroad: Abroad.Plan? = null,
    ): Map<String, Any?> {
        require(servers.isNotEmpty()) { "В подписках нет ни одного поддерживаемого сервера" }
        // Из-за рубежа российские сайты идут через российский VPN; когда через него и так всё — их списки не нужны.
        val ruRules = if (abroad != null) !abroad.allViaRu else settings.russiaDirect
        val ruOutbound = if (abroad != null) PROXY_TAG else DIRECT_TAG

        val directDomains = settings.rules.filter { it.mode == RuleMode.DIRECT }.map { normalizeDomain(it.domain) }.filter { it.isNotEmpty() }
        val proxyDomains = settings.rules.filter { it.mode == RuleMode.PROXY }.map { normalizeDomain(it.domain) }.filter { it.isNotEmpty() }

        val workDomains = if (cisco != null) settings.cisco.workDomains.map { normalizeDomain(it) }.filter { it.isNotEmpty() } else emptyList()
        val workSubnets = if (cisco != null) {
            (settings.cisco.workSubnets.ifEmpty { cisco.splitRoutes } + cisco.dnsServers.map { "$it/32" })
                .map { it.trim() }.filter { it.isNotEmpty() && !it.endsWith("/0") }.distinct()
        } else emptyList()

        // ---- outbounds ----
        val selectedId = if (abroad != null) settings.abroad.ruServer else settings.selectedServer
        val outbounds = serverOutbounds(servers, selectedId, abroad?.pinned.orEmpty())
        outbounds.add(linkedMapOf("type" to "direct", "tag" to DIRECT_TAG))

        // ---- DNS ----
        val dnsServers = arrayListOf<Any?>(
            linkedMapOf("type" to "https", "tag" to "dns-remote", "server" to "1.1.1.1", "detour" to PROXY_TAG),
            // Из-за рубежа — зашифрованный: местный провайдер не видит имён (и что вы ходите в VPN).
            if (abroad != null) linkedMapOf("type" to "https", "tag" to "dns-direct", "server" to "1.1.1.1")
            else linkedMapOf("type" to "udp", "tag" to "dns-direct", "server" to "77.88.8.8"),
        )
        pinnedDns(abroad?.pinned.orEmpty())?.let { dnsServers.add(it) }
        val dnsRules = ArrayList<Any?>()
        val hasWorkDns = cisco != null && cisco.dnsServers.isNotEmpty()
        if (hasWorkDns) {
            // Через интерфейс Cisco: иначе auto_detect_interface отправит запрос мимо туннеля.
            dnsServers.add(linkedMapOf<String, Any?>("type" to "udp", "tag" to "dns-work", "server" to cisco!!.dnsServers.first())
                .apply { if (cisco.tunDev.isNotBlank()) put("bind_interface", cisco.tunDev) })
            // DNS Cisco спрашиваем только про рабочие домены: свои из вкладки Cisco и те, что прислал сервер.
            // Остальные имена до компании не доходят.
            val workDnsDomains = (workDomains + cisco.domains.map { normalizeDomain(it) }).filter { it.isNotEmpty() }.distinct()
            if (workDnsDomains.isNotEmpty()) dnsRules.add(linkedMapOf("domain_suffix" to workDnsDomains, "server" to "dns-work"))
        }
        val subHostsDns = settings.subscriptions.mapNotNull { runCatching { java.net.URI(it.url.trim()).host?.lowercase() }.getOrNull() }.distinct()
        if (subHostsDns.isNotEmpty()) dnsRules.add(linkedMapOf("domain" to subHostsDns, "server" to "dns-remote"))
        if (proxyDomains.isNotEmpty()) dnsRules.add(linkedMapOf("domain_suffix" to proxyDomains, "server" to "dns-remote"))
        if (directDomains.isNotEmpty()) dnsRules.add(linkedMapOf("domain_suffix" to directDomains, "server" to "dns-direct"))
        if (ruRules) {
            val ruDns = if (abroad != null) "dns-remote" else "dns-direct"
            dnsRules.add(linkedMapOf("domain_suffix" to RU_SUFFIXES, "server" to ruDns))
            dnsRules.add(linkedMapOf("rule_set" to listOf("geosite-ru"), "server" to ruDns))
        }

        // ---- route ----
        val routeRules = arrayListOf<Any?>(
            linkedMapOf("action" to "sniff"),
            linkedMapOf("protocol" to "dns", "action" to "hijack-dns"),
            linkedMapOf("ip_is_private" to true, "outbound" to DIRECT_TAG),
        )
        // Российские серверы мимо TUN (route_exclude_address): если что-то всё же попало сюда — тоже напрямую, без VPN в VPN.
        val ruIps = abroad?.ruIps.orEmpty().map { "$it/32" }
        if (ruIps.isNotEmpty()) routeRules.add(linkedMapOf("ip_cidr" to ruIps, "outbound" to DIRECT_TAG))
        if (workSubnets.isNotEmpty()) routeRules.add(linkedMapOf("ip_cidr" to workSubnets, "outbound" to DIRECT_TAG))
        if (workDomains.isNotEmpty()) routeRules.add(linkedMapOf("domain_suffix" to workDomains, "outbound" to DIRECT_TAG))
        // Адреса подписок — всегда через VPN: провайдеры их часто блокируют, а .ru иначе ушёл бы напрямую.
        val subHosts = settings.subscriptions.mapNotNull { runCatching { java.net.URI(it.url.trim()).host?.lowercase() }.getOrNull() }.distinct()
        if (subHosts.isNotEmpty()) routeRules.add(linkedMapOf("domain" to subHosts, "outbound" to PROXY_TAG))
        if (proxyDomains.isNotEmpty()) routeRules.add(linkedMapOf("domain_suffix" to proxyDomains, "outbound" to PROXY_TAG))
        if (directDomains.isNotEmpty()) routeRules.add(linkedMapOf("domain_suffix" to directDomains, "outbound" to DIRECT_TAG))
        val ruleSets = ArrayList<Any?>()
        if (ruRules) {
            routeRules.add(linkedMapOf("domain_suffix" to RU_SUFFIXES, "outbound" to ruOutbound))
            routeRules.add(linkedMapOf("rule_set" to listOf("geosite-ru", "geoip-ru"), "outbound" to ruOutbound))
            RULE_SETS.forEach { (tag, url) ->
                ruleSets.add(localRuleSets[tag]?.let { localRuleSet(tag, it) } ?: remoteRuleSet(tag, url))
            }
        }

        val tun = linkedMapOf<String, Any?>(
            "type" to "tun", "tag" to "tun-in",
            "address" to listOf("$TUN_ADDRESS/30"),
            "mtu" to 1500,
            "auto_route" to true,
            "strict_route" to false, // true ломает маршруты Cisco на Windows
            "stack" to "mixed",
        )
        // На macOS имя может быть только utunN — sing-box сам возьмёт свободный (utun с Cisco уже занят).
        if (System.getProperty("os.name").lowercase().contains("win")) tun["interface_name"] = "VpnRunner"
        (workSubnets + ruIps).takeIf { it.isNotEmpty() }?.let { tun["route_exclude_address"] = it }

        val route = linkedMapOf<String, Any?>(
            "rules" to routeRules,
            "final" to if (abroad != null && !abroad.allViaRu) DIRECT_TAG else PROXY_TAG,
            "auto_detect_interface" to true,
            "default_domain_resolver" to "dns-direct",
        )
        if (ruleSets.isNotEmpty()) route["rule_set"] = ruleSets

        return linkedMapOf(
            "log" to linkedMapOf("level" to "info", "timestamp" to true),
            "dns" to linkedMapOf(
                "servers" to dnsServers,
                "rules" to dnsRules,
                "final" to "dns-remote",
                "strategy" to "ipv4_only",
            ),
            "inbounds" to listOf(
                tun,
                // Локальный прокси — на случай, если какой-то программе нужно указать прокси вручную.
                linkedMapOf("type" to "mixed", "tag" to "mixed-in", "listen" to "127.0.0.1", "listen_port" to 2080),
            ),
            "outbounds" to outbounds,
            "route" to route,
            "experimental" to linkedMapOf(
                "cache_file" to linkedMapOf("enabled" to true, "path" to "$cacheDir/cache.db"),
                "clash_api" to linkedMapOf("external_controller" to CLASH_API),
            ),
        )
    }

    /**
     * sing-box-«цепочка» (режим «из-за рубежа»): только прокси 127.0.0.1:$CHAIN_PORT → российский сервер, без TUN.
     * Через него подключается openconnect. Имя сервера Cisco резолвит уже российский сервер — локальный DNS
     * о нём не узнаёт.
     */
    fun buildChain(settings: AppSettings, ruServers: List<Server>, pinned: Map<String, List<String>>): Map<String, Any?> {
        require(ruServers.isNotEmpty()) { "Нет российских серверов" }
        val dnsServers = arrayListOf<Any?>(
            linkedMapOf("type" to "https", "tag" to "dns-remote", "server" to "1.1.1.1", "detour" to PROXY_TAG),
        )
        pinnedDns(pinned)?.let { dnsServers.add(it) }
        // Мимо TUN основного sing-box, даже если адрес сервера сменился и не попал в его исключения
        val route = linkedMapOf<String, Any?>("final" to PROXY_TAG, "auto_detect_interface" to true)
        if (pinned.isNotEmpty()) route["default_domain_resolver"] = "dns-pin"
        return linkedMapOf(
            "log" to linkedMapOf("level" to "info", "timestamp" to true),
            "dns" to linkedMapOf("servers" to dnsServers, "final" to "dns-remote", "strategy" to "ipv4_only"),
            "inbounds" to listOf(linkedMapOf("type" to "mixed", "tag" to "chain-in", "listen" to "127.0.0.1", "listen_port" to CHAIN_PORT)),
            "outbounds" to serverOutbounds(ruServers, settings.abroad.ruServer, pinned),
            "route" to route,
            "experimental" to linkedMapOf("clash_api" to linkedMapOf("external_controller" to CHAIN_CLASH_API)),
        )
    }

    /** Серверы + «авто» (самый быстрый) + переключатель PROXY_TAG. */
    private fun serverOutbounds(servers: List<Server>, selectedId: String, pinned: Map<String, List<String>>): ArrayList<Any?> {
        val serverTags = servers.map { tagFor(it) }
        val outbounds = ArrayList<Any?>()
        servers.forEach { s ->
            outbounds.add(LinkedHashMap(s.outbound).apply {
                put("tag", tagFor(s))
                // Адрес сервера — из заранее найденных, а не через DNS (который сам идёт через этот сервер)
                if ((get("server") as? String)?.let { it in pinned } == true) put("domain_resolver", "dns-pin")
            })
        }
        outbounds.add(linkedMapOf(
            "type" to "urltest", "tag" to AUTO_TAG, "outbounds" to serverTags,
            "url" to "https://www.gstatic.com/generate_204", "interval" to "3m", "tolerance" to 100,
        ))
        val selected = servers.firstOrNull { it.id == selectedId }?.let { tagFor(it) } ?: AUTO_TAG
        outbounds.add(linkedMapOf(
            "type" to "selector", "tag" to PROXY_TAG, "outbounds" to listOf(AUTO_TAG) + serverTags,
            "default" to selected, "interrupt_exist_connections" to true,
        ))
        return outbounds
    }

    private fun pinnedDns(pinned: Map<String, List<String>>) =
        if (pinned.isEmpty()) null else linkedMapOf("type" to "hosts", "tag" to "dns-pin", "predefined" to pinned)

    private fun localRuleSet(tag: String, path: String) = linkedMapOf(
        "type" to "local", "tag" to tag, "format" to "binary", "path" to path,
    )

    private fun remoteRuleSet(tag: String, url: String) = linkedMapOf(
        "type" to "remote", "tag" to tag, "format" to "binary", "url" to url,
        "download_detour" to PROXY_TAG, "update_interval" to "3d",
    )

    /** "https://www.Example.com/path" → "example.com"; кириллица → punycode. */
    fun normalizeDomain(input: String): String {
        var d = input.trim().lowercase()
        d = d.substringAfter("://").substringBefore('/').substringBefore(':')
        d = d.removePrefix("*.").removePrefix(".").removePrefix("www.")
        if (d.isEmpty()) return ""
        return runCatching { java.net.IDN.toASCII(d) }.getOrDefault(d)
    }
}
