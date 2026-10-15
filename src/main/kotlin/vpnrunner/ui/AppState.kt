package vpnrunner.ui

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import vpnrunner.core.*
import vpnrunner.platform.*
import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class Phase { STOPPED, STARTING, RUNNING, STOPPING }

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

    private val backend: VpnBackend? = runCatching { Platform.backend() }.getOrNull()
    private val controller = backend?.let { b -> VpnController(b, secrets) { msg -> log(msg) } }
    private var monitorJob: Job? = null

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")

    init {
        if (backend == null) error = "Эта ОС не поддерживается — нужна macOS или Windows"
        if (backend?.leftovers() == true) {
            phase = Phase.RUNNING
            log("VPN был запущен ранее — можно остановить кнопкой Stop")
            startMonitor()
        }
        if (settings.subscriptions.isNotEmpty()) refreshServers()
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
        scope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    val list = c.loadServers(settings, preferCache = true)
                    withContext(Dispatchers.Main) { servers = list }
                    c.run(settings, list) { scope.launch(Dispatchers.Main) { ciscoUp = true } }
                }
            }
            r.onSuccess {
                phase = Phase.RUNNING
                startMonitor()
                // Теперь подписки доступны через VPN — обновляем их и кэш в фоне.
                refreshServers()
            }.onFailure {
                log("Ошибка: ${it.message}")
                error = it.message
                ciscoUp = false
                phase = Phase.STOPPED
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
        if (key == SecretStore.CISCO_TOTP) totpSecretCache = null
        if (key == SecretStore.CISCO_PASSWORD) hasPassword = false else hasTotp = false
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

    fun readLog(cisco: Boolean): String {
        val b = backend ?: return ""
        val f = if (cisco) b.ciscoLog else b.singboxLog
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
                        break
                    }
                } else deadChecks = 0
                delay(3000)
            }
        }
    }
}
