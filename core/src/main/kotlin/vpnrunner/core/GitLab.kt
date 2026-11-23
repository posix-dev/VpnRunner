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

data class GitLabCommit(val id: String, val title: String, val author: String, val authoredAt: String, val webUrl: String)
data class GitLabPipeline(val id: Long, val status: String, val ref: String, val sha: String, val createdAt: String, val webUrl: String)
data class GitLabChange(
    val oldPath: String, val newPath: String, val diff: String,
    val newFile: Boolean, val deletedFile: Boolean, val renamedFile: Boolean,
    val diffUnavailable: Boolean = false,
)
data class GitLabDiscussionNote(val author: String, val body: String, val createdAt: String)
data class GitLabDiscussionThread(
    val id: String,
    val path: String?,
    val oldLine: Int?,
    val newLine: Int?,
    val resolved: Boolean,
    val notes: List<GitLabDiscussionNote>,
    val resolvable: Boolean = false,
    val writable: Boolean = true,
    val oldRangeEnd: Int? = null,
    val newRangeEnd: Int? = null,
)

private data class GitLabDiscussionAnchor(
    val path: String?, val oldLine: Int?, val newLine: Int?, val oldRangeEnd: Int?, val newRangeEnd: Int?,
)

private fun lineCodeNumbers(endpoint: Map<String, Any?>): Pair<Int?, Int?> {
    val parts = endpoint.str("line_code")?.split('_') ?: return null to null
    return parts.getOrNull(parts.lastIndex - 1)?.toIntOrNull()?.takeIf { it > 0 } to
        parts.getOrNull(parts.lastIndex)?.toIntOrNull()?.takeIf { it > 0 }
}

private fun discussionAnchor(position: Map<String, Any?>): GitLabDiscussionAnchor {
    val range = position["line_range"].obj()
    val start = range["start"].obj()
    val end = range["end"].obj()
    val startLines = lineCodeNumbers(start)
    val endLines = lineCodeNumbers(end)
    return GitLabDiscussionAnchor(
        path = position.str("new_path") ?: position.str("old_path"),
        oldLine = position.str("old_line")?.toIntOrNull() ?: startLines.first,
        newLine = position.str("new_line")?.toIntOrNull() ?: startLines.second,
        oldRangeEnd = endLines.first,
        newRangeEnd = endLines.second,
    )
}
data class GitLabReviewState(
    val approved: Boolean = false,
    val userHasApproved: Boolean = false,
    val userCanApprove: Boolean = false,
    val approvers: List<String> = emptyList(),
)
data class GitLabDiffRefs(val baseSha: String, val headSha: String, val startSha: String)
data class GitLabMergeRequestDetails(
    val description: String,
    val state: String,
    val sourceBranch: String,
    val targetBranch: String,
    val createdAt: String,
    val updatedAt: String,
    val mergeStatus: String,
    val labels: List<String>,
    val commits: List<GitLabCommit>,
    val pipelines: List<GitLabPipeline>,
    val changes: List<GitLabChange>,
    val changesOverflow: Boolean,
    val discussions: List<GitLabDiscussionThread> = emptyList(),
    val discussionsError: String? = null,
    val review: GitLabReviewState = GitLabReviewState(),
    val diffRefs: GitLabDiffRefs? = null,
)

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
class GitLabClient(private val token: String, private val writeToken: String? = null) {
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

    /** Подробности MR для внутреннего экрана: описание, коммиты, пайплайны и diff по файлам. */
    fun mergeRequestDetails(mr: MergeRequest): GitLabMergeRequestDetails {
        val uri = URI(mr.webUrl)
        val host = "${uri.scheme}://${uri.rawAuthority}"
        val base = "/api/v4/projects/${enc(mr.projectPath)}/merge_requests/${mr.iid}"
        val detail = get(host, base).obj()
        val approval = get(host, "$base/approvals").obj()
        val refs = detail["diff_refs"].obj()
        val directRefs = GitLabDiffRefs(refs.str("base_sha") ?: "", refs.str("head_sha") ?: "", refs.str("start_sha") ?: "")
        val diffRefs = if (directRefs.baseSha.isNotBlank() && directRefs.headSha.isNotBlank() && directRefs.startSha.isNotBlank()) directRefs
        else runCatching {
            get(host, "$base/versions").arr().firstOrNull()?.obj()?.let { version ->
                GitLabDiffRefs(version.str("base_commit_sha") ?: "", version.str("head_commit_sha") ?: "", version.str("start_commit_sha") ?: "")
            }
        }.getOrNull()?.takeIf { it.baseSha.isNotBlank() && it.headSha.isNotBlank() && it.startSha.isNotBlank() }
        val commits = pages(host, "$base/commits").map {
            GitLabCommit(it.str("id") ?: "", it.str("title") ?: "", it.str("author_name") ?: "",
                it.str("authored_date") ?: "", it.str("web_url") ?: "")
        }
        val pipelines = pages(host, "$base/pipelines").map {
            GitLabPipeline(it.str("id")?.toLongOrNull() ?: 0, it.str("status") ?: "unknown",
                it.str("ref") ?: "", it.str("sha") ?: "", it.str("created_at") ?: "", it.str("web_url") ?: "")
        }
        // /changes устарел; /diffs поддерживает постраничную загрузку больших MR.
        val changeMaps = pages(host, "$base/diffs")
        val changes = changeMaps.map {
            GitLabChange(it.str("old_path") ?: "", it.str("new_path") ?: "", it.str("diff") ?: "",
                it.bool("new_file"), it.bool("deleted_file"), it.bool("renamed_file"),
                it.bool("collapsed") || it.bool("too_large"))
        }
        // Discussions API включает inline-треды и общие комментарии; системные события не показываем как review.
        val discussionResponse = runCatching { pages(host, "$base/discussions") }
        val discussions = discussionResponse.getOrDefault(emptyList()).mapNotNull { discussion ->
            val notes = discussion["notes"].arr().map { it.obj() }
                .filterNot { it.bool("system") }
                .map { note ->
                    GitLabDiscussionNote(
                        author = note["author"].obj().str("name") ?: note["author"].obj().str("username") ?: "Неизвестный автор",
                        body = note.str("body") ?: "",
                        createdAt = note.str("created_at") ?: "",
                    )
                }
            if (notes.isEmpty()) return@mapNotNull null
            val position = discussion["notes"].arr().map { it.obj()["position"].obj() }.firstOrNull { it.isNotEmpty() }.orEmpty()
            val anchor = discussionAnchor(position)
            GitLabDiscussionThread(
                id = discussion.str("id") ?: notes.first().createdAt,
                path = anchor.path,
                oldLine = anchor.oldLine,
                newLine = anchor.newLine,
                resolved = discussion["notes"].arr().map { it.obj() }.any { it.bool("resolved") },
                notes = notes,
                resolvable = discussion["notes"].arr().any { it.obj().bool("resolvable") },
                oldRangeEnd = anchor.oldRangeEnd,
                newRangeEnd = anchor.newRangeEnd,
            )
        }
        // Старые self-managed GitLab могут не отдавать обсуждения через discussions API, но отдавать обычные notes.
        val noteResponse = if (discussions.isEmpty()) runCatching { pages(host, "$base/notes") } else null
        val noteFallback = noteResponse?.getOrDefault(emptyList()).orEmpty()
        val fallbackThreads = noteFallback.mapNotNull { note ->
            if (note.bool("system")) return@mapNotNull null
            val anchor = discussionAnchor(note["position"].obj())
            val author = note["author"].obj().str("name") ?: note["author"].obj().str("username") ?: "Неизвестный автор"
            val discussionId = note.str("discussion_id")
            GitLabDiscussionThread(
                id = discussionId ?: note.str("id") ?: note.str("created_at").orEmpty(),
                path = anchor.path,
                oldLine = anchor.oldLine,
                newLine = anchor.newLine,
                resolved = note.bool("resolved"),
                notes = listOf(GitLabDiscussionNote(author, note.str("body") ?: "", note.str("created_at") ?: "")),
                resolvable = note.bool("resolvable"),
                writable = !discussionId.isNullOrBlank(),
                oldRangeEnd = anchor.oldRangeEnd,
                newRangeEnd = anchor.newRangeEnd,
            )
        }
        val allDiscussions = discussions.ifEmpty { fallbackThreads }
        val discussionsError = if (allDiscussions.isEmpty()) {
            discussionResponse.exceptionOrNull()?.message ?: noteResponse?.exceptionOrNull()?.message ?: "GitLab вернул пустой список обсуждений"
        } else null
        return GitLabMergeRequestDetails(
            description = detail.str("description") ?: "",
            state = detail.str("state") ?: "opened",
            sourceBranch = detail.str("source_branch") ?: "",
            targetBranch = detail.str("target_branch") ?: "",
            createdAt = detail.str("created_at") ?: mr.createdAt,
            updatedAt = detail.str("updated_at") ?: "",
            mergeStatus = detail.str("detailed_merge_status") ?: detail.str("merge_status") ?: "",
            labels = detail["labels"].arr().mapNotNull { it?.toString() },
            commits = commits,
            pipelines = pipelines,
            changes = changes,
            changesOverflow = changes.size >= 1000 || detail.str("changes_count") == "1000+",
            discussions = allDiscussions,
            discussionsError = discussionsError,
            review = GitLabReviewState(
                approved = approval.bool("approved"),
                userHasApproved = approval.bool("user_has_approved"),
                userCanApprove = approval.bool("user_can_approve"),
                approvers = approval["approved_by"].arr().mapNotNull { it.obj()["user"].obj().str("name") },
            ),
            diffRefs = diffRefs,
        )
    }

    /** Добавить общий комментарий к merge request. Для записи токену нужен scope api. */
    fun addMergeRequestNote(mr: MergeRequest, body: String) {
        val (host, base) = mrApiPath(mr)
        request(host, "$base/notes", "POST", mapOf("body" to body), write = true)
    }

    /** Ответить в существующем треде MR. */
    fun replyToMergeRequestDiscussion(mr: MergeRequest, discussionId: String, body: String) {
        val (host, base) = mrApiPath(mr)
        request(host, "$base/discussions/${enc(discussionId)}/notes", "POST", mapOf("body" to body), write = true)
    }

    /** Решить тред или снова открыть его. */
    fun resolveMergeRequestDiscussion(mr: MergeRequest, discussionId: String, resolved: Boolean) {
        val (host, base) = mrApiPath(mr)
        request(host, "$base/discussions/${enc(discussionId)}", "PUT", mapOf("resolved" to resolved), write = true)
    }

    /** Добавить обсуждение к строке diff. */
    fun addMergeRequestDiffNote(
        mr: MergeRequest, body: String, refs: GitLabDiffRefs, oldPath: String, newPath: String,
        oldLine: Int?, newLine: Int?,
    ) {
        require(oldLine != null || newLine != null) { "Выберите строку diff для комментария" }
        val (host, base) = mrApiPath(mr)
        val position = linkedMapOf<String, Any?>(
            "position_type" to "text",
            "base_sha" to refs.baseSha,
            "head_sha" to refs.headSha,
            "start_sha" to refs.startSha,
            "old_path" to oldPath,
            "new_path" to newPath,
        )
        oldLine?.let { position["old_line"] = it }
        newLine?.let { position["new_line"] = it }
        request(host, "$base/discussions", "POST", mapOf("body" to body, "position" to position), write = true)
    }

    /** Одобрить MR или убрать своё одобрение. */
    fun setMergeRequestApproval(mr: MergeRequest, approve: Boolean) {
        val (host, base) = mrApiPath(mr)
        request(host, "$base/${if (approve) "approve" else "unapprove"}", "POST", write = true)
    }

    private fun mrApiPath(mr: MergeRequest): Pair<String, String> {
        val uri = URI(mr.webUrl)
        val host = "${uri.scheme}://${uri.rawAuthority}"
        return host to "/api/v4/projects/${enc(mr.projectPath)}/merge_requests/${mr.iid}"
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

    /** GitLab по умолчанию отдаёт только первую страницу; читаем до 1000 элементов на раздел. */
    private fun pages(host: String, path: String): List<Map<String, Any?>> {
        val out = mutableListOf<Map<String, Any?>>()
        for (page in 1..10) {
            val batch = get(host, "$path?page=$page&per_page=100").arr().map { it.obj() }
            out += batch
            if (batch.size < 100) break
        }
        return out
    }

    private fun get(host: String, path: String): Any? = request(host, path)

    private fun request(host: String, path: String, method: String = "GET", body: Any? = null, write: Boolean = false): Any? {
        val requestToken = if (write) writeToken ?: error("Для review и комментариев сохраните отдельный токен GitLab с правами api") else token
        val req = HttpRequest.newBuilder(URI(host + path)).header("PRIVATE-TOKEN", requestToken)
            .header("Accept", "application/json").timeout(Duration.ofSeconds(20)).apply {
                if (body != null) header("Content-Type", "application/json")
                when (method) {
                    "POST" -> if (body == null) POST(HttpRequest.BodyPublishers.noBody())
                        else POST(HttpRequest.BodyPublishers.ofString(Json.write(body, pretty = false)))
                    "PUT" -> if (body == null) PUT(HttpRequest.BodyPublishers.noBody())
                        else PUT(HttpRequest.BodyPublishers.ofString(Json.write(body, pretty = false)))
                    else -> GET()
                }
            }.build()
        val r = try {
            http.send(req, HttpResponse.BodyHandlers.ofString())
        } catch (e: java.net.ConnectException) {
            throw IllegalStateException("GitLab ($host) недоступен — подключён ли Cisco?")
        } catch (e: java.net.http.HttpTimeoutException) {
            throw IllegalStateException("GitLab ($host) не ответил — подключён ли Cisco?")
        }
        when (r.statusCode()) {
            in 200..299 -> return if (r.body().isNullOrBlank()) null else Json.parse(r.body())
            401 -> throw IllegalStateException("GitLab не принял токен — создайте новый (права read_api)")
            403 -> throw IllegalStateException(if (write) "GitLab запретил запись. Проверьте права api у токена и вашу роль в проекте" else "У токена не хватает прав — нужны права read_api")
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
