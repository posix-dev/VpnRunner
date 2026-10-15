package vpnrunner.core

/**
 * Подписка в формате Xray JSON: массив полных конфигов Xray (или один конфиг).
 * Из каждого конфига берутся outbound'ы с поддерживаемыми протоколами и переводятся в формат sing-box.
 * Имя сервера — поле "remarks" конфига.
 */
object XrayJson {

    data class Parsed(val servers: List<SubscriptionParser.Result2>, val skipped: List<String>)

    fun parse(text: String): Parsed {
        val root = Json.parse(text)
        val configs: List<Map<String, Any?>> = when (root) {
            is List<*> -> root.map { it.obj() }
            is Map<*, *> -> listOf(root.obj())
            else -> emptyList()
        }
        val servers = ArrayList<SubscriptionParser.Result2>()
        val skipped = ArrayList<String>()
        configs.forEachIndexed { i, cfg ->
            val remarks = cfg.str("remarks")?.takeIf { it.isNotBlank() } ?: "Сервер ${i + 1}"
            val outs = cfg["outbounds"].arr().map { it.obj() }
                .filter { (it.str("protocol") ?: "") !in setOf("freedom", "blackhole", "dns", "loopback") }
            outs.forEachIndexed { j, ob ->
                val name = if (outs.size > 1) "$remarks ${j + 1}" else remarks
                runCatching { convert(ob) }
                    .onSuccess { servers += SubscriptionParser.Result2(name, it) }
                    .onFailure { skipped += "${ob.str("protocol") ?: "?"}: ${it.message}" }
            }
        }
        return Parsed(servers, skipped)
    }

    /** Один outbound Xray → outbound sing-box. */
    fun convert(ob: Map<String, Any?>): Map<String, Any?> {
        val protocol = ob.str("protocol")?.lowercase() ?: throw IllegalArgumentException("нет протокола")
        val settings = ob["settings"].obj()
        val stream = ob["streamSettings"].obj()

        val o = linkedMapOf<String, Any?>()
        val host: String
        when (protocol) {
            "vless", "vmess" -> {
                val vnext = settings["vnext"].arr().firstOrNull().obj()
                val user = vnext["users"].arr().firstOrNull().obj()
                host = vnext.str("address") ?: throw IllegalArgumentException("нет адреса")
                o["type"] = protocol
                o["server"] = host
                o["server_port"] = port(vnext)
                o["uuid"] = user.str("id") ?: throw IllegalArgumentException("нет id")
                if (protocol == "vless") {
                    user.str("flow")?.takeIf { it.isNotBlank() }?.let { o["flow"] = it.removeSuffix("-udp443") }
                } else {
                    o["security"] = user.str("security")?.takeIf { it.isNotBlank() } ?: "auto"
                    o["alter_id"] = user.str("alterId")?.toIntOrNull() ?: 0
                }
                o["packet_encoding"] = "xudp"
            }
            "trojan" -> {
                val srv = settings["servers"].arr().firstOrNull().obj()
                host = srv.str("address") ?: throw IllegalArgumentException("нет адреса")
                o["type"] = "trojan"; o["server"] = host; o["server_port"] = port(srv)
                o["password"] = srv.str("password") ?: ""
            }
            "shadowsocks" -> {
                val srv = settings["servers"].arr().firstOrNull().obj()
                host = srv.str("address") ?: throw IllegalArgumentException("нет адреса")
                o["type"] = "shadowsocks"; o["server"] = host; o["server_port"] = port(srv)
                o["method"] = srv.str("method") ?: ""
                o["password"] = srv.str("password") ?: ""
                return o // у shadowsocks нет TLS/транспорта
            }
            else -> throw IllegalArgumentException("протокол $protocol не поддерживается")
        }

        // TLS / Reality: переводим в те же параметры, что у ссылок, и используем общий код.
        val q = HashMap<String, String>()
        val security = stream.str("security")?.lowercase() ?: "none"
        q["security"] = security
        when (security) {
            "reality" -> {
                val r = stream["realitySettings"].obj()
                r.str("serverName")?.let { q["sni"] = it }
                r.str("publicKey")?.let { q["pbk"] = it }
                r.str("shortId")?.let { q["sid"] = it }
                r.str("fingerprint")?.let { q["fp"] = it }
            }
            "tls" -> {
                val t = stream["tlsSettings"].obj()
                t.str("serverName")?.let { q["sni"] = it }
                t.str("fingerprint")?.let { q["fp"] = it }
                if (t.bool("allowInsecure")) q["allowInsecure"] = "1"
                t["alpn"].arr().mapNotNull { it?.toString() }.takeIf { it.isNotEmpty() }?.let { q["alpn"] = it.joinToString(",") }
            }
        }

        val network = stream.str("network")?.lowercase() ?: "tcp"
        when (network) {
            "ws" -> {
                val ws = stream["wsSettings"].obj()
                ws.str("path")?.let { q["path"] = it }
                (ws.str("host") ?: ws["headers"].obj().str("Host"))?.let { q["host"] = it }
            }
            "grpc" -> stream["grpcSettings"].obj().str("serviceName")?.let { q["serviceName"] = it }
            "httpupgrade" -> {
                val hu = stream["httpupgradeSettings"].obj()
                hu.str("path")?.let { q["path"] = it }
                hu.str("host")?.let { q["host"] = it }
            }
            "h2", "http" -> {
                val h = stream["httpSettings"].obj()
                h.str("path")?.let { q["path"] = it }
                h["host"].arr().mapNotNull { it?.toString() }.takeIf { it.isNotEmpty() }?.let { q["host"] = it.joinToString(",") }
            }
            "tcp", "raw" -> {
                val header = (stream["tcpSettings"].obj().ifEmpty { stream["rawSettings"].obj() })["header"].obj().str("type")
                if (header != null) q["headerType"] = header
            }
        }

        SubscriptionParser.tls(q, host, defaultEnabled = protocol == "trojan")?.let { o["tls"] = it }
        SubscriptionParser.transport(network, q)?.let { o["transport"] = it }
        return o
    }

    private fun port(m: Map<String, Any?>): Int =
        m.str("port")?.toIntOrNull() ?: throw IllegalArgumentException("нет порта")
}
