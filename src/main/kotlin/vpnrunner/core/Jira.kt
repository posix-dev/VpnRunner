package vpnrunner.core

import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.LocalDate
import java.util.Base64
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.roundToLong

/** Списание времени в рабочей Jira (через Tempo). */
data class JiraSettings(
    val enabled: Boolean = false,
    /** Ссылка на доску из браузера, например https://jira.company.ru/secure/RapidBoard.jspa?rapidView=2827&projectKey=SYRAX */
    val boardUrl: String = "",
    /** Сколько часов в день нужно списать (вместе с контейнером спринта). */
    val dayHours: Int = 8,
    /** Задача-контейнер спринта: на неё программа сама списывает containerHours каждый рабочий день. */
    val container: JiraContainer? = null,
    val containerHours: Int = 2,
    /** Автосписание на контейнер — только в дни, когда был включён Cisco (отпуск и больничный не заполнятся сами). */
    val containerOnlyCisco: Boolean = true,
    /** Последние выбранные значения обязательных атрибутов Tempo (ключ атрибута → значение) — для формы и автосписания. */
    val attributes: Map<String, String> = emptyMap(),
    /** Задачи, добавленные вручную по ключу или ссылке (из закрытого спринта, чужие, с другой доски), — пока их не уберут. */
    val extraIssues: List<String> = emptyList(),
)

/** since — с какого дня (yyyy-MM-dd) списывать: прошлые дни сами не заполняются, чтобы не насписывать лишнего. */
data class JiraContainer(val sprintId: Long, val sprintName: String, val issueKey: String, val issueId: String, val since: String)

/** Доска из ссылки: адрес Jira, номер доски и ключ проекта (если есть в ссылке). */
data class JiraBoard(val host: String, val id: Long, val projectKey: String?) {
    companion object {
        /** …/secure/RapidBoard.jspa?rapidView=2827&projectKey=SYRAX или …/jira/software/projects/X/boards/2827 → доска; иначе null. */
        fun parse(url: String): JiraBoard? = runCatching {
            val u = URI(url.trim())
            if (u.host.isNullOrBlank()) return null
            val q = (u.rawQuery ?: "").split('&').filter { it.contains('=') }
                .associate { dec(it.substringBefore('=')) to dec(it.substringAfter('=')) }
            val path = u.rawPath ?: ""
            val id = q["rapidView"]?.toLongOrNull() ?: Regex("/boards/(\\d+)").find(path)?.groupValues?.get(1)?.toLongOrNull() ?: return null
            // Jira может стоять не в корне сайта (https://host/jira/secure/…)
            val i = listOf("/secure/", "/jira/software/").map { path.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
            JiraBoard("${u.scheme ?: "https"}://${u.rawAuthority}${path.substring(0, i)}", id, q["projectKey"]?.takeIf { it.isNotBlank() })
        }.getOrNull()

        private fun dec(s: String) = URLDecoder.decode(s, Charsets.UTF_8)
    }
}

data class JiraUser(val key: String, val name: String, val displayName: String)

data class JiraSprint(val id: Long, val name: String, val state: String, val start: LocalDate?, val end: LocalDate?) {
    val active: Boolean get() = state.equals("active", true)
}

/** Время — в секундах; remaining = null — у задачи нет оценки. */
data class JiraIssue(
    val id: String, val key: String, val summary: String, val status: String,
    val remaining: Long?, val original: Long?, val spent: Long,
)

data class TempoWorklog(
    val id: Long, val issueKey: String, val issueId: String, val issueSummary: String,
    val date: LocalDate, val seconds: Long, val comment: String,
)

/** Атрибут списания Tempo (например «Тип работ»). values — значение → подпись; пусто — вводится текстом. */
data class TempoAttribute(val id: Long, val key: String, val name: String, val required: Boolean, val values: List<Pair<String, String>>)

/** Время в форме: «1h 30m», «1ч 30м», «90m», «1.5» (число без единиц — часы). */
object WorkTime {
    // Длинные слова раньше коротких: иначе «мин» разобралось бы как «м» + лишнее «ин».
    private val part = Regex("(\\d+(?:\\.\\d+)?)\\s*(час[а-я]*|мин[а-я]*|h|ч|m|м)")

    fun parse(s: String): Long? {
        val t = s.trim().lowercase().replace(',', '.')
        if (t.isEmpty()) return null
        t.toDoubleOrNull()?.let { h -> return (h * 3600).roundToLong().takeIf { it > 0 } }
        if (part.replace(t, "").isNotBlank()) return null
        val total = part.findAll(t).sumOf { m ->
            val v = m.groupValues[1].toDouble()
            if (m.groupValues[2].first() in "hч") v * 3600 else v * 60
        }
        return total.roundToLong().takeIf { it > 0 }
    }

    /** Секунды → «1h 30m» — так время показывается в поле формы. */
    fun field(sec: Long): String {
        val m = (sec + 30) / 60
        return listOfNotNull((m / 60).takeIf { it > 0 }?.let { "${it}h" }, (m % 60).takeIf { it > 0 }?.let { "${it}m" }).joinToString(" ").ifEmpty { "0m" }
    }

    /** Как parse, но «0», «0h», пусто — ноль (очистить ячейку, обнулить остаток). null — не разобралось. */
    fun parseOrZero(s: String): Long? = if (Regex("\\s*(0+(\\.0*)?\\s*[hmчм]?)?\\s*").matches(s.lowercase())) 0 else parse(s)

    /** Секунды → часы для ячейки недели: «2», «1.5», «0.25»; ноль — пусто. */
    fun hours(sec: Long): String {
        if (sec <= 0) return ""
        return "%.2f".format(java.util.Locale.ROOT, sec / 3600.0).trimEnd('0').trimEnd('.')
    }

    /** Секунды → «1 ч 30 мин». */
    fun text(sec: Long): String = WorkClock.duration(Duration.ofSeconds(sec))
}

/** Ключи задач (SYRAX-123) в названиях веток и MR. */
object IssueKeys {
    private val re = Regex("(?i)(?<![a-z0-9])([a-z][a-z0-9_]+-\\d+)")
    fun find(text: String): List<String> = re.findAll(text).map { it.groupValues[1].uppercase() }.toList()
}

/** Сертификат сервера не прошёл проверку — можно предложить пользователю доверять ему (см. [JiraTrust]). */
class JiraCertException(val host: String, val port: Int) : Exception("Сертификат Jira ($host) не подписан известным центром сертификации")

/**
 * Корпоративная Jira часто подписана своим центром сертификации, которого нет в Java. Пользователь один раз
 * подтверждает сертификат (как отпечаток сервера Cisco), он сохраняется в файл и добавляется к стандартным:
 * проверка цепочки и имени сервера остаётся обычной.
 */
object JiraTrust {
    /** Цепочка сертификатов сервера без проверки — только чтобы показать пользователю, кому он будет доверять. */
    fun fetchChain(host: String, port: Int): List<X509Certificate> {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), null) }
        (ctx.socketFactory.createSocket() as SSLSocket).use { s ->
            s.connect(InetSocketAddress(host, port), 10_000)
            s.soTimeout = 10_000
            s.sslParameters = s.sslParameters.apply { serverNames = listOf(SNIHostName(host)) }
            s.startHandshake()
            return s.session.peerCertificates.map { it as X509Certificate }
        }
    }

    /** Чему доверять: корневому сертификату цепочки (он переживает замену сертификата сервера), если сервер его прислал. */
    fun anchor(chain: List<X509Certificate>): X509Certificate = chain.last()

    fun name(c: X509Certificate): String =
        Regex("CN=([^,]+)").find(c.subjectX500Principal.name)?.groupValues?.get(1) ?: c.subjectX500Principal.name

    fun sha256(c: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(c.encoded).joinToString(":") { "%02X".format(it) }

    fun save(c: X509Certificate, file: File) {
        file.parentFile?.mkdirs()
        val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(c.encoded)
        file.writeText("-----BEGIN CERTIFICATE-----\n$b64\n-----END CERTIFICATE-----\n")
    }

    fun load(file: File): X509Certificate? = runCatching {
        file.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
    }.getOrNull()

    /** Стандартные центры Java + сертификат, которому доверился пользователь. */
    fun context(extra: X509Certificate): SSLContext {
        fun tm(ks: KeyStore?) = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(ks) }.trustManagers.filterIsInstance<X509ExtendedTrustManager>().first()
        val std = tm(null)
        val own = tm(KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("jira", extra) })
        val both = object : X509ExtendedTrustManager() {
            private inline fun either(a: () -> Unit, b: () -> Unit) = try { a() } catch (e: CertificateException) { b() }
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String, s: java.net.Socket?) = either({ std.checkServerTrusted(c, a, s) }) { own.checkServerTrusted(c, a, s) }
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String, e: SSLEngine?) = either({ std.checkServerTrusted(c, a, e) }) { own.checkServerTrusted(c, a, e) }
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) = either({ std.checkServerTrusted(c, a) }) { own.checkServerTrusted(c, a) }
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String, s: java.net.Socket?) = std.checkClientTrusted(c, a, s)
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String, e: SSLEngine?) = std.checkClientTrusted(c, a, e)
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) = std.checkClientTrusted(c, a)
            override fun getAcceptedIssuers(): Array<X509Certificate> = std.acceptedIssuers + extra
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(both), null) }
    }
}

/** Запросы к Jira Server / Data Center и Tempo Timesheets с личным токеном (Personal Access Token). */
class JiraClient(private val host: String, private val token: String, trusted: X509Certificate?) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        // Без токена Jira уводит на страницу входа (SSO) — переход по ней только запутал бы ошибку.
        .followRedirects(HttpClient.Redirect.NEVER)
        .apply { if (trusted != null) sslContext(JiraTrust.context(trusted)) }
        .build()

    private val fields = "summary,status,timeestimate,timeoriginalestimate,timespent"

    fun myself(): JiraUser = call("GET", "/rest/api/2/myself").obj().let {
        JiraUser(it.str("key") ?: it.str("name") ?: "", it.str("name") ?: "", it.str("displayName") ?: "")
    }

    /** Активные и будущие спринты доски. */
    fun sprints(board: Long): List<JiraSprint> =
        call("GET", "/rest/agile/1.0/board/$board/sprint?state=active,future&maxResults=100").obj()["values"].arr().map { it.obj() }.map {
            JiraSprint(
                id = it.str("id")?.toLongOrNull() ?: 0, name = it.str("name") ?: "", state = it.str("state") ?: "",
                start = it.str("startDate")?.let { s -> date(s) }, end = it.str("endDate")?.let { s -> date(s) },
            )
        }

    /** Мои задачи спринта (без подзадач) — фильтр доски Jira применяет сама. */
    fun sprintIssues(board: Long, sprint: Long): List<JiraIssue> {
        val jql = enc("assignee = currentUser() AND issuetype not in subTaskIssueTypes() ORDER BY Rank")
        return issues(call("GET", "/rest/agile/1.0/board/$board/sprint/$sprint/issue?jql=$jql&fields=$fields&maxResults=200"))
    }

    fun search(jql: String, max: Int = 50): List<JiraIssue> =
        issues(call("GET", "/rest/api/2/search?jql=${enc(jql)}&fields=$fields&maxResults=$max"))

    /** null — такой задачи нет или к ней нет доступа. */
    fun issue(key: String): JiraIssue? = runCatching { call("GET", "/rest/api/2/issue/${enc(key)}?fields=$fields") }
        .fold({ issue(it.obj()) }, { if (it is NotFound) null else throw it })

    fun worklogs(worker: String, from: LocalDate, to: LocalDate): List<TempoWorklog> =
        call("POST", "/rest/tempo-timesheets/4/worklogs/search",
            linkedMapOf("from" to from.toString(), "to" to to.toString(), "worker" to listOf(worker))).arr().map { worklog(it.obj()) }

    private fun worklog(m: Map<String, Any?>): TempoWorklog {
        val issue = m["issue"].obj()
        return TempoWorklog(
            id = m.str("tempoWorklogId")?.toLongOrNull() ?: m.str("id")?.toLongOrNull() ?: 0,
            issueKey = issue.str("key") ?: "",
            issueId = issue.str("id") ?: m.str("originTaskId") ?: "",
            issueSummary = issue.str("summary") ?: "",
            date = date(m.str("started") ?: ""),
            seconds = m.str("timeSpentSeconds")?.toLongOrNull() ?: 0,
            comment = m.str("comment") ?: "",
        )
    }

    /** remaining — новая оставшаяся оценка задачи после списания (секунды). Возвращает созданные списания (Tempo отдаёт их списком). */
    fun addWorklog(
        worker: String, issueId: String, day: LocalDate, seconds: Long, comment: String,
        remaining: Long?, attrs: List<Pair<TempoAttribute, String>>,
    ): List<TempoWorklog> =
        call("POST", "/rest/tempo-timesheets/4/worklogs", worklogBody(worker, issueId, day, seconds, comment, remaining, attrs))
            .let { r -> (r as? List<*>) ?: listOfNotNull(r) }.map { worklog(it.obj()) }

    /** Изменить время у существующего списания (ячейка недели) — комментарий и день прежние. */
    fun updateWorklog(
        w: TempoWorklog, worker: String, seconds: Long, remaining: Long?, attrs: List<Pair<TempoAttribute, String>>,
    ): TempoWorklog? =
        call("PUT", "/rest/tempo-timesheets/4/worklogs/${w.id}", worklogBody(worker, w.issueId, w.date, seconds, w.comment, remaining, attrs))
            ?.let { r -> ((r as? List<*>)?.firstOrNull() ?: r).obj() }?.takeIf { it.isNotEmpty() }?.let { worklog(it) }

    private fun worklogBody(
        worker: String, issueId: String, day: LocalDate, seconds: Long, comment: String,
        remaining: Long?, attrs: List<Pair<TempoAttribute, String>>,
    ): Map<String, Any?> {
        val body = linkedMapOf<String, Any?>(
            "worker" to worker,
            "originTaskId" to issueId,
            "started" to day.toString(),
            "timeSpentSeconds" to seconds,
            "comment" to comment,
            "endDate" to null,
            "includeNonWorkingDays" to false,
            "attributes" to attrs.associate { (a, v) -> a.key to linkedMapOf("name" to a.name, "workAttributeId" to a.id, "value" to v) },
        )
        if (remaining != null) body["remainingEstimate"] = remaining
        return body
    }

    fun deleteWorklog(id: Long) { call("DELETE", "/rest/tempo-timesheets/4/worklogs/$id") }

    /** Атрибуты списания; у Tempo без атрибутов (или без доступа к ним) — пусто. */
    fun attributes(): List<TempoAttribute> = runCatching { call("GET", "/rest/tempo-core/1/work-attribute") }.getOrNull()
        .arr().map { it.obj() }.map { a ->
            TempoAttribute(
                id = a.str("id")?.toLongOrNull() ?: 0,
                key = a.str("key") ?: "",
                name = a.str("name") ?: "",
                required = a.bool("required"),
                values = a["staticListValues"].arr().map { it.obj() }.filter { !it.bool("removed") }
                    .mapNotNull { v -> (v.str("value") ?: v.str("name"))?.let { it to (v.str("name") ?: it) } },
            )
        }.filter { it.key.isNotBlank() }

    private fun issues(resp: Any?) = resp.obj()["issues"].arr().map { issue(it.obj()) }

    private fun issue(m: Map<String, Any?>): JiraIssue {
        val f = m["fields"].obj()
        return JiraIssue(
            id = m.str("id") ?: "", key = m.str("key") ?: "", summary = f.str("summary") ?: "",
            status = f["status"].obj().str("name") ?: "",
            remaining = f.str("timeestimate")?.toLongOrNull(), original = f.str("timeoriginalestimate")?.toLongOrNull(),
            spent = f.str("timespent")?.toLongOrNull() ?: 0,
        )
    }

    private class NotFound(msg: String) : Exception(msg)

    private fun call(method: String, path: String, body: Any? = null): Any? {
        val b = HttpRequest.newBuilder(URI(host + path)).header("Authorization", "Bearer $token")
            .header("Accept", "application/json").timeout(Duration.ofSeconds(30))
        if (body != null) b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(Json.write(body, pretty = false)))
        else b.method(method, HttpRequest.BodyPublishers.noBody())
        val r = try {
            http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: javax.net.ssl.SSLHandshakeException) {
            val u = URI(host)
            throw JiraCertException(u.host, if (u.port > 0) u.port else 443)
        } catch (e: java.net.ConnectException) {
            throw IllegalStateException("Jira ($host) недоступна — подключён ли Cisco?")
        } catch (e: java.net.http.HttpTimeoutException) {
            throw IllegalStateException("Jira ($host) не ответила — подключён ли Cisco?")
        }
        val code = r.statusCode()
        val text = r.body() ?: ""
        if (code in 200..299) return if (text.isBlank()) null else Json.parse(text)
        val msg = errorText(text)
        when (code) {
            in 300..399, 401 -> throw IllegalStateException("Jira не приняла токен — создайте новый (Профиль → Personal Access Tokens)")
            403 -> throw IllegalStateException("Jira: нет прав" + (msg?.let { " — $it" } ?: ""))
            404 -> throw NotFound("Jira: не найдено ($path)" + (msg?.let { " — $it" } ?: ""))
            else -> throw IllegalStateException("Jira ответила $code" + (msg?.let { ": $it" } ?: ": ${text.take(200)}"))
        }
    }

    /** Текст ошибки из ответа Jira ({"errorMessages":[…],"errors":{…}}) или Tempo ({"errors":[{"message":…}]}). */
    private fun errorText(body: String): String? = runCatching {
        val m = Json.parse(body).obj()
        val list = m["errorMessages"].arr().mapNotNull { it?.toString() } +
            m["errors"].obj().values.mapNotNull { it?.toString() } +
            m["errors"].arr().mapNotNull { it.obj().str("message") } +
            listOfNotNull(m.str("message"))
        list.filter { it.isNotBlank() }.joinToString("; ").ifBlank { null }
    }.getOrNull()

    private fun date(s: String): LocalDate = LocalDate.parse(s.take(10))
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
}
