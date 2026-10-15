package vpnrunner.core

import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * Скачивает подписку и превращает ссылки vless:// vmess:// trojan:// ss:// hysteria2://
 * в outbound'ы sing-box.
 */
object SubscriptionParser {

    data class Result(val servers: List<Server>, val skipped: List<String>)

    /** User-Agent, на который панели (Remnawave/Marzban/3x-ui) отдают обычный base64-список ссылок. */
    private const val USER_AGENT = "v2rayN/7.13.8"

    /** Локальные прокси, через которые можно попробовать скачать подписку: наш sing-box, Throne/Nekoray, v2rayN (HTTP), Clash. */
    private val LOCAL_PROXY_PORTS = listOf(2080, 10809, 7890)

    /**
     * Заголовки устройства (x-hwid и др.), как у Happ. Панели с лимитом устройств (Remnawave HWID)
     * без них вместо серверов отдают заглушку «App not supported». Задаётся при старте программы.
     */
    @Volatile var deviceHeaders: Map<String, String> = emptyMap()

    /**
     * Скачивает подписку. Порядок попыток:
     *  1. напрямую;
     *  2. через системный прокси macOS/Windows (если включён в другом VPN-клиенте);
     *  3. через локальные прокси на 127.0.0.1 (наш sing-box, если VPN уже работает, или другой клиент).
     * Адреса подписок часто блокируются провайдерами, поэтому прямая попытка может не пройти.
     */
    fun fetch(sub: Subscription, log: (String) -> Unit = {}): Result {
        val uri = URI(sub.url.trim())
        val routes = ArrayList<Pair<String, java.net.ProxySelector>>()
        routes += "напрямую" to java.net.ProxySelector.of(null)
        systemProxy(uri)?.let { routes += "через системный прокси $it" to fixedProxy(it) }
        LOCAL_PROXY_PORTS.filter { portOpen(it) }.forEach { port ->
            val addr = java.net.InetSocketAddress("127.0.0.1", port)
            if (routes.none { it.first.endsWith(addr.toString()) }) routes += "через 127.0.0.1:$port" to fixedProxy(addr)
        }

        val errors = ArrayList<String>()
        for ((how, selector) in routes) {
            try {
                val body = download(uri, selector)
                if (how != "напрямую") log("Подписка «${sub.name}» скачана $how")
                return parse(body, sub.name)
            } catch (e: HttpStatusException) {
                throw IllegalStateException("Подписка «${sub.name}»: сервер ответил HTTP ${e.code} — проверьте ссылку")
            } catch (e: IllegalStateException) {
                throw e // ошибка формата (happ:// и т.п.) — дальше пробовать бессмысленно
            } catch (e: Exception) {
                errors += "$how: ${describe(e)}"
            }
        }
        val hint = if (routes.size == 1)
            "Сервер подписки недоступен напрямую — похоже, провайдер его блокирует. " +
                "Включите любой VPN (Happ, Throne) и нажмите «Обновить»: программа скачает подписку через него и запомнит."
        else "Не удалось ни одним способом."
        throw java.io.IOException("$hint (${errors.joinToString("; ")})")
    }

    private class HttpStatusException(val code: Int) : Exception()

    private fun download(uri: URI, selector: java.net.ProxySelector): String {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .proxy(selector)
            .build()
        val req = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .apply { deviceHeaders.forEach { (k, v) -> header(k, v) } }
            .GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) throw HttpStatusException(resp.statusCode())
        return resp.body()
    }

    private fun describe(e: Exception): String = when (e) {
        is java.net.http.HttpConnectTimeoutException -> "нет соединения (таймаут)"
        is java.net.http.HttpTimeoutException -> "сервер не ответил вовремя"
        is java.net.ConnectException -> "соединение отклонено"
        is java.net.UnknownHostException -> "адрес не найден (DNS)"
        is javax.net.ssl.SSLException -> "ошибка TLS: ${e.message}"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun fixedProxy(addr: java.net.InetSocketAddress): java.net.ProxySelector = java.net.ProxySelector.of(addr)

    /** HTTP(S)-прокси, включённый в настройках системы (нужен -Djava.net.useSystemProxies=true, ставится в main). */
    private fun systemProxy(uri: URI): java.net.InetSocketAddress? = runCatching {
        java.net.ProxySelector.getDefault()?.select(uri)
            ?.firstOrNull { it.type() == java.net.Proxy.Type.HTTP }
            ?.address() as? java.net.InetSocketAddress
    }.getOrNull()?.let { if (it.isUnresolved) java.net.InetSocketAddress(it.hostString, it.port) else it }

    private fun portOpen(port: Int): Boolean = runCatching {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 300) }
        true
    }.getOrDefault(false)

    fun parse(body: String, subName: String): Result {
        val text = body.trim()
        if (text.startsWith("happ://")) {
            throw IllegalStateException("Подписка «$subName» зашифрована для Happ — нужна обычная ссылка от сервиса")
        }
        val decoded = when {
            looksLikeJson(text) -> text
            text.contains("://") -> text
            else -> decodeBase64Loose(text)?.takeIf { looksLikeJson(it.trim()) || it.contains("://") } ?: text
        }
        val servers = ArrayList<Server>()
        val skipped = ArrayList<String>()
        val usedNames = HashMap<String, Int>()

        // Формат Xray JSON (Remnawave/Marzban отдают его Happ и v2rayNG): массив конфигов или один конфиг.
        val entries: List<Result2> = if (looksLikeJson(decoded.trim())) {
            XrayJson.parse(decoded.trim()).also { r -> skipped += r.skipped }.servers
        } else decoded.lines().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
            val parsed = runCatching { parseUri(line) }
            parsed.getOrNull()?.let { Result2(it.first, it.second) } ?: run {
                val scheme = line.substringBefore("://", "?").take(20)
                skipped.add("$scheme: ${parsed.exceptionOrNull()?.message ?: "не поддерживается"}")
                null
            }
        }

        // Заглушки провайдера («App not supported», «Лимит устройств» и т.п.) — серверы с адресом 0.0.0.0:1.
        val stubs = entries.filter { isStub(it.outbound) }
        if (stubs.isNotEmpty() && stubs.size == entries.size) throw IllegalStateException(
            "Подписка «$subName»: вместо серверов сервис прислал заглушку «${stubs.joinToString("», «") { it.name }}». " +
                "Обычно это лимит устройств — удалите лишнее устройство в боте или личном кабинете сервиса и нажмите «Обновить»"
        )
        for ((name, outbound) in entries.filterNot { isStub(it.outbound) }) {
            val base = name.ifBlank { "${outbound["server"]}:${outbound["server_port"]}" }
            val n = usedNames.merge(base, 1, Int::plus)!!
            val unique = if (n > 1) "$base ($n)" else base
            servers.add(Server(id = "$subName/$unique", name = unique, subscription = subName, outbound = outbound))
        }
        return Result(servers, skipped)
    }

    // ---------------------------------------------------------------------------------------

    data class Result2(val name: String, val outbound: Map<String, Any?>)

    /** Сервер-заглушка: подключиться к нему нельзя, панели так передают сообщения пользователю. */
    fun isStub(outbound: Map<String, Any?>): Boolean {
        val host = outbound["server"]?.toString()?.trim().orEmpty()
        val port = (outbound["server_port"] as? Number)?.toInt() ?: outbound["server_port"]?.toString()?.toIntOrNull() ?: 0
        return host.isEmpty() || host == "0.0.0.0" || host.startsWith("127.") || host == "localhost" || port <= 1
    }

    private fun looksLikeJson(t: String) = t.startsWith("[") || t.startsWith("{")

    fun parseUri(uri: String): Pair<String, Map<String, Any?>> {
        val scheme = uri.substringBefore("://").lowercase()
        return when (scheme) {
            "vless" -> parseVless(uri)
            "vmess" -> parseVmess(uri)
            "trojan" -> parseTrojan(uri)
            "ss" -> parseShadowsocks(uri)
            "hysteria2", "hy2" -> parseHysteria2(uri)
            else -> throw IllegalArgumentException("протокол $scheme не поддерживается")
        }
    }

    private class Parts(val user: String, val host: String, val port: Int, val query: Map<String, String>, val name: String)

    /** scheme://user@host:port?query#name — разбор вручную, т.к. java.net.URI капризен к символам. */
    private fun split(uri: String): Parts {
        var rest = uri.substringAfter("://")
        val name = if (rest.contains('#')) decode(rest.substringAfter('#')) else ""
        rest = rest.substringBefore('#')
        val query = if (rest.contains('?')) parseQuery(rest.substringAfter('?')) else emptyMap()
        rest = rest.substringBefore('?').trimEnd('/')
        val user = if (rest.contains('@')) decode(rest.substringBeforeLast('@')) else ""
        val hostPort = rest.substringAfterLast('@')
        val host: String
        val portStr: String
        if (hostPort.startsWith("[")) {
            host = hostPort.substringAfter('[').substringBefore(']')
            portStr = hostPort.substringAfter("]:", "")
        } else {
            host = hostPort.substringBeforeLast(':')
            portStr = hostPort.substringAfterLast(':', "")
        }
        val port = portStr.toIntOrNull() ?: throw IllegalArgumentException("нет порта")
        if (host.isBlank()) throw IllegalArgumentException("нет адреса сервера")
        return Parts(user, host, port, query, name)
    }

    private fun parseQuery(q: String): Map<String, String> = q.split('&').filter { it.isNotEmpty() }.associate {
        val k = decode(it.substringBefore('='))
        val v = decode(it.substringAfter('=', ""))
        k to v
    }

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s.replace("+", "%2B"), Charsets.UTF_8) }.getOrDefault(s)

    internal fun tls(q: Map<String, String>, host: String, defaultEnabled: Boolean): Map<String, Any?>? {
        val security = q["security"]?.lowercase() ?: if (defaultEnabled) "tls" else "none"
        if (security == "none" || security.isEmpty()) return null
        val sni = q["sni"]?.takeIf { it.isNotBlank() } ?: q["peer"]?.takeIf { it.isNotBlank() }
            ?: q["host"]?.takeIf { it.isNotBlank() } ?: host
        val t = linkedMapOf<String, Any?>("enabled" to true, "server_name" to sni)
        val insecure = q["allowInsecure"] ?: q["insecure"] ?: q["allow_insecure"]
        if (insecure == "1" || insecure.equals("true", true)) t["insecure"] = true
        q["alpn"]?.takeIf { it.isNotBlank() }?.let { a -> t["alpn"] = a.split(',').map { it.trim() }.filter { it.isNotEmpty() } }
        val fp = q["fp"]?.takeIf { it.isNotBlank() }
        if (security == "reality") {
            val pbk = q["pbk"] ?: throw IllegalArgumentException("reality без pbk")
            t["reality"] = linkedMapOf("enabled" to true, "public_key" to pbk, "short_id" to (q["sid"] ?: ""))
            t["utls"] = linkedMapOf("enabled" to true, "fingerprint" to (fp ?: "chrome"))
        } else if (fp != null) {
            t["utls"] = linkedMapOf("enabled" to true, "fingerprint" to fp)
        }
        return t
    }

    internal fun transport(type: String?, q: Map<String, String>, headerType: String? = q["headerType"]): Map<String, Any?>? {
        return when (type?.lowercase()) {
            null, "", "tcp", "raw" -> {
                if (headerType == "http") throw IllegalArgumentException("tcp+http obfs не поддерживается sing-box")
                null
            }
            "ws" -> linkedMapOf<String, Any?>("type" to "ws").apply {
                val path = q["path"] ?: "/"
                // ?ed=2048 в пути — early data
                val ed = Regex("[?&]ed=(\\d+)").find(path)?.groupValues?.get(1)?.toIntOrNull()
                put("path", if (ed != null) path.replace(Regex("[?&]ed=\\d+"), "") else path)
                if (ed != null) { put("max_early_data", ed); put("early_data_header_name", "Sec-WebSocket-Protocol") }
                q["host"]?.takeIf { it.isNotBlank() }?.let { put("headers", mapOf("Host" to it)) }
            }
            "grpc" -> linkedMapOf<String, Any?>("type" to "grpc", "service_name" to (q["serviceName"] ?: q["path"] ?: ""))
            "httpupgrade" -> linkedMapOf<String, Any?>("type" to "httpupgrade", "path" to (q["path"] ?: "/")).apply {
                q["host"]?.takeIf { it.isNotBlank() }?.let { put("host", it) }
            }
            "h2", "http" -> linkedMapOf<String, Any?>("type" to "http", "path" to (q["path"] ?: "/")).apply {
                q["host"]?.takeIf { it.isNotBlank() }?.let { put("host", it.split(',')) }
            }
            "xhttp", "splithttp" -> throw IllegalArgumentException("транспорт xhttp не поддерживается sing-box")
            "kcp", "mkcp", "quic" -> throw IllegalArgumentException("транспорт $type не поддерживается sing-box")
            else -> throw IllegalArgumentException("транспорт $type не поддерживается")
        }
    }

    private fun parseVless(uri: String): Pair<String, Map<String, Any?>> {
        val p = split(uri)
        val o = linkedMapOf<String, Any?>(
            "type" to "vless", "server" to p.host, "server_port" to p.port, "uuid" to p.user,
        )
        p.query["flow"]?.takeIf { it.isNotBlank() }?.let { o["flow"] = it.removeSuffix("-udp443") }
        tls(p.query, p.host, defaultEnabled = false)?.let { o["tls"] = it }
        transport(p.query["type"], p.query)?.let { o["transport"] = it }
        o["packet_encoding"] = "xudp"
        return p.name to o
    }

    private fun parseTrojan(uri: String): Pair<String, Map<String, Any?>> {
        val p = split(uri)
        val o = linkedMapOf<String, Any?>(
            "type" to "trojan", "server" to p.host, "server_port" to p.port, "password" to p.user,
        )
        tls(p.query, p.host, defaultEnabled = true)?.let { o["tls"] = it }
        transport(p.query["type"], p.query)?.let { o["transport"] = it }
        return p.name to o
    }

    private fun parseVmess(uri: String): Pair<String, Map<String, Any?>> {
        val body = uri.substringAfter("://").substringBefore('#')
        val json = decodeBase64Loose(body) ?: throw IllegalArgumentException("vmess: не base64")
        val m = Json.parse(json).obj()
        val host = m.str("add") ?: throw IllegalArgumentException("vmess без адреса")
        val port = m.str("port")?.toIntOrNull() ?: throw IllegalArgumentException("vmess без порта")
        val o = linkedMapOf<String, Any?>(
            "type" to "vmess", "server" to host, "server_port" to port,
            "uuid" to (m.str("id") ?: ""),
            "security" to (m.str("scy")?.takeIf { it.isNotBlank() } ?: "auto"),
            "alter_id" to (m.str("aid")?.toIntOrNull() ?: 0),
        )
        val q = linkedMapOf<String, String>()
        m.str("tls")?.let { q["security"] = it.ifBlank { "none" } }
        m.str("sni")?.let { q["sni"] = it }
        m.str("alpn")?.let { q["alpn"] = it }
        m.str("fp")?.let { q["fp"] = it }
        m.str("host")?.let { q["host"] = it }
        m.str("path")?.let { q["path"] = it; q["serviceName"] = it }
        tls(q, host, defaultEnabled = false)?.let { o["tls"] = it }
        transport(m.str("net"), q, headerType = m.str("type"))?.let { o["transport"] = it }
        o["packet_encoding"] = "xudp"
        return (m.str("ps") ?: "") to o
    }

    private fun parseShadowsocks(uri: String): Pair<String, Map<String, Any?>> {
        var rest = uri.substringAfter("://")
        val name = if (rest.contains('#')) decode(rest.substringAfter('#')) else ""
        rest = rest.substringBefore('#')
        val query = if (rest.contains('?')) parseQuery(rest.substringAfter('?')) else emptyMap()
        rest = rest.substringBefore('?').trimEnd('/')
        if (!rest.contains('@')) {
            // старый формат: ss://base64(method:password@host:port)
            rest = decodeBase64Loose(rest) ?: throw IllegalArgumentException("ss: не base64")
        }
        val userInfo = rest.substringBeforeLast('@')
        val hostPort = rest.substringAfterLast('@')
        val creds = if (userInfo.contains(':')) decode(userInfo) else decodeBase64Loose(decode(userInfo))
            ?: throw IllegalArgumentException("ss: плохие учётные данные")
        val method = creds.substringBefore(':')
        val password = creds.substringAfter(':')
        val host = hostPort.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
        val port = hostPort.substringAfterLast(':').toIntOrNull() ?: throw IllegalArgumentException("ss: нет порта")
        if (query["plugin"] != null) throw IllegalArgumentException("ss с plugin не поддерживается")
        return name to linkedMapOf(
            "type" to "shadowsocks", "server" to host, "server_port" to port,
            "method" to method, "password" to password,
        )
    }

    private fun parseHysteria2(uri: String): Pair<String, Map<String, Any?>> {
        val p = split(uri)
        val o = linkedMapOf<String, Any?>(
            "type" to "hysteria2", "server" to p.host, "server_port" to p.port, "password" to p.user,
        )
        if (p.query["obfs"] == "salamander") {
            o["obfs"] = linkedMapOf("type" to "salamander", "password" to (p.query["obfs-password"] ?: ""))
        }
        val t = linkedMapOf<String, Any?>("enabled" to true, "server_name" to (p.query["sni"]?.takeIf { it.isNotBlank() } ?: p.host))
        if (p.query["insecure"] == "1") t["insecure"] = true
        t["alpn"] = listOf("h3")
        o["tls"] = t
        return p.name to o
    }

    fun decodeBase64Loose(s: String): String? {
        val clean = s.trim().replace("\n", "").replace("\r", "").replace(" ", "")
        if (clean.isEmpty()) return null
        for (decoder in listOf(Base64.getDecoder(), Base64.getUrlDecoder())) {
            val padded = clean.trimEnd('=').let { it + "=".repeat((4 - it.length % 4) % 4) }
            val r = runCatching { String(decoder.decode(padded), Charsets.UTF_8) }.getOrNull()
            if (r != null) return r
        }
        return null
    }
}
