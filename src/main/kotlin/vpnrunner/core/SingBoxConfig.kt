package vpnrunner.core

/**
 * Генерирует конфиг sing-box (формат 1.12+):
 *  - TUN перехватывает весь трафик системы;
 *  - рабочие подсети Cisco исключены из TUN — ими занимается openconnect;
 *  - российские сайты и пользовательские DIRECT-правила идут напрямую;
 *  - PROXY-правила и всё остальное — через выбранный сервер (или авто).
 */
object SingBoxConfig {

    const val CLASH_API = "127.0.0.1:9090"
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
    ): Map<String, Any?> {
        require(servers.isNotEmpty()) { "В подписках нет ни одного поддерживаемого сервера" }

        val directDomains = settings.rules.filter { it.mode == RuleMode.DIRECT }.map { normalizeDomain(it.domain) }.filter { it.isNotEmpty() }
        val proxyDomains = settings.rules.filter { it.mode == RuleMode.PROXY }.map { normalizeDomain(it.domain) }.filter { it.isNotEmpty() }

        val workDomains = if (cisco != null) settings.cisco.workDomains.map { normalizeDomain(it) }.filter { it.isNotEmpty() } else emptyList()
        val workSubnets = if (cisco != null) {
            (settings.cisco.workSubnets.ifEmpty { cisco.splitRoutes } + cisco.dnsServers.map { "$it/32" })
                .map { it.trim() }.filter { it.isNotEmpty() && !it.endsWith("/0") }.distinct()
        } else emptyList()

        // ---- outbounds ----
        val serverTags = servers.map { tagFor(it) }
        val outbounds = ArrayList<Any?>()
        servers.forEach { s -> outbounds.add(LinkedHashMap(s.outbound).apply { put("tag", tagFor(s)) }) }
        outbounds.add(linkedMapOf(
            "type" to "urltest", "tag" to AUTO_TAG, "outbounds" to serverTags,
            "url" to "https://www.gstatic.com/generate_204", "interval" to "3m", "tolerance" to 100,
        ))
        val selected = servers.firstOrNull { it.id == settings.selectedServer }?.let { tagFor(it) } ?: AUTO_TAG
        outbounds.add(linkedMapOf(
            "type" to "selector", "tag" to PROXY_TAG, "outbounds" to listOf(AUTO_TAG) + serverTags,
            "default" to selected, "interrupt_exist_connections" to true,
        ))
        outbounds.add(linkedMapOf("type" to "direct", "tag" to DIRECT_TAG))

        // ---- DNS ----
        val dnsServers = arrayListOf<Any?>(
            linkedMapOf("type" to "https", "tag" to "dns-remote", "server" to "1.1.1.1", "detour" to PROXY_TAG),
            linkedMapOf("type" to "udp", "tag" to "dns-direct", "server" to "77.88.8.8"),
        )
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
        if (settings.russiaDirect) {
            dnsRules.add(linkedMapOf("domain_suffix" to RU_SUFFIXES, "server" to "dns-direct"))
            dnsRules.add(linkedMapOf("rule_set" to listOf("geosite-ru"), "server" to "dns-direct"))
        }

        // ---- route ----
        val routeRules = arrayListOf<Any?>(
            linkedMapOf("action" to "sniff"),
            linkedMapOf("protocol" to "dns", "action" to "hijack-dns"),
            linkedMapOf("ip_is_private" to true, "outbound" to DIRECT_TAG),
        )
        if (workSubnets.isNotEmpty()) routeRules.add(linkedMapOf("ip_cidr" to workSubnets, "outbound" to DIRECT_TAG))
        if (workDomains.isNotEmpty()) routeRules.add(linkedMapOf("domain_suffix" to workDomains, "outbound" to DIRECT_TAG))
        // Адреса подписок — всегда через VPN: провайдеры их часто блокируют, а .ru иначе ушёл бы напрямую.
        val subHosts = settings.subscriptions.mapNotNull { runCatching { java.net.URI(it.url.trim()).host?.lowercase() }.getOrNull() }.distinct()
        if (subHosts.isNotEmpty()) routeRules.add(linkedMapOf("domain" to subHosts, "outbound" to PROXY_TAG))
        if (proxyDomains.isNotEmpty()) routeRules.add(linkedMapOf("domain_suffix" to proxyDomains, "outbound" to PROXY_TAG))
        if (directDomains.isNotEmpty()) routeRules.add(linkedMapOf("domain_suffix" to directDomains, "outbound" to DIRECT_TAG))
        val ruleSets = ArrayList<Any?>()
        if (settings.russiaDirect) {
            routeRules.add(linkedMapOf("domain_suffix" to RU_SUFFIXES, "outbound" to DIRECT_TAG))
            routeRules.add(linkedMapOf("rule_set" to listOf("geosite-ru", "geoip-ru"), "outbound" to DIRECT_TAG))
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
        if (workSubnets.isNotEmpty()) tun["route_exclude_address"] = workSubnets

        val route = linkedMapOf<String, Any?>(
            "rules" to routeRules,
            "final" to PROXY_TAG,
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
