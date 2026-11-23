package vpnrunner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import vpnrunner.BuildInfo
import vpnrunner.core.*
import vpnrunner.platform.Os
import vpnrunner.platform.Platform
import vpnrunner.platform.SecretStore

private val Green = Color(0xFF2E9E5B)
private val Amber = Color(0xFFD9922B)
private val Gray = Color(0xFF8A8F98)
private val DarkBlue = Color(0xFF86A6FF)
private val LightBlue = Color(0xFF365FC5)

private val PlexSansFamily = FontFamily(
    Font("fonts/ibm-plex/IBMPlexSans-Regular.ttf", FontWeight.Normal),
    Font("fonts/ibm-plex/IBMPlexSans-Medium.ttf", FontWeight.Medium),
    Font("fonts/ibm-plex/IBMPlexSans-SemiBold.ttf", FontWeight.SemiBold),
)
internal val PlexMonoFamily = FontFamily(
    Font("fonts/ibm-plex/IBMPlexMono-Regular.ttf", FontWeight.Normal),
    Font("fonts/ibm-plex/IBMPlexMono-Medium.ttf", FontWeight.Medium),
    Font("fonts/ibm-plex/IBMPlexMono-SemiBold.ttf", FontWeight.SemiBold),
)

private fun Typography.withPlexSans() = copy(
    displayLarge = displayLarge.copy(fontFamily = PlexSansFamily),
    displayMedium = displayMedium.copy(fontFamily = PlexSansFamily),
    displaySmall = displaySmall.copy(fontFamily = PlexSansFamily),
    headlineLarge = headlineLarge.copy(fontFamily = PlexSansFamily),
    headlineMedium = headlineMedium.copy(fontFamily = PlexSansFamily),
    headlineSmall = headlineSmall.copy(fontFamily = PlexSansFamily),
    titleLarge = titleLarge.copy(fontFamily = PlexSansFamily),
    titleMedium = titleMedium.copy(fontFamily = PlexSansFamily),
    titleSmall = titleSmall.copy(fontFamily = PlexSansFamily),
    bodyLarge = bodyLarge.copy(fontFamily = PlexSansFamily),
    bodyMedium = bodyMedium.copy(fontFamily = PlexSansFamily),
    bodySmall = bodySmall.copy(fontFamily = PlexSansFamily),
    labelLarge = labelLarge.copy(fontFamily = PlexSansFamily),
    labelMedium = labelMedium.copy(fontFamily = PlexSansFamily),
    labelSmall = labelSmall.copy(fontFamily = PlexSansFamily),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(
        primary = DarkBlue, onPrimary = Color(0xFF14254D), primaryContainer = Color(0xFF2C3856), onPrimaryContainer = Color(0xFFD9E2FF),
        secondary = Color(0xFF63CF96), onSecondary = Color(0xFF102D1E), secondaryContainer = Color(0xFF233D30), onSecondaryContainer = Color(0xFFD2F4DF),
        background = Color(0xFF171A20), onBackground = Color(0xFFEDF0F5), surface = Color(0xFF22262E), onSurface = Color(0xFFEDF0F5),
        surfaceVariant = Color(0xFF282D36), onSurfaceVariant = Color(0xFF9BA4B2), outline = Color(0xFF454D5A), outlineVariant = Color(0xFF363D48),
    ) else lightColorScheme(
        primary = LightBlue, onPrimary = Color.White, primaryContainer = Color(0xFFEAF0FF), onPrimaryContainer = Color(0xFF233D82),
        secondary = Color(0xFF258553), onSecondary = Color.White, secondaryContainer = Color(0xFFE9F6EF), onSecondaryContainer = Color(0xFF174A30),
        background = Color(0xFFF4F6F9), onBackground = Color(0xFF202630), surface = Color.White, onSurface = Color(0xFF202630),
        surfaceVariant = Color(0xFFF8F9FB), onSurfaceVariant = Color(0xFF77808D), outline = Color(0xFFD5DAE2), outlineVariant = Color(0xFFE3E7ED),
    )
    MaterialTheme(colorScheme = scheme, typography = Typography().withPlexSans(), content = content)
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(state: AppState) {
    var tab by remember { mutableStateOf(0) }
    val mrCount = state.mergeRequests.size.takeIf { state.settings.gitlab.enabled && it > 0 }
    val tabs = listOf("Главная", "GitLab" + (mrCount?.let { " ($it)" } ?: ""), "Jira", "Журнал") +
        listOfNotNull("Браузер".takeIf { BrowserEngine.TAB_ENABLED })
    val tabScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val wheelStep = with(LocalDensity.current) { 48.dp.toPx() }
    // Вкладка «Браузер» открывается с последнего адреса — подставляем ссылку (MR, задача) и переключаемся на неё.
    // Встроенного браузера нет (программа не на JBR с JCEF) — открываем в обычном.
    val openInBrowserTab: (String) -> Unit = { url ->
        if (BrowserEngine.TAB_ENABLED) BrowserEngine.start()
        val s = BrowserEngine.status
        if (!BrowserEngine.TAB_ENABLED || s is BrowserEngine.Status.NeedsJbr || s is BrowserEngine.Status.Failed) openInSystemBrowser(url)
        else { state.update { it.copy(browserUrl = url) }; tab = tabs.indexOf("Браузер") }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).background(MaterialTheme.colorScheme.surface).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("VpnRunner", fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.1.sp)
            Spacer(Modifier.width(8.dp))
            Text("Версия ${BuildInfo.version}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            val (statusText, statusColor) = when (state.phase) {
                Phase.RUNNING -> "VPN работает" to Green
                Phase.STARTING -> "Подключаюсь" to Amber
                Phase.STOPPING -> "Отключаюсь" to Amber
                Phase.STOPPED -> "VPN выключен" to Gray
            }
            Box(Modifier.size(9.dp).clip(CircleShape).background(statusColor))
            Spacer(Modifier.width(8.dp))
            Text(statusText, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Верхняя навигация остаётся компактной и прокручивается колесом на узком окне.
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.onPointerEvent(PointerEventType.Scroll) { e ->
                    val change = e.changes.firstOrNull() ?: return@onPointerEvent
                    val d = change.scrollDelta
                    if (change.isConsumed || d.x != 0f || d.y == 0f) return@onPointerEvent
                    change.consume()
                    scope.launch { tabScroll.scrollBy(d.y * wheelStep) }
                },
            ) {
                Row(Modifier.fillMaxWidth().horizontalScroll(tabScroll).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    tabs.forEachIndexed { i, title ->
                        val selected = tab == i
                        Surface(
                            onClick = { tab = i },
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        ) {
                            Text(
                                title, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), maxLines = 1,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        state.error?.let { msg ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    SelectionContainer(Modifier.weight(1f)) {
                        Text(msg, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 13.sp)
                    }
                    TextButton(onClick = { state.error = null }) { Text("Скрыть") }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> HomeTab(state) { tab = 2 }
                1 -> state.selectedGitLabMr?.let { GitLabMrPage(state, it, openInBrowserTab) }
                    ?: GitLabTab(state, state::openGitLabMr)
                2 -> JiraTab(state, openInBrowserTab)
                3 -> LogTab(state)
                4 -> BrowserTab(state)
            }
        }
        }
    }
}

// ============================================================================ Главная

/** Рабочий день: когда сегодня подключился Cisco, сколько осталось до конца, были ли обрывы. */
@Composable
private fun WorkDayCard(state: AppState) {
    val h = state.settings.workHours
    val now = state.workNow
    // По выходным карточки нет совсем
    if (!h.enabled || !state.settings.cisco.enabled || !h.isWorkday(now)) return
    val d = state.workToday
    val hm = WorkClock.HHMM
    val warn = MaterialTheme.colorScheme.error
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Рабочий день, ${h.start.format(hm)}–${h.end.format(hm)} МСК", fontWeight = FontWeight.SemiBold)
            val t = now.toLocalTime()
            val first = d.firstUp
            val (line, color) = when {
                first == null && t < h.start -> "Cisco ещё не подключался — нужно к ${h.start.format(hm)}" to Gray
                first == null -> "Cisco сегодня не подключался, уже ${t.format(hm)}" to warn
                first > h.start -> "Cisco с ${first.format(hm)} — позже ${h.start.format(hm)}" to Amber
                else -> "Cisco с ${first.format(hm)} ✓" to Green
            }
            Text(line, color = color, fontSize = 14.sp)
            val last = d.lastUp
            when {
                state.workVpnUp && t < h.end ->
                    Text("До ${h.end.format(hm)} осталось ${WorkClock.duration(java.time.Duration.between(t, h.end))}", fontSize = 13.sp)
                state.workVpnUp -> Text("${h.end.format(hm)} прошло — можно выключать ✓", color = Green, fontSize = 13.sp)
                h.isWorkTime(now) -> Text("Сейчас Cisco не подключён — нажмите Run", color = warn, fontSize = 13.sp)
                last != null && t >= h.end && last < h.end -> Text("Cisco выключен в ${last.format(hm)} — раньше ${h.end.format(hm)}", color = Amber, fontSize = 13.sp)
            }
            if (d.drops > 0) Text("Обрывов в рабочее время: ${d.drops}", fontSize = 12.sp, color = Gray)
            if (d.upSeconds >= 60) Text("Всего подключён (пока открыта программа): ${WorkClock.duration(java.time.Duration.ofSeconds(d.upSeconds))}", fontSize = 12.sp, color = Gray)
        }
    }
}

@Composable
private fun HomeTab(state: AppState, onOpenJira: () -> Unit) {
    var settingsSection by remember { mutableStateOf<Int?>(null) }
    val section = settingsSection
    if (section != null) HomeVpnSettings(state, section) { settingsSection = null }
    else HomeDashboard(state, onSettings = { settingsSection = it }, onOpenJira = onOpenJira)
}

@Composable
private fun HomeDashboard(state: AppState, onSettings: (Int) -> Unit, onOpenJira: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val now = WorkClock.now()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Подключение", fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
                Text("Состояние туннелей и быстрые действия", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(now.format(DateTimeFormatter.ofPattern("EE, d MMM", java.util.Locale.forLanguageTag("ru"))),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (Platform.os == Os.WINDOWS && !state.isAdmin) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Нужны права администратора", fontWeight = FontWeight.SemiBold)
                    Text("Без них Windows не даст создать VPN-интерфейсы.", fontSize = 13.sp)
                    Button(onClick = { state.relaunchAsAdmin() }) { Text("Перезапустить от имени администратора") }
                }
            }
        }

        val abroad = state.settings.abroad
        AbroadHomeControls(state)

        if (!abroad.enabled) {
            RussiaDirectCard(state)
        }

        if (state.killSwitchStuck && state.phase == Phase.STOPPED) KillSwitchStuckCard(state)
        if (state.restartNeeded) RestartBanner()
        HomeConnectionCard(state)
        HomeConnectionMetrics(state)
        if (!abroad.enabled) ServerPicker(state)
        WorkDayCard(state)
        if (state.rulesDirty) ApplyRulesBanner(state)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Быстрый доступ", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { onSettings(0) }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Все настройки VPN  →") }
        }
        HomeQuickCard("Cisco", if (state.settings.cisco.enabled) "Подключение, доступы и рабочие сети" else "Подключение Cisco выключено", "◉", Modifier.fillMaxWidth()) { onSettings(0) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeQuickCard("Подписки", "${state.settings.subscriptions.size} подключено · ${state.servers.size} серверов", "⇄", Modifier.weight(1f)) { onSettings(1) }
            HomeQuickCard("Правила сайтов", "${state.settings.rules.size} правил маршрутизации", "⌁", Modifier.weight(1f)) { onSettings(2) }
        }
        val newTasks = state.jira.pendingAssignmentKeys.size
        if (newTasks > 0) Card(
            Modifier.fillMaxWidth().clickable(onClick = onOpenJira),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("▣", color = MaterialTheme.colorScheme.primary, fontSize = 15.sp)
                Spacer(Modifier.width(9.dp))
                Text("$newTasks новых задач Jira назначены на вас", Modifier.weight(1f), fontSize = 12.sp)
                Text("Открыть →", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun HomeConnectionCard(state: AppState) {
    val phase = state.phase
    val busy = phase == Phase.STARTING || phase == Phase.STOPPING
    val active = phase == Phase.RUNNING
    val (headline, caption) = when (phase) {
        Phase.RUNNING -> "Защищённое подключение активно" to "Cisco и VPN по подписке работают штатно"
        Phase.STARTING -> "Подключаю защищённое соединение" to "Подождите, пока поднимутся туннели"
        Phase.STOPPING -> "Отключаю соединение" to "Останавливаю VPN и Cisco"
        Phase.STOPPED -> "Подключение выключено" to "Запустите VPN, когда будете готовы"
    }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                Modifier.size(44.dp),
                shape = RoundedCornerShape(13.dp),
                color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (busy) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
                    else Text(if (active) "✓" else "↗", fontSize = 20.sp,
                        color = if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(headline, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(caption, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Button(
                onClick = { if (phase == Phase.STOPPED) state.run() else state.requestStop() },
                enabled = !busy,
                modifier = Modifier.height(46.dp),
                shape = RoundedCornerShape(11.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                Text(when (phase) { Phase.STOPPED -> "Run"; Phase.STARTING -> "Подключаю…"; Phase.RUNNING -> "Отключить"; Phase.STOPPING -> "Отключаю…" },
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

@Composable
private fun HomeConnectionMetrics(state: AppState) {
    val cisco = when {
        !state.settings.cisco.enabled -> "Выключен" to MaterialTheme.colorScheme.onSurfaceVariant
        state.ciscoUp -> ("Подключён" + state.workToday.firstUp?.let { " · ${it.format(WorkClock.HHMM)}" }.orEmpty()) to Green
        state.phase == Phase.STARTING -> "Подключается…" to Amber
        else -> "Отключён" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val selected = if (state.settings.selectedServer == AppSettings.AUTO) "Авто"
    else state.servers.firstOrNull { it.id == state.settings.selectedServer }?.name ?: "Авто"
    val vpn = if (state.phase == Phase.RUNNING) state.currentServer ?: selected else selected
    val route = if (state.settings.abroad.enabled) {
        if (state.settings.abroad.allViaRu) "Всё → российский VPN" else "Работа → Cisco"
    } else "Работа → Cisco"
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HomeMetric("РАБОЧИЙ VPN", cisco.first, cisco.second, Modifier.weight(1f))
        HomeMetric("VPN-СЕРВЕР", vpn, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
        HomeMetric("МАРШРУТЫ", route, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
    }
}

@Composable
private fun HomeMetric(label: String, value: String, color: Color, modifier: Modifier) {
    Card(modifier, shape = RoundedCornerShape(11.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, fontSize = 11.sp, letterSpacing = 0.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(value, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RussiaDirectCard(state: AppState) {
    Card(shape = RoundedCornerShape(11.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(30.dp), shape = RoundedCornerShape(9.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Box(contentAlignment = Alignment.Center) { Text("↗", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary) }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("Российские сайты мимо VPN", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(".ru, .рф, .su и российские сервисы", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = state.settings.russiaDirect, onCheckedChange = { v -> state.update { it.copy(russiaDirect = v) } })
        }
    }
}

@Composable
private fun HomeQuickCard(title: String, subtitle: String, glyph: String, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(11.dp)) {
        Row(Modifier.padding(horizontal = 11.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Surface(Modifier.size(31.dp), shape = RoundedCornerShape(9.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Box(contentAlignment = Alignment.Center) { Text(glyph, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp)
        }
    }
}

@Composable
private fun HomeVpnSettings(state: AppState, initialSection: Int, onBack: () -> Unit) {
    var selected by remember { mutableStateOf(initialSection) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("‹  Главная") }
            Text("Настройки", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected == 0, { selected = 0 }, label = { Text("Cisco") })
            FilterChip(selected == 1, { selected = 1 }, label = { Text("Подписки (${state.settings.subscriptions.size})") })
            FilterChip(selected == 2, { selected = 2 }, label = { Text("Правила (${state.settings.rules.size})") })
        }
        Box(Modifier.fillMaxSize()) {
            when (selected) {
                0 -> CiscoTab(state)
                1 -> SubscriptionsTab(state)
                else -> RulesTab(state)
            }
        }
    }
}

@Composable
private fun AbroadHomeControls(state: AppState) {
    val a = state.settings.abroad
    var expanded by remember { mutableStateOf(false) }
    val autoFound = state.servers.filter { Abroad.looksRussian(it) }
    fun upd(t: (AbroadSettings) -> AbroadSettings) = state.update { it.copy(abroad = t(it.abroad)) }
    Card(shape = RoundedCornerShape(11.dp)) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = a.enabled, onCheckedChange = { v -> upd { it.copy(enabled = v) } })
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Я сейчас за рубежом", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (a.enabled) "Cisco подключится через российский VPN" else "Обычный режим подключения",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (a.enabled) "Включён" else "Выключен", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (a.enabled) {
                if (state.restartNeeded) RestartBanner()
                val selectedName = if (a.ruServer == AppSettings.AUTO) "Авто — самый быстрый российский"
                else state.servers.firstOrNull { it.id == a.ruServer }?.name ?: "Авто — самый быстрый российский"
                Card(shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Surface(Modifier.size(29.dp), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Box(contentAlignment = Alignment.Center) { Text("🇷🇺", fontSize = 13.sp) }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(selectedName, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${autoFound.size} российских серверов найдено", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box {
                            OutlinedButton(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)) {
                                Text("Сменить  ⌄", fontSize = 11.sp)
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                DropdownMenuItem(text = { Text("Авто — самый быстрый из российских (${autoFound.size})") },
                                    onClick = { state.selectRuServer(AppSettings.AUTO); expanded = false })
                                state.servers.sortedByDescending { Abroad.looksRussian(it) }.forEach { server ->
                                    DropdownMenuItem(text = { Text("${server.name} · ${server.subscription}", maxLines = 1) },
                                        onClick = { state.selectRuServer(server.id); expanded = false })
                                }
                            }
                        }
                    }
                }
                SettingSwitchRow("Весь трафик через российский VPN",
                    if (a.allViaRu) "Все сайты видят российский адрес" else "Остальное идёт напрямую через местный интернет",
                    a.allViaRu) { v -> upd { it.copy(allViaRu = v) } }
                SettingSwitchRow("Kill switch",
                    if (Platform.os == Os.MAC) "При обрыве российского VPN блокирует интернет" else "На Windows защищает только Cisco",
                    a.killSwitch) { v -> upd { it.copy(killSwitch = v) } }
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun StatusRow(label: String, value: Pair<String, Color>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(value.second))
        Spacer(Modifier.width(10.dp))
        Text(label, Modifier.width(140.dp), color = Gray)
        Text(value.first, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ServerPicker(state: AppState) {
    var expanded by remember { mutableStateOf(false) }
    val selectedId = state.settings.selectedServer
    val selected = if (selectedId == AppSettings.AUTO) null else state.servers.firstOrNull { it.id == selectedId }
    val selectedName = selected?.name ?: "Авто"
    val selectedDetail = selected?.subscription ?: "${state.settings.subscriptions.size} подписки · ${state.servers.size} серверов"

    Card(shape = RoundedCornerShape(11.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(Modifier.size(31.dp), shape = RoundedCornerShape(9.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Box(contentAlignment = Alignment.Center) { Text("↗", fontSize = 15.sp, color = MaterialTheme.colorScheme.primary) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(selectedName, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(selectedDetail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                OutlinedButton(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 11.dp, vertical = 5.dp)) {
                    Text("Сменить  ⌄", fontSize = 11.sp)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text("Авто") }, onClick = { state.selectServer(AppSettings.AUTO); expanded = false })
                    state.servers.groupBy { it.subscription }.forEach { (sub, list) ->
                        HorizontalDivider()
                        Text(sub, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 11.sp, color = Gray)
                        list.forEach { server ->
                            DropdownMenuItem(text = { Text(server.name, maxLines = 1) }, onClick = { state.selectServer(server.id); expanded = false })
                        }
                    }
                }
            }
            TextButton(onClick = { state.refreshServers() }, enabled = !state.loadingServers, contentPadding = PaddingValues(horizontal = 5.dp)) {
                Text(if (state.loadingServers) "…" else "↻", fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun RestartBanner() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Text("Настройки «из-за рубежа» изменены — они начнут действовать после Stop → Run", Modifier.padding(12.dp))
    }
}

@Composable
private fun KillSwitchStuckCard(state: AppState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Интернет заблокирован kill switch", fontWeight = FontWeight.SemiBold)
            Text("Программа остановилась нештатно, а блокировка осталась. Run включит VPN заново (блокировка снимется сама), " +
                "или снимите её — понадобится пароль администратора.", fontSize = 13.sp)
            OutlinedButton(onClick = { state.releaseKillSwitch() }) { Text("Снять блокировку") }
        }
    }
}

@Composable
private fun ApplyRulesBanner(state: AppState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Правила изменены", Modifier.weight(1f))
            Button(onClick = { state.applyRules() }) { Text("Применить") }
        }
    }
}

// ============================================================================ Правила

@Composable
private fun RulesTab(state: AppState) {
    var input by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(RuleMode.DIRECT) }

    fun add() {
        val parts = input.split(',', ' ', '\n').map { SingBoxConfig.normalizeDomain(it) }.filter { it.isNotEmpty() }.distinct()
        if (parts.isEmpty()) return
        state.update { s ->
            val existing = s.rules.filter { r -> parts.none { it == SingBoxConfig.normalizeDomain(r.domain) } }
            s.copy(rules = existing + parts.map { SiteRule(it, mode) })
        }
        input = ""
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Сайт работает и для поддоменов: example.com включает www.example.com и api.example.com. " +
                "«Через VPN» сильнее правила для российских сайтов.",
            fontSize = 13.sp, color = Gray,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, singleLine = true,
                label = { Text("Сайт, например gosuslugi.ru") }, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { add() }, enabled = input.isNotBlank()) { Text("Добавить") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == RuleMode.DIRECT, onClick = { mode = RuleMode.DIRECT }, label = { Text("Напрямую") })
            FilterChip(selected = mode == RuleMode.PROXY, onClick = { mode = RuleMode.PROXY }, label = { Text("Через VPN") })
        }
        if (state.rulesDirty) ApplyRulesBanner(state)
        HorizontalDivider()
        if (state.settings.rules.isEmpty()) {
            Text("Своих правил пока нет", color = Gray)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.settings.rules, key = { it.domain }) { rule ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(rule.domain, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = {
                        state.update { s ->
                            s.copy(rules = s.rules.map {
                                if (it.domain == rule.domain) it.copy(mode = if (it.mode == RuleMode.DIRECT) RuleMode.PROXY else RuleMode.DIRECT) else it
                            })
                        }
                    }) {
                        Text(if (rule.mode == RuleMode.DIRECT) "Напрямую" else "Через VPN",
                            color = if (rule.mode == RuleMode.DIRECT) Green else MaterialTheme.colorScheme.primary)
                    }
                    TextButton(onClick = { state.update { s -> s.copy(rules = s.rules.filter { it.domain != rule.domain }) } }) {
                        Text("Удалить", color = Gray)
                    }
                }
            }
        }
    }
}

// ============================================================================ Подписки

@Composable
private fun SubscriptionsTab(state: AppState) {
    var name by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    /** Для какой подписки по ссылке сейчас открыто поле «Вставить содержимое». */
    var pasteFor by remember { mutableStateOf<String?>(null) }
    var pasteText by remember { mutableStateOf("") }

    val trimmed = input.trim()
    val isLink = trimmed.startsWith("https://") || trimmed.startsWith("http://")
    val isContent = !isLink && trimmed.isNotEmpty() && !trimmed.startsWith("happ://")

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Вставьте ссылку подписки (https://…) или её содержимое — список vless://… или base64-текст. " +
                "Если ссылка не открывается с этого компьютера, откройте её в браузере на телефоне, скопируйте текст и вставьте сюда.",
            fontSize = 13.sp, color = Gray,
        )
        OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Название, например WowSecure") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            input, { input = it },
            label = { Text("Ссылка или содержимое подписки") },
            minLines = 1, maxLines = 6,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                if (isLink) {
                    val n = name.trim().ifBlank { runCatching { java.net.URI(trimmed).host }.getOrNull() ?: "Подписка" }
                    state.update { s -> s.copy(subscriptions = s.subscriptions.filter { it.url != trimmed && it.name != n } + Subscription(n, trimmed)) }
                    name = ""; input = ""
                    state.refreshServers()
                } else if (state.importSubscription(name, trimmed)) {
                    name = ""; input = ""
                }
            },
            enabled = isLink || isContent,
        ) { Text(if (isContent) "Сохранить серверы" else "Добавить подписку") }
        if (trimmed.startsWith("happ://")) {
            Text("Это зашифрованная ссылка Happ — попросите у сервиса обычную https-ссылку.", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }
        HorizontalDivider()
        state.settings.subscriptions.forEach { sub ->
            val count = state.servers.count { it.subscription == sub.name }
            val where = when {
                sub.isManual -> "вставлено вручную"
                state.hasSavedCopy(sub) -> "${maskUrl(sub.url)}  ·  есть сохранённая копия"
                else -> "${maskUrl(sub.url)}  ·  ещё не скачивалась"
            }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(sub.name, fontWeight = FontWeight.Medium)
                        Text("$where  ·  серверов: $count", fontSize = 12.sp, color = Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { pasteFor = if (pasteFor == sub.url) null else sub.url; pasteText = "" }) {
                        Text(if (sub.isManual) "Заменить" else "Вставить")
                    }
                    TextButton(onClick = { state.removeSubscription(sub) }) { Text("Удалить", color = Gray) }
                }
                if (pasteFor == sub.url) {
                    OutlinedTextField(
                        pasteText, { pasteText = it },
                        label = { Text("Содержимое подписки «${sub.name}»") },
                        minLines = 3, maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        Button(
                            onClick = { if (state.importSubscription(sub.name, pasteText.trim(), target = sub)) { pasteFor = null; pasteText = "" } },
                            enabled = pasteText.isNotBlank(),
                        ) { Text("Сохранить") }
                        TextButton(onClick = { pasteFor = null }) { Text("Отмена") }
                    }
                }
            }
        }
        OutlinedButton(onClick = { state.refreshServers() }, enabled = !state.loadingServers && state.settings.subscriptions.isNotEmpty()) {
            Text(if (state.loadingServers) "Загружаю…" else "Обновить серверы")
        }
    }
}

private fun maskUrl(u: String): String = runCatching {
    val uri = java.net.URI(u)
    val path = uri.path ?: ""
    val tail = path.takeLast(4)
    "${uri.host}/…$tail"
}.getOrDefault("…")

// ============================================================================ Cisco

@Composable
private fun CiscoTab(state: AppState) {
    val c = state.settings.cisco
    fun upd(t: (CiscoSettings) -> CiscoSettings) = state.update { it.copy(cisco = t(it.cisco)) }
    var password by remember { mutableStateOf("") }
    var totp by remember { mutableStateOf("") }
    var code by remember { mutableStateOf<String?>(null) }
    var left by remember { mutableStateOf(0) }

    LaunchedEffect(state.hasTotp) {
        while (state.hasTotp) {
            code = state.totpPreview()
            left = Totp.secondsLeft()
            delay(1000)
        }
        code = null
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Подключать Cisco при запуске", Modifier.weight(1f))
            Switch(checked = c.enabled, onCheckedChange = { v -> upd { it.copy(enabled = v) } })
        }
        OutlinedTextField(c.server, { v -> upd { it.copy(server = v.trim()) } }, singleLine = true,
            label = { Text("Сервер (как в AnyConnect), например vpn.company.ru") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(c.user, { v -> upd { it.copy(user = v.trim()) } }, singleLine = true,
                label = { Text("Логин") }, modifier = Modifier.weight(1f))
            OutlinedTextField(c.authGroup, { v -> upd { it.copy(authGroup = v.trim()) } }, singleLine = true,
                label = { Text("Группа (если есть)") }, modifier = Modifier.weight(1f))
        }

        SecretField(
            title = "Пароль",
            saved = state.hasPassword,
            value = password, onValue = { password = it },
            onSave = { state.savePassword(password); password = "" },
            onForget = { state.forgetSecret(SecretStore.CISCO_PASSWORD) },
        )
        SecretField(
            title = "Секрет Google Authenticator (из TOTP Vault)",
            saved = state.hasTotp,
            value = totp, onValue = { totp = it },
            onSave = { if (state.saveTotp(totp)) totp = "" },
            onForget = { state.forgetSecret(SecretStore.CISCO_TOTP) },
            hint = "Строка вида JBSWY3DPEHPK3PXP или ссылка otpauth://…",
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Код вводится вместе с паролем")
                Text("Включите, если в AnyConnect код дописывается к паролю в том же поле. " +
                    "Если код спрашивают отдельно — оставьте выключенным.", fontSize = 12.sp, color = Gray)
            }
            Switch(checked = c.otpCombined, onCheckedChange = { v -> upd { it.copy(otpCombined = v) } })
        }
        code?.let {
            Text("Текущий код: ${it.chunked(3).joinToString(" ")}  (ещё $left с) — должен совпадать с TOTP Vault",
                fontFamily = PlexMonoFamily, fontSize = 13.sp)
        }

        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Следить за рабочим временем")
                Text("Будни, по Москве. Напомнит включить Cisco, сообщит об обрыве и спросит, прежде чем выключать раньше конца дня.",
                    fontSize = 12.sp, color = Gray)
            }
            Switch(checked = state.settings.workHoursEnabled, onCheckedChange = { v -> state.update { it.copy(workHoursEnabled = v) } })
        }
        if (state.settings.workHoursEnabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(state.settings.workStart, { v -> state.update { it.copy(workStart = v.trim()) } }, singleLine = true,
                    label = { Text("Начало, ЧЧ:ММ") }, isError = WorkClock.parseTimeOrNull(state.settings.workStart) == null,
                    modifier = Modifier.weight(1f))
                OutlinedTextField(state.settings.workEnd, { v -> state.update { it.copy(workEnd = v.trim()) } }, singleLine = true,
                    label = { Text("Конец, ЧЧ:ММ") }, isError = WorkClock.parseTimeOrNull(state.settings.workEnd) == null,
                    modifier = Modifier.weight(1f))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Не давать компьютеру засыпать")
                Text("Пока VPN включён, экран не гаснет и компьютер не уходит в сон — во сне Cisco отключается. " +
                    "Закрытая крышка ноутбука без внешнего монитора всё равно усыпит его.",
                    fontSize = 12.sp, color = Gray)
            }
            Switch(checked = state.settings.keepAwake, onCheckedChange = { v -> state.update { it.copy(keepAwake = v) } })
        }

        HorizontalDivider()
        Text("Что пускать через Cisco", fontWeight = FontWeight.SemiBold)
        Text("Если оставить пустым — возьмём подсети, которые присылает сервер. Через Cisco пойдут только они.",
            fontSize = 12.sp, color = Gray)
        ListField("Рабочие подсети, например 10.0.0.0/8", c.workSubnets) { v -> upd { it.copy(workSubnets = v) } }
        ListField("Рабочие домены, например corp.local", c.workDomains) { v -> upd { it.copy(workDomains = v) } }

        HorizontalDivider()
        Text("Дополнительно", fontWeight = FontWeight.SemiBold)
        OutlinedTextField(c.serverCert, { v -> upd { it.copy(serverCert = v.trim()) } }, singleLine = true,
            label = { Text("Отпечаток сертификата сервера (pin-sha256:…), если просит") }, modifier = Modifier.fillMaxWidth())
        PathField("openconnect", state.settings.openconnectPath, Platform.findOpenconnect()) { v -> state.update { it.copy(openconnectPath = v) } }
        PathField("vpnc-script", state.settings.vpncScriptPath,
            Platform.findVpncScript(state.settings.openconnectPath.ifBlank { Platform.findOpenconnect() })) { v -> state.update { it.copy(vpncScriptPath = v) } }
        PathField("sing-box", state.settings.singboxPath, Platform.findSingBox()) { v -> state.update { it.copy(singboxPath = v) } }
    }
}

@Composable
internal fun SecretField(
    title: String, saved: Boolean, value: String, onValue: (String) -> Unit,
    onSave: () -> Unit, onForget: () -> Unit, hint: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = value, onValueChange = onValue, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text(if (saved) "$title — сохранён" else title) },
                placeholder = { if (saved) Text("••••••  (ввести заново, чтобы заменить)") },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = onSave, enabled = value.isNotEmpty()) { Text("Сохранить") }
            if (saved) TextButton(onClick = onForget) { Text("Удалить", color = Gray) }
        }
        val store = if (Platform.os == Os.MAC) "Связке ключей macOS" else "защищённом хранилище Windows"
        Text((hint?.let { "$it. " } ?: "") + "Хранится в $store, не в файле настроек.", fontSize = 12.sp, color = Gray)
    }
}

@Composable
private fun ListField(label: String, values: List<String>, onChange: (List<String>) -> Unit) {
    var text by remember { mutableStateOf(values.joinToString("\n")) }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v
            onChange(v.split('\n', ',', ' ').map { it.trim() }.filter { it.isNotEmpty() })
        },
        label = { Text(label) },
        minLines = 2, maxLines = 6,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PathField(name: String, value: String, detected: String?, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = { onChange(it) }, singleLine = true,
        label = { Text("Путь к $name") },
        placeholder = { Text(detected?.let { "найден: $it" } ?: "не найден — укажите путь") },
        supportingText = { if (value.isBlank()) Text(detected?.let { "Используется найденный: $it" } ?: "Не найден", fontSize = 11.sp) },
        modifier = Modifier.fillMaxWidth(),
    )
}

// ============================================================================ Из-за рубежа

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AbroadTab(state: AppState) {
    val a = state.settings.abroad
    fun upd(t: (AbroadSettings) -> AbroadSettings) = state.update { it.copy(abroad = t(it.abroad)) }
    var expanded by remember { mutableStateOf(false) }
    val autoFound = state.servers.filter { Abroad.looksRussian(it) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Cisco не пускает зарубежные адреса. В этом режиме Run сначала подключает российский VPN, проверяет, " +
                "что снаружи виден российский адрес, и только потом подключает Cisco — через российский VPN. " +
                "Сервер Cisco видит российский адрес.",
            fontSize = 13.sp, color = Gray,
        )
        Card {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Я за рубежом", fontWeight = FontWeight.SemiBold)
                    Text("Подключаться через российский VPN", fontSize = 12.sp, color = Gray)
                }
                Switch(checked = a.enabled, onCheckedChange = { v -> upd { it.copy(enabled = v) } })
            }
        }
        if (state.restartNeeded) RestartBanner()

        Text("Российский сервер", fontWeight = FontWeight.SemiBold)
        val autoLabel = "Авто — самый быстрый из российских (${autoFound.size})"
        val selectedName = if (a.ruServer == AppSettings.AUTO) autoLabel
        else state.servers.firstOrNull { it.id == a.ruServer }?.let { "${it.name}  ·  ${it.subscription}" } ?: autoLabel
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = selectedName, onValueChange = {}, readOnly = true, singleLine = true,
                label = { Text("Сервер в России") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text(autoLabel) }, onClick = { state.selectRuServer(AppSettings.AUTO); expanded = false })
                // Сначала похожие на российские, потом остальные — вдруг российский назван иначе
                state.servers.sortedByDescending { Abroad.looksRussian(it) }.groupBy { it.subscription }.forEach { (sub, list) ->
                    HorizontalDivider()
                    Text(sub, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 12.sp, color = Gray)
                    list.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.name, maxLines = 1, color = if (Abroad.looksRussian(s)) Color.Unspecified else Gray) },
                            onClick = { state.selectRuServer(s.id); expanded = false },
                        )
                    }
                }
            }
        }
        when {
            state.servers.isEmpty() -> Text("Серверов пока нет — добавьте подписку в главной → «Настройки VPN» → «Подписки».", fontSize = 12.sp, color = Gray)
            a.ruServer == AppSettings.AUTO && autoFound.isEmpty() -> Text(
                "В названиях серверов нет 🇷🇺, RU или «Россия» — выберите российский сервер вручную.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.error,
            )
            a.ruServer == AppSettings.AUTO -> Text(
                "Найдены: " + autoFound.joinToString(", ") { it.name }.let { if (it.length > 160) it.take(160) + "…" else it },
                fontSize = 12.sp, color = Gray,
            )
        }
        Text("Если выход окажется не в России, Cisco не подключится, а программа скажет, какая страна видна.", fontSize = 12.sp, color = Gray)

        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Весь трафик через российский VPN")
                Text(
                    if (a.allViaRu) "Все сайты видят российский адрес."
                    else "Через российский VPN — только Cisco и российские сайты (.ru, .рф, российские сервисы и IP), остальное — напрямую, " +
                        "через местный интернет. Правила из главной → «Настройки VPN» действуют: «Через VPN» — через российский.",
                    fontSize = 12.sp, color = Gray,
                )
            }
            Switch(checked = a.allViaRu, onCheckedChange = { v -> upd { it.copy(allViaRu = v) } })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Kill switch")
                Text(
                    (if (Platform.os == Os.MAC) "Оборвался российский VPN — интернета нет совсем, пока он не вернётся: никто не увидит " +
                        "зарубежный адрес. IPv6 и обычный DNS мимо VPN тоже закрыты. Блокировка снимается при Stop."
                    else "На Windows — только для Cisco (см. ниже); остальной трафик при обрыве пойдёт напрямую.") +
                        " Cisco в этом режиме в любом случае подключается только через российский VPN — напрямую никогда.",
                    fontSize = 12.sp, color = Gray,
                )
            }
            Switch(checked = a.killSwitch, onCheckedChange = { v -> upd { it.copy(killSwitch = v) } })
        }

        HorizontalDivider()
        Text("Что никуда не утекает", fontWeight = FontWeight.SemiBold)
        listOf(
            "Сервер Cisco: openconnect подключается только через российский VPN (как через прокси). Упал VPN — Cisco ждёт, " +
                "а не идёт напрямую. UDP (DTLS) выключен — он прошёл бы мимо прокси, Cisco работает по TCP.",
            "Имя сервера Cisco узнаёт уже российский сервер — местный DNS о нём не спрашивают.",
            "DNS Cisco спрашивается только про рабочие домены (как и дома) — остальные имена до компании не доходят.",
            "Остальной DNS — только зашифрованный (DNS-over-HTTPS): через российский VPN, а для сайтов «напрямую» — к 1.1.1.1.",
            "Адреса российских серверов узнаются тоже через DNS-over-HTTPS и запоминаются на случай, если он недоступен.",
        ).forEach { Text("•  $it", fontSize = 12.sp) }

        if (state.killSwitchStuck && state.phase == Phase.STOPPED) KillSwitchStuckCard(state)
    }
}

// ============================================================================ GitLab

@Composable
private fun GitLabTab(state: AppState, onOpen: (MergeRequest) -> Unit) {
    val g = state.settings.gitlab
    fun upd(t: (GitLabSettings) -> GitLabSettings) = state.update { it.copy(gitlab = t(it.gitlab)) }
    var readToken by remember { mutableStateOf("") }
    var writeToken by remember { mutableStateOf("") }
    var settingsExpanded by remember { mutableStateOf(!state.hasGitLabToken || g.urls.isEmpty()) }
    val host = g.urls.firstNotNullOfOrNull { GitLabList.parse(it) }?.host

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Уведомлять о новых merge request")
                Text("Проверка раз в 2 минуты, пока подключён Cisco. Уведомление — от значка в строке меню.", fontSize = 12.sp, color = Gray)
            }
            Switch(checked = g.enabled, onCheckedChange = { v -> upd { it.copy(enabled = v) } })
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable { settingsExpanded = !settingsExpanded }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Адреса и токены GitLab", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (g.urls.isEmpty()) "Списки MR не настроены" else "${g.urls.size} ${if (g.urls.size == 1) "список MR" else "списка MR"} · " +
                                if (state.hasGitLabToken) "токен чтения сохранён" else "нужен токен чтения",
                            fontSize = 12.sp, color = Gray,
                        )
                    }
                    Text(if (settingsExpanded) "⌃" else "⌄", fontSize = 22.sp, color = Gray)
                }
                if (settingsExpanded) {
                    ListField("Ссылки на списки merge request, по одной в строке", g.urls) { v -> upd { it.copy(urls = v) } }
                    Text("Откройте список MR в браузере, настройте фильтры (не черновики, не мои, не одобренные мной…) и скопируйте адрес. " +
                        "Учитываются автор, черновики, одобрения, ревьюер, исполнитель, метки и ветки.", fontSize = 12.sp, color = Gray)
                    g.urls.filter { GitLabList.parse(it) == null }.forEach {
                        Text("Не похоже на список merge request (нужно …/-/merge_requests): $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                    SecretField(
                        title = "Токен GitLab для чтения",
                        saved = state.hasGitLabToken,
                        value = readToken, onValue = { readToken = it },
                        onSave = { state.saveGitLabToken(readToken); readToken = "" },
                        onForget = { state.forgetSecret(SecretStore.GITLAB_TOKEN) },
                        hint = "Отдельный токен read_api только для просмотра MR",
                    )
                    if (host != null && !state.hasGitLabToken) {
                        TextButton(onClick = { openInSystemBrowser("$host/-/user_settings/personal_access_tokens?name=VpnRunner-read&scopes=read_api") }) {
                            Text("Создать токен для чтения")
                        }
                    }
                    SecretField(
                        title = "Токен GitLab для review и комментариев",
                        saved = state.hasGitLabWriteToken,
                        value = writeToken, onValue = { writeToken = it },
                        onSave = { state.saveGitLabWriteToken(writeToken); writeToken = "" },
                        onForget = { state.forgetSecret(SecretStore.GITLAB_WRITE_TOKEN) },
                        hint = "Отдельный токен api только для отправки review и комментариев",
                    )
                    if (host != null && !state.hasGitLabWriteToken) {
                        TextButton(onClick = { openInSystemBrowser("$host/-/user_settings/personal_access_tokens?name=VpnRunner-write&scopes=api") }) {
                            Text("Создать токен для review")
                        }
                    }
                }
            }
        }

        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.gitlabStatus ?: "Уведомления выключены", Modifier.weight(1f), fontSize = 13.sp,
                color = if (state.gitlabStatus?.startsWith("Ошибка") == true) MaterialTheme.colorScheme.error else Gray)
            if (state.gitlabChecking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else TextButton(onClick = { state.checkGitLabNow() }, enabled = g.enabled) { Text("Проверить сейчас") }
        }
        if (g.enabled && state.mergeRequests.isEmpty() && state.gitlabStatus?.startsWith("Проверено") == true) {
            Text("Открытых merge request по этим фильтрам нет", fontSize = 13.sp, color = Gray)
        }
        if (g.enabled) state.mergeRequests.forEach { mr ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(mr) }) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text("!${mr.iid}  ${mr.title}", fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOf(mr.author, mr.projectPath.substringAfterLast('/'), shortTime(mr.createdAt)).filter { it.isNotBlank() }.joinToString(" · "),
                        fontSize = 12.sp, color = Gray)
                }
            }
        }
    }
}

private enum class GitLabMrSection { OVERVIEW, COMMITS, PIPELINES, CHANGES }
private enum class ExternalBrowser(val label: String, val macApp: String, val windowsExe: String) {
    CHROME("Chrome · по умолчанию", "Google Chrome", "chrome.exe"),
    SAFARI("Safari", "Safari", ""),
    FIREFOX("Firefox", "Firefox", "firefox.exe"),
    EDGE("Microsoft Edge", "Microsoft Edge", "msedge.exe"),
    SYSTEM("Системный браузер", "", ""),
}

private data class GitLabDiffLine(
    val oldNumber: String,
    val newNumber: String,
    val text: String,
    val kind: Kind,
) {
    enum class Kind { CONTEXT, ADDED, REMOVED, HUNK, META }
}

private fun parseGitLabDiff(diff: String): List<GitLabDiffLine> {
    var oldLine = 0
    var newLine = 0
    val hunk = Regex("@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@")
    return diff.lineSequence().mapNotNull { line ->
        when {
            line.startsWith("@@") -> {
                hunk.find(line)?.let { oldLine = it.groupValues[1].toInt(); newLine = it.groupValues[2].toInt() }
                GitLabDiffLine("", "", line, GitLabDiffLine.Kind.HUNK)
            }
            line.startsWith("+++") || line.startsWith("---") || line.startsWith("diff --git") || line.startsWith("index ") -> null
            line.startsWith("+") -> GitLabDiffLine("", (newLine++).toString(), line.drop(1), GitLabDiffLine.Kind.ADDED)
            line.startsWith("-") -> GitLabDiffLine((oldLine++).toString(), "", line.drop(1), GitLabDiffLine.Kind.REMOVED)
            line.startsWith(" ") -> GitLabDiffLine((oldLine++).toString(), (newLine++).toString(), line.drop(1), GitLabDiffLine.Kind.CONTEXT)
            line.startsWith("\\") -> GitLabDiffLine("", "", line, GitLabDiffLine.Kind.META)
            else -> null
        }
    }.toList()
}

@Composable
private fun GitLabMrPage(state: AppState, mr: MergeRequest, openExternal: (String) -> Unit) {
    var section by remember(mr.key) { mutableStateOf(GitLabMrSection.OVERVIEW) }
    var browserMenu by remember(mr.key) { mutableStateOf(false) }
    val details = state.gitlabMrDetails

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = state::closeGitLabMr, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("‹  MR") }
            Column(Modifier.weight(1f)) {
                Text("!${mr.iid} · ${mr.title}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${mr.projectPath} · ${mr.author}", fontSize = 12.sp, color = Gray)
            }
            Box {
                OutlinedButton(onClick = { browserMenu = true }) { Text("Открыть в GitLab  ▾") }
                DropdownMenu(expanded = browserMenu, onDismissRequest = { browserMenu = false }) {
                    externalBrowserChoices().forEach { browser ->
                        DropdownMenuItem(text = { Text(browser.label) }, onClick = {
                            browserMenu = false
                            openInExternalBrowser(mr.webUrl, browser)
                        })
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        TabRow(selectedTabIndex = section.ordinal, containerColor = Color.Transparent) {
            listOf("Overview", "Commits", "Pipelines", "Changes").forEachIndexed { i, title ->
                val tab = GitLabMrSection.entries[i]
                val count = when (tab) {
                    GitLabMrSection.OVERVIEW -> null
                    GitLabMrSection.COMMITS -> details?.commits?.size
                    GitLabMrSection.PIPELINES -> details?.pipelines?.size
                    GitLabMrSection.CHANGES -> details?.changes?.size
                }
                Tab(selected = section == tab, onClick = { section = tab }, text = {
                    Text(if (count == null) title else "$title  $count", maxLines = 1, fontSize = 12.sp)
                })
            }
        }
        when {
            state.gitlabMrLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator()
                    Text("Загружаю merge request из GitLab…", color = Gray, fontSize = 13.sp)
                }
            }
            state.gitlabMrError != null -> Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Не удалось загрузить MR: ${state.gitlabMrError}", color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { state.openGitLabMr(mr) }) { Text("Повторить") }
            }
            details == null -> Text("Нет данных MR", color = Gray, modifier = Modifier.padding(16.dp))
            else -> if (section == GitLabMrSection.CHANGES) {
                GitLabChangesPage(
                    Modifier.fillMaxSize().padding(top = 12.dp), details.changes, details.diffRefs,
                    details.discussions, details.discussionsError, details.changesOverflow, state, mr,
                )
            } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (section) {
                    GitLabMrSection.OVERVIEW -> {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(mrStatus(details.state), fontWeight = FontWeight.SemiBold, color = mrStatusColor(details.state))
                                    Text("${details.sourceBranch}  →  ${details.targetBranch}", fontSize = 12.sp, fontFamily = PlexMonoFamily, color = Gray)
                                }
                                Text("Автор: ${mr.author}  ·  создан ${shortTime(details.createdAt).ifBlank { "—" }}", fontSize = 12.sp, color = Gray)
                                if (details.mergeStatus.isNotBlank()) Text("Слияние: ${mergeStatus(details.mergeStatus)}", fontSize = 12.sp, color = Gray)
                                if (details.labels.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    details.labels.forEach { AssistChip(onClick = {}, label = { Text(it, fontSize = 11.sp) }) }
                                }
                            }
                        }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Описание", fontWeight = FontWeight.SemiBold)
                                Text(details.description.ifBlank { "Описания нет" }, fontSize = 13.sp, color = if (details.description.isBlank()) Gray else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        GitLabReviewPanel(state, mr, details.review)
                        GitLabDiscussionsPanel(details.discussions, details.discussionsError, details.changes, state, mr)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MrStat("Коммиты", details.commits.size.toString(), Modifier.weight(1f))
                            MrStat("Пайплайны", details.pipelines.size.toString(), Modifier.weight(1f))
                            MrStat("Файлы", details.changes.size.toString(), Modifier.weight(1f))
                        }
                    }
                    GitLabMrSection.COMMITS -> {
                        if (details.commits.isEmpty()) Text("Коммитов нет", color = Gray)
                        details.commits.forEach { commit ->
                            Card(Modifier.fillMaxWidth().clickable { if (commit.webUrl.isNotBlank()) openExternal(commit.webUrl) }) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(commit.title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text("${commit.id.take(8)} · ${commit.author} · ${shortTime(commit.authoredAt)}", fontSize = 11.sp, color = Gray)
                                }
                            }
                        }
                    }
                    GitLabMrSection.PIPELINES -> {
                        if (details.pipelines.isEmpty()) Text("Пайплайнов нет", color = Gray)
                        details.pipelines.forEach { p ->
                            Card(Modifier.fillMaxWidth().clickable { if (p.webUrl.isNotBlank()) openExternal(p.webUrl) }) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(pipelineMark(p.status), fontSize = 15.sp, color = pipelineColor(p.status))
                                    Column(Modifier.weight(1f)) {
                                        Text("${pipelineStatus(p.status)} · ${p.ref}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                        Text("#${p.id} · ${p.sha.take(8)} · ${shortTime(p.createdAt)}", fontSize = 11.sp, color = Gray)
                                    }
                                    Text("↗", color = Gray)
                                }
                            }
                        }
                    }
                    GitLabMrSection.CHANGES -> Unit
                }
            }
        }
    }
}

@Composable
private fun GitLabChangesPage(
    modifier: Modifier,
    changes: List<GitLabChange>,
    refs: GitLabDiffRefs?,
    discussions: List<GitLabDiscussionThread>,
    discussionsError: String?,
    overflow: Boolean,
    state: AppState,
    mr: MergeRequest,
) {
    val diffListState = rememberLazyListState()
    val fileListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var selectedPath by remember(mr.key) { mutableStateOf(changes.firstOrNull()?.let { it.newPath.ifBlank { it.oldPath } } ?: "") }
    val generalThreads = remember(discussions) { discussions.filter { it.path.isNullOrBlank() } }
    val topOffset = if (generalThreads.isNotEmpty()) 1 else 0

    LaunchedEffect(diffListState.firstVisibleItemIndex, changes) {
        val fileIndex = diffListState.firstVisibleItemIndex - topOffset
        selectedPath = changes.getOrNull(fileIndex)?.let { it.newPath.ifBlank { it.oldPath } } ?: selectedPath
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Изменено файлов: ${changes.size}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("Обсуждения · ${discussions.size}", fontSize = 12.sp,
                color = if (discussions.isEmpty()) Gray else MaterialTheme.colorScheme.primary)
        }
        if (discussions.isEmpty()) Text(discussionsError ?: "У GitLab нет обсуждений для этого MR", color = Gray, fontSize = 11.sp)
        if (overflow) Text("GitLab показал не все файлы. Полный список доступен в браузере.", color = Gray, fontSize = 12.sp)
        if (refs == null) Text("Комментарии к строкам недоступны: GitLab не вернул версию diff.", color = Gray, fontSize = 12.sp)
        if (changes.isEmpty()) {
            Text("Изменений нет", color = Gray)
            return@Column
        }

        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            Column(Modifier.width(230.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant)) {
                Text("Файлы · ${changes.size}", Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                HorizontalDivider()
                LazyColumn(state = fileListState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 4.dp)) {
                    itemsIndexed(changes, key = { _, c -> c.newPath.ifBlank { c.oldPath } }) { index, change ->
                        val path = change.newPath.ifBlank { change.oldPath }
                        val filename = path.substringAfterLast('/')
                        val directory = path.removeSuffix(filename).trimEnd('/')
                        val added = change.diff.lineSequence().count { it.startsWith("+") && !it.startsWith("+++") }
                        val removed = change.diff.lineSequence().count { it.startsWith("-") && !it.startsWith("---") }
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable {
                                selectedPath = path
                                scope.launch { diffListState.animateScrollToItem(index + topOffset) }
                            }.padding(horizontal = 5.dp, vertical = 2.dp),
                            color = if (path == selectedPath) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Column(Modifier.padding(horizontal = 8.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(filename, fontSize = 12.sp, fontFamily = PlexMonoFamily, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (directory.isNotBlank()) Text(directory, fontSize = 11.sp, color = Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("+$added", color = Green, fontSize = 11.sp, fontFamily = PlexMonoFamily)
                                    Text("−$removed", color = MaterialTheme.colorScheme.error, fontSize = 11.sp, fontFamily = PlexMonoFamily)
                                }
                            }
                        }
                    }
                }
            }
            VerticalDivider()
            LazyColumn(
                state = diffListState,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(start = 10.dp, end = 4.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (generalThreads.isNotEmpty()) {
                    item(key = "general-discussions") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Общие обсуждения · ${generalThreads.size}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            generalThreads.forEach { GitLabDiscussionCard(it, state, mr, changes) }
                        }
                    }
                }
                itemsIndexed(changes, key = { _, c -> c.newPath.ifBlank { c.oldPath } }) { _, change ->
                    val path = change.newPath.ifBlank { change.oldPath }
                    val added = change.diff.lineSequence().count { it.startsWith("+") && !it.startsWith("+++") }
                    val removed = change.diff.lineSequence().count { it.startsWith("-") && !it.startsWith("---") }
                    Card(Modifier.fillMaxWidth()) {
                        Column {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(when { change.newFile -> "+"; change.deletedFile -> "−"; change.renamedFile -> "↪"; else -> "▤" },
                                    color = if (change.deletedFile) MaterialTheme.colorScheme.error else Green, fontWeight = FontWeight.Bold)
                                Text(path, Modifier.weight(1f), fontSize = 12.sp, fontFamily = PlexMonoFamily,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("+$added", color = Green, fontSize = 11.sp, fontFamily = PlexMonoFamily)
                                Text("−$removed", color = MaterialTheme.colorScheme.error, fontSize = 11.sp, fontFamily = PlexMonoFamily)
                            }
                            HorizontalDivider()
                            if (change.diffUnavailable) {
                                Text("GitLab скрыл diff этого файла из-за размера. Его можно посмотреть на странице MR.",
                                    Modifier.padding(12.dp), fontSize = 12.sp, color = Gray)
                            } else {
                                GitLabDiff(change, refs, discussions.filter { it.path == change.newPath || it.path == change.oldPath }, state, mr)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GitLabReviewPanel(state: AppState, mr: MergeRequest, review: GitLabReviewState) {
    var comment by remember(mr.key) { mutableStateOf("") }
    val tokenUrl = remember(mr.webUrl) {
        runCatching { java.net.URI(mr.webUrl).let { "${it.scheme}://${it.rawAuthority}/-/user_settings/personal_access_tokens?name=VpnRunner-write&scopes=api" } }.getOrNull()
    }
    LaunchedEffect(state.gitlabMrActionMessage) {
        if (state.gitlabMrActionMessage == "Комментарий отправлен в GitLab") comment = ""
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Review и комментарий", fontWeight = FontWeight.SemiBold)
                    Text(if (review.approvers.isEmpty()) "Пока никто не одобрил" else "Одобрили: ${review.approvers.joinToString()}",
                        fontSize = 12.sp, color = Gray)
                }
                if (review.userCanApprove) {
                    Button(onClick = { state.setGitLabMrApproval(mr, !review.userHasApproved) }, enabled = !state.gitlabMrActionBusy) {
                        Text(if (review.userHasApproved) "Снять одобрение" else "Одобрить MR")
                    }
                }
            }
            OutlinedTextField(
                value = comment, onValueChange = { comment = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Комментарий к MR") }, placeholder = { Text("Напишите review или замечание…") },
                minLines = 2, maxLines = 5,
            )
            if (!state.hasGitLabWriteToken) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Нужен токен GitLab с правом api для отправки комментариев.", Modifier.weight(1f), fontSize = 11.sp, color = Gray)
                    if (tokenUrl != null) TextButton(onClick = { openInSystemBrowser(tokenUrl) }) { Text("Создать токен") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                state.gitlabMrActionMessage?.let { message ->
                    Text(message, Modifier.weight(1f), fontSize = 12.sp,
                        color = if (message.startsWith("GitLab") || message.startsWith("Для комментариев") || message.startsWith("Для review") || message.startsWith("Не удалось")) MaterialTheme.colorScheme.error else Green)
                } ?: Spacer(Modifier.weight(1f))
                Button(onClick = { state.commentGitLabMr(mr, comment) },
                    enabled = comment.isNotBlank() && !state.gitlabMrActionBusy && state.hasGitLabWriteToken) {
                    Text(if (state.gitlabMrActionBusy) "Отправляю…" else "Отправить комментарий")
                }
            }
        }
    }
}

@Composable
private fun GitLabDiscussionCard(
    thread: GitLabDiscussionThread,
    state: AppState,
    mr: MergeRequest,
    changes: List<GitLabChange> = emptyList(),
) {
    var reply by remember(thread.id) { mutableStateOf("") }
    var expanded by remember(thread.id) { mutableStateOf(true) }
    LaunchedEffect(state.gitlabMrActionMessage) {
        if (state.gitlabMrActionMessage == "Ответ отправлен в обсуждение") reply = ""
    }
    val lineLabel = when {
        thread.newLine != null -> "+${thread.newLine}" + (thread.newRangeEnd?.takeIf { it != thread.newLine }?.let { "–+$it" } ?: "")
        thread.oldLine != null -> "−${thread.oldLine}" + (thread.oldRangeEnd?.takeIf { it != thread.oldLine }?.let { "–−$it" } ?: "")
        else -> null
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Surface(modifier = Modifier.size(30.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Box(contentAlignment = Alignment.Center) {
                Text(thread.notes.firstOrNull()?.author?.firstOrNull()?.uppercase() ?: "?",
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
        }
        Surface(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.medium,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(horizontal = 13.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(thread.notes.firstOrNull()?.author ?: "Неизвестный автор", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("создал обсуждение в изменениях", fontSize = 11.sp, color = Gray)
                    Spacer(Modifier.weight(1f))
                    Text(shortTime(thread.notes.firstOrNull()?.createdAt.orEmpty()), fontSize = 11.sp, color = Gray)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (thread.path != null || lineLabel != null) {
                        Text(listOfNotNull(thread.path, lineLabel).joinToString("  ·  "), Modifier.weight(1f), fontSize = 11.sp, color = Gray,
                            fontFamily = if (thread.path != null) PlexMonoFamily else PlexSansFamily)
                    } else Spacer(Modifier.weight(1f))
                    Surface(
                        shape = CircleShape,
                        color = if (thread.resolved) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(if (thread.resolved) "Решено" else "Открыто", Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            color = if (thread.resolved) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary)
                    }
                }
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                ) {
                    Text(if (expanded) "⌄  Свернуть ответы" else "›  Показать обсуждение · ${thread.notes.size}", fontSize = 11.sp)
                }
                if (expanded) {
                changes.firstOrNull { it.newPath == thread.path || it.oldPath == thread.path }
                    ?.let { GitLabDiscussionDiffPreview(it, thread) }
                    thread.notes.forEachIndexed { index, note ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Column(
                            Modifier.fillMaxWidth().padding(start = if (index == 0) 0.dp else 18.dp, top = 2.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (index > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("↳", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                                Text(note.author, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Text(shortTime(note.createdAt), fontSize = 11.sp, color = Gray)
                            }
                            Text(note.body, fontSize = if (index == 0) 13.sp else 12.sp, lineHeight = 18.sp,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    if (thread.notes.size > 1) Text("Ответов: ${thread.notes.size - 1}", fontSize = 11.sp, color = Gray)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (thread.writable) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = reply,
                                onValueChange = { reply = it },
                                modifier = Modifier.weight(1f),
                                enabled = state.hasGitLabWriteToken,
                                placeholder = { Text("Ответить в этот тред…") },
                                minLines = 1,
                                maxLines = 3,
                            )
                            Button(onClick = { state.replyGitLabMrDiscussion(mr, thread, reply) },
                                enabled = state.hasGitLabWriteToken && reply.isNotBlank() && !state.gitlabMrActionBusy) {
                                Text(if (state.gitlabMrActionBusy) "Отправляю…" else "Отправить")
                            }
                            if (thread.resolvable && state.hasGitLabWriteToken) {
                                TextButton(onClick = { state.resolveGitLabMrDiscussion(mr, thread, !thread.resolved) },
                                    enabled = !state.gitlabMrActionBusy) {
                                    Text(if (thread.resolved) "Открыть снова" else "Решить тред")
                                }
                            }
                        }
                        if (!state.hasGitLabWriteToken) {
                            Text("Для ответа нужен токен GitLab с правом api. Его можно создать в настройках GitLab.", fontSize = 11.sp, color = Gray)
                        }
                        state.gitlabMrActionMessage?.takeIf { it.contains("обсуждени", ignoreCase = true) || it.contains("обсуждение", ignoreCase = true) }?.let { message ->
                            Text(message, fontSize = 11.sp,
                                color = if (message.startsWith("Не удалось") || message.startsWith("GitLab")) MaterialTheme.colorScheme.error else Green)
                        }
                    } else {
                        Text("GitLab вернул этот тред только для чтения.", fontSize = 11.sp, color = Gray)
                    }
                }
            }
        }
    }
}

@Composable
private fun GitLabDiscussionDiffPreview(change: GitLabChange, thread: GitLabDiscussionThread) {
    val lines = remember(change.diff, thread.oldLine, thread.newLine, thread.oldRangeEnd, thread.newRangeEnd) {
        val parsed = parseGitLabDiff(change.diff)
        val start = parsed.indexOfFirst { line ->
            (thread.oldLine != null && line.oldNumber.toIntOrNull() == thread.oldLine) ||
                (thread.newLine != null && line.newNumber.toIntOrNull() == thread.newLine)
        }
        if (start < 0) emptyList() else {
            val endLine = thread.newRangeEnd ?: thread.oldRangeEnd
            val end = if (endLine == null) start else parsed.indexOfFirst { line ->
                (thread.newRangeEnd != null && line.newNumber.toIntOrNull() == thread.newRangeEnd) ||
                    (thread.oldRangeEnd != null && line.oldNumber.toIntOrNull() == thread.oldRangeEnd)
            }.takeIf { it >= start } ?: start
            parsed.subList((start - 2).coerceAtLeast(0), (end + 3).coerceAtMost(parsed.size))
        }
    }
    if (lines.isEmpty()) return
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
        Column {
            lines.forEach { line ->
                val bg = when (line.kind) {
                    GitLabDiffLine.Kind.ADDED -> Color(0xFF183D2C)
                    GitLabDiffLine.Kind.REMOVED -> Color(0xFF51272A)
                    GitLabDiffLine.Kind.HUNK -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    else -> Color.Transparent
                }
                val fg = when (line.kind) {
                    GitLabDiffLine.Kind.ADDED -> Color(0xFFD1FFE0)
                    GitLabDiffLine.Kind.REMOVED -> Color(0xFFFFD9D9)
                    GitLabDiffLine.Kind.HUNK -> MaterialTheme.colorScheme.primary
                    GitLabDiffLine.Kind.META -> Gray
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Row(Modifier.fillMaxWidth().background(bg).heightIn(min = 22.dp), verticalAlignment = Alignment.Top) {
                    Text(if (line.kind == GitLabDiffLine.Kind.ADDED) "+" else if (line.kind == GitLabDiffLine.Kind.REMOVED) "−" else " ",
                        Modifier.width(20.dp).padding(top = 3.dp), fontSize = 11.sp, color = fg, fontFamily = PlexMonoFamily, textAlign = TextAlign.Center)
                    Text(line.oldNumber, Modifier.width(34.dp).padding(top = 3.dp), fontSize = 11.sp, color = Gray, fontFamily = PlexMonoFamily)
                    Text(line.newNumber, Modifier.width(34.dp).padding(top = 3.dp), fontSize = 11.sp, color = Gray, fontFamily = PlexMonoFamily)
                    Text(line.text, Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 3.dp),
                        fontSize = 11.sp, lineHeight = 14.sp, color = fg, fontFamily = PlexMonoFamily, softWrap = true)
                }
            }
            Text("Комментарий к строкам ${thread.newLine?.let { "+$it" } ?: thread.oldLine?.let { "−$it" } ?: "diff"}" +
                (thread.newRangeEnd?.takeIf { it != thread.newLine }?.let { " – +$it" } ?: ""),
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 9.dp, vertical = 5.dp),
                fontSize = 11.sp, color = Gray)
        }
    }
}

@Composable
private fun GitLabDiscussionsPanel(discussions: List<GitLabDiscussionThread>, error: String?, changes: List<GitLabChange>, state: AppState, mr: MergeRequest) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Обсуждения", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(7.dp))
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(discussions.size.toString(), Modifier.padding(horizontal = 8.dp, vertical = 3.dp), fontSize = 11.sp, color = Gray)
            }
        }
        if (discussions.isEmpty()) {
            Text(error ?: "У GitLab нет обсуждений для этого MR", fontSize = 12.sp, color = Gray)
        } else {
            discussions.forEach { GitLabDiscussionCard(it, state, mr, changes) }
        }
    }
}

@Composable
private fun GitLabDiff(
    change: GitLabChange,
    refs: GitLabDiffRefs?,
    discussions: List<GitLabDiscussionThread>,
    state: AppState,
    mr: MergeRequest,
) {
    val lines = remember(change.diff) { parseGitLabDiff(change.diff) }
    var commentLine by remember(change.oldPath, change.newPath) { mutableStateOf<Int?>(null) }
    var draft by remember(commentLine) { mutableStateOf("") }
    LaunchedEffect(state.gitlabMrActionMessage) {
        if (state.gitlabMrActionMessage == "Комментарий к строке отправлен в GitLab") commentLine = null
    }
    if (lines.isEmpty()) {
        Text("GitLab не вернул diff для этого файла.", Modifier.padding(10.dp), fontSize = 12.sp, color = Gray)
        return
    }
    val addedCount = lines.count { it.kind == GitLabDiffLine.Kind.ADDED }
    val removedCount = lines.count { it.kind == GitLabDiffLine.Kind.REMOVED }
    val fileThreads = discussions.filter { it.oldLine == null && it.newLine == null }
    SelectionContainer {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Добавлено: +$addedCount", color = Color(0xFF70D99A), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text("Удалено: −$removedCount", color = Color(0xFFFF8585), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Text("Нажмите «＋» рядом со строкой, чтобы оставить комментарий",
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
                fontSize = 11.sp, color = Gray)
            fileThreads.forEach { GitLabDiscussionCard(it, state, mr, listOf(change)) }
            lines.forEachIndexed { index, line ->
                val background = when (line.kind) {
                    GitLabDiffLine.Kind.ADDED -> Color(0xFF183D2C)
                    GitLabDiffLine.Kind.REMOVED -> Color(0xFF51272A)
                    GitLabDiffLine.Kind.HUNK -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
                    else -> Color.Transparent
                }
                val foreground = when (line.kind) {
                    GitLabDiffLine.Kind.ADDED -> Color(0xFFD1FFE0)
                    GitLabDiffLine.Kind.REMOVED -> Color(0xFFFFD9D9)
                    GitLabDiffLine.Kind.HUNK -> MaterialTheme.colorScheme.primary
                    GitLabDiffLine.Kind.META -> Gray
                    else -> MaterialTheme.colorScheme.onSurface
                }
                val marker = when (line.kind) {
                    GitLabDiffLine.Kind.ADDED -> "+"
                    GitLabDiffLine.Kind.REMOVED -> "−"
                    else -> " "
                }
                val markerColor = when (line.kind) {
                    GitLabDiffLine.Kind.ADDED -> Color(0xFF70D99A)
                    GitLabDiffLine.Kind.REMOVED -> Color(0xFFFF8585)
                    else -> Gray
                }
                Column {
                    Row(Modifier.fillMaxWidth().heightIn(min = 28.dp).background(background), verticalAlignment = Alignment.Top) {
                        Text(marker, Modifier.width(26.dp).padding(top = 4.dp).background(markerColor.copy(alpha = 0.18f).takeIf { marker != " " } ?: Color.Transparent),
                            fontSize = 13.sp, color = markerColor, fontWeight = FontWeight.Bold, fontFamily = PlexMonoFamily,
                            textAlign = TextAlign.Center, maxLines = 1)
                        Text(line.oldNumber, Modifier.width(40.dp).padding(top = 5.dp).background(Color.Black.copy(alpha = 0.12f)).padding(horizontal = 6.dp),
                            fontSize = 11.sp, color = Gray, fontFamily = PlexMonoFamily, maxLines = 1)
                        Text(line.newNumber, Modifier.width(40.dp).padding(top = 5.dp).background(Color.Black.copy(alpha = 0.08f)).padding(horizontal = 6.dp),
                            fontSize = 11.sp, color = Gray, fontFamily = PlexMonoFamily, maxLines = 1)
                        Text(line.text, Modifier.weight(1f).widthIn(min = 0.dp).padding(horizontal = 8.dp, vertical = 4.dp),
                            fontSize = 12.sp, lineHeight = 17.sp, color = foreground, fontFamily = PlexMonoFamily, softWrap = true)
                        if (refs != null && line.kind in setOf(GitLabDiffLine.Kind.CONTEXT, GitLabDiffLine.Kind.ADDED, GitLabDiffLine.Kind.REMOVED)) {
                            TextButton(onClick = { commentLine = index }, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                                enabled = !state.gitlabMrActionBusy) { Text("＋", fontSize = 16.sp, maxLines = 1) }
                        }
                    }
                    val oldLine = line.oldNumber.toIntOrNull()
                    val newLine = line.newNumber.toIntOrNull()
                    discussions.filter { thread ->
                        thread.oldLine != null && thread.oldLine == oldLine || thread.newLine != null && thread.newLine == newLine
                    }.forEach { GitLabDiscussionCard(it, state, mr, listOf(change)) }
                    if (commentLine == index) {
                        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), singleLine = true,
                                label = { Text("Комментарий к строке ${line.newNumber.ifBlank { line.oldNumber }}") })
                            Button(onClick = {
                                state.commentGitLabMrDiff(mr, draft, refs!!, change,
                                    line.oldNumber.toIntOrNull(), line.newNumber.toIntOrNull())
                            }, enabled = draft.isNotBlank() && !state.gitlabMrActionBusy) { Text("Отправить") }
                        }
                        state.gitlabMrActionMessage?.let { message ->
                            Text(message, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 10.dp, vertical = 4.dp),
                                fontSize = 11.sp, color = if (message.startsWith("GitLab") || message.startsWith("Для комментариев") || message.startsWith("Для review")) MaterialTheme.colorScheme.error else Green)
                        }
                    }
                }
            }
        }
    }
}

private fun externalBrowserChoices(): List<ExternalBrowser> = when (Platform.os) {
    Os.MAC -> listOf(ExternalBrowser.CHROME, ExternalBrowser.SAFARI, ExternalBrowser.FIREFOX, ExternalBrowser.EDGE, ExternalBrowser.SYSTEM)
    Os.WINDOWS -> listOf(ExternalBrowser.CHROME, ExternalBrowser.FIREFOX, ExternalBrowser.EDGE, ExternalBrowser.SYSTEM)
    Os.OTHER -> listOf(ExternalBrowser.SYSTEM)
}

private fun openInExternalBrowser(url: String, browser: ExternalBrowser) {
    val launched = runCatching {
        when {
            browser == ExternalBrowser.SYSTEM || Platform.os == Os.OTHER -> java.awt.Desktop.getDesktop().browse(java.net.URI(url))
            Platform.os == Os.MAC -> {
                val appFolders = listOf(
                    java.io.File("/Applications/${browser.macApp}.app"),
                    java.io.File("/System/Applications/${browser.macApp}.app"),
                    java.io.File(System.getProperty("user.home"), "Applications/${browser.macApp}.app"),
                )
                check(appFolders.any { it.exists() }) { "Браузер не найден" }
                ProcessBuilder("open", "-a", browser.macApp, url).start()
            }
            Platform.os == Os.WINDOWS -> {
                val roots = listOfNotNull(System.getenv("PROGRAMFILES"), System.getenv("PROGRAMFILES(X86)"), System.getenv("LOCALAPPDATA"))
                val paths = when (browser) {
                    ExternalBrowser.CHROME -> roots.map { java.io.File(it, "Google/Chrome/Application/chrome.exe") } +
                        System.getenv("LOCALAPPDATA")?.let { java.io.File(it, "Google/Chrome/Application/chrome.exe") }
                    ExternalBrowser.FIREFOX -> roots.map { java.io.File(it, "Mozilla Firefox/firefox.exe") }
                    ExternalBrowser.EDGE -> roots.map { java.io.File(it, "Microsoft/Edge/Application/msedge.exe") }
                    else -> emptyList()
                }.filterNotNull()
                val executable = paths.firstOrNull { it.isFile }?.absolutePath ?: browser.windowsExe
                ProcessBuilder(executable, url).start()
            }
            else -> java.awt.Desktop.getDesktop().browse(java.net.URI(url))
        }
        true
    }.getOrDefault(false)
    if (!launched) openInSystemBrowser(url)
}

@Composable
private fun MrStat(label: String, value: String, modifier: Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(value, fontWeight = FontWeight.SemiBold)
            Text(label, fontSize = 11.sp, color = Gray)
        }
    }
}

private fun mrStatus(s: String) = when (s.lowercase()) { "opened" -> "Открыт"; "merged" -> "Влит"; "closed" -> "Закрыт"; else -> s }
@Composable
private fun mrStatusColor(s: String) = if (s.equals("merged", true)) Green else if (s.equals("closed", true)) Gray else MaterialTheme.colorScheme.primary
private fun mergeStatus(s: String) = when (s.lowercase()) { "can_be_merged" -> "можно влить"; "cannot_be_merged" -> "есть конфликты"; "checking" -> "проверяется"; "unchecked" -> "не проверено"; else -> s.replace('_', ' ') }
private fun pipelineStatus(s: String) = when (s.lowercase()) {
    "success" -> "Успешно"; "failed" -> "Ошибка"; "running" -> "Выполняется"; "pending" -> "В очереди"; "canceled" -> "Отменён"; "skipped" -> "Пропущен"; else -> s
}
private fun pipelineMark(s: String) = when (s.lowercase()) { "success" -> "✓"; "failed" -> "×"; "running" -> "◌"; "pending" -> "…"; "canceled" -> "−"; else -> "•" }
@Composable
private fun pipelineColor(s: String) = when (s.lowercase()) {
    "success" -> Green; "failed" -> MaterialTheme.colorScheme.error; "running", "pending" -> Amber; else -> Gray
}

internal fun openInSystemBrowser(url: String) {
    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
}

/** 2026-10-18T09:12:00.000+03:00 → «18.10 09:12» по часам этого компьютера. */
private fun shortTime(iso: String): String = runCatching {
    java.time.OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm"))
}.getOrDefault("")

// ============================================================================ Журнал

@Composable
private fun LogTab(state: AppState) {
    var which by remember { mutableStateOf(0) }
    var fileText by remember { mutableStateOf("") }
    LaunchedEffect(which) {
        while (which != 0) {
            fileText = state.readLog(when (which) { 1 -> AppState.LogKind.CISCO; 3 -> AppState.LogKind.CHAIN; else -> AppState.LogKind.SINGBOX })
            delay(2000)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(which == 0, { which = 0 }, label = { Text("Программа") })
            FilterChip(which == 1, { which = 1 }, label = { Text("Cisco") })
            FilterChip(which == 2, { which = 2 }, label = { Text("sing-box") })
            if (state.settings.abroad.enabled) FilterChip(which == 3, { which = 3 }, label = { Text("Российский VPN") })
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            SelectionContainer {
                if (which == 0) {
                    val listState = rememberLazyListState()
                    LaunchedEffect(state.logLines.size) {
                        if (state.logLines.isNotEmpty()) listState.scrollToItem(state.logLines.size - 1)
                    }
                    LazyColumn(state = listState, modifier = Modifier.padding(10.dp)) {
                        items(state.logLines) { Text(it, fontFamily = PlexMonoFamily, fontSize = 12.sp) }
                    }
                } else {
                    Text(
                        fileText.ifBlank { "(журнал пуст)" },
                        fontFamily = PlexMonoFamily, fontSize = 11.sp,
                        modifier = Modifier.padding(10.dp).verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}
