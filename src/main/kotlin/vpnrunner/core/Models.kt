package vpnrunner.core

import java.io.File

/** Куда идёт сайт из пользовательского списка. */
enum class RuleMode { DIRECT, PROXY }

data class SiteRule(val domain: String, val mode: RuleMode = RuleMode.DIRECT)

data class Subscription(val name: String, val url: String) {
    /** Подписка, вставленная вручную (без ссылки): url = "manual:<имя>". */
    val isManual: Boolean get() = url.startsWith(MANUAL_PREFIX)

    companion object {
        const val MANUAL_PREFIX = "manual:"
        fun manual(name: String) = Subscription(name, MANUAL_PREFIX + name)
    }
}

/** Сервер из подписки — уже готовый outbound sing-box (без tag). */
data class Server(
    val id: String,          // стабильный id: имя подписки + имя сервера
    val name: String,
    val subscription: String,
    val outbound: Map<String, Any?>,
)

data class CiscoSettings(
    val enabled: Boolean = true,
    val server: String = "",
    val user: String = "",
    val authGroup: String = "",
    /** Подсети, которые пускать через Cisco (10.0.0.0/8 и т.п.). Пусто — что пришлёт сервер. */
    val workSubnets: List<String> = emptyList(),
    /** Домены, которые резолвить через DNS Cisco (corp.local и т.п.). */
    val workDomains: List<String> = emptyList(),
    /** Отпечаток сертификата сервера, если он самоподписанный (pin-sha256:...). */
    val serverCert: String = "",
    /** Код Google Authenticator вводится в одно поле с паролем (пароль+код). */
    val otpCombined: Boolean = false,
)

data class AppSettings(
    val subscriptions: List<Subscription> = emptyList(),
    /** id выбранного сервера; "auto" — автовыбор самого быстрого. */
    val selectedServer: String = AUTO,
    val russiaDirect: Boolean = true,
    val rules: List<SiteRule> = emptyList(),
    val cisco: CiscoSettings = CiscoSettings(),
    val openconnectPath: String = "",
    val vpncScriptPath: String = "",
    val singboxPath: String = "",
) {
    companion object {
        const val AUTO = "auto"

        fun load(file: File): AppSettings {
            if (!file.exists()) return AppSettings()
            val m = Json.parse(file.readText()).obj()
            val c = m["cisco"].obj()
            return AppSettings(
                subscriptions = m["subscriptions"].arr().map { it.obj() }
                    .map { Subscription(it.str("name") ?: "", it.str("url") ?: "") }
                    .filter { it.url.isNotBlank() },
                selectedServer = m.str("selectedServer") ?: AUTO,
                russiaDirect = m.bool("russiaDirect", true),
                rules = m["rules"].arr().map { it.obj() }.mapNotNull {
                    val d = it.str("domain") ?: return@mapNotNull null
                    SiteRule(d, runCatching { RuleMode.valueOf(it.str("mode") ?: "DIRECT") }.getOrDefault(RuleMode.DIRECT))
                },
                cisco = CiscoSettings(
                    enabled = c.bool("enabled", true),
                    server = c.str("server") ?: "",
                    user = c.str("user") ?: "",
                    authGroup = c.str("authGroup") ?: "",
                    workSubnets = c["workSubnets"].arr().mapNotNull { it?.toString() },
                    workDomains = c["workDomains"].arr().mapNotNull { it?.toString() },
                    serverCert = c.str("serverCert") ?: "",
                    otpCombined = c.bool("otpCombined", false),
                ),
                openconnectPath = m.str("openconnectPath") ?: "",
                vpncScriptPath = m.str("vpncScriptPath") ?: "",
                singboxPath = m.str("singboxPath") ?: "",
            )
        }
    }

    fun save(file: File) {
        file.parentFile?.mkdirs()
        val m = linkedMapOf(
            "subscriptions" to subscriptions.map { mapOf("name" to it.name, "url" to it.url) },
            "selectedServer" to selectedServer,
            "russiaDirect" to russiaDirect,
            "rules" to rules.map { mapOf("domain" to it.domain, "mode" to it.mode.name) },
            "cisco" to linkedMapOf(
                "enabled" to cisco.enabled,
                "server" to cisco.server,
                "user" to cisco.user,
                "authGroup" to cisco.authGroup,
                "workSubnets" to cisco.workSubnets,
                "workDomains" to cisco.workDomains,
                "serverCert" to cisco.serverCert,
                "otpCombined" to cisco.otpCombined,
            ),
            "openconnectPath" to openconnectPath,
            "vpncScriptPath" to vpncScriptPath,
            "singboxPath" to singboxPath,
        )
        val tmp = File(file.path + ".tmp")
        tmp.writeText(Json.write(m))
        Fs.restrictToOwner(tmp)
        tmp.renameTo(file) || run { file.delete(); tmp.renameTo(file) }
    }
}

/** Что сообщил скрипт-обёртка openconnect после подключения. */
data class CiscoState(
    val tunDev: String,
    val dnsServers: List<String>,
    val splitRoutes: List<String>,
    val serverPushedFullTunnel: Boolean,
    /** Домены, которые прислал сервер (домен по умолчанию и split-DNS): их имена знает только DNS Cisco. */
    val domains: List<String> = emptyList(),
) {
    companion object {
        /** Формат файла: строки key=value. */
        fun parse(text: String): CiscoState {
            val kv = text.lines().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }.toMap()
            return CiscoState(
                tunDev = kv["TUNDEV"] ?: "",
                dnsServers = (kv["DNS"] ?: "").split(' ', ',').filter { it.isNotBlank() },
                splitRoutes = (kv["ROUTES"] ?: "").split(' ', ',').filter { it.isNotBlank() },
                serverPushedFullTunnel = kv["SERVER_FULL_TUNNEL"] == "1",
                domains = (kv["DOMAINS"] ?: "").split(' ', ',').filter { it.isNotBlank() },
            )
        }
    }
}

object Fs {
    /** chmod 600/700 там, где это поддерживается. */
    fun restrictToOwner(f: File) {
        runCatching {
            f.setReadable(false, false); f.setReadable(true, true)
            f.setWritable(false, false); f.setWritable(true, true)
            if (f.isDirectory) { f.setExecutable(false, false); f.setExecutable(true, true) }
        }
    }
}
