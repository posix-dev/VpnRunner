package vpnrunner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vpnrunner.core.*
import vpnrunner.platform.SecretStore
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Muted = Color(0xFF8A8F98)
private val Done = Color(0xFF2E9E5B)

@Composable
fun JiraTab(state: AppState, onOpen: (String) -> Unit) {
    val js = state.jira
    val j = state.settings.jira
    val configured = j.enabled && js.board != null && js.hasToken
    var showSettings by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        js.certOffer?.let { CertCard(it) { js.trustCert() } }
        if (!configured || showSettings) {
            JiraSettingsBlock(state)
            HorizontalDivider()
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(js.status ?: "Списание времени выключено", Modifier.weight(1f), fontSize = 13.sp,
                color = if (js.status?.startsWith("Ошибка") == true) MaterialTheme.colorScheme.error else Muted)
            if (js.loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else TextButton(onClick = { js.refresh() }, enabled = configured) { Text("Обновить") }
        }
        if (configured && js.sprints.isNotEmpty()) {
            ViewAndSprint(js)
            if (js.weekView) {
                WeekNav(js)
                DefaultAttributes(state)
                WeekGrid(state, onOpen)
                AddIssueField(js)
            } else {
                DayNav(js)
                DaySummary(state)
                ContainerCard(state)
                AddIssueField(js)
                LaunchedEffect(js.justAdded) { js.justAdded?.let { expanded = it } }
                val (inSprint, other) = js.rows.partition { it.inSprint }
                Section("Спринт · на мне", inSprint, "В этом спринте на вас задач нет", state, expanded, { expanded = it }, onOpen)
                if (other.isNotEmpty()) Section("Вне спринта", other, null, state, expanded, { expanded = it }, onOpen)
                DayLogs(js)
            }
        }
        if (configured) TextButton(onClick = { showSettings = !showSettings }) {
            Text(if (showSettings) "Скрыть настройки" else "Настройки Jira")
        }
    }
}

// ---------------------------------------------------------------- настройки

@Composable
private fun JiraSettingsBlock(state: AppState) {
    val js = state.jira
    val j = state.settings.jira
    fun upd(t: (JiraSettings) -> JiraSettings) = state.update { it.copy(jira = t(it.jira)) }
    var token by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Списывать время в Jira")
                Text("Мои задачи спринта с оставшимся временем, списание через Tempo. Обновление раз в 10 минут, пока подключён Cisco.",
                    fontSize = 12.sp, color = Muted)
            }
            Switch(checked = j.enabled, onCheckedChange = { v -> upd { it.copy(enabled = v) } })
        }
        OutlinedTextField(
            value = j.boardUrl, onValueChange = { v -> upd { it.copy(boardUrl = v.trim()) } }, singleLine = true,
            label = { Text("Ссылка на доску Jira") },
            placeholder = { Text("https://jira.company.ru/secure/RapidBoard.jspa?rapidView=…") },
            isError = j.boardUrl.isNotBlank() && js.board == null,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(if (j.boardUrl.isNotBlank() && js.board == null) "Не похоже на ссылку на доску — нужен адрес с rapidView=… (откройте доску и скопируйте адрес)"
            else "Откройте доску спринта в браузере и скопируйте адрес.",
            fontSize = 12.sp, color = if (j.boardUrl.isNotBlank() && js.board == null) MaterialTheme.colorScheme.error else Muted)
        SecretField(
            title = "Токен Jira", saved = js.hasToken, value = token, onValue = { token = it },
            onSave = { js.saveToken(token); token = "" },
            onForget = { state.forgetSecret(SecretStore.JIRA_TOKEN) },
            hint = "Personal Access Token: Jira → профиль → Personal Access Tokens",
        )
        js.board?.takeIf { !js.hasToken }?.let { b ->
            TextButton(onClick = { openInSystemBrowser("${b.host}/secure/ViewProfile.jspa?selectedTab=com.atlassian.pats.pats-plugin:jira-user-personal-access-tokens") }) {
                Text("Создать токен в Jira")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HoursField("Норма в день, ч", j.dayHours, Modifier.weight(1f)) { v -> upd { it.copy(dayHours = v) } }
            HoursField("Контейнер спринта, ч в день", j.containerHours, Modifier.weight(1f)) { v -> upd { it.copy(containerHours = v) } }
        }
    }
}

@Composable
private fun HoursField(label: String, value: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text, singleLine = true, label = { Text(label) }, modifier = modifier,
        onValueChange = { v -> text = v.filter { it.isDigit() }.take(2); text.toIntOrNull()?.takeIf { it in 1..24 }?.let(onChange) },
    )
}

@Composable
private fun CertCard(o: CertOffer, onTrust: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val c = MaterialTheme.colorScheme.onErrorContainer
            Text("Jira (${o.host}) подписана сертификатом «${o.name}» — его нет среди известных центров сертификации.", fontSize = 13.sp, color = c)
            SelectionContainer { Text("SHA-256: ${o.sha256}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = c) }
            Text("Доверяйте, только если это корневой сертификат вашей компании (отпечаток можно сверить с IT). " +
                "Доверие действует только для запросов программы к Jira.", fontSize = 12.sp, color = c)
            Button(onClick = onTrust) { Text("Доверять") }
        }
    }
}

// ---------------------------------------------------------------- спринт, день, итог

private val dayFmt = DateTimeFormatter.ofPattern("EE, dd.MM", Locale.forLanguageTag("ru"))

private fun dayLabel(d: LocalDate): String {
    val today = WorkClock.now().toLocalDate()
    return when (d) {
        today -> "Сегодня, ${d.format(JiraState.DM)}"
        today.minusDays(1) -> "Вчера, ${d.format(JiraState.DM)}"
        else -> d.format(dayFmt)
    }
}

@Composable
private fun ViewAndSprint(js: JiraState) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = js.weekView, onClick = { js.weekView = true }, label = { Text("Неделя") })
        FilterChip(selected = !js.weekView, onClick = { js.weekView = false }, label = { Text("День") })
        Spacer(Modifier.width(4.dp))
        Box(Modifier.weight(1f)) {
            OutlinedButton(onClick = { open = true }) {
                val s = js.sprint
                Text((s?.let { sprintTitle(it) } ?: "Спринт") + "  ▾", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                js.sprints.forEach { s ->
                    DropdownMenuItem(text = { Text(sprintTitle(s) + if (s.active) "" else " · будущий") }, onClick = { open = false; js.selectSprint(s.id) })
                }
            }
        }
    }
}

@Composable
private fun DayNav(js: JiraState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { js.shiftDay(-1) }, contentPadding = PaddingValues(8.dp)) { Text("‹", fontSize = 18.sp) }
        Text(dayLabel(js.day), fontSize = 13.sp)
        TextButton(onClick = { js.shiftDay(1) }, contentPadding = PaddingValues(8.dp)) { Text("›", fontSize = 18.sp) }
        if (js.day != WorkClock.now().toLocalDate()) TextButton(onClick = { js.goTo(WorkClock.now().toLocalDate()) }) { Text("Сегодня", fontSize = 12.sp) }
        DatePickButton(js)
    }
}

private fun sprintTitle(s: JiraSprint) = s.name + (s.end?.let { " · до ${it.format(JiraState.DM)}" } ?: "")

@Composable
private fun DaySummary(state: AppState) {
    val js = state.jira
    val done = js.dayLogs.sumOf { it.seconds }
    val norm = state.settings.jira.dayHours * 3600L
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                Text("Списано ${WorkTime.text(done)} из ${state.settings.jira.dayHours} ч", Modifier.weight(1f), fontSize = 14.sp)
                if (js.day == state.workToday.date) state.workToday.firstUp?.let {
                    Text("Cisco с ${it.format(WorkClock.HHMM)}", fontSize = 12.sp, color = Muted)
                }
            }
            LinearProgressIndicator(progress = { (done.toFloat() / norm).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(),
                color = if (done >= norm) Done else MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ContainerCard(state: AppState) {
    val js = state.jira
    val k = state.settings.jira.container ?: return
    val hours = state.settings.jira.containerHours
    val done = js.dayLogs.filter { it.issueKey == k.issueKey }.sumOf { it.seconds }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Контейнер спринта · ", fontSize = 13.sp, color = Muted)
                Text(k.issueKey, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                Checkbox(checked = true, onCheckedChange = { js.markContainer(null) })
            }
            js.container?.let { Text(it.summary, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Text("${dayLabel(js.day)}: ${WorkTime.text(done)} из $hours ч" + if (done >= hours * 3600L) " ✓" else "",
                fontSize = 13.sp, color = if (done >= hours * 3600L) Done else Muted)
            js.containerNote?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
            Text("Спринт «${k.sprintName}». Снять галку — перестать списывать.", fontSize = 11.sp, color = Muted)
            ContainerFill(state)
        }
    }
}

// ---------------------------------------------------------------- задачи и форма списания

/** Задача не из списка (закрытый спринт, чужая, другая доска): ключ или ссылка → задача появляется в «Вне спринта». */
@Composable
private fun AddIssueField(js: JiraState) {
    var text by remember { mutableStateOf("") }
    fun add() { if (text.isNotBlank()) { js.addIssue(text); text = "" } }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                text, { text = it }, singleLine = true,
                label = { Text("Другая задача") },
                placeholder = { Text("SYRAX-1234 или ссылка на задачу") },
                isError = js.addError != null,
                modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) { add(); true } else false
                },
            )
            Button(onClick = { add() }, enabled = text.isNotBlank() && !js.busy) { Text("Добавить") }
        }
        Text(js.addError ?: "Чтобы списать на задачу не из списка: из закрытого спринта, не на вас, с другой доски.",
            fontSize = 12.sp, color = if (js.addError != null) MaterialTheme.colorScheme.error else Muted)
    }
}

@Composable
private fun Section(
    title: String, rows: List<JiraRow>, empty: String?, state: AppState,
    expanded: String?, onExpand: (String?) -> Unit, onOpen: (String) -> Unit,
) {
    Text(title, fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
    if (rows.isEmpty() && empty != null) Text(empty, fontSize = 13.sp, color = Muted)
    rows.forEach { r ->
        IssueRow(r, state, expanded == r.issue.key, { onExpand(if (expanded == r.issue.key) null else r.issue.key) }, onOpen) { onExpand(null) }
    }
}

@Composable
private fun IssueRow(row: JiraRow, state: AppState, expanded: Boolean, onToggle: () -> Unit, onOpen: (String) -> Unit, onLogged: () -> Unit) {
    val i = row.issue
    val over = i.original != null && i.original > 0 && i.spent > i.original
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.clickable { onToggle() }.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(i.key, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                Text(i.summary, Modifier.weight(1f), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(i.status, fontSize = 11.sp, color = Muted, maxLines = 1)
            }
            if (row.tags.isNotEmpty()) Text(row.tags.joinToString(" · "), fontSize = 11.sp, color = Muted)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val total = i.spent + (i.remaining ?: 0)
                LinearProgressIndicator(progress = { if (total > 0) i.spent.toFloat() / total else 0f }, modifier = Modifier.weight(1f),
                    color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                Text(timeLine(i), fontSize = 12.sp, color = if (over) MaterialTheme.colorScheme.error else Muted)
            }
        }
        if (expanded) LogForm(i, row.manual, state, onOpen, onLogged)
    }
}

private fun timeLine(i: JiraIssue): String {
    val spent = if (i.spent > 0) "списано ${WorkTime.text(i.spent)}" else "без списаний"
    val of = i.original?.takeIf { it > 0 && i.spent > 0 }?.let { " из ${WorkTime.text(it)}" } ?: ""
    return if (i.remaining == null) "без оценки · $spent" else "осталось ${WorkTime.text(i.remaining)} · $spent$of"
}

/** «0», «0h», «0м» — оставшаяся оценка ноль (WorkTime.parse ноль не принимает: списать 0 нельзя). */
private fun parseRemaining(s: String): Long? = if (Regex("\\s*0+\\s*[hmчм]?\\s*").matches(s.lowercase())) 0 else WorkTime.parse(s)

@Composable
private fun LogForm(i: JiraIssue, manual: Boolean, state: AppState, onOpen: (String) -> Unit, onLogged: () -> Unit) {
    val js = state.jira
    val j = state.settings.jira
    var time by remember(i.key) { mutableStateOf("") }
    var comment by remember(i.key) { mutableStateOf("") }
    var remainingText by remember(i.key) { mutableStateOf<String?>(null) } // null — считать самим
    var attrs by remember(i.key) { mutableStateOf(js.attributes.mapNotNull { a -> j.attributes[a.key]?.let { a.key to it } }.toMap()) }
    val sec = WorkTime.parse(time)
    val autoRemaining = i.remaining?.let { r -> maxOf(0L, r - (sec ?: 0)) }
    val remaining = remainingText?.let { parseRemaining(it) } ?: autoRemaining
    val missingAttr = js.attributes.firstOrNull { attrs[it.key].isNullOrBlank() }
    val leftToday = (j.dayHours * 3600L - js.dayLogs.sumOf { it.seconds }).coerceAtLeast(0)

    Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("15m" to "15м", "30m" to "30м", "1h" to "1ч", "2h" to "2ч").forEach { (v, label) ->
                OutlinedButton(onClick = { time = v; remainingText = null }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(label, fontSize = 12.sp) }
            }
            OutlinedButton(onClick = { time = WorkTime.field(leftToday); remainingText = null }, enabled = leftToday > 0,
                contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Остаток дня", fontSize = 12.sp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(time, { time = it; remainingText = null }, singleLine = true, label = { Text("Время") },
                placeholder = { Text("1h 30m") }, isError = time.isNotBlank() && sec == null, modifier = Modifier.weight(1f))
            if (i.remaining != null) OutlinedTextField(
                remainingText ?: WorkTime.field(autoRemaining ?: 0), { remainingText = it }, singleLine = true,
                label = { Text("Останется") }, isError = remainingText != null && remaining == null, modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(comment, { comment = it }, label = { Text("Что сделано (необязательно)") }, maxLines = 3, modifier = Modifier.fillMaxWidth())
        js.attributes.forEach { a -> AttributeField(a, attrs[a.key] ?: "") { v -> attrs = attrs + (a.key to v) } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (js.sprint != null) {
                Checkbox(checked = false, onCheckedChange = { js.markContainer(i) })
                Text("Контейнер спринта: ${j.containerHours} ч каждый рабочий день", Modifier.weight(1f), fontSize = 12.sp, color = Muted)
            } else Spacer(Modifier.weight(1f))
            if (manual) TextButton(onClick = { js.removeIssue(i.key) }) { Text("Убрать", color = Muted) }
            js.board?.let { b -> TextButton(onClick = { onOpen("${b.host}/browse/${i.key}") }) { Text("Открыть") } }
            Button(
                onClick = { js.addWorklog(i, sec ?: return@Button, comment, remaining.takeIf { i.remaining != null }, attrs) { onLogged() } },
                enabled = sec != null && missingAttr == null && (remainingText == null || remaining != null) && !js.busy,
            ) { Text("Списать") }
        }
        val hint = when {
            sec == null && time.isNotBlank() -> "Время — как в Jira: 1h 30m, 45m, 1.5"
            missingAttr != null -> "Выберите «${missingAttr.name}» — без него Tempo не примет списание"
            sec != null -> "За ${dayLabel(js.day).lowercase()}: ${WorkTime.text(sec)}"
            else -> null
        }
        hint?.let { Text(it, fontSize = 12.sp, color = Muted) }
    }
}

@Composable
private fun AttributeField(a: TempoAttribute, value: String, onChange: (String) -> Unit) {
    if (a.values.isEmpty()) {
        OutlinedTextField(value, onChange, singleLine = true, label = { Text(a.name) }, modifier = Modifier.fillMaxWidth())
        return
    }
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text("${a.name}: " + (a.values.firstOrNull { it.first == value }?.second ?: "выберите") + "  ▾", fontSize = 13.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            a.values.forEach { (v, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onChange(v) }) }
        }
    }
}

@Composable
private fun DayLogs(js: JiraState) {
    Text("Списано за ${dayLabel(js.day).lowercase()}", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
    if (js.dayLogs.isEmpty()) { Text("Пока ничего", fontSize = 13.sp, color = Muted); return }
    var confirm by remember { mutableStateOf<Long?>(null) }
    js.dayLogs.forEach { w ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(w.issueKey, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
            Text(WorkTime.text(w.seconds), fontSize = 13.sp)
            Text(w.comment.ifBlank { w.issueSummary }, Modifier.weight(1f), fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (confirm == w.id) {
                TextButton(onClick = { confirm = null; js.deleteWorklog(w) }, enabled = !js.busy) { Text("Удалить", color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                TextButton(onClick = { confirm = null }) { Text("Нет", fontSize = 12.sp) }
            } else TextButton(onClick = { confirm = w.id }) { Text("✕", color = Muted) }
        }
    }
}

// ---------------------------------------------------------------- неделя (как в Tempo)

private val CELL = 46.dp
private val TOTAL = 48.dp
private val weekDayFmt = DateTimeFormatter.ofPattern("EE", Locale.forLanguageTag("ru"))
private val monthFmt = DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("ru"))

@Composable
private fun WeekNav(js: JiraState) {
    val m = js.weekStart
    val today = WorkClock.now().toLocalDate()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { js.shiftWeek(-1) }, contentPadding = PaddingValues(8.dp)) { Text("‹", fontSize = 18.sp) }
            Text("${m.dayOfMonth} – ${m.plusDays(6).format(monthFmt)}", fontSize = 13.sp)
            TextButton(onClick = { js.shiftWeek(1) }, contentPadding = PaddingValues(8.dp)) { Text("›", fontSize = 18.sp) }
            if (today.with(java.time.DayOfWeek.MONDAY) != m) TextButton(onClick = { js.goTo(today) }) { Text("Сегодня", fontSize = 12.sp) }
            DatePickButton(js)
            Spacer(Modifier.weight(1f))
            FilterChip(selected = js.showWeekends, onClick = { js.showWeekends = !js.showWeekends }, label = { Text("Сб, Вс", fontSize = 12.sp) })
        }
        Text("Часы — прямо в ячейке, Enter — сохранить. Можно любой день, в том числе будущий.", fontSize = 11.sp, color = Muted)
    }
}

/** Переход к любому дню — календарь (месяцы и дни недели по-русски). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickButton(js: JiraState) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("Дата…", fontSize = 12.sp) }
    if (!open) return
    // Календарь в UTC: полночь выбранного дня — без сдвига часового пояса.
    val picker = remember {
        DatePickerState(Locale.forLanguageTag("ru"), initialSelectedDateMillis = js.day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
    }
    DatePickerDialog(
        onDismissRequest = { open = false },
        confirmButton = {
            TextButton(onClick = {
                picker.selectedDateMillis?.let { js.goTo(java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate()) }
                open = false
            }) { Text("Перейти") }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text("Отмена") } },
    ) {
        DatePicker(
            state = picker, showModeToggle = false,
            title = { Text("Перейти к дню", Modifier.padding(start = 24.dp, top = 16.dp)) },
            headline = { Text(picker.selectedDateMillis?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate().format(dayFmt) } ?: "",
                Modifier.padding(start = 24.dp, bottom = 8.dp), fontSize = 22.sp) },
        )
    }
}

/**
 * Контейнер спринта: проставить containerHours на будни недели или всего спринта, где на него ещё не списано,
 * и автосписание — только в дни с Cisco или во все будни.
 */
@Composable
private fun ContainerFill(state: AppState) {
    val js = state.jira
    val j = state.settings.jira
    val k = j.container ?: return
    val week = js.containerGaps(js.weekStart, js.weekStart.plusDays(6))
    val sp = js.containerSprint
    val sprint = sp?.let { js.containerGaps(it.start ?: js.weekStart, it.end ?: js.weekStart.plusDays(6)) } ?: emptyList()
    var ask by remember { mutableStateOf<List<LocalDate>?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Контейнер ${k.issueKey}: по ${j.containerHours} ч", fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { ask = week }, enabled = week.isNotEmpty() && !js.busy, contentPadding = PaddingValues(horizontal = 10.dp)) {
                Text(if (week.isEmpty()) "Неделя заполнена" else "На неделю (${week.size} дн.)", fontSize = 12.sp)
            }
            if (sp != null) OutlinedButton(onClick = { ask = sprint }, enabled = sprint.isNotEmpty() && !js.busy, contentPadding = PaddingValues(horizontal = 10.dp)) {
                Text(if (sprint.isEmpty()) "Спринт заполнен" else "На спринт (${sprint.size} дн.)", fontSize = 12.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = !j.containerOnlyCisco, onCheckedChange = { v -> state.update { it.copy(jira = it.jira.copy(containerOnlyCisco = !v)) }; js.refresh() })
            Text(if (j.containerOnlyCisco) "Сам ставит ${j.containerHours} ч только в будни, когда был включён Cisco. Отметьте — во все будни с ${shortDate(k.since)}"
                else "Сам ставит ${j.containerHours} ч во все будни с ${shortDate(k.since)} по сегодня (будущие — кнопками)",
                fontSize = 11.sp, color = Muted)
        }
    }
    ask?.let { days ->
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text("Проставить контейнер") },
            text = {
                Text("${k.issueKey}: по ${j.containerHours} ч за ${days.size} дн. (всего ${WorkTime.text(days.size * j.containerHours * 3600L)}):\n" +
                    days.joinToString(", ") { it.format(JiraState.DM) } + "\n\nПраздники не учитываются — только суббота и воскресенье.")
            },
            confirmButton = { Button(onClick = { ask = null; js.fillContainer(days) }) { Text("Проставить") } },
            dismissButton = { TextButton(onClick = { ask = null }) { Text("Отмена") } },
        )
    }
}

private fun shortDate(iso: String): String = runCatching { LocalDate.parse(iso).format(JiraState.DM) }.getOrDefault(iso)

/** Обязательные атрибуты Tempo для списаний из таблицы (в виде «День» они выбираются в форме). */
@Composable
private fun DefaultAttributes(state: AppState) {
    val js = state.jira
    if (js.attributes.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        js.attributes.forEach { a ->
            AttributeField(a, state.settings.jira.attributes[a.key] ?: "") { v ->
                state.update { it.copy(jira = it.jira.copy(attributes = it.jira.attributes + (a.key to v))) }
            }
        }
    }
}

@Composable
private fun WeekGrid(state: AppState, onOpen: (String) -> Unit) {
    val js = state.jira
    val j = state.settings.jira
    val today = WorkClock.now().toLocalDate()
    val week = js.weekLogs
    // Выходные — только если в них что-то списано: так таблица помещается в окно.
    val days = (0L..6L).map { js.weekStart.plusDays(it) }.filter { d -> js.showWeekends || WorkClock.isWorkday(d) || week.any { it.date == d } }
    val norm = j.dayHours * 3600L
    val divider = MaterialTheme.colorScheme.outlineVariant

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Задача", Modifier.weight(1f), fontSize = 12.sp, color = Muted)
            days.forEach { d ->
                Column(Modifier.width(CELL).clickable { js.openDay(d) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(d.format(weekDayFmt), fontSize = 11.sp, color = Muted)
                    Text(d.dayOfMonth.toString(), fontSize = 13.sp,
                        color = if (d == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
            }
            Text("Итого", Modifier.width(TOTAL), fontSize = 12.sp, color = Muted, textAlign = TextAlign.Center)
        }
        HorizontalDivider(color = divider)
        val k = j.container
        val cont = js.container ?: k?.let { JiraIssue(it.issueId, it.issueKey, it.sprintName, "", null, null, 0) }
        if (cont != null) WeekRow(cont, "контейнер · ${j.containerHours} ч в день сам", days, js, today, onOpen)
        js.rows.forEach { r -> WeekRow(r.issue, r.tags.firstOrNull(), days, js, today, onOpen) }
        if (cont == null && js.rows.isEmpty()) Text("Задач нет — добавьте задачу ниже", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(vertical = 8.dp))
        HorizontalDivider(color = divider)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text("За день", Modifier.weight(1f), fontSize = 12.sp, color = Muted)
            days.forEach { d ->
                val sum = week.filter { it.date == d }.sumOf { it.seconds }
                val color = when {
                    sum >= norm -> Done
                    d.isBefore(today) && WorkClock.isWorkday(d) -> MaterialTheme.colorScheme.error
                    else -> Muted
                }
                Text(WorkTime.hours(sum).ifEmpty { "0" }, Modifier.width(CELL), fontSize = 13.sp, color = color, textAlign = TextAlign.Center)
            }
            val total = week.sumOf { it.seconds }
            val weekNorm = norm * days.count { WorkClock.isWorkday(it) }
            Text(WorkTime.hours(total).ifEmpty { "0" }, Modifier.width(TOTAL), fontSize = 13.sp,
                color = if (total >= weekNorm) Done else MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        }
        Text("Норма — ${j.dayHours} ч в день, за неделю ${WorkTime.hours(norm * days.count { WorkClock.isWorkday(it) })} ч. " +
            "Щелчок по дню — подробный вид: комментарий, остаток, удаление.", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
        js.containerNote?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }
        Spacer(Modifier.height(6.dp))
        ContainerFill(state)
    }
}

@Composable
private fun WeekRow(issue: JiraIssue, label: String?, days: List<LocalDate>, js: JiraState, today: LocalDate, onOpen: (String) -> Unit) {
    val mine = js.weekLogs.filter { it.issueKey == issue.key }
    val over = issue.original != null && issue.original > 0 && issue.spent > issue.original
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        Column(Modifier.weight(1f).padding(end = 4.dp)) {
            Row {
                Text(issue.key, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { js.board?.let { onOpen("${it.host}/browse/${issue.key}") } })
                label?.let { Text("  $it", fontSize = 11.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Text(issue.summary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            issue.remaining?.let {
                Text("осталось ${WorkTime.text(it)}", fontSize = 11.sp, color = if (over) MaterialTheme.colorScheme.error else Muted)
            }
        }
        days.forEach { d ->
            HoursCell(
                seconds = mine.filter { it.date == d }.sumOf { it.seconds },
                enabled = true, today = d == today, version = js.logsVersion,
            ) { js.setCell(issue, d, it) }
        }
        Text(WorkTime.hours(mine.sumOf { it.seconds }).ifEmpty { "—" }, Modifier.width(TOTAL), fontSize = 12.sp, color = Muted, textAlign = TextAlign.Center)
    }
}

/**
 * Ячейка недели: часы за день («2», «1.5», «1h 30m», «45m»), Enter или уход из ячейки — сохранить,
 * Esc — отменить, пусто или 0 — удалить списания за этот день.
 */
@Composable
private fun HoursCell(seconds: Long, enabled: Boolean, today: Boolean, version: Int, onSet: (Long) -> Unit) {
    val shown = WorkTime.hours(seconds)
    var text by remember { mutableStateOf(shown) }
    // Что уже отправили: Enter снимает фокус — без этого то же значение ушло бы второй раз.
    var sent by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    // Данные обновились (или запись не удалась) — показать то, что в Tempo; пока в ячейке печатают — не мешать.
    LaunchedEffect(shown, version) { if (!focused) { text = shown; sent = null } }
    val focus = LocalFocusManager.current
    val bad = WorkTime.parseOrZero(text) == null
    fun commit() {
        val t = text.trim()
        if (t == shown || t == sent) return
        val sec = WorkTime.parseOrZero(t) ?: return
        sent = t
        onSet(sec)
    }
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = text, onValueChange = { text = it }, enabled = enabled, singleLine = true,
        textStyle = TextStyle(fontSize = 13.sp, textAlign = TextAlign.Center, color = if (bad) colors.error else colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        modifier = Modifier.width(CELL).height(30.dp).padding(2.dp)
            .background(if (today) colors.primary.copy(alpha = 0.08f) else Color.Transparent, RoundedCornerShape(4.dp))
            .border(if (focused) 1.dp else 0.5.dp, if (focused) colors.primary else colors.outlineVariant, RoundedCornerShape(4.dp))
            .onFocusChanged { f -> if (focused && !f.isFocused) commit(); focused = f.isFocused }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.Enter, Key.NumPadEnter -> { commit(); focus.clearFocus(); true }
                    Key.Escape -> { text = shown; focus.clearFocus(); true }
                    else -> false
                }
            },
        decorationBox = { inner -> Box(contentAlignment = Alignment.Center) { inner() } },
    )
}
