package vpnrunner.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multiplatform.webview.web.WebView
import com.multiplatform.webview.web.rememberWebViewNavigator
import com.multiplatform.webview.web.rememberWebViewState
import dev.datlag.kcef.KCEF
import dev.datlag.kcef.KCEFAcknowledge
import org.cef.CefApp
import org.cef.CefSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import vpnrunner.platform.Os
import vpnrunner.platform.Platform
import java.io.File

/**
 * Движок встроенного браузера — Chromium из JetBrains Runtime (JBR) с JCEF: программу нужно запускать
 * на этой Java (см. build.gradle.kts). Ничего не скачивается. Кэш и куки — в папке настроек,
 * поэтому вход на сайты сохраняется между запусками.
 *
 * На обычной Java браузер не запускается: KCEF тогда качает свой Chromium, но на macOS ищет его рядом
 * с запущенной Java и роняет программу целиком (SIGSEGV) — вместе с ней пропало бы и управление VPN.
 */
object BrowserEngine {
    /**
     * Вкладка «Браузер» временно скрыта: ссылки (MR, задачи Jira) открываются в обычном браузере,
     * программа не перезапускается на JBR с JCEF. Вернуть вкладку — true.
     */
    const val TAB_ENABLED = false

    sealed interface Status {
        data object NotStarted : Status
        data object Preparing : Status
        data object Ready : Status
        data object NeedsJbr : Status
        data class Failed(val message: String) : Status
    }

    var status by mutableStateOf<Status>(Status.NotStarted)
        private set

    // Своя область: запуск не должен прерываться, если пользователь ушёл с вкладки.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Параметры запуска Chromium из JBR: пути к фреймворку, helper-процессу и т.п. null — JCEF в этой Java нет. */
    private fun jbrCef(): Pair<Array<String>, String?>? = runCatching {
        if (ModuleLayer.boot().findModule("jcef").isEmpty) return null
        // На macOS Chromium лежит рядом с Java (Contents/Frameworks); в урезанной сборке его может не быть.
        if (Platform.os == Os.MAC && !File(System.getProperty("java.home"), "../Frameworks/Chromium Embedded Framework.framework").isDirectory) return null
        val cls = Class.forName("com.jetbrains.cef.JCefAppConfig")
        val cfg = cls.getMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        val args = cls.getMethod("getAppArgs").invoke(cfg) as Array<String>
        val cefSettings = cls.getMethod("getCefSettings").invoke(cfg)
        val subprocess = runCatching { cefSettings.javaClass.getField("browser_subprocess_path").get(cefSettings) as String? }.getOrNull()
        args to subprocess
    }.getOrNull()

    fun start() {
        if (status != Status.NotStarted) return
        val jbr = jbrCef()
        if (jbr == null) { status = Status.NeedsJbr; return }
        status = Status.Preparing
        val dir = File(Platform.appDir, "browser")
        scope.launch {
            // CefApp запускаем сами, с параметрами из JBR: KCEF на этом пути создаёт его без них,
            // и Chromium не находит свои ресурсы (icudtl.dat) — падает. Готовый экземпляр отдаём KCEF.
            runCatching {
                val (args, subprocess) = jbr
                CefApp.startup(args)
                val settings = CefSettings().apply {
                    cache_path = File(dir, "cache").absolutePath
                    persist_session_cookies = true
                    subprocess?.let { browser_subprocess_path = it }
                }
                val app = CefApp.getInstance(args, settings)
                app.onInitialization { state -> if (state == CefApp.CefAppState.INITIALIZED) status = Status.Ready }
                @OptIn(KCEFAcknowledge::class)
                KCEF.init(app)
            }.onFailure { status = Status.Failed(it.message ?: it.javaClass.simpleName) }
        }
    }

    /** При выходе из программы — иначе процессы Chromium могут остаться висеть. */
    fun dispose() {
        if (status == Status.Ready) runCatching { KCEF.disposeBlocking() }
    }
}

@Composable
fun BrowserTab(state: AppState) {
    LaunchedEffect(Unit) { BrowserEngine.start() }
    when (val s = BrowserEngine.status) {
        BrowserEngine.Status.Ready -> Browser(state.settings.browserUrl) { url ->
            if (url != state.settings.browserUrl) state.update { it.copy(browserUrl = url) }
        }
        BrowserEngine.Status.NotStarted, BrowserEngine.Status.Preparing ->
            EngineMessage("Запускаю встроенный браузер…", progress = null)
        BrowserEngine.Status.NeedsJbr ->
            EngineMessage("Встроенному браузеру нужна Java «JetBrains Runtime с JCEF» — программа сейчас запущена на другой. " +
                "Как установить — в README, раздел «Вкладка Браузер».", progress = null, done = true)
        is BrowserEngine.Status.Failed ->
            EngineMessage("Не удалось запустить браузер: ${s.message}", progress = null, done = true)
    }
}

@Composable
private fun EngineMessage(text: String, progress: Float?, done: Boolean = false) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, fontSize = 14.sp)
        if (!done) {
            Spacer(Modifier.height(12.dp))
            if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.width(280.dp))
            else LinearProgressIndicator(Modifier.width(280.dp))
        }
    }
}

/** startUrl — откуда начать (последний адрес); onUrl — вызывается при переходе на новую страницу. */
@Composable
fun Browser(startUrl: String, onUrl: (String) -> Unit) {
    // Открываем последний адрес — так вкладка возвращается туда, где была (например, на доску Jira).
    val start = remember { startUrl }
    val web = rememberWebViewState(start.ifBlank { "about:blank" })
    val nav = rememberWebViewNavigator()
    var address by remember { mutableStateOf(start) }

    LaunchedEffect(web.lastLoadedUrl) {
        val url = web.lastLoadedUrl ?: return@LaunchedEffect
        if (!url.startsWith("http")) return@LaunchedEffect
        address = url
        onUrl(url)
    }

    fun open() {
        val text = address.trim()
        if (text.isEmpty()) return
        val url = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(text)) text else "https://$text"
        address = url
        nav.loadUrl(url)
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { nav.navigateBack() }, enabled = nav.canGoBack, contentPadding = PaddingValues(8.dp)) { Text("←", fontSize = 18.sp) }
            TextButton(onClick = { nav.navigateForward() }, enabled = nav.canGoForward, contentPadding = PaddingValues(8.dp)) { Text("→", fontSize = 18.sp) }
            TextButton(onClick = { nav.reload() }, contentPadding = PaddingValues(8.dp)) { Text("⟳", fontSize = 18.sp) }
            OutlinedTextField(
                address, { address = it },
                singleLine = true,
                placeholder = { Text("Адрес, например jira.company.ru") },
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) { open(); true } else false
                },
            )
            TextButton(
                onClick = { address.trim().takeIf { it.startsWith("http") }?.let { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(it)) } } },
                enabled = address.startsWith("http"),
            ) { Text("В браузере", fontSize = 12.sp) }
        }
        if (web.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        else Spacer(Modifier.height(4.dp))
        // Chromium при ошибке загрузки (например, недоверенный сертификат) оставляет пустую страницу без объяснений.
        // -3 (ERR_ABORTED) — загрузку прервал сам сайт или переход на другую страницу, это не ошибка.
        web.errorsForCurrentRequest.lastOrNull { it.isFromMainFrame && it.code != -3 }?.let { e ->
            val text = if (e.code in -299..-200)
                "Страница не открылась: компьютер не доверяет сертификату сайта (${e.description}). " +
                    "Обычно это значит, что не установлен корневой сертификат компании — как установить, в README, раздел «Вкладка Браузер»."
            else "Страница не открылась: ${e.description} (код ${e.code})."
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(text, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
            }
        }
        if (start.isBlank() && web.lastLoadedUrl.isNullOrBlank()) {
            Text(
                "Введите адрес сайта и нажмите Enter. Последняя открытая страница запоминается, вход на сайты сохраняется.",
                fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        WebView(web, Modifier.fillMaxSize(), navigator = nav)
    }
}
