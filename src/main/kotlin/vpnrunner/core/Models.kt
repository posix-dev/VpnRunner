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

/**
 * Режим «из-за рубежа»: Cisco не пускает зарубежные адреса, поэтому сначала поднимается российский VPN,
 * и Cisco подключается через него — сервер Cisco видит российский адрес.
 */
data class AbroadSettings(
    val enabled: Boolean = false,
    /** id российского сервера; "auto" — все серверы с 🇷🇺 / RU / Россия в названии, самый быстрый из них. */
    val ruServer: String = AppSettings.AUTO,
    /** Весь трафик через российский VPN. false — через него только Cisco и российские сайты, остальное напрямую. */
    val allViaRu: Boolean = true,
    /** Оборвался российский VPN — интернета нет, пока он не вернётся (только macOS). */
    val killSwitch: Boolean = true,
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
    /** Последний адрес во вкладке «Браузер» — с него она и открывается. */
    val browserUrl: String = "",
    /** Следить, чтобы рабочий VPN был включён в рабочее время (по Москве, в будни). */
    val workHoursEnabled: Boolean = true,
    val workStart: String = "09:00",
    val workEnd: String = "18:00",
    /** Пока VPN включён, не давать компьютеру и экрану засыпать — во сне Cisco отключается. */
    val keepAwake: Boolean = true,
    val abroad: AbroadSettings = AbroadSettings(),
    val gitlab: GitLabSettings = GitLabSettings(),
    val jira: JiraSettings = JiraSettings(),
) {
    val workHours: WorkHours get() = WorkHours(
        enabled = workHoursEnabled,
        start = WorkClock.parseTime(workStart, java.time.LocalTime.of(9, 0)),
        end = WorkClock.parseTime(workEnd, java.time.LocalTime.of(18, 0)),
    )

    companion object {
        const val AUTO = "auto"

        fun load(file: File): AppSettings {
            if (!file.exists()) return AppSettings()
            val m = Json.parse(file.readText()).obj()
            val c = m["cisco"].obj()
            val a = m["abroad"].obj()
            val g = m["gitlab"].obj()
            val j = m["jira"].obj()
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
                browserUrl = m.str("browserUrl") ?: "",
                workHoursEnabled = m.bool("workHoursEnabled", true),
                workStart = m.str("workStart") ?: "09:00",
                workEnd = m.str("workEnd") ?: "18:00",
                keepAwake = m.bool("keepAwake", true),
                abroad = AbroadSettings(
                    enabled = a.bool("enabled", false),
                    ruServer = a.str("ruServer") ?: AUTO,
                    allViaRu = a.bool("allViaRu", true),
                    killSwitch = a.bool("killSwitch", true),
                ),
                gitlab = GitLabSettings(
                    enabled = g.bool("enabled", false),
                    urls = g["urls"].arr().mapNotNull { it?.toString() },
                ),
                jira = JiraSettings(
                    enabled = j.bool("enabled", false),
                    boardUrl = j.str("boardUrl") ?: "",
                    dayHours = j.str("dayHours")?.toIntOrNull() ?: 8,
                    container = j["container"].obj().let { c ->
                        val key = c.str("issueKey") ?: return@let null
                        JiraContainer(c.str("sprintId")?.toLongOrNull() ?: 0, c.str("sprintName") ?: "", key, c.str("issueId") ?: "", c.str("since") ?: "")
                    },
                    containerHours = j.str("containerHours")?.toIntOrNull() ?: 2,
                    containerOnlyCisco = j.bool("containerOnlyCisco", true),
                    attributes = j["attributes"].obj().mapNotNull { (k, v) -> v?.toString()?.let { k to it } }.toMap(),
                    extraIssues = j["extraIssues"].arr().mapNotNull { it?.toString() },
                ),
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
            "browserUrl" to browserUrl,
            "workHoursEnabled" to workHoursEnabled,
            "workStart" to workStart,
            "workEnd" to workEnd,
            "keepAwake" to keepAwake,
            "abroad" to linkedMapOf(
                "enabled" to abroad.enabled,
                "ruServer" to abroad.ruServer,
                "allViaRu" to abroad.allViaRu,
                "killSwitch" to abroad.killSwitch,
            ),
            "gitlab" to linkedMapOf(
                "enabled" to gitlab.enabled,
                "urls" to gitlab.urls,
            ),
            "jira" to linkedMapOf(
                "enabled" to jira.enabled,
                "boardUrl" to jira.boardUrl,
                "dayHours" to jira.dayHours,
                "container" to jira.container?.let {
                    linkedMapOf("sprintId" to it.sprintId, "sprintName" to it.sprintName, "issueKey" to it.issueKey, "issueId" to it.issueId, "since" to it.since)
                },
                "containerHours" to jira.containerHours,
                "containerOnlyCisco" to jira.containerOnlyCisco,
                "attributes" to jira.attributes,
                "extraIssues" to jira.extraIssues,
            ),
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
