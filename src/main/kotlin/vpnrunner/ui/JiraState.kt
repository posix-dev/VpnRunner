package vpnrunner.ui

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import vpnrunner.core.*
import vpnrunner.platform.Platform
import vpnrunner.platform.SecretStore
import java.io.File
import java.security.cert.X509Certificate
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Строка списка: задача и откуда она взялась (спринт, «в работе», MR в GitLab) — каждая задача один раз. manual — добавлена по ключу. */
data class JiraRow(val issue: JiraIssue, val inSprint: Boolean, val tags: List<String>, val manual: Boolean = false)

/** Сертификат Jira, которому пользователь может решить доверять. */
class CertOffer(val host: String, val cert: X509Certificate) {
    val name = JiraTrust.name(cert)
    val sha256 = JiraTrust.sha256(cert)
}

/**
 * Вкладка «Jira»: мои задачи спринта с оставшимся временем, списание через Tempo, автосписание на контейнер спринта.
 * Jira во внутренней сети — обновляемся раз в 10 минут, пока подключён Cisco (и по кнопке).
 */
class JiraState(private val app: AppState, private val scope: CoroutineScope) {
    var hasToken by mutableStateOf(app.secrets.get(SecretStore.JIRA_TOKEN) != null)
        private set
    var status by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    /** Идёт списание или удаление — кнопки формы недоступны. */
    var busy by mutableStateOf(false)
        private set
    var sprints by mutableStateOf<List<JiraSprint>>(emptyList())
        private set
    var sprintId by mutableStateOf<Long?>(null)
        private set
    var rows by mutableStateOf<List<JiraRow>>(emptyList())
        private set
    /** Задача-контейнер спринта (свежие данные из Jira), если отмечена. */
    var container by mutableStateOf<JiraIssue?>(null)
        private set
    /** Почему автосписание на контейнер сейчас не работает, или null. */
    var containerNote by mutableStateOf<String?>(null)
        private set
    /** За какой день списываем и показываем списания (по Москве). */
    var day by mutableStateOf(WorkClock.now().toLocalDate())
        private set
    /** Неделя (как в Tempo) или один день с подробной формой. */
    var weekView by mutableStateOf(true)
    /** Списания с начала показанной недели (или автосписания) по сегодня. */
    var logs by mutableStateOf<List<TempoWorklog>>(emptyList())
        private set
    /** Растёт после каждой записи из ячейки — ячейки сбрасывают введённый текст к тому, что в Tempo. */
    var logsVersion by mutableStateOf(0)
        private set
    val dayLogs: List<TempoWorklog> get() = logs.filter { it.date == day }
    /** Понедельник недели выбранного дня. */
    val weekStart: LocalDate get() = day.with(java.time.DayOfWeek.MONDAY)
    val weekLogs: List<TempoWorklog> get() = weekStart.let { m -> logs.filter { !it.date.isBefore(m) && !it.date.isAfter(m.plusDays(6)) } }
    /** Обязательные атрибуты Tempo — без них списание не примут. */
    var attributes by mutableStateOf<List<TempoAttribute>>(emptyList())
        private set
    var certOffer by mutableStateOf<CertOffer?>(null)
        private set
    /** Только что добавленная вручную задача — вкладка раскрывает её форму. */
    var justAdded by mutableStateOf<String?>(null)
        private set
    /** Ошибка поля «Другая задача» (не нашлась и т.п.). */
    var addError by mutableStateOf<String?>(null)
        private set

    val board: JiraBoard? get() = JiraBoard.parse(app.settings.jira.boardUrl)
    val sprint: JiraSprint? get() = sprints.firstOrNull { it.id == sprintId }

    private val caFile = File(Platform.appDir, "jira-ca.pem")
    private val workLog = WorkLog(File(Platform.appDir, "work-days.json"))
    // Токены читаем из Связки ключей один раз: иначе каждые 10 минут запускалась бы утилита security.
    private var client: Pair<String, JiraClient>? = null
    private var gitlab: GitLabClient? = null
    private var me: JiraUser? = null
    private var attributesLoaded = false
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var remindedDay: LocalDate? = null
    private var lastError: String? = null

    fun start() {
        scope.launch {
            delay(4000) // дать трекеру рабочего времени узнать, подключён ли Cisco
            var force = false
            while (isActive) {
                val ok = runOnce(force)
                force = withTimeoutOrNull(if (ok) 600_000L else 60_000L) { wake.receive() } != null
            }
        }
    }

    /** Обновить сейчас, даже если Cisco не подключён. */
    fun refresh() { wake.trySend(Unit) }

    fun selectSprint(id: Long) { sprintId = id; refresh() }

    /** Показывать субботу и воскресенье в неделе, даже если в них ничего не списано. */
    var showWeekends by mutableStateOf(false)

    /** Предыдущий / следующий день — любой, в том числе будущий (Tempo это принимает). */
    fun shiftDay(days: Long) = goTo(day.plusDays(days))

    fun shiftWeek(weeks: Long) = goTo(day.plusWeeks(weeks))

    /** Перейти к любому дню (и его неделе). */
    fun goTo(d: LocalDate) {
        if (d == day) return
        val sameWeek = d.with(java.time.DayOfWeek.MONDAY) == weekStart
        day = d
        // Списания этой недели уже загружены — для другого дня той же недели не перезагружаем.
        if (!sameWeek) refresh()
    }

    /** Из недели — в подробный вид дня (комментарии, остаток, атрибуты). */
    fun openDay(d: LocalDate) {
        day = d
        weekView = false
    }

    /**
     * Будни с from по to (в пределах спринта контейнера), где на контейнер ещё ничего не списано, —
     * для кнопок «Проставить контейнер». Праздники не учитываются — только суббота и воскресенье.
     */
    fun containerGaps(from: LocalDate, to: LocalDate): List<LocalDate> {
        val k = app.settings.jira.container ?: return emptyList()
        val sp = sprints.firstOrNull { it.id == k.sprintId }
        val start = maxOf(from, sp?.start ?: from)
        val end = minOf(to, sp?.end ?: to)
        return generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }
            .filter { d -> WorkClock.isWorkday(d) && logs.none { it.date == d && it.issueKey == k.issueKey } }
            .toList()
    }

    /** Спринт контейнера (если он ещё есть среди активных и будущих). */
    val containerSprint: JiraSprint? get() = app.settings.jira.container?.let { k -> sprints.firstOrNull { it.id == k.sprintId } }

    /** Проставить containerHours на контейнер за эти дни (кнопкой — в том числе будущие и без Cisco). */
    fun fillContainer(days: List<LocalDate>) {
        if (busy || days.isEmpty()) return
        val j = app.settings.jira
        val k = j.container ?: return
        attributes.firstOrNull { j.attributes[it.key].isNullOrBlank() }?.let {
            app.error = "Выберите «${it.name}» над таблицей — без него Tempo не примет списание"
            return
        }
        busy = true
        scope.launch {
            val (done, err) = logContainer(k, days)
            busy = false
            if (done > 0) app.log("Jira: на контейнер ${k.issueKey} проставлено по ${j.containerHours} ч за $done дн.")
            err?.let { app.error = "Проставлено $done из ${days.size} дн., дальше ошибка: $it" }
            refresh()
        }
    }

    /** Списать containerHours на контейнер за дни по порядку; сразу добавляет созданное в logs. (сколько вышло, ошибка) */
    private suspend fun logContainer(k: JiraContainer, days: List<LocalDate>): Pair<Int, String?> {
        val j = app.settings.jira
        val sec = j.containerHours * 3600L
        val attrs = attributes.map { it to (j.attributes[it.key] ?: "") }
        var remaining = container?.remaining
        var done = 0
        for (d in days) {
            val newRemaining = remaining?.let { maxOf(0L, it - sec) }
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    val c = client()
                    c.addWorklog(user(c).key, k.issueId, d, sec, container?.summary ?: k.sprintName, newRemaining, attrs)
                }
            }
            r.exceptionOrNull()?.let { return done to (it.message ?: it.javaClass.simpleName) }
            logs = logs + r.getOrThrow().ifEmpty { listOf(TempoWorklog(0, k.issueKey, k.issueId, container?.summary ?: "", d, sec, "")) }
            remaining = newRemaining
            done++
        }
        container = container?.let { c -> c.copy(remaining = remaining, spent = c.spent + done * sec) }
        logsVersion++
        return done to null
    }

    /**
     * Ячейка недели: newSeconds — сколько должно быть списано на задачу за день.
     * Пусто — списаний не было: создаём; одно списание — меняем его время; 0 — удаляем все списания ячейки.
     * Несколько списаний в ячейке можно только увеличить (добавится разница) — уменьшать их — в виде «День».
     */
    fun setCell(issue: JiraIssue, date: LocalDate, newSeconds: Long) {
        if (busy) { logsVersion++; return }
        val cell = logs.filter { it.issueKey == issue.key && it.date == date }
        val old = cell.sumOf { it.seconds }
        if (newSeconds == old) return
        if (cell.size > 1 && newSeconds in 1 until old) {
            app.error = "На ${issue.key} за ${date.format(DM)} несколько списаний — уменьшите нужное в виде «День»"
            logsVersion++
            return
        }
        val j = app.settings.jira
        val missing = attributes.firstOrNull { j.attributes[it.key].isNullOrBlank() }
        if (newSeconds > 0 && missing != null) {
            app.error = "Выберите «${missing.name}» над таблицей — без него Tempo не примет списание"
            logsVersion++
            return
        }
        val attrs = attributes.map { it to (j.attributes[it.key] ?: "") }
        val delta = newSeconds - old
        // Остаток уменьшаем на добавленное время; при уменьшении не трогаем — пусть решает Jira.
        val remaining = if (delta > 0) issue.remaining?.let { maxOf(0L, it - delta) } else null
        busy = true
        scope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    val c = client()
                    val u = user(c).key
                    when {
                        newSeconds == 0L -> { cell.forEach { c.deleteWorklog(it.id) }; logs - cell.toSet() }
                        cell.size == 1 -> {
                            val upd = c.updateWorklog(cell[0], u, newSeconds, remaining, attrs) ?: cell[0].copy(seconds = newSeconds)
                            logs.map { if (it.id == cell[0].id) upd else it }
                        }
                        else -> logs + c.addWorklog(u, issue.id, date, delta, issue.summary, remaining, attrs)
                            .ifEmpty { listOf(TempoWorklog(0, issue.key, issue.id, issue.summary, date, delta, issue.summary)) }
                    }
                }
            }
            busy = false
            r.onSuccess { next ->
                logs = next
                rows = rows.map { if (it.issue.key == issue.key && remaining != null) it.copy(issue = it.issue.copy(remaining = remaining)) else it }
                app.log("Jira: ${issue.key} за ${date.format(DM)} — ${if (newSeconds == 0L) "списания удалены" else WorkTime.text(newSeconds)}")
                refresh()
            }.onFailure { app.error = "Не удалось записать ${issue.key} за ${date.format(DM)}: ${it.message}" }
            logsVersion++
        }
    }

    fun saveToken(value: String) {
        val v = value.trim()
        if (v.isEmpty()) return
        runCatching { app.secrets.set(SecretStore.JIRA_TOKEN, v) }
            .onSuccess { hasToken = true; client = null; me = null; refresh() }
            .onFailure { app.error = it.message }
    }

    fun forgetToken() {
        app.secrets.delete(SecretStore.JIRA_TOKEN)
        hasToken = false; client = null; me = null
    }

    /** Ссылка на доску или включение поменялись — сбросить данные прошлой доски и обновить. */
    fun settingsChanged() {
        client = null; me = null; attributesLoaded = false
        sprints = emptyList(); sprintId = null; rows = emptyList(); container = null
        refresh()
    }

    fun trustCert() {
        val o = certOffer ?: return
        runCatching { JiraTrust.save(o.cert, caFile) }
            .onSuccess { app.log("Jira: доверяю сертификату «${o.name}» (SHA-256 ${o.sha256})"); certOffer = null; client = null; refresh() }
            .onFailure { app.error = "Не удалось сохранить сертификат: ${it.message}" }
    }

    /** Отметить задачу контейнером выбранного спринта (null — снять отметку). Списывать начнём с сегодняшнего дня. */
    fun markContainer(issue: JiraIssue?) {
        val s = sprint
        app.update { st ->
            st.copy(jira = st.jira.copy(container = if (issue == null || s == null) null
                else JiraContainer(s.id, s.name, issue.key, issue.id, WorkClock.now().toLocalDate().toString())))
        }
        containerNote = null
        if (issue == null) app.log("Jira: контейнер спринта снят")
        else app.log("Jira: контейнер спринта — ${issue.key}, по ${app.settings.jira.containerHours} ч в рабочие дни")
        refresh()
    }

    /** Списать на задачу за выбранный день. remaining — новая оставшаяся оценка (секунды) или null — не менять. */
    fun addWorklog(issue: JiraIssue, seconds: Long, comment: String, remaining: Long?, attrs: Map<String, String>, onDone: () -> Unit) {
        if (busy) return
        val d = day
        busy = true
        scope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    val c = client()
                    // Tempo часто требует описание работ — пустое заменяем названием задачи.
                    c.addWorklog(user(c).key, issue.id, d, seconds, comment.trim().ifBlank { issue.summary }, remaining,
                        attributes.map { it to (attrs[it.key] ?: "") })
                }
            }
            busy = false
            r.onSuccess {
                app.log("Jira: списано ${WorkTime.text(seconds)} на ${issue.key} за ${d.format(DM)}")
                if (attrs.isNotEmpty()) app.update { s -> s.copy(jira = s.jira.copy(attributes = s.jira.attributes + attrs)) }
                onDone()
                refresh()
            }.onFailure { app.error = "Не удалось списать на ${issue.key}: ${it.message}" }
        }
    }

    /** Добавить задачу по ключу (SYRAX-1234) или ссылке на неё — чтобы списать на задачу не из списка. */
    fun addIssue(input: String) {
        val key = IssueKeys.find(input).firstOrNull()
        if (key == null) { addError = "Не вижу ключа задачи — вставьте SYRAX-1234 или ссылку на задачу"; return }
        addError = null
        rows.firstOrNull { it.issue.key == key }?.let { justAdded = key; return }
        if (busy) return
        busy = true
        scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { client().issue(key) } }
            busy = false
            val issue = r.getOrNull()
            when {
                r.isFailure -> addError = r.exceptionOrNull()?.message
                issue == null -> addError = "Задача $key не найдена или у вас нет к ней доступа"
                else -> {
                    app.update { s -> s.copy(jira = s.jira.copy(extraIssues = (s.jira.extraIssues - key + key).takeLast(20))) }
                    rows = rows + JiraRow(issue, false, listOf(MANUAL), manual = true)
                    justAdded = key
                }
            }
        }
    }

    /** Убрать задачу, добавленную вручную (на списания не влияет). */
    fun removeIssue(key: String) {
        app.update { s -> s.copy(jira = s.jira.copy(extraIssues = s.jira.extraIssues - key)) }
        rows = rows.mapNotNull { r ->
            when {
                r.issue.key != key || !r.manual -> r
                r.tags == listOf(MANUAL) -> null
                else -> r.copy(tags = r.tags - MANUAL, manual = false)
            }
        }
        if (justAdded == key) justAdded = null
    }

    fun deleteWorklog(w: TempoWorklog) {
        if (busy) return
        busy = true
        scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { client().deleteWorklog(w.id) } }
            busy = false
            r.onSuccess { app.log("Jira: удалено списание ${WorkTime.text(w.seconds)} с ${w.issueKey} за ${w.date.format(DM)}"); refresh() }
                .onFailure { app.error = "Не удалось удалить списание: ${it.message}" }
        }
    }

    // ---------------------------------------------------------------- загрузка

    private class Snapshot(
        val sprints: List<JiraSprint>, val sprintId: Long?, val rows: List<JiraRow>, val container: JiraIssue?,
        val logs: List<TempoWorklog>, val attributes: List<TempoAttribute>?,
    )

    /** true — данные обновлены. */
    private suspend fun runOnce(force: Boolean): Boolean {
        val j = app.settings.jira
        val b = board
        when {
            !j.enabled -> { status = null; return false }
            b == null -> { status = "Вставьте ссылку на доску Jira"; return false }
            !hasToken -> { status = "Сохраните токен Jira"; return false }
            certOffer != null -> { status = "Подтвердите сертификат Jira"; return false }
            !force && app.settings.cisco.enabled && !app.workVpnUp -> { status = "Обновлю, когда подключится Cisco"; return false }
        }
        loading = true
        val wantedSprint = sprintId
        val shownWeek = weekStart
        val r = runCatching { withContext(Dispatchers.IO) { fetch(b!!, j, wantedSprint, shownWeek) } }
        loading = false
        r.onSuccess { s ->
            sprints = s.sprints
            sprintId = s.sprintId
            rows = s.rows
            container = s.container
            logs = s.logs
            logsVersion++
            s.attributes?.let { attributes = it; attributesLoaded = true }
            status = "Обновлено в ${LocalTime.now().format(WorkClock.HHMM)}"
            lastError = null
            autoContainer(s)
            remind()
            return true
        }
        val e = r.exceptionOrNull()!!
        if (e is JiraCertException) {
            val chain = runCatching { withContext(Dispatchers.IO) { JiraTrust.fetchChain(e.host, e.port) } }.getOrNull()
            val anchor = chain?.lastOrNull()?.let { JiraTrust.anchor(chain) }
            val trusted = JiraTrust.load(caFile)
            status = when {
                anchor == null -> "Ошибка: ${e.message}"
                // Сертификату уже доверяем, а проверка не прошла — значит, он выписан на другое имя сервера.
                trusted != null && trusted.encoded.contentEquals(anchor.encoded) ->
                    "Ошибка: сертификат Jira не подходит к адресу ${e.host} — проверьте ссылку на доску"
                else -> { certOffer = CertOffer(e.host, anchor); "Подтвердите сертификат Jira" }
            }
        } else {
            val msg = e.message ?: e.javaClass.simpleName
            if (msg != lastError) app.log("Jira: $msg")
            lastError = msg
            status = "Ошибка: $msg"
        }
        return false
    }

    private fun client(): JiraClient {
        val b = board ?: error("Не указана ссылка на доску Jira")
        client?.let { (host, c) -> if (host == b.host) return c }
        val token = app.secrets.get(SecretStore.JIRA_TOKEN) ?: error("Токен Jira не найден в Связке ключей")
        return JiraClient(b.host, token, JiraTrust.load(caFile)).also { client = b.host to it }
    }

    private fun user(c: JiraClient): JiraUser = me ?: c.myself().also { me = it }

    private fun fetch(b: JiraBoard, j: JiraSettings, wantedSprint: Long?, week: LocalDate): Snapshot {
        val c = client()
        val u = user(c)
        val sps = c.sprints(b.id)
        val sid = wantedSprint?.takeIf { id -> sps.any { it.id == id } } ?: sps.firstOrNull { it.active }?.id ?: sps.firstOrNull()?.id
        val inSprint = sid?.let { c.sprintIssues(b.id, it) } ?: emptyList()
        val inWork = c.search("assignee = currentUser() AND statusCategory = 4 AND issuetype not in subTaskIssueTypes() ORDER BY updated DESC", 30)

        val rows = LinkedHashMap<String, JiraRow>()
        inSprint.forEach { rows[it.key] = JiraRow(it, true, emptyList()) }
        inWork.forEach { if (it.key !in rows) rows[it.key] = JiraRow(it, false, listOf("в работе")) }
        // Ключи из моих MR: только проектов этой доски — иначе «UTF-8» в названии ветки сошло бы за задачу.
        val projects = (listOfNotNull(b.projectKey) + inSprint.map { it.key.substringBefore('-') }).toSet()
        val fromGit = LinkedHashMap<String, MutableList<String>>()
        myMergeRequests().forEach { mr ->
            IssueKeys.find(mr.title).distinct().filter { it.substringBefore('-') in projects }
                .forEach { fromGit.getOrPut(it) { mutableListOf() } += "MR !${mr.iid}" }
        }
        fromGit.entries.take(15).forEach { (key, tags) ->
            val r = rows[key]
            if (r != null) rows[key] = r.copy(tags = (r.tags + tags).distinct())
            else c.issue(key)?.let { rows[key] = JiraRow(it, false, tags.distinct()) }
        }
        j.extraIssues.forEach { key ->
            val r = rows[key]
            if (r != null) rows[key] = r.copy(tags = (r.tags + MANUAL).distinct(), manual = true)
            else c.issue(key)?.let { rows[key] = JiraRow(it, false, listOf(MANUAL), manual = true) }
        }
        val cont = j.container?.let { k -> rows.remove(k.issueKey)?.issue ?: c.issue(k.issueKey) }

        val today = WorkClock.now().toLocalDate()
        val since = j.container?.since?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        // Весь спринт контейнера — чтобы кнопка «на весь спринт» видела, где уже списано.
        val cs = j.container?.let { k -> sps.firstOrNull { it.id == k.sprintId } }
        val from = listOfNotNull(week, today, since?.takeIf { !it.isAfter(today) }, cs?.start).minOrNull()!!
        val to = listOfNotNull(week.plusDays(6), today, cs?.end).maxOrNull()!!
        val logs = c.worklogs(u.key, from, to)
        // Задачи, на которые на этой неделе уже списывали, — строками таблицы, как в Tempo (даже не мои и не из спринта).
        logs.filter { !it.date.isBefore(week) && !it.date.isAfter(week.plusDays(6)) }.map { it.issueKey }.distinct()
            .filter { it.isNotBlank() && it !in rows && it != j.container?.issueKey }.take(20)
            .forEach { key -> c.issue(key)?.let { rows[key] = JiraRow(it, false, listOf("есть списания")) } }
        val attrs = if (attributesLoaded) null else c.attributes().filter { it.required }
        return Snapshot(sps, sid, rows.values.toList(), cont, logs, attrs)
    }

    /** Мои открытые MR в GitLab (если настроен) — источник ключей задач; не вышло — просто без них. */
    private fun myMergeRequests(): List<MergeRequest> {
        val host = app.settings.gitlab.urls.firstNotNullOfOrNull { GitLabList.parse(it) }?.host ?: return emptyList()
        if (!app.hasGitLabToken) return emptyList()
        return runCatching {
            val g = gitlab ?: GitLabClient(app.secrets.get(SecretStore.GITLAB_TOKEN) ?: return emptyList()).also { gitlab = it }
            g.myMergeRequests(host)
        }.getOrElse { gitlab = null; emptyList() }
    }

    // ---------------------------------------------------------------- контейнер спринта и напоминание

    /**
     * Списать containerHours на контейнер за каждый рабочий день спринта с момента отметки, когда был включён Cisco
     * (работал) и на контейнер за этот день ещё ничего не списано — ни программой, ни вручную.
     */
    private suspend fun autoContainer(s: Snapshot) {
        val j = app.settings.jira
        val k = j.container ?: run { containerNote = null; return }
        val sp = s.sprints.firstOrNull { it.id == k.sprintId }
        if (sp == null || !sp.active) {
            containerNote = if (sp == null) "Спринт «${k.sprintName}» закончился — отметьте контейнер нового спринта"
                else "Спринт «${k.sprintName}» ещё не начался — списывать начну с его первого дня"
            return
        }
        val since = runCatching { LocalDate.parse(k.since) }.getOrNull() ?: return
        val today = WorkClock.now().toLocalDate()
        val worked = withContext(Dispatchers.IO) { workLog.load() }
        val missing = containerGaps(since, today)
            .filter { d -> !j.containerOnlyCisco || worked[d]?.firstUp != null || (d == today && app.workVpnUp) }
        val noValue = attributes.firstOrNull { j.attributes[it.key].isNullOrBlank() }
        if (noValue != null) {
            containerNote = "Для автосписания нужен атрибут «${noValue.name}» — спишите один раз вручную, программа запомнит его значение"
            return
        }
        containerNote = null
        if (missing.isEmpty() || busy) return
        busy = true
        val (logged, err) = logContainer(k, missing)
        busy = false
        if (logged > 0) app.notice(Notice("Списано ${j.containerHours} ч на контейнер",
            "${k.issueKey} · " + missing.take(logged).joinToString(", ") { it.format(DM) }, warning = false))
        if (err != null) {
            containerNote = "Автосписание на ${k.issueKey} не удалось: $err"
            app.log("Jira: $containerNote")
        }
        // Показать новые списания. После ошибки не обновляем сразу — иначе повторяли бы её без паузы.
        if (logged > 0) refresh()
    }

    /** За 30 минут до конца рабочего дня, если сегодня работали, а списано меньше нормы. */
    private fun remind() {
        val h = app.settings.workHours
        val now = WorkClock.now()
        val today = now.toLocalDate()
        val t = now.toLocalTime()
        if (!h.isWorkday(now) || remindedDay == today || app.workToday.firstUp == null) return
        if (t < h.end.minusMinutes(30) || t > h.end.plusHours(3)) return
        remindedDay = today
        val done = logs.filter { it.date == today }.sumOf { it.seconds }
        val norm = app.settings.jira.dayHours * 3600L
        if (done < norm) app.notice(Notice("Списано ${WorkTime.text(done)} из ${app.settings.jira.dayHours} ч",
            "Осталось списать ${WorkTime.text(norm - done)} — вкладка «Jira» в VpnRunner"))
    }

    companion object {
        const val MANUAL = "добавлена вручную"
        val DM: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM")
    }
}
