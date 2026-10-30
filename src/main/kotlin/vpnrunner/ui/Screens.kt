package vpnrunner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import vpnrunner.core.*
import vpnrunner.platform.Os
import vpnrunner.platform.Platform
import vpnrunner.platform.SecretStore

private val Green = Color(0xFF2E9E5B)
private val Amber = Color(0xFFD9922B)
private val Gray = Color(0xFF8A8F98)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(primary = Color(0xFF7FB0FF), secondary = Green)
    else lightColorScheme(primary = Color(0xFF2F5FD0), secondary = Green)
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(state: AppState) {
    var tab by remember { mutableStateOf(0) }
    val mrCount = state.mergeRequests.size.takeIf { state.settings.gitlab.enabled && it > 0 }
    val tabs = listOf("Главная", "Правила", "Подписки", "Cisco", "Из-за рубежа", "GitLab" + (mrCount?.let { " ($it)" } ?: ""), "Jira", "Журнал") +
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
    Column(Modifier.fillMaxSize()) {
        // Строка вкладок горизонтальная, а обычное колесо мыши крутит по вертикали — без этого
        // её можно прокрутить только трекпадом или Shift+колесо. Переводим вертикальную прокрутку в горизонтальную.
        SecondaryScrollableTabRow(
            selectedTabIndex = tab,
            scrollState = tabScroll,
            edgePadding = 8.dp,
            modifier = Modifier.onPointerEvent(PointerEventType.Scroll) { e ->
                val change = e.changes.firstOrNull() ?: return@onPointerEvent
                val d = change.scrollDelta
                if (change.isConsumed || d.x != 0f || d.y == 0f) return@onPointerEvent
                change.consume()
                scope.launch { tabScroll.scrollBy(d.y * wheelStep) }
            },
        ) {
            tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, maxLines = 1) }) }
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
        Box(Modifier.fillMaxSize().padding(16.dp)) {
            when (tab) {
                0 -> HomeTab(state)
                1 -> RulesTab(state)
                2 -> SubscriptionsTab(state)
                3 -> CiscoTab(state)
                4 -> AbroadTab(state)
                5 -> GitLabTab(state, openInBrowserTab)
                6 -> JiraTab(state, openInBrowserTab)
                7 -> LogTab(state)
                8 -> BrowserTab(state)
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
private fun HomeTab(state: AppState) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (Platform.os == Os.WINDOWS && !state.isAdmin) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Нужны права администратора", fontWeight = FontWeight.SemiBold)
                    Text("Без них Windows не даст создать VPN-интерфейсы.", fontSize = 13.sp)
                    Button(onClick = { state.relaunchAsAdmin() }) { Text("Перезапустить от имени администратора") }
                }
            }
        }

        val phase = state.phase
        val busy = phase == Phase.STARTING || phase == Phase.STOPPING
        Button(
            onClick = { if (phase == Phase.STOPPED) state.run() else state.requestStop() },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(64.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (phase == Phase.RUNNING) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            ),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(12.dp))
            }
            Text(
                when (phase) {
                    Phase.STOPPED -> "Run"
                    Phase.STARTING -> "Подключаю…"
                    Phase.RUNNING -> "Stop"
                    Phase.STOPPING -> "Останавливаю…"
                },
                fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            )
        }

        if (state.killSwitchStuck && phase == Phase.STOPPED) KillSwitchStuckCard(state)
        if (state.restartNeeded) RestartBanner()

        WorkDayCard(state)

        val abroad = state.settings.abroad
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (abroad.enabled) StatusRow("Режим", "из-за рубежа" to MaterialTheme.colorScheme.primary)
                StatusRow(
                    "Cisco",
                    when {
                        !state.settings.cisco.enabled -> "выключен" to Gray
                        state.ciscoUp -> (if (abroad.enabled) "подключён через РФ" else "подключён") to Green
                        phase == Phase.STARTING -> "подключается…" to Amber
                        else -> "отключён" to Gray
                    },
                )
                StatusRow(
                    if (abroad.enabled) "Российский VPN" else "VPN",
                    when (phase) {
                        Phase.RUNNING -> (state.currentServer ?: "работает") to Green
                        Phase.STARTING -> (if (abroad.enabled) "подключается…" else "ждёт Cisco…") to Amber
                        Phase.STOPPING -> "останавливается…" to Amber
                        Phase.STOPPED -> "отключён" to Gray
                    },
                )
                if (abroad.enabled) {
                    if (phase == Phase.RUNNING) StatusRow("Снаружи видно", (state.exitInfo ?: "—") to if (state.exitInfo != null) Green else Gray)
                    StatusRow("Остальной трафик", if (abroad.allViaRu) "через российский VPN" to Green else "напрямую" to Amber)
                    StatusRow("Kill switch", when {
                        !abroad.killSwitch -> "выключен" to Amber
                        Platform.os != Os.MAC -> "только для Cisco" to Amber
                        else -> "включён" to Green
                    })
                } else {
                    StatusRow(
                        "Российские сайты",
                        if (state.settings.russiaDirect) "напрямую" to Green else "через VPN" to Amber,
                    )
                }
            }
        }

        if (abroad.enabled) {
            Text("Российский сервер и остальные настройки — во вкладке «Из-за рубежа».", fontSize = 12.sp, color = Gray)
        } else {
            ServerPicker(state)

            Card {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Российские сайты мимо VPN")
                        Text(".ru, .рф, .su и список российских сервисов", fontSize = 12.sp, color = Gray)
                    }
                    Switch(checked = state.settings.russiaDirect, onCheckedChange = { v -> state.update { it.copy(russiaDirect = v) } })
                }
            }
        }
        if (state.rulesDirty) ApplyRulesBanner(state)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerPicker(state: AppState) {
    var expanded by remember { mutableStateOf(false) }
    val selectedId = state.settings.selectedServer
    val selectedName = if (selectedId == AppSettings.AUTO) "Авто — самый быстрый"
    else state.servers.firstOrNull { it.id == selectedId }?.let { "${it.name}  ·  ${it.subscription}" } ?: "Авто — самый быстрый"

    Row(verticalAlignment = Alignment.CenterVertically) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.weight(1f)) {
            OutlinedTextField(
                value = selectedName, onValueChange = {}, readOnly = true, singleLine = true,
                label = { Text("Сервер VPN (${state.servers.size})") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text("Авто — самый быстрый") }, onClick = { state.selectServer(AppSettings.AUTO); expanded = false })
                state.servers.groupBy { it.subscription }.forEach { (sub, list) ->
                    HorizontalDivider()
                    Text(sub, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 12.sp, color = Gray)
                    list.forEach { s ->
                        DropdownMenuItem(text = { Text(s.name, maxLines = 1) }, onClick = { state.selectServer(s.id); expanded = false })
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { state.refreshServers() }, enabled = !state.loadingServers) {
            Text(if (state.loadingServers) "…" else "Обновить")
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
                fontFamily = FontFamily.Monospace, fontSize = 13.sp)
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
            state.servers.isEmpty() -> Text("Серверов пока нет — добавьте подписку во вкладке «Подписки».", fontSize = 12.sp, color = Gray)
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
                        "через местный интернет. Правила из вкладки «Правила» действуют: «Через VPN» — через российский.",
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
private fun GitLabTab(state: AppState, onOpen: (String) -> Unit) {
    val g = state.settings.gitlab
    fun upd(t: (GitLabSettings) -> GitLabSettings) = state.update { it.copy(gitlab = t(it.gitlab)) }
    var token by remember { mutableStateOf("") }
    val host = g.urls.firstNotNullOfOrNull { GitLabList.parse(it) }?.host

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Уведомлять о новых merge request")
                Text("Проверка раз в 2 минуты, пока подключён Cisco. Уведомление — от значка в строке меню.", fontSize = 12.sp, color = Gray)
            }
            Switch(checked = g.enabled, onCheckedChange = { v -> upd { it.copy(enabled = v) } })
        }
        ListField("Ссылки на списки merge request, по одной в строке", g.urls) { v -> upd { it.copy(urls = v) } }
        Text("Откройте список MR в браузере, настройте фильтры (не черновики, не мои, не одобренные мной…) и скопируйте адрес. " +
            "Учитываются автор, черновики, одобрения, ревьюер, исполнитель, метки и ветки.", fontSize = 12.sp, color = Gray)
        g.urls.filter { GitLabList.parse(it) == null }.forEach {
            Text("Не похоже на список merge request (нужно …/-/merge_requests): $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
        SecretField(
            title = "Токен GitLab",
            saved = state.hasGitLabToken,
            value = token, onValue = { token = it },
            onSave = { state.saveGitLabToken(token); token = "" },
            onForget = { state.forgetSecret(SecretStore.GITLAB_TOKEN) },
            hint = "Личный токен с правами read_api (только чтение)",
        )
        if (host != null && !state.hasGitLabToken) {
            TextButton(onClick = { openInSystemBrowser("$host/-/user_settings/personal_access_tokens?name=VpnRunner&scopes=read_api") }) {
                Text("Создать токен в GitLab")
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
            Card(Modifier.fillMaxWidth().clickable { onOpen(mr.webUrl) }) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text("!${mr.iid}  ${mr.title}", fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOf(mr.author, mr.projectPath.substringAfterLast('/'), shortTime(mr.createdAt)).filter { it.isNotBlank() }.joinToString(" · "),
                        fontSize = 12.sp, color = Gray)
                }
            }
        }
    }
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
                        items(state.logLines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                    }
                } else {
                    Text(
                        fileText.ifBlank { "(журнал пуст)" },
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        modifier = Modifier.padding(10.dp).verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}
