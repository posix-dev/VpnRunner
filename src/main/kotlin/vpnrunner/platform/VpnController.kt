package vpnrunner.platform

import vpnrunner.core.*
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Порядок Run: подписки → Cisco (openconnect) → ждём подключения → sing-box с правилами.
 * Порядок Stop: sing-box → Cisco.
 * Все методы блокирующие — вызывать из фонового потока.
 */
class VpnController(
    private val backend: VpnBackend,
    private val secrets: SecretStore,
    private val log: (String) -> Unit,
) {
    @Volatile var lastCiscoState: CiscoState? = null
        private set

    private val http = HttpClient.newBuilder()
        .proxy(java.net.ProxySelector.of(null))
        .connectTimeout(Duration.ofSeconds(3)).build()

    /** preferCache = true: если есть сохранённая копия — сразу взять её (для быстрого Run; свежая подписка скачается после запуска VPN). */
    fun loadServers(settings: AppSettings, preferCache: Boolean = false): List<Server> {
        val all = ArrayList<Server>()
        val errors = ArrayList<String>()
        for (sub in settings.subscriptions) {
            if (preferCache && !sub.isManual) {
                val cached = cachedServers(sub)
                if (cached.isNotEmpty()) { all += cached; continue }
            }
            if (sub.isManual) {
                val cached = cachedServers(sub)
                if (cached.isNotEmpty()) all += cached else errors += "«${sub.name}»: нет сохранённых серверов — вставьте содержимое заново"
                continue
            }
            try {
                val r = SubscriptionParser.fetch(sub, log)
                log("Подписка «${sub.name}»: серверов ${r.servers.size}" + if (r.skipped.isNotEmpty()) ", пропущено ${r.skipped.size}" else "")
                r.skipped.groupingBy { it }.eachCount().forEach { (reason, n) -> log("  пропущено ($n): $reason") }
                all += r.servers
                cacheServers(sub, r.servers)
            } catch (e: Exception) {
                val cached = cachedServers(sub)
                if (cached.isNotEmpty()) {
                    log("Подписка «${sub.name}» сейчас недоступна — использую сохранённую копию (${cached.size} серв.)")
                    all += cached
                } else errors += "«${sub.name}»: ${e.message}"
            }
        }
        if (all.isNotEmpty()) errors.forEach { log("Подписка $it") }
        if (all.isEmpty()) throw IllegalStateException(
            if (errors.isNotEmpty()) "Не удалось загрузить подписки: ${errors.joinToString("; ")}"
            else "Добавьте хотя бы одну подписку"
        )
        return all
    }

    fun run(settings: AppSettings, servers: List<Server>, onCiscoUp: () -> Unit = {}) {
        val singbox = settings.singboxPath.trim().ifBlank { Platform.findSingBox() ?: "" }
        check(singbox.isNotBlank() && File(singbox).isFile) { "Не найден sing-box — укажите путь в настройках" }

        // Другой VPN не мешает запуску: занятые им рабочие подсети обёртка Cisco заберёт себе,
        // а что всё работает, проверяется по факту после подключения (checkCiscoRoutes, checkWorkDns).
        Platform.connectedVpns().takeIf { it.isNotEmpty() }?.let {
            log("⚠ Включён другой VPN: ${it.joinToString("», «", "«", "»")}. Рабочие подсети Cisco заберу себе, но такие VPN " +
                "обычно перехватывают DNS всей системы — тогда рабочие сайты по имени (jira…) не откроются. Если так — выключите его.")
        }

        backend.prepare()
        lastCiscoState = null

        val cisco = if (settings.cisco.enabled) prepareCisco(settings) else null
        log(if (cisco != null) "Подключаю Cisco (${settings.cisco.server})…" else "Cisco выключен — только VPN")
        backend.start(cisco, singbox)

        var state: CiscoState? = null
        if (cisco != null) {
            state = waitForCisco(settings.cisco)
            lastCiscoState = state
            checkCiscoRoutes(state)
            runCatching { File(backend.runDir, "route-fix.log").readLines() }.getOrDefault(emptyList())
                .forEach { log(it.substringAfter(' ').substringAfter(' ')) } // без даты и времени
            log("Cisco подключён: интерфейс ${state.tunDev}, DNS ${state.dnsServers.joinToString()}, маршрутов ${state.splitRoutes.size}")
            val workDns = (settings.cisco.workDomains.map { SingBoxConfig.normalizeDomain(it) } + state.domains).filter { it.isNotEmpty() }.distinct()
            log(if (workDns.isEmpty()) "DNS Cisco не используется: сервер не прислал рабочие домены — укажите их во вкладке Cisco"
                else "DNS Cisco спрашиваю только про: ${workDns.joinToString()}")
            if (state.serverPushedFullTunnel && settings.cisco.workSubnets.isEmpty()) {
                log("⚠ Сервер Cisco хочет пускать через себя ВЕСЬ трафик. Укажите рабочие подсети во вкладке Cisco, иначе VPN поверх него может не работать.")
            }
            onCiscoUp()
        }

        ruleSetFiles = if (settings.russiaDirect) downloadRuleSets() else emptyMap()
        val cfg = SingBoxConfig.build(settings, servers, state, backend.runDir.path, ruleSetFiles)
        backend.singboxConfig.writeText(Json.write(cfg))
        log("Запускаю VPN (sing-box)…")
        backend.startSingBox()
        waitForSingBox()
        if (state != null) checkWorkDns(state)
        log("Готово: VPN работает. Российские сайты ${if (settings.russiaDirect) "идут напрямую" else "идут через VPN"}.")
    }

    private fun prepareCisco(settings: AppSettings): CiscoLaunch {
        val c = settings.cisco
        check(c.server.isNotBlank()) { "Укажите адрес сервера Cisco" }
        check(c.user.isNotBlank()) { "Укажите логин Cisco" }
        val oc = settings.openconnectPath.trim().ifBlank { Platform.findOpenconnect() ?: "" }
        check(oc.isNotBlank() && File(oc).isFile) { "Не найден openconnect — укажите путь в настройках" }
        val orig = settings.vpncScriptPath.trim().ifBlank { Platform.findVpncScript(oc) ?: "" }
        check(orig.isNotBlank() && File(orig).isFile) { "Не найден vpnc-script — укажите путь в настройках" }
        val password = secrets.get(SecretStore.CISCO_PASSWORD)
        check(!password.isNullOrEmpty()) { "Сохраните пароль Cisco во вкладке Cisco" }
        val totpSecret = secrets.get(SecretStore.CISCO_TOTP)

        val subnets = CiscoWrapper.parseCidrs(c.workSubnets)
        val wrapper = File(backend.runDir, backend.wrapperName())
        backend.runDir.mkdirs()
        wrapper.writeText(backend.wrapperContent(orig, subnets, c.workDomains.map { SingBoxConfig.normalizeDomain(it) }.filter { it.isNotEmpty() }))
        wrapper.setExecutable(true, false)

        // Код Google Authenticator:
        //  - отдельным полем (обычный случай) — openconnect сам генерирует код (--token-mode=totp)
        //    и подставляет его в поле второго пароля / в запрос кода после пароля;
        //  - вместе с паролем — в поле пароля уходит «пароль+код».
        val useToken = !totpSecret.isNullOrBlank() && !c.otpCombined
        val tokenFile = File(backend.runDir, "token.secret")
        tokenFile.delete()
        if (useToken) {
            tokenFile.writeText("base32:" + Totp.cleanSecret(totpSecret!!))
            Fs.restrictToOwner(tokenFile)
        }

        val args = buildList {
            add("--protocol=anyconnect")
            add("--user=${c.user}")
            if (c.authGroup.isNotBlank()) add("--authgroup=${c.authGroup}")
            if (c.serverCert.isNotBlank()) add("--servercert=${c.serverCert}")
            add("--passwd-on-stdin")
            add("--non-inter") // не задавать вопросов — иначе openconnect ждёт ввода и падает
            if (useToken) {
                add("--token-mode=totp")
                add("--token-secret=@${tokenFile.path}")
            }
            add("--reconnect-timeout=60")
            add(c.server)
        }
        pendingTokenFile = if (useToken) tokenFile else null
        return CiscoLaunch(oc, args, wrapper) {
            // openconnect ждёт пароль в stdin и только потом идёт на сервер, так что код
            // (свой или сгенерированный openconnect) появится через секунду-две после этого места.
            if (!totpSecret.isNullOrBlank()) waitForFreshCode()
            buildString {
                append(password)
                if (!totpSecret.isNullOrBlank() && c.otpCombined) append(Totp.code(totpSecret))
                append('\n')
            }
        }
    }

    /** Окно TOTP, код из которого уже уходил на сервер. */
    @Volatile private var lastTotpWindow = -1L

    /**
     * Ждёт новый код, если текущий вот-вот сменится (сервер может получить уже просроченный)
     * или уже отправлялся при прошлой попытке (повторно тот же код сервер обычно отклоняет).
     */
    private fun waitForFreshCode() {
        val reused = Totp.window() == lastTotpWindow
        if (reused || Totp.secondsLeft() < 8) {
            val ms = Totp.secondsLeft() * 1000L + 500
            log("Жду новый код Google Authenticator (${(ms + 999) / 1000} с)…")
            Thread.sleep(ms)
        }
        lastTotpWindow = Totp.window()
    }

    /** Файл с секретом TOTP для openconnect — удаляется сразу после подключения или ошибки. */
    @Volatile private var pendingTokenFile: File? = null

    private fun wipeTokenFile() {
        pendingTokenFile?.let { f -> runCatching { if (f.exists()) { f.writeText("x".repeat(64)); f.delete() } } }
        pendingTokenFile = null
    }

    private fun waitForCisco(c: CiscoSettings, timeoutMs: Long = 90_000): CiscoState {
        val deadline = System.currentTimeMillis() + timeoutMs
        Thread.sleep(1000)
        while (System.currentTimeMillis() < deadline) {
            val f = backend.ciscoStateFile
            if (f.exists()) { wipeTokenFile(); return CiscoState.parse(f.readText()) }
            if (!backend.ciscoAlive() && System.currentTimeMillis() > deadline - timeoutMs + 5000) {
                wipeTokenFile()
                backend.stop()
                throw IllegalStateException("Cisco не подключился: ${explainCiscoFailure(c)}")
            }
            Thread.sleep(500)
        }
        wipeTokenFile()
        backend.stop()
        throw IllegalStateException("Cisco не подключился за ${timeoutMs / 1000} с: ${explainCiscoFailure(c)}")
    }

    private fun explainCiscoFailure(c: CiscoSettings): String {
        val all = runCatching { backend.ciscoLog.readLines() }.getOrDefault(emptyList())
        val text = all.joinToString("\n")
        val authFailed = { line: String ->
            line.contains("Authentication failed", true) || line.contains("Login failed", true) || line.contains("Login denied", true)
        }
        // openconnect пишет это, когда сам подставил код во второе поле формы
        val tokenSent = text.contains("Generating OATH TOTP token code", true)
        val hasTotp = !secrets.get(SecretStore.CISCO_TOTP).isNullOrBlank()
        val hint = when {
            authFailed(text) && (tokenSent || c.otpCombined) ->
                "сервер не принял логин, пароль или код Google Authenticator. Логин должен быть ровно тот, что в AnyConnect " +
                    "(обычно без @домена). Сверьте «Текущий код» во вкладке Cisco с TOTP Vault: не совпадает — сохраните секрет заново"
            authFailed(text) && hasTotp ->
                "сервер не принял логин/пароль/код. Проверьте пароль; если в AnyConnect код вводится в одно поле с паролем — " +
                    "включите во вкладке Cisco «Код вводится вместе с паролем»"
            authFailed(text) -> "сервер не принял логин или пароль. Проверьте логин и сохраните пароль заново"
            text.contains("User input required", true) && c.otpCombined ->
                "сервер спрашивает код отдельным полем — выключите во вкладке Cisco «Код вводится вместе с паролем»"
            text.contains("User input required", true) && !hasTotp ->
                "сервер спрашивает второй пароль (код) — сохраните во вкладке Cisco секрет Google Authenticator"
            text.contains("Certificate from VPN server", true) || text.contains("verify failed", true) ->
                "сертификат сервера не прошёл проверку — укажите его отпечаток (pin-sha256) во вкладке Cisco"
            text.contains("Failed to open tun", true) || text.contains("Failed to open TUN", true) -> "не удалось создать сетевой интерфейс"
            else -> "подробности в журнале Cisco"
        }
        log("Журнал Cisco (последние строки):")
        all.takeLast(25).forEach { log("  $it") }
        // После отказа openconnect снова показывает форму и падает на пустом вводе — эти строки только путают.
        val firstFail = all.indexOfFirst(authFailed)
        val shown = (if (firstFail >= 0) all.take(firstFail + 1) else all).takeLast(6)
        return hint + if (shown.isNotEmpty()) "\n— " + shown.joinToString("\n— ") else ""
    }

    /**
     * Рабочие адреса должны идти через Cisco. Если их маршрут занят (другой VPN или программа
     * уже направила, например, 10.0.0.0/8 через Wi-Fi), Cisco свой маршрут добавить не может.
     */
    private fun checkCiscoRoutes(state: CiscoState) {
        val cidrs = CiscoWrapper.parseCidrs(state.splitRoutes)
        val probe = state.dnsServers.firstOrNull { ip -> cidrs.any { inCidr(ip, it) } } ?: return
        val via = Platform.routeInterface(probe) ?: return
        if (via == state.tunDev) return
        val others = Platform.connectedVpns()
        stop()
        throw IllegalStateException(
            "рабочие адреса Cisco (например, $probe) идут не через Cisco, а через $via. " +
                (if (others.isNotEmpty()) "Выключите ${others.joinToString("», «", "«", "»")} и нажмите Run ещё раз"
                else "Похоже, их перехватывает другая VPN-программа — выключите её и нажмите Run ещё раз")
        )
    }

    /**
     * Проверка по факту: имя из домена Cisco (например, corp.example) через системный DNS должно
     * получить внутренний адрес. Иначе рабочие сайты не откроются — значит, DNS перехватил кто-то другой.
     */
    private fun checkWorkDns(state: CiscoState) {
        val name = state.domains.firstOrNull { it.contains('.') } ?: return
        val cidrs = CiscoWrapper.parseCidrs(state.splitRoutes)
        var addrs: List<String>? = null
        val t = Thread { addrs = runCatching { java.net.InetAddress.getAllByName(name).mapNotNull { it.hostAddress } }.getOrNull() }
        t.isDaemon = true; t.start(); t.join(8000)
        val a = addrs.orEmpty()
        when {
            a.any { ip -> cidrs.any { inCidr(ip, it) } } -> log("Проверка: рабочее имя $name → ${a.first()}, DNS работает")
            else -> log("⚠ Рабочее имя $name не получило внутренний адрес (${a.joinToString().ifEmpty { "нет ответа" }}) — " +
                "системный DNS перехватывает другая программа" +
                Platform.connectedVpns().let { if (it.isNotEmpty()) " (включён ${it.joinToString("», «", "«", "»")})" else "" } +
                ", рабочие сайты могут не открываться")
        }
    }

    private fun inCidr(ip: String, c: CiscoWrapper.Cidr): Boolean {
        fun toLong(a: String) = a.split('.').fold(0L) { acc, p -> (acc shl 8) or (p.toLongOrNull() ?: return -1L) }
        val x = toLong(ip); if (x < 0) return false
        val mask = if (c.maskLen == 0) 0L else (0xFFFFFFFFL shl (32 - c.maskLen)) and 0xFFFFFFFFL
        return (x and mask) == (toLong(c.addr) and mask)
    }

    /** Скачанные списки российских сайтов и IP (тег → путь), их получает sing-box. */
    @Volatile private var ruleSetFiles: Map<String, String> = emptyMap()

    /**
     * Списки российских сайтов и IP скачиваем сами, напрямую, и отдаём sing-box файлами. Если оставить
     * это sing-box, он качает их при старте через ещё не проверенный сервер и падает, когда тот не работает.
     * Обновляем раз в 3 дня; не скачалось — берём старую копию, а без неё sing-box попробует сам через VPN.
     */
    private fun downloadRuleSets(): Map<String, String> {
        val dir = File(Platform.appDir, "rules").apply { mkdirs() }
        val files = LinkedHashMap<String, String>()
        for ((tag, url) in SingBoxConfig.RULE_SETS) {
            val f = File(dir, "$tag.srs")
            if (!f.exists() || System.currentTimeMillis() - f.lastModified() > Duration.ofDays(3).toMillis()) {
                runCatching {
                    val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30)).GET().build()
                    val r = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
                    check(r.statusCode() == 200 && r.body().isNotEmpty()) { "HTTP ${r.statusCode()}" }
                    val tmp = File(dir, "$tag.srs.tmp")
                    tmp.writeBytes(r.body())
                    check(tmp.renameTo(f) || (f.delete() && tmp.renameTo(f))) { "не удалось сохранить файл" }
                }.onFailure { log("Список $tag не скачался напрямую (${it.message ?: it.javaClass.simpleName})" + if (f.exists()) " — беру прошлую копию" else "") }
            }
            if (f.isFile && f.length() > 0) files[tag] = f.path
        }
        return files
    }

    private fun waitForSingBox(timeoutMs: Long = 30_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        Thread.sleep(800)
        while (System.currentTimeMillis() < deadline) {
            if (clashGet("/version") != null) return
            if (!backend.singboxAlive() && System.currentTimeMillis() > deadline - timeoutMs + 3000) break
            Thread.sleep(400)
        }
        val tail = runCatching { backend.singboxLog.readLines().takeLast(6).joinToString("\n— ") }.getOrDefault("")
        stop()
        throw IllegalStateException("VPN (sing-box) не запустился.\n— $tail")
    }

    /** Пересобрать конфиг с новыми правилами и перезапустить только sing-box. */
    fun applyRules(settings: AppSettings, servers: List<Server>) {
        if (settings.russiaDirect && ruleSetFiles.isEmpty()) ruleSetFiles = downloadRuleSets()
        val cfg = SingBoxConfig.build(settings, servers, lastCiscoState, backend.runDir.path, ruleSetFiles)
        backend.singboxConfig.writeText(Json.write(cfg))
        backend.reloadSingBox()
        log("Правила применены")
    }

    fun stop() {
        log("Останавливаю…")
        backend.stop()
        lastCiscoState = null
        log("Остановлено")
    }

    fun isRunning() = backend.singboxAlive() || backend.ciscoAlive()

    // ---- управление запущенным sing-box через Clash API ----

    /** Переключить сервер на лету. tag = SingBoxConfig.tagFor(server) или "auto". */
    fun switchServer(tag: String): Boolean {
        val body = Json.write(mapOf("name" to tag), pretty = false)
        return runCatching {
            val req = HttpRequest.newBuilder(URI("http://${SingBoxConfig.CLASH_API}/proxies/${enc(SingBoxConfig.PROXY_TAG)}"))
                .timeout(Duration.ofSeconds(3))
                .PUT(HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", "application/json").build()
            http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() in 200..299
        }.getOrDefault(false)
    }

    /** Через какой сервер сейчас идёт трафик (для режима «авто» — какой выбран автоматически). */
    fun currentServer(): String? {
        val proxy = clashGet("/proxies/${enc(SingBoxConfig.PROXY_TAG)}")?.let { Json.parse(it).obj().str("now") } ?: return null
        if (proxy != SingBoxConfig.AUTO_TAG) return proxy
        val auto = clashGet("/proxies/${enc(SingBoxConfig.AUTO_TAG)}")?.let { Json.parse(it).obj().str("now") }
        return "авто → ${auto ?: "…"}"
    }

    private fun clashGet(path: String): String? = runCatching {
        val req = HttpRequest.newBuilder(URI("http://${SingBoxConfig.CLASH_API}$path")).timeout(Duration.ofSeconds(2)).GET().build()
        val r = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (r.statusCode() == 200) r.body() else null
    }.getOrNull()

    private fun enc(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

    // ---- кэш подписок: если сервис недоступен, работаем по последней копии ----

    /**
     * Сохраняет вручную вставленное содержимое подписки (base64 или список vless://…).
     * Дальше эти серверы используются, пока подписку не удастся скачать по ссылке.
     */
    fun importContent(sub: Subscription, text: String): SubscriptionParser.Result {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        require(lines.isEmpty() || !lines.all { it.startsWith("http://") || it.startsWith("https://") }) {
            "Это ссылка подписки, а сюда нужно её содержимое. Откройте ссылку в браузере на телефоне " +
                "и скопируйте текст со страницы — длинную строку букв или строки vless://…"
        }
        val r = SubscriptionParser.parse(text, sub.name)
        require(r.servers.isNotEmpty()) {
            "В тексте не нашлось ни одного сервера" + if (r.skipped.isNotEmpty()) " (пропущено: ${r.skipped.distinct().joinToString()})" else ""
        }
        cacheServers(sub, r.servers).getOrThrow()
        log("Подписка «${sub.name}»: сохранено серверов ${r.servers.size}" + if (r.skipped.isNotEmpty()) ", пропущено ${r.skipped.size}" else "")
        return r
    }

    fun hasCache(sub: Subscription) = cacheFile(sub).exists()

    fun forgetCache(sub: Subscription) { cacheFile(sub).delete() }

    private fun cacheFile(sub: Subscription) =
        File(Platform.appDir, "cache/" + sub.url.hashCode().toUInt().toString(16) + ".json")

    private fun cacheServers(sub: Subscription, servers: List<Server>) = runCatching {
        val f = cacheFile(sub)
        f.parentFile.mkdirs()
        f.writeText(Json.write(servers.map { mapOf("id" to it.id, "name" to it.name, "sub" to it.subscription, "outbound" to it.outbound) }, pretty = false))
        Fs.restrictToOwner(f)
    }

    private fun cachedServers(sub: Subscription): List<Server> = runCatching {
        Json.parse(cacheFile(sub).readText()).arr().map { it.obj() }.map {
            Server(it.str("id")!!, it.str("name")!!, it.str("sub")!!, it["outbound"].obj())
        }.filterNot { SubscriptionParser.isStub(it.outbound) }
    }.getOrDefault(emptyList())
}
