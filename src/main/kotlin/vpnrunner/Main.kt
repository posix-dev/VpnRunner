package vpnrunner

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import vpnrunner.ui.*

fun main() {
    // Чтобы видеть прокси, включённый в системе другим VPN-клиентом (нужно для скачивания подписок).
    System.setProperty("java.net.useSystemProxies", "true")
    vpnrunner.core.SubscriptionParser.deviceHeaders = vpnrunner.platform.Platform.deviceHeaders()
    app()
}

private fun app() = application {
    val scope = rememberCoroutineScope()
    val state = remember { AppState(scope) }
    val windowState = rememberWindowState(size = DpSize(560.dp, 720.dp))
    var windowVisible by remember { mutableStateOf(true) }
    val quit = {
        state.stopBlocking()
        exitApplication()
    }

    // Значок в строке меню: серый — выключено, жёлтый — подключается, зелёный — работает.
    Tray(
        icon = TrayIcon.forPhase(state.phase),
        tooltip = when (state.phase) {
            Phase.STOPPED -> "VpnRunner — выключен"
            Phase.STARTING -> "VpnRunner — подключается…"
            Phase.STOPPING -> "VpnRunner — отключается…"
            Phase.RUNNING -> "VpnRunner — работает" + (state.currentServer?.let { ": $it" } ?: "")
        },
        onAction = { windowVisible = true },
        menu = {
            Item("Включить", enabled = state.phase == Phase.STOPPED, onClick = { state.run() })
            Item("Выключить", enabled = state.phase == Phase.RUNNING || state.phase == Phase.STARTING, onClick = { state.stop() })
            Separator()
            Item("Открыть окно", onClick = { windowVisible = true })
            Item("Выход", onClick = quit)
        },
    )

    Window(
        // Закрытие окна прячет его в строку меню, VPN продолжает работать. Совсем выйти — «Выход» в меню значка.
        onCloseRequest = { windowVisible = false },
        visible = windowVisible,
        title = "VpnRunner",
        state = windowState,
    ) {
        LaunchedEffect(windowVisible) { if (windowVisible) window.toFront() }
        AppTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                AppScreen(state)
            }
        }
    }
}
