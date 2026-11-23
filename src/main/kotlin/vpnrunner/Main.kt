package vpnrunner

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import vpnrunner.platform.KeepAwake
import vpnrunner.ui.*
import java.awt.Taskbar
import java.io.File
import javax.imageio.ImageIO
import kotlin.system.exitProcess

fun main() {
    relaunchOnJcefJava()
    // Чтобы видеть прокси, включённый в системе другим VPN-клиентом (нужно для скачивания подписок).
    System.setProperty("java.net.useSystemProxies", "true")
    vpnrunner.core.SubscriptionParser.deviceHeaders = vpnrunner.platform.Platform.deviceHeaders()
    app()
}

/**
 * Зелёная стрелка у `main` в Android Studio / IntelliJ запускает программу на Java самой IDE — в ней нет
 * Chromium, и вкладка «Браузер» не работает. Если рядом установлена JetBrains Runtime с JCEF (та же, что ищет
 * build.gradle.kts), перезапускаемся на ней с теми же классами и ждём завершения: окно IDE «Run» видит
 * вывод программы, а кнопка «Stop» останавливает и её. Отладчик IDE к новому процессу не подключается.
 * Собранное приложение (.dmg) не трогаем — у него своя Java.
 */
private fun relaunchOnJcefJava() {
    if (!BrowserEngine.TAB_ENABLED || vpnrunner.platform.Platform.os != vpnrunner.platform.Os.MAC) return
    if (System.getProperty("jpackage.app-path") != null || System.getenv("VPNRUNNER_RELAUNCHED") != null) return
    if (ModuleLayer.boot().findModule("jcef").isPresent) return
    val home = File(System.getProperty("user.home"), "Library/Java/JavaVirtualMachines").listFiles()
        ?.filter { it.name.startsWith("jbrsdk_jcef-17") }?.maxByOrNull { it.name }
        ?.resolve("Contents/Home") ?: return
    val java = File(home, "bin/java").takeIf { it.canExecute() } ?: return
    val classPath = System.getProperty("java.class.path").takeUnless { it.isNullOrBlank() } ?: return
    println("VpnRunner: перезапуск на ${home.parentFile.parentFile.name} — для вкладки «Браузер»")
    // Флаги --add-opens — те же, что в build.gradle.kts: Chromium встраивается в окно через внутренние классы AWT.
    val opens = listOf("java.desktop/sun.awt", "java.desktop/java.awt.peer", "java.desktop/sun.lwawt", "java.desktop/sun.lwawt.macosx")
        .flatMap { listOf("--add-opens", "$it=ALL-UNNAMED") }
    val cmd = listOf(java.path) + opens + listOf("-Dfile.encoding=UTF-8", "-cp", classPath, "vpnrunner.MainKt")
    val child = runCatching {
        ProcessBuilder(cmd).inheritIO().apply { environment()["VPNRUNNER_RELAUNCHED"] = "1" }.start()
    }.getOrElse { println("VpnRunner: не удалось перезапуститься (${it.message}) — работаю без браузера"); return }
    // «Stop» в IDE завершает этот процесс — вместе с ним завершаем и программу.
    Runtime.getRuntime().addShutdownHook(Thread { if (child.isAlive) child.destroy() })
    exitProcess(child.waitFor())
}

private fun app() = application {
    val scope = rememberCoroutineScope()
    val state = remember { AppState(scope) }
    installDockIcon()
    val windowState = rememberWindowState(size = DpSize(560.dp, 720.dp))
    val reviewWindowSize = DpSize(1000.dp, 820.dp)
    var sizeBeforeReview by remember { mutableStateOf<DpSize?>(null) }
    LaunchedEffect(state.selectedGitLabMr) {
        if (state.selectedGitLabMr != null) {
            if (sizeBeforeReview == null) sizeBeforeReview = windowState.size
            windowState.size = reviewWindowSize
        } else {
            sizeBeforeReview?.let { previous ->
                if (windowState.size == reviewWindowSize) windowState.size = previous
            }
            sizeBeforeReview = null
        }
    }
    var windowVisible by remember { mutableStateOf(true) }
    val trayState = rememberTrayState()
    // Рабочие напоминания, новые MR и назначения Jira — уведомлением macOS / Windows от значка
    LaunchedEffect(Unit) {
        state.notifications.collect {
            trayState.sendNotification(Notification(it.title, it.text, if (it.warning) Notification.Type.Warning else Notification.Type.Info))
        }
    }
    // Пока VPN включён — компьютер и экран не засыпают (во сне Cisco отключается)
    LaunchedEffect(state.phase, state.settings.keepAwake) {
        KeepAwake.set(state.settings.keepAwake && (state.phase == Phase.RUNNING || state.phase == Phase.STARTING), state::log)
    }
    val quit = {
        state.stopBlocking()
        KeepAwake.set(false)
        BrowserEngine.dispose()
        exitApplication()
    }

    // Знак VpnRunner в строке меню меняет цвет по состоянию подключения.
    Tray(
        state = trayState,
        icon = AppIconPainter.forPhase(state.phase),
        tooltip = when (state.phase) {
            Phase.STOPPED -> "VpnRunner — выключен"
            Phase.STARTING -> "VpnRunner — подключается…"
            Phase.STOPPING -> "VpnRunner — отключается…"
            Phase.RUNNING -> "VpnRunner — работает" + (state.currentServer?.let { ": $it" } ?: "")
        },
        onAction = { windowVisible = true },
        menu = {
            Item("Включить", enabled = state.phase == Phase.STOPPED, onClick = { state.run() })
            // До конца рабочего дня спросит подтверждение — поэтому сначала показываем окно
            Item("Выключить", enabled = state.phase == Phase.RUNNING || state.phase == Phase.STARTING, onClick = { windowVisible = true; state.requestStop() })
            Separator()
            Item("Открыть окно", onClick = { windowVisible = true })
            Item("Выход", onClick = { windowVisible = true; state.requestQuit(quit) })
        },
    )

    Window(
        // Закрытие окна прячет его в строку меню, VPN продолжает работать. Совсем выйти — «Выход» в меню значка.
        onCloseRequest = { windowVisible = false },
        visible = windowVisible,
        title = "VpnRunner",
        icon = AppIconPainter.Window,
        state = windowState,
    ) {
        LaunchedEffect(windowVisible) { if (windowVisible) window.toFront() }
        state.confirm?.let { c ->
            AlertDialog(
                onDismissRequest = { state.confirm = null },
                title = { Text("Рабочее время ещё не закончилось") },
                text = { Text(c.text) },
                confirmButton = { TextButton(onClick = { state.confirm = null; c.action() }) { Text(c.confirmLabel) } },
                dismissButton = { Button(onClick = { state.confirm = null }) { Text("Оставить включённым") } },
            )
        }
        AppTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                AppScreen(state)
            }
        }
    }
}

/** Gradle/IDE-запуск не использует .app bundle, поэтому явно задаём значок Dock через AWT. */
private fun installDockIcon() {
    if (vpnrunner.platform.Platform.os != vpnrunner.platform.Os.MAC) return
    runCatching {
        if (!Taskbar.isTaskbarSupported()) return
        val taskbar = Taskbar.getTaskbar()
        if (!taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) return
        val image = AppIconPainter::class.java.getResourceAsStream("/vpnrunner.png")?.use { ImageIO.read(it) } ?: return
        taskbar.setIconImage(image)
    }
}
