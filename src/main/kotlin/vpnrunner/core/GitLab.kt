package vpnrunner.core

import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Уведомления о новых merge request в рабочем GitLab. */
data class GitLabSettings(
    val enabled: Boolean = false,
    /**
     * Ссылки на списки MR из браузера, вместе с фильтрами, например
     * https://gitlab.company.ru/group/app/-/merge_requests?draft=no&not[author_username]=me
     */
    val urls: List<String> = emptyList(),
)

data class MergeRequest(
    val projectPath: String,
    val iid: Long,
    val title: String,
    val author: String,
    val webUrl: String,
    val createdAt: String,
) {
    /** Один и тот же номер (!12) бывает в разных проектах. */
    val key: String get() = "$projectPath!$iid"
}

/**
 * Что открыто в списке по ссылке: фильтры из адреса страницы переводятся в параметры API.
 * Фильтров «одобрен / не одобрен мной» в REST API нет — их проверяем сами по /approvals.
 */
class GitLabList(val host: String, val projectPath: String, private val query: List<Pair<String, String>>) {
    /** Параметры для GET /projects/:id/merge_requests. */
    fun apiQuery(): List<Pair<String, String>> {
        val out = mutableListOf("scope" to "all", "per_page" to "100", "order_by" to "created_at")
        var state = "opened"
        for ((k, v) in query) when (k) {
            "state" -> state = v
            "draft" -> out += "wip" to v                                  // на странице draft, в API — wip
            "label_name[]" -> out += "labels" to v
            "not[label_name][]" -> out += "not[labels]" to v
            "author_username", "not[author_username]",
            "assignee_username", "not[assignee_username]", "assignee_username[]",
            "reviewer_username", "not[reviewer_username]",
            "target_branch", "source_branch", "milestone_title", "search" -> out += k to v
        }
        out += "state" to state
        return out
    }

    val wantDraft: Boolean? = when (param("draft")) { "yes" -> true; "no" -> false; else -> null }
    val approvedBy: List<String> = params("approved_by_usernames[]")
    val notApprovedBy: List<String> = params("not[approved_by_usernames][]")
    val notAuthor: String? = param("not[author_username]")

    private fun param(k: String) = query.firstOrNull { it.first == k }?.second
    private fun params(k: String) = query.filter { it.first == k }.map { it.second }

    companion object {
        /** https://host/group/app/-/merge_requests?… → список; null — ссылка не на список MR. */
        fun parse(url: String): GitLabList? = runCatching {
            val u = URI(url.trim())
            val path = u.rawPath.trim('/')
            val i = path.indexOf("/-/merge_requests")
            if (u.host.isNullOrBlank() || i <= 0) return null
            val query = (u.rawQuery ?: "").split('&').filter { it.isNotBlank() }.map { p ->
                val eq = p.indexOf('=')
                val k = dec(if (eq < 0) p else p.substring(0, eq))
                k to (if (eq < 0) "" else dec(p.substring(eq + 1)))
            }
            GitLabList("${u.scheme ?: "https"}://${u.rawAuthority}", dec(path.substring(0, i)), query)
        }.getOrNull()

        private fun dec(s: String) = URLDecoder.decode(s, Charsets.UTF_8)
    }
}

/** Запросы к GitLab API с личным токеном (права read_api). */
class GitLabClient(private val token: String) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL).build()

    // Одобрения MR меняются, только когда меняется сам MR — запоминаем по updated_at, чтобы не спрашивать каждый раз.
    private val approvalsCache = HashMap<String, Pair<String, List<String>>>()

    fun mergeRequests(list: GitLabList): List<MergeRequest> {
        val project = enc(list.projectPath)
        val q = list.apiQuery().joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        val items = get(list.host, "/api/v4/projects/$project/merge_requests?$q").arr().map { it.obj() }
        return items.filter { m ->
            val draft = m.bool("draft") || m.bool("work_in_progress")
            if (list.wantDraft != null && draft != list.wantDraft) return@filter false
            if (list.notAuthor != null && m["author"].obj().str("username") == list.notAuthor) return@filter false
            if (list.approvedBy.isEmpty() && list.notApprovedBy.isEmpty()) return@filter true
            val approvers = approvers(list, m)
            list.approvedBy.all { it in approvers } && list.notApprovedBy.none { it in approvers }
        }.map { m ->
            MergeRequest(
                projectPath = list.projectPath,
                iid = m.str("iid")?.toLongOrNull() ?: 0,
                title = m.str("title") ?: "",
                author = m["author"].obj().let { it.str("name") ?: it.str("username") } ?: "",
                webUrl = m.str("web_url") ?: "",
                createdAt = m.str("created_at") ?: "",
            )
        }
    }

    /** Мои открытые MR на сервере host: «заголовок + ветка» → ссылка (для поиска ключей задач Jira). */
    fun myMergeRequests(host: String): List<MergeRequest> =
        get(host, "/api/v4/merge_requests?scope=created_by_me&state=opened&per_page=100").arr().map { it.obj() }.map { m ->
            MergeRequest(
                projectPath = m["references"].obj().str("full")?.substringBefore('!') ?: "",
                iid = m.str("iid")?.toLongOrNull() ?: 0,
                title = (m.str("title") ?: "") + " " + (m.str("source_branch") ?: ""),
                author = m["author"].obj().str("username") ?: "",
                webUrl = m.str("web_url") ?: "",
                createdAt = m.str("created_at") ?: "",
            )
        }

    private fun approvers(list: GitLabList, m: Map<String, Any?>): List<String> {
        val iid = m.str("iid") ?: return emptyList()
        val key = "${list.projectPath}!$iid"
        val updated = m.str("updated_at") ?: ""
        approvalsCache[key]?.let { (at, who) -> if (at == updated) return who }
        val who = get(list.host, "/api/v4/projects/${enc(list.projectPath)}/merge_requests/$iid/approvals")
            .obj()["approved_by"].arr().mapNotNull { it.obj()["user"].obj().str("username") }
        approvalsCache[key] = updated to who
        return who
    }

    private fun get(host: String, path: String): Any? {
        val req = HttpRequest.newBuilder(URI(host + path)).header("PRIVATE-TOKEN", token)
            .header("Accept", "application/json").timeout(Duration.ofSeconds(20)).GET().build()
        val r = try {
            http.send(req, HttpResponse.BodyHandlers.ofString())
        } catch (e: java.net.ConnectException) {
            throw IllegalStateException("GitLab ($host) недоступен — подключён ли Cisco?")
        } catch (e: java.net.http.HttpTimeoutException) {
            throw IllegalStateException("GitLab ($host) не ответил — подключён ли Cisco?")
        }
        when (r.statusCode()) {
            200 -> return Json.parse(r.body())
            401 -> throw IllegalStateException("GitLab не принял токен — создайте новый (права read_api)")
            403 -> throw IllegalStateException("У токена не хватает прав — нужны права read_api")
            404 -> throw IllegalStateException("GitLab: проект не найден или у токена нет к нему доступа")
            else -> throw IllegalStateException("GitLab ответил ${r.statusCode()}: ${r.body().take(200)}")
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
}

/**
 * Какие MR уже показаны — чтобы после перезапуска программы не уведомлять о них снова.
 * lists — ссылки, которые уже проверялись: о MR из только что добавленной ссылки не уведомляем (их могут быть десятки).
 */
data class SeenMergeRequests(val lists: Set<String>, val keys: Set<String>) {
    fun save(file: File) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(Json.write(linkedMapOf("lists" to lists.sorted(), "mergeRequests" to keys.sorted())))
            tmp.renameTo(file) || run { file.delete(); tmp.renameTo(file) }
        }
    }

    companion object {
        fun load(file: File): SeenMergeRequests = runCatching {
            val m = Json.parse(file.readText()).obj()
            SeenMergeRequests(
                m["lists"].arr().mapNotNull { it?.toString() }.toSet(),
                m["mergeRequests"].arr().mapNotNull { it?.toString() }.toSet(),
            )
        }.getOrDefault(SeenMergeRequests(emptySet(), emptySet()))
    }
}
