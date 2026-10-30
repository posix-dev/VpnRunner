package vpnrunner.ui

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import vpnrunner.core.*
import vpnrunner.platform.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

enum class Phase { STOPPED, STARTING, RUNNING, STOPPING }

/** Уведомление от значка в строке меню. warning — напоминание о рабочем времени; иначе — просто новость (новый MR). */
data class Notice(val title: String, val text: String, val warning: Boolean = true)

class AppState(private val scope: CoroutineScope) {
    var settings by mutableStateOf(AppSettings.load(Platform.settingsFile))
        private set
    var servers by mutableStateOf<List<Server>>(emptyList())
        private set
    var phase by mutableStateOf(Phase.STOPPED)
        private set
    var ciscoUp by mutableStateOf(false)
        private set
    var currentServer by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    var loadingServers by mutableStateOf(false)
        private set
    val logLines = mutableStateListOf<String>()

    val isAdmin = Platform.isWindowsAdmin()
    val secrets = Platform.secrets
    var hasPassword by mutableStateOf(secrets.get(SecretStore.CISCO_PASSWORD) != null)
        private set
    var hasTotp by mutableStateOf(secrets.get(SecretStore.CISCO_TOTP) != null)
        private set
    /** Правила поменялись, пока VPN работает. */
    var rulesDirty by mutableStateOf(false)
        private set
    /** Настройки «из-за рубежа» поменялись, пока VPN работает — нужен перезапуск. */
    var restartNeeded by mutableStateOf(false)
        private set
    /** Из-за рубежа: с какого адреса нас видно снаружи. */
    var exitInfo by mutableStateOf<String?>(null)
        private set
    /** Kill switch остался после сбоя — интернета нет, пока его не снять. */
    var killSwitchStuck by mutableStateOf(false)
        private set

    private val backend: VpnBackend? = runCatching { Platform.backend() }.getOrNull()
    private val controller = backend?.let { b -> VpnController(b, secrets) { msg -> log(msg) } }
    private var monitorJob: Job? = null

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")

    // ---- рабочее время: работодатель видит, когда включён Cisco (по Москве) ----

    /** Учёт сегодняшнего дня (по Москве). */
    var workToday by mutableStateOf(WorkDay(WorkClock.now().toLocalDate()))
        private set
    /** Cisco подключён прямо сейчас (туннель поднят), обновляется раз в 20 с. */
    var workVpnUp by mutableStateOf(false)
        private set
    /** Время последней проверки — чтобы карточка на главной пересчитывала «осталось». */
    var workNow by mutableStateOf(WorkClock.now())
        private set

    private val _notifications = MutableSharedFlow<Notice>(extraBufferCapacity = 8)
    /** Напоминания и новости — показываются уведомлением от значка в строке меню. */
    val notifications: SharedFlow<Notice> = _notifications

    /** Вопрос «точно выключить?» — показывается диалогом в окне. */
    class Confirm(val text: String, val confirmLabel: String, val action: () -> Unit)
    var confirm by mutableStateOf<Confirm?>(null)

    private val workLog = WorkLog(File(Platform.appDir, "work-days.json"))
    /** Выключили сами (с подтверждением) — обрыв не нужно объявлять уведомлением. */
    @Volatile private var intentionalStop = false

    /** Почему выключать сейчас рано (рабочее время и Cisco подключён), или null — можно. */
    private fun earlyStopWarning(): String? {
        val h = settings.workHours
        val now = WorkClock.now()
        if (!h.isWorkTime(now) || !(workVpnUp || ciscoUp)) return null
        val left = WorkClock.duration(Duration.between(now.toLocalTime(), h.end))
        return "Сейчас ${now.format(WorkClock.HHMM)} МСК. Рабочий VPN должен быть включён до ${h.end.format(WorkClock.HHMM)} " +
            "(осталось $left) — если выключить, на работе это будет видно."
    }

    /** Stop с проверкой рабочего времени. */
    fun requestStop() {
        val w = earlyStopWarning()
        if (w == null) stop() else confirm = Confirm(w, "Всё равно выключить") { intentionalStop = true; stop() }
    }

    /** Выход из программы (с остановкой VPN) с проверкой рабочего времени. */
    fun requestQuit(quit: () -> Unit) {
        val w = earlyStopWarning()
        if (w == null || phase == Phase.STOPPED) quit()
        else confirm = Confirm(w, "Всё равно выйти") { intentionalStop = true; quit() }
    }

    private fun notify(text: String) {
        log(text)
        _notifications.tryEmit(Notice("VpnRunner", text))
    }

    /** Уведомление от значка (и строка в журнале) — для вкладок со своим состоянием (Jira). */
    fun notice(n: Notice) {
        log("${n.title}: ${n.text.replace('\n', ' ')}")
        _notifications.tryEmit(n)
    }

    // ---- merge request в рабочем GitLab ----

    var hasGitLabToken by mutableStateOf(secrets.get(SecretStore.GITLAB_TOKEN) != null)
        private set
    /** Открытые MR по ссылкам из настроек (с их фильтрами) — по последней удачной проверке. */
    var mergeRequests by mutableStateOf<List<MergeRequest>>(emptyList())
        private set
    var gitlabStatus by mutableStateOf<String?>(null)
        private set
    var gitlabChecking by mutableStateOf(false)
        private set
    // Токен читаем из Связки ключей один раз: иначе каждые 2 минуты запускалась бы утилита security.
    private var gitlabClient: GitLabClient? = null
    private val gitlabWake = Channel<Unit>(Channel.CONFLATED)
    private val gitlabSeenFile = File(Platform.appDir, "gitlab-seen.json")

    /** Списание времени в Jira (вкладка «Jira»). */
    val jira = JiraState(this, scope)

    // init — после всех полей: из него стартуют корутины, которые их читают.
    init {
        if (backend == null) error = "Эта ОС не поддерживается — нужна macOS или Windows"
        killSwitchStuck = backend?.killSwitchStuck() == true
        if (backend?.leftovers() == true) {
            phase = Phase.RUNNING
            log("VPN был запущен ранее — можно остановить кнопкой Stop")
            startMonitor()
        }
        if (settings.subscriptions.isNotEmpty()) refreshServers()
        startWorkTracker()
        startGitLabWatcher()
        jira.start()
    }

    /** Проверить сейчас, не дожидаясь очередных 2 минут (и даже если Cisco не подключён). */
    fun checkGitLabNow() { gitlabWake.trySend(Unit) }

    fun saveGitLabToken(value: String) {
        gitlabClient = null
        saveSecret(SecretStore.GITLAB_TOKEN, value.trim()) { hasGitLabToken = it }
        checkGitLabNow()
    }

    /**
     * Раз в 2 минуты, пока подключён Cisco (GitLab — во внутренней сети): новые MR по ссылкам из настроек.
     * О MR, которые уже были открыты при первой проверке ссылки, не уведомляем — только о появившихся после.
     */
    private fun startGitLabWatcher() {
        scope.launch {
            var seen = withContext(Dispatchers.IO) { SeenMergeRequests.load(gitlabSeenFile) }
            var force = false
            delay(3000) // дать трекеру рабочего времени узнать, подключён ли Cisco
            while (isActive) {
                val g = settings.gitlab
                val lists = g.urls.mapNotNull { url -> GitLabList.parse(url)?.let { url to it } }
                var ok = false
                when {
                    !g.enabled -> gitlabStatus = null
                    lists.isEmpty() -> gitlabStatus = "Добавьте ссылку на список merge request"
                    !hasGitLabToken -> gitlabStatus = "Сохраните токен GitLab"
                    !force && settings.cisco.enabled && !workVpnUp -> gitlabStatus = "Проверю, когда подключится Cisco"
                    else -> {
                        gitlabChecking = true
                        val r = runCatching {
                            withContext(Dispatchers.IO) {
                                val c = gitlabClient ?: GitLabClient(secrets.get(SecretStore.GITLAB_TOKEN)
                                    ?: error("Токен GitLab не найден в Связке ключей")).also { gitlabClient = it }
                                lists.map { (url, list) -> url to c.mergeRequests(list) }
                            }
                        }
                        gitlabChecking = false
                        r.onSuccess { found ->
                            val fresh = found.filter { (url, _) -> url in seen.lists }.flatMap { it.second }
                                .distinctBy { it.key }.filter { it.key !in seen.keys }
                            when {
                                fresh.size > 3 -> _notifications.tryEmit(Notice("Новых merge request: ${fresh.size}",
                                    fresh.take(3).joinToString("\n") { "!${it.iid} ${it.title}" }, warning = false))
                                else -> fresh.forEach { _notifications.tryEmit(Notice("Новый merge request !${it.iid}", "${it.title}\n${it.author}", warning = false)) }
                            }
                            fresh.forEach { log("Новый merge request !${it.iid}: ${it.title} (${it.author})") }
                            val all = found.flatMap { it.second }.distinctBy { it.key }
                            // Помним только открытые сейчас: MR, который снова появится в списке (сняли черновик, сбросили одобрение), — снова новость.
                            val next = SeenMergeRequests(found.map { it.first }.toSet(), all.map { it.key }.toSet())
                            if (next != seen) { seen = next; withContext(Dispatchers.IO) { next.save(gitlabSeenFile) } }
                            mergeRequests = all
                            gitlabStatus = "Проверено в ${LocalTime.now().format(WorkClock.HHMM)}"
                            ok = true
                        }.onFailure { e ->
                            val msg = e.message ?: e.javaClass.simpleName
                            if (gitlabStatus?.endsWith(msg) != true) log("GitLab: $msg")
                            gitlabStatus = "Ошибка: $msg"
                        }
                    }
                }
                force = withTimeoutOrNull(if (ok) 120_000L else 20_000L) { gitlabWake.receive() } != null
            }
        }
    }

    /**
     * Раз в 20 с: подключён ли Cisco, учёт дня, напоминания. Работает всё время, пока открыта программа.
     *  - за 10 минут до начала, если Cisco не подключён;
     *  - в рабочее время, если Cisco не подключён — раз в 15 минут;
     *  - если Cisco оборвался в рабочее время;
     *  - в конце рабочего дня — что можно выключать.
     */
    private fun startWorkTracker() {
        scope.launch {
            val days = withContext(Dispatchers.IO) { workLog.load() }
            var wasUp: Boolean? = null
            var lastTick = WorkClock.now()
            var lastSave = lastTick
            var remindedStart: LocalDate? = null
            var remindedEnd: LocalDate? = null
            var lastNotUpNotice: ZonedDateTime? = null
            while (isActive) {
                val up = withContext(Dispatchers.IO) { backend?.let { it.ciscoAlive() && it.ciscoStateFile.exists() } == true }
                val now = WorkClock.now()
                val date = now.toLocalDate()
                val t = now.toLocalTime().withNano(0)
                val h = settings.workHours
                var d = days[date] ?: WorkDay(date)

                if (up) d = d.copy(
                    firstUp = d.firstUp ?: t,
                    lastUp = t,
                    upSeconds = d.upSeconds + if (wasUp == true) Duration.between(lastTick, now).seconds.coerceIn(0, 120) else 0,
                )
                if (wasUp == true && !up && h.isWorkTime(now)) {
                    d = d.copy(drops = d.drops + 1)
                    if (!intentionalStop) notify("Рабочий VPN (Cisco) отключился в ${t.format(WorkClock.HHMM)} МСК — до ${h.end.format(WorkClock.HHMM)} он должен быть включён")
                }
                if (up) intentionalStop = false

                if (h.isWorkday(now)) {
                    val hhmm = t.format(WorkClock.HHMM)
                    if (!up && t >= h.start.minusMinutes(10) && t < h.start && remindedStart != date) {
                        notify("В ${h.start.format(WorkClock.HHMM)} МСК нужно быть в рабочем VPN — включите VpnRunner")
                        remindedStart = date
                    }
                    if (!up && h.isWorkTime(now) && (lastNotUpNotice == null || Duration.between(lastNotUpNotice, now).toMinutes() >= 15)) {
                        notify("Рабочий VPN не подключён, сейчас $hhmm МСК (нужен с ${h.start.format(WorkClock.HHMM)} до ${h.end.format(WorkClock.HHMM)})")
                        lastNotUpNotice = now
                    }
                    if (up) lastNotUpNotice = null
                    if (up && t >= h.end && remindedEnd != date) {
                        notify("${h.end.format(WorkClock.HHMM)} МСК — рабочий VPN можно выключать")
                        remindedEnd = date
                    }
                }

                val prev = days[date]
                val changed = d != prev
                days[date] = d
                workToday = d
                workVpnUp = up
                workNow = now
                // Сохраняем при первом подключении за день, обрыве — сразу, остальное — раз в 5 минут.
                val important = changed && (d.firstUp != prev?.firstUp || d.drops != prev?.drops || wasUp != up)
                if (changed && (important || Duration.between(lastSave, now).toMinutes() >= 5)) {
                    val snapshot = HashMap(days)
                    withContext(Dispatchers.IO) { workLog.save(snapshot) }
                    lastSave = now
                }
                wasUp = up
                lastTick = now
                delay(20_000)
            }
        }
    }

    fun log(msg: String) {
        scope.launch(Dispatchers.Main) {
            logLines.add("${LocalTime.now().format(timeFmt)}  $msg")
            while (logLines.size > 500) logLines.removeAt(0)
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val old = settings
        val new = transform(old)
        settings = new
        runCatching { new.save(Platform.settingsFile) }.onFailure { error = "Не удалось сохранить настройки: ${it.message}" }
        if (phase == Phase.RUNNING && (old.rules != new.rules || old.russiaDirect != new.russiaDirect)) rulesDirty = true
        if (phase == Phase.RUNNING && old.abroad != new.abroad) restartNeeded = true
        if (!old.gitlab.enabled && new.gitlab.enabled) checkGitLabNow()
        if (old.jira.enabled != new.jira.enabled || old.jira.boardUrl != new.jira.boardUrl) jira.settingsChanged()
    }

    /** Российские серверы, которые возьмёт Run в режиме «из-за рубежа». */
    val ruServers: List<Server> get() = Abroad.ruServers(settings, servers)

    fun selectRuServer(id: String) = update { it.copy(abroad = it.abroad.copy(ruServer = id)) }

    fun releaseKillSwitch() {
        val b = backend ?: return
        scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { b.releaseKillSwitch() } }
            r.onSuccess { killSwitchStuck = false; error = null; log("Блокировка интернета (kill switch) снята") }
                .onFailure { error = it.message }
        }
    }

    // ---------------- действия ----------------

    /**
     * Сохранить вставленное содержимое подписки.
     * target != null — для уже добавленной подписки по ссылке; иначе создаётся ручная подписка с именем name.
     */
    fun importSubscription(name: String, text: String, target: Subscription? = null): Boolean {
        val c = controller ?: return false
        val sub = target ?: Subscription.manual(name.trim().ifBlank { "Вручную" })
        return runCatching { c.importContent(sub, text) }
            .onSuccess {
                if (target == null) update { s -> s.copy(subscriptions = s.subscriptions.filter { it.url != sub.url } + sub) }
                error = null
                refreshServers()
            }
            .onFailure { error = it.message }
            .isSuccess
    }

    fun removeSubscription(sub: Subscription) {
        update { s -> s.copy(subscriptions = s.subscriptions.filter { it.url != sub.url }) }
        controller?.forgetCache(sub)
        refreshServers()
    }

    fun hasSavedCopy(sub: Subscription) = controller?.hasCache(sub) == true

    fun refreshServers() {
        val c = controller ?: return
        if (settings.subscriptions.isEmpty()) { servers = emptyList(); return }
        if (loadingServers) return
        loadingServers = true
        scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { c.loadServers(settings) } }
            r.onSuccess { servers = it }.onFailure { error = it.message }
            loadingServers = false
        }
    }

    fun run() {
        val c = controller ?: return
        if (phase != Phase.STOPPED) return
        if (!isAdmin) { error = "Запустите программу от имени администратора"; return }
        error = null
        phase = Phase.STARTING
        rulesDirty = false
        restartNeeded = false
        scope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    val list = c.loadServers(settings, preferCache = true)
                    withContext(Dispatchers.Main) { servers = list }
                    c.run(settings, list) { scope.launch(Dispatchers.Main) { ciscoUp = true } }
                }
            }
            r.onSuccess {
                exitInfo = c.lastExit
                killSwitchStuck = false
                phase = Phase.RUNNING
                startMonitor()
                // Теперь подписки доступны через VPN — обновляем их и кэш в фоне.
                refreshServers()
            }.onFailure {
                log("Ошибка: ${it.message}")
                error = it.message
                ciscoUp = false
                phase = Phase.STOPPED
                killSwitchStuck = backend?.killSwitchStuck() == true
            }
        }
    }

    fun stop() {
        val c = controller ?: return
        if (phase == Phase.STOPPED || phase == Phase.STOPPING) return
        phase = Phase.STOPPING
        monitorJob?.cancel()
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { c.stop() } }
            ciscoUp = false
            currentServer = null
            rulesDirty = false
            restartNeeded = false
            exitInfo = null
            killSwitchStuck = withContext(Dispatchers.IO) { backend?.killSwitchStuck() == true }
            phase = Phase.STOPPED
        }
    }

    /** Блокирующая остановка при закрытии окна. */
    fun stopBlocking() {
        if (phase == Phase.RUNNING || phase == Phase.STARTING) runCatching { controller?.stop() }
    }

    fun applyRules() {
        val c = controller ?: return
        if (phase != Phase.RUNNING) return
        scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { c.applyRules(settings, servers) } }
            r.onSuccess { rulesDirty = false }.onFailure { error = it.message }
        }
    }

    fun selectServer(id: String) {
        update { it.copy(selectedServer = id) }
        val c = controller ?: return
        if (phase != Phase.RUNNING) return
        val tag = if (id == AppSettings.AUTO) SingBoxConfig.AUTO_TAG
        else servers.firstOrNull { it.id == id }?.let { SingBoxConfig.tagFor(it) } ?: return
        scope.launch(Dispatchers.IO) {
            if (!c.switchServer(tag)) log("Не удалось переключить сервер на лету — перезапустите VPN")
        }
    }

    fun savePassword(value: String) = saveSecret(SecretStore.CISCO_PASSWORD, value) { hasPassword = it }

    fun saveTotp(value: String): Boolean {
        if (!Totp.isValid(value)) { error = "Секрет TOTP не похож на base32 (буквы A–Z и цифры 2–7)"; return false }
        totpSecretCache = null
        saveSecret(SecretStore.CISCO_TOTP, Totp.cleanSecret(value)) { hasTotp = it }
        return true
    }

    fun forgetSecret(key: String) {
        secrets.delete(key)
        when (key) {
            SecretStore.CISCO_PASSWORD -> hasPassword = false
            SecretStore.CISCO_TOTP -> { totpSecretCache = null; hasTotp = false }
            SecretStore.GITLAB_TOKEN -> { gitlabClient = null; hasGitLabToken = false }
            SecretStore.JIRA_TOKEN -> jira.forgetToken()
        }
    }

    /** Текущий код — чтобы сверить с TOTP Vault. */
    // Секрет кэшируется: иначе раз в секунду на UI-потоке запускался бы security / PowerShell.
    private var totpSecretCache: String? = null

    fun totpPreview(): String? {
        val s = totpSecretCache ?: secrets.get(SecretStore.CISCO_TOTP)?.also { totpSecretCache = it } ?: return null
        return runCatching { Totp.code(s) }.getOrNull()
    }

    fun relaunchAsAdmin() {
        if (Platform.relaunchAsAdmin()) kotlin.system.exitProcess(0) else error = "Не удалось перезапустить с правами администратора"
    }

    enum class LogKind { CISCO, SINGBOX, CHAIN }

    fun readLog(kind: LogKind): String {
        val b = backend ?: return ""
        val f = when (kind) { LogKind.CISCO -> b.ciscoLog; LogKind.SINGBOX -> b.singboxLog; LogKind.CHAIN -> b.chainLog }
        return runCatching { f.readLines().takeLast(200).joinToString("\n") }.getOrDefault("(журнал пуст)")
    }

    private fun saveSecret(key: String, value: String, done: (Boolean) -> Unit) {
        if (value.isEmpty()) return
        runCatching { secrets.set(key, value) }
            .onSuccess { done(true) }
            .onFailure { error = it.message }
    }

    private fun startMonitor() {
        monitorJob?.cancel()
        val c = controller ?: return
        monitorJob = scope.launch {
            var deadChecks = 0
            while (isActive) {
                val (cur, alive, cisco) = withContext(Dispatchers.IO) {
                    Triple(c.currentServer(), c.isRunning(), backend?.ciscoAlive() == true)
                }
                currentServer = cur
                ciscoUp = cisco
                if (!alive) {
                    if (++deadChecks >= 3 && phase == Phase.RUNNING) {
                        log("VPN неожиданно остановился — см. журналы")
                        withContext(Dispatchers.IO) { runCatching { c.stop() } }
                        phase = Phase.STOPPED
                        ciscoUp = false
                        currentServer = null
                        exitInfo = null
                        break
                    }
                } else deadChecks = 0
                delay(3000)
            }
        }
    }
}
