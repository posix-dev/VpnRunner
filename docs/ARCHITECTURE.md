# VpnRunner — как всё устроено

Документ для разработчиков и агентов: что делает каждая вкладка, какие классы за ней стоят, как идут Run/Stop
и где лежат грабли. Пользовательская инструкция — в [README](../README.md).

Ссылки на код даны по именам классов/функций (номера строк быстро устаревают).

---

## 1. Что это

Десктопное приложение (Kotlin + Compose Desktop, macOS и Windows), которое одной кнопкой **Run**:

- подключает рабочий **Cisco AnyConnect** через `openconnect` (логин, пароль, TOTP подставляются сами);
- поднимает **VPN по подписке** через `sing-box` (TUN на всю систему) с раздельным туннелированием;
- следит за рабочим временем (работодатель видит, когда включён Cisco).

Два режима запуска:

| Режим | Порядок | Остальной трафик |
|---|---|---|
| Дома (в РФ), по умолчанию | Cisco → sing-box | российское — напрямую, остальное — зарубежный VPN |
| Из-за рубежа (`AbroadSettings.enabled`) | российский VPN («цепочка») + kill switch → проверка выхода → Cisco через цепочку → sing-box | всё (или российское) — через российский VPN |

Внешние программы: `openconnect` (+ `vpnc-script`), `sing-box` ≥ 1.12 (проверено на 1.14.3). На macOS — `brew install openconnect sing-box`.

---

## 2. Карта кода

```
src/main/kotlin/vpnrunner/
  Main.kt                 точка входа: окно, значок в строке меню (Tray), уведомления, диалог подтверждения
  core/                   без зависимостей от UI и ОС (кроме java.*)
    Models.kt             AppSettings (+ load/save settings.json), CiscoSettings, AbroadSettings, Server, CiscoState
    SingBoxConfig.kt      генератор конфига sing-box: build() — основной (TUN), buildChain() — цепочка «из-за рубежа»
    Abroad.kt             режим «из-за рубежа»: выбор российских серверов по названию, Abroad.Plan
    SubscriptionParser.kt скачивание подписок (+ x-hwid), разбор vless/vmess/trojan/ss/hysteria2 → outbound sing-box
    XrayJson.kt           подписки в формате Xray JSON
    Totp.kt               RFC 6238 (код Google Authenticator)
    GitLab.kt             merge request: GitLabList (ссылка → фильтры API), GitLabClient, SeenMergeRequests (gitlab-seen.json)
    Jira.kt               списание времени: JiraSettings, JiraBoard (ссылка на доску), JiraClient (Jira + Tempo), JiraTrust (свой сертификат), WorkTime
    WorkDay.kt            рабочее время по Москве: WorkClock, WorkHours, WorkDay, WorkLog (work-days.json)
    Json.kt               свой мини-JSON (parse/write) + хелперы obj()/arr()/str()/bool()
  platform/               всё, что зависит от ОС
    VpnController.kt      оркестрация Run/Stop/applyRules, проверки, кэш подписок, Clash API
    Backends.kt           VpnBackend: MacBackend (супервизор от root + pf) и WindowsBackend (дочерние процессы)
    CiscoWrapper.kt       обёртка над vpnc-script: split-подсети, split-DNS (/etc/resolver), отбор маршрутов у чужого VPN
    Platform.kt           пути (appDir/runDir), поиск бинарников, права админа Windows, exec()
    Secrets.kt            пароль и TOTP: Связка ключей macOS / DPAPI Windows
  ui/
    AppState.kt           всё состояние UI + действия (run/stop/update…), мониторинг, трекер рабочего времени
    Screens.kt            вкладки (кроме «Браузер»), общие компоненты
    BrowserTab.kt         встроенный Chromium (KCEF/JCEF)
    JiraState.kt          вкладка «Jira»: загрузка задач, списание, автосписание контейнера спринта, напоминание
    JiraTab.kt            вкладка «Jira» (UI)
    TrayIcon.kt           иконка-щит по Phase
```

Слои: `ui` → `platform` → `core`. `core` ничего не знает о процессах и UI — его удобно проверять отдельно (см. §8).

---

## 3. Вкладки

Порядок задан в `AppScreen` (`Screens.kt`): `SecondaryScrollableTabRow` (колесо мыши прокручивает его по горизонтали), индекс → функция.

### 3.1 Главная — `HomeTab`
- Кнопка **Run/Stop** (`AppState.run()` / `requestStop()`; Stop в рабочее время спрашивает подтверждение — `earlyStopWarning`).
- Карточки-баннеры: права админа (Windows), `KillSwitchStuckCard` (kill switch остался после сбоя), `RestartBanner` (изменены настройки «из-за рубежа» на ходу), `ApplyRulesBanner` (изменены правила).
- `WorkDayCard` — учёт рабочего дня (только будни, если Cisco включён).
- Статусы: Cisco / VPN / российские сайты; в режиме «из-за рубежа» — «Режим», «Российский VPN», «Снаружи видно» (`AppState.exitInfo` ← `VpnController.lastExit`), «Остальной трафик», «Kill switch».
- `ServerPicker` — выбор сервера VPN; переключение на лету через Clash API (`VpnController.switchServer`). В режиме «из-за рубежа» скрыт (сервер выбирается во вкладке «Из-за рубежа»).
- Переключатель «Российские сайты мимо VPN» (`AppSettings.russiaDirect`) — только дома.

### 3.2 Правила — `RulesTab`
Список `SiteRule(domain, DIRECT|PROXY)` → `SingBoxConfig.build`: правило действует и на поддомены (`domain_suffix`), PROXY сильнее правил для российских сайтов. При работающем VPN изменения помечают `rulesDirty`, кнопка «Применить» → `VpnController.applyRules` (пересборка конфига + перечитывание sing-box, Cisco не трогается).
Из-за рубежа: PROXY = через российский VPN, DIRECT = напрямую (местный интернет).

### 3.3 Подписки — `SubscriptionsTab`
Ссылка `https://…` или вставленное содержимое (base64 / список ссылок / Xray JSON). Ручные подписки — `Subscription.manual` (`url = "manual:<имя>"`), их серверы живут только в кэше.
Скачивание — `SubscriptionParser.fetch`: напрямую → системный прокси → локальные прокси 127.0.0.1:2080/10809/7890; передаёт `x-hwid` и др. (`Platform.deviceHeaders`). Заглушки «App not supported» отбрасываются (`isStub`).
Кэш: `appDir/cache/<hash(url)>.json` (`VpnController.cacheServers/cachedServers`); Run берёт кэш (`preferCache = true`), свежая копия скачивается после запуска.

### 3.4 Cisco — `CiscoTab`
`CiscoSettings`: сервер, логин, группа, рабочие подсети и домены, `serverCert` (pin-sha256), `otpCombined` (код в одном поле с паролем). Пароль и TOTP-секрет — в `SecretStore` (не в settings.json); `totpPreview` показывает текущий код для сверки.
Здесь же: «Не давать компьютеру засыпать» (`keepAwake`: `platform/KeepAwake` — на macOS `caffeinate -dims -w <наш PID>`, на Windows PowerShell с `SetThreadExecutionState`; включается из `Main.kt` по `phase` RUNNING/STARTING, процесс умирает вместе с программой), рабочие часы (`workHoursEnabled/workStart/workEnd`) и пути к openconnect / vpnc-script / sing-box (пусто — автопоиск `Platform.find*`).

### 3.5 Из-за рубежа — `AbroadTab`
`AbroadSettings`:

| Поле | Что делает |
|---|---|
| `enabled` | режим «я за рубежом» |
| `ruServer` | id сервера или `auto` — все, у кого в названии 🇷🇺/RU/MSK/Россия/Москва/СПб… (`Abroad.looksRussian`), urltest выбирает быстрый |
| `allViaRu` | `true` — весь трафик через российский VPN; `false` — через него Cisco и российские сайты, остальное напрямую |
| `killSwitch` | pf-блокировка на macOS (§5.4) |

Изменение на ходу → `restartNeeded` (действует после Stop → Run). Подробно поток — §4.2.

### 3.6 GitLab — `GitLabTab`
Уведомления о новых merge request. Ссылка на список MR из браузера → `GitLabList.parse` (хост, путь проекта, фильтры из адреса) → `apiQuery()` переводит фильтры страницы в параметры `GET /api/v4/projects/:path/merge_requests` (`draft` → `wip`, `label_name[]` → `labels`). Фильтров «одобрен / не одобрен мной» в REST API нет — `GitLabClient` проверяет их сам по `/merge_requests/:iid/approvals` (кэш по `updated_at`). Токен (`read_api`) — `SecretStore.GITLAB_TOKEN`, заголовок `PRIVATE-TOKEN`.

Опрос — `AppState.startGitLabWatcher`: раз в 2 мин, только пока `workVpnUp` (GitLab во внутренней сети; если Cisco выключен в настройках — всегда), после ошибки — через 20 с; «Проверить сейчас» (`checkGitLabNow`) проверяет сразу и без Cisco. Новые = ключи `проект!iid`, которых не было в `gitlab-seen.json`; MR из ссылок, которые ещё ни разу не проверялись, — не новые (не засыпать уведомлениями). В файле — только открытые сейчас: MR, снова попавший под фильтр, — снова новость. Больше 3 новых сразу — одно общее уведомление. Щелчок по MR (`AppScreen`, колбэк `onOpen`) кладёт его адрес в `browserUrl` и переключает на вкладку «Браузер» — она при каждом показе открывается с `browserUrl`; если `BrowserEngine` в `NeedsJbr`/`Failed` — `Desktop.browse`.

### 3.7 Jira — `JiraTab.kt`, `JiraState.kt`
Списание времени. Ссылка на доску → `JiraBoard.parse` (`rapidView` или `/boards/N`, `projectKey`, база Jira до `/secure/`). Токен — Personal Access Token (`SecretStore.JIRA_TOKEN`), заголовок `Authorization: Bearer` (Jira Server/DC ≥ 8.14). Редиректы выключены: без токена Jira уводит на SSO — это ошибка токена.

`JiraState.fetch` (раз в 10 мин, пока `workVpnUp`; «Обновить» — сразу): `GET /rest/api/2/myself` (ключ пользователя для Tempo), `/rest/agile/1.0/board/:id/sprint?state=active,future`, задачи спринта `…/sprint/:id/issue?jql=assignee = currentUser() AND issuetype not in subTaskIssueTypes()`, «в работе» — `/rest/api/2/search` с `statusCategory = 4`, ключи из моих MR (`GitLabClient.myMergeRequests`, `scope=created_by_me`; `IssueKeys`, только проекты доски, не больше 15 задач по `GET /issue/:key`). Плюс задачи из `jira.extraIssues` (поле «Другая задача»: `IssueKeys` берёт ключ из ключа или ссылки, `addIssue` проверяет его `GET /issue/:key`; `JiraRow.manual` — для кнопки «Убрать»). Строки — без дублей по ключу, источники — метки. Контейнер исключается из списка и показывается карточкой. Списания — Tempo Timesheets 4: `POST /rest/tempo-timesheets/4/worklogs/search` (с начала автосписания по сегодня), `POST …/worklogs` (`originTaskId` — id задачи, `started` — дата, `remainingEstimate` — новый остаток), `DELETE …/worklogs/:id`; обязательные атрибуты — `GET /rest/tempo-core/1/work-attribute`, последние значения — в `jira.attributes`. Пустой комментарий заменяется названием задачи (Tempo часто требует описание).

Вид «Неделя» (`WeekGrid`, по умолчанию; `JiraState.weekView`): неделя выбранного дня (`weekStart` — понедельник), Tempo-запрос покрывает её целиком; задачи со списаниями на этой неделе, которых нет в списке, добавляются строками (метка «есть списания», до 20). Ячейка (`HoursCell`, `BasicTextField`) сохраняет по Enter или при потере фокуса через `JiraState.setCell`: пустая ячейка → `POST`, одно списание → `PUT /worklogs/:id` (время; комментарий и день прежние), 0 → `DELETE` всех списаний ячейки, несколько списаний — только увеличение (добавляется разница). Остаток уменьшается на добавленное время, при уменьшении не передаётся. После записи `logs` правятся сразу из ответа Tempo, затем полное обновление; `logsVersion` сбрасывает текст ячеек, которые не в фокусе. Атрибуты для таблицы — из `jira.attributes` (выбор над таблицей).

Сертификат: `SSLHandshakeException` → `JiraCertException` → `JiraTrust.fetchChain` (без проверки, только чтобы показать) → карточка с корневым сертификатом цепочки и SHA-256 → «Доверять» сохраняет его в `jira-ca.pem`. `JiraTrust.context` — стандартные центры Java **или** этот сертификат; проверка цепочки и имени сервера обычная. Уже доверенный сертификат снова не предлагается (значит, не совпало имя сервера).

Контейнер: `containerGaps(from, to)` — будни (`WorkClock.isWorkday`, без праздников) в пределах спринта контейнера, где на него нет ни одного списания в Tempo; Tempo-запрос `fetch` покрывает весь спринт контейнера. `logContainer` списывает по дням, сразу добавляя созданное в `logs`. Кнопки «На неделю» / «На спринт» (`ContainerFill` → `fillContainer`, с подтверждением) — любые дни, в том числе будущие. Автосписание (`autoContainer`, после каждого обновления): спринт контейнера активен; `containerGaps(since, сегодня)`; при `containerOnlyCisco` (по умолчанию) — только дни, где в `work-days.json` есть `firstUp` (Cisco включали) или сегодня `workVpnUp`. Без значений обязательных атрибутов не списывает (пишет почему). После ошибки не повторяет до следующего обновления. Напоминание (`remind`): будни, Cisco сегодня был, с `workEnd − 30 мин` до `workEnd + 3 ч`, списано меньше `dayHours` — раз в день.

### 3.8 Журнал — `LogTab`
«Программа» — `AppState.logLines` (последние 500 строк); «Cisco» — `run/openconnect.log`; «sing-box» — `run/sing-box.log`; «Российский VPN» (в режиме «из-за рубежа») — `run/chain.log`. Файлы читаются раз в 2 с (`AppState.readLog(LogKind)`).

### 3.9 Браузер — `BrowserTab.kt`
**Сейчас скрыта:** `BrowserEngine.TAB_ENABLED = false` — вкладки нет в `AppScreen`, `onOpen` (GitLab, Jira) открывает ссылки через `Desktop.browse`, `Main.relaunchOnJcefJava` ничего не делает. Вернуть — `true`.

Встроенный Chromium через compose-webview + KCEF, но **движок — JCEF из JetBrains Runtime** (`BrowserEngine.jbrCef`): программу нужно запускать на JBR 17 с JCEF (`build.gradle.kts` ищет `~/Library/Java/JavaVirtualMachines/jbrsdk_jcef-17*` или `-Pjbr.jcef.home`). На обычной Java вкладка показывает инструкцию — KCEF на macOS роняет весь процесс, если качает свой Chromium. Кэш/куки — `appDir/browser/`, последний адрес — `AppSettings.browserUrl`. Из IDE лучше запускать через `.run/VpnRunner.run.xml` (Gradle `run`). Стрелка у `main` идёт на Java IDE без JCEF и без `--add-opens` из `build.gradle.kts`, поэтому `Main.relaunchOnJcefJava` (только macOS, не в собранном приложении) перезапускает программу на `jbrsdk_jcef-17*` с тем же classpath и теми же `--add-opens`, ждёт её и завершает при «Stop» (повторный перезапуск не делается — переменная окружения `VPNRUNNER_RELAUNCHED`). Флаги `--add-opens` продублированы в `build.gradle.kts` и `Main.kt` — менять в обоих местах. Ошибки загрузки главного фрейма (`WebViewState.errorsForCurrentRequest`, кроме `-3` ERR_ABORTED) показываются плашкой над страницей: Chromium при ошибке, например при недоверенном сертификате (коды `-200…-299`), оставляет пустую страницу.

### Вне вкладок
- **Значок в строке меню** (`Main.kt`, `TrayIcon.forPhase`): серый/жёлтый/зелёный; меню Включить/Выключить/Открыть окно/Выход. Закрытие окна только прячет его.
- **Уведомления** — `AppState.notifications` (`Notice`): `warning = true` — рабочее время, `false` — новые MR (`Notification.Type.Info`).
- **Трекер рабочего времени** (`AppState.startWorkTracker`): раз в 20 с проверяет, поднят ли Cisco (`ciscoAlive && cisco.state`), ведёт `WorkDay`, шлёт уведомления (за 10 мин до начала, каждые 15 мин без Cisco, обрыв, конец дня). Время — всегда по Москве (`WorkClock.ZONE`).

---

## 4. Потоки Run / Stop

Все методы `VpnController` блокирующие — вызываются из `Dispatchers.IO` (`AppState.run/stop`).

### 4.1 Дома (по умолчанию)

```
AppState.run → VpnController.loadServers(preferCache) → VpnController.run:
  backend.prepare()                       чистит run/ от прошлого запуска
  prepareCisco()                          wrapper-скрипт (CiscoWrapper), token.secret, аргументы openconnect
  backend.start(cisco, singbox)           macOS: супервизор от root (osascript), пароль+код в FIFO
  waitForCisco()                          ждёт run/cisco.state от обёртки (TUNDEV, DNS, DOMAINS, ROUTES)
  checkCiscoRoutes()                      рабочий адрес идёт через utun Cisco? (иначе — другой VPN перехватил)
  downloadRuleSets()                      geosite-ru / geoip-ru → appDir/rules (раз в 3 дня)
  SingBoxConfig.build(...) → config.json → backend.startSingBox() → waitForSingBox() (Clash API :9090)
  checkWorkDns()                          рабочее имя резолвится во внутренний адрес?
```
Stop: `backend.stop()` → супервизор: sing-box → openconnect → (цепочка) → kill switch → `repair_dns`.

### 4.2 Из-за рубежа

```
VpnController.run, ветка abroad:
  Abroad.ruServers()                      выбранный или «российские» по названию; пусто → ошибка
  resolveServers()                        DoH (1.1.1.1 / 8.8.8.8, JSON) → кэш appDir/cache/ru-hosts.json → системный DNS
                                          серверы без адреса отбрасываются
  Abroad.Plan(ruIps, pinned, allViaRu)
  SingBoxConfig.buildChain() → run/chain.json
  KillSwitch(ruIps, allowRoot)            только macOS
  prepareCisco(proxy = http://127.0.0.1:2081)   → --proxy=… --no-dtls
  backend.start(cisco, singbox, ChainLaunch(ks)) { beforeCisco }
     супервизор: ks_release (остаток) → ks_arm → start_chain → ждёт run/chain.ok
     beforeCisco (в программе): waitForChain() (Clash API :9091) → checkExit()
        checkExit: ipinfo.io / ifconfig.co / ip-api.com через прокси цепочки; страна ≠ RU или нет ответа → исключение,
        Cisco не запускается
     программа пишет chain.ok → супервизор запускает openconnect → программа пишет пароль в FIFO
  waitForCisco / checkCiscoRoutes          как дома
  downloadRuleSets(viaChain = true)       только если !allViaRu; через прокси цепочки (под kill switch напрямую нельзя)
  SingBoxConfig.build(..., abroad = plan) с российскими серверами → startSingBox
```

Почему **две** копии sing-box: цепочка (A) обслуживает только openconnect, основной (B) — всю систему. B перезапускается при «Применить правила» и после сна — если бы Cisco шёл через B, каждый такой перезапуск рвал бы Cisco. Цепочка от B не зависит, а B стартует после Cisco, как дома.

---

## 5. Как устроено внутри

### 5.1 Конфиг основного sing-box — `SingBoxConfig.build`
- **outbounds** (`serverOutbounds`): все серверы + `auto` (urltest) + `proxy` (selector, `default` = выбранный) + `direct`.
- **TUN** `172.19.0.1/30`, `auto_route`, `strict_route: false` (true ломает маршруты Cisco на Windows), `route_exclude_address` = рабочие подсети + DNS Cisco (+ адреса российских серверов из-за рубежа). На macOS имя utun выбирает sing-box.
- **route.rules** по порядку: sniff → hijack-dns → private → российские серверы (abroad) → рабочие подсети/домены → хосты подписок (через `proxy`) → PROXY-правила → DIRECT-правила → российские сайты (дома `direct`, из-за рубежа `proxy`). `final`: `proxy`, из-за рубежа при `!allViaRu` — `direct`.
- **DNS**: `dns-remote` (DoH 1.1.1.1 через `proxy`, final); `dns-direct` (дома UDP 77.88.8.8, из-за рубежа DoH 1.1.1.1 напрямую); `dns-work` (DNS Cisco, `bind_interface` = utun Cisco) — **только** для рабочих доменов (свои + присланные сервером); `dns-pin` (`type: hosts`) — адреса российских серверов, чтобы не спрашивать DNS, который ходит через них же. `strategy: ipv4_only`.
- Clash API `127.0.0.1:9090`, mixed-прокси `127.0.0.1:2080`, `cache.db` в run/.

### 5.2 Цепочка — `SingBoxConfig.buildChain`
Только inbound `mixed` 127.0.0.1:2081 → российские серверы (`proxy`/`auto`), `auto_detect_interface: true` (мимо TUN основного sing-box), Clash API `127.0.0.1:9091`, без TUN и без cache_file (иначе конфликт с cache.db основного). openconnect шлёт `CONNECT vpn.company.ru:443` — имя резолвит уже российский сервер.

### 5.3 Cisco — `CiscoWrapper` + `prepareCisco`
openconnect вызывает обёртку (`--script`), та — штатный vpnc-script. Обёртка:
1. подменяет `CISCO_SPLIT_INC` на свои подсети (если заданы);
2. **не даёт vpnc-script менять DNS системы** (`unset INTERNAL_IP4_DNS`) — вместо этого `/etc/resolver/<рабочий домен>` с DNS Cisco (помечены `# VpnRunner`, удаляются при отключении); основной DNS системы ставит супервизор (`publish_dns` — DNS sing-box на его же интерфейсе);
3. забирает рабочие подсети у другого VPN (`take_routes`, журнал `run/route-fix.log`) и возвращает при отключении;
4. пишет `run/cisco.state`;
5. через прокси шлюз = 127.0.0.1 — при отключении `VPNGATEWAY` снимается, чтобы vpnc-script не удалил маршрут к localhost;
6. убирает мёртвые маршруты к серверам Cisco (`drop_dead_routes`) перед подключением, перед переподключением и после отключения; супервизор делает то же при старте и остановке. vpnc-script ведёт сервер через шлюз текущей сети, а удаляет маршрут уже со шлюзом новой сети и промахивается. Удаляются только постоянные маршруты к одному адресу не через utun, чей шлюз больше не в локальной сети; журнал — `run/route-fix.log`.

TOTP: обычно `--token-mode=totp` (openconnect сам генерирует код из `run/token.secret`, файл стирается сразу после подключения); при `otpCombined` — «пароль+код» в stdin. `waitForFreshCode` не отправляет код, который скоро истечёт или уже уходил.

### 5.4 macOS: супервизор и kill switch — `MacBackend`
Один раз за Run — `osascript … with administrator privileges` запускает `run/supervisor.sh` (генерирует `supervisorScript()`), дальше программа и супервизор общаются **файлами в run/**:

| Файл | Кто пишет | Смысл |
|---|---|---|
| `openconnect.stdin` (FIFO) | программа | пароль (+код) для openconnect |
| `chain.ok` | программа | выход через Россию проверен — можно запускать openconnect |
| `singbox.ready` | программа | config.json готов — запускать sing-box |
| `reload` | программа | HUP sing-box (новые правила) |
| `stop` | программа | остановить всё |
| `stopped` | супервизор | всё остановлено |
| `*.pid` | супервизор | для `ciscoAlive/singboxAlive/chainAlive` |
| `cisco.state` | обёртка | TUNDEV, DNS, DOMAINS, ROUTES, SERVER_FULL_TUNNEL |
| `killswitch.tables/.rules` | программа | части правил pf (`writeKillSwitch`) |
| `killswitch.conf/.on/.token/.log` | супервизор | итоговые правила, флаг «включён», токен `pfctl -E`, журнал |

Цикл супервизора (каждые 0.5 с): reload, `publish_dns`, `chain_watch` (перезапуск упавшей цепочки), `ks_check` (обновить проходы pf при смене utun), раз в ~10 с — перезапуск упавшего sing-box и `take_routes`.

**Kill switch** — якорь pf `com.apple/250.VpnRunner` (подключается штатным `anchor "com.apple/*"` из `/etc/pf.conf`; сам файл не меняем, если якоря нет в загруженных правилах — `pfctl -f /etc/pf.conf`). Правила (только `out`):
```
pass  на lo0, utun sing-box, utun Cisco
pass  tcp/udp к <vr_ru>                 (адреса российских серверов)
block tcp/udp порт 53                   (обычный DNS мимо туннелей)
pass  к <vr_lan>, DHCP                  (локальная сеть, link-local, multicast)
pass  tcp/udp user root                 (только если есть «напрямую»: allViaRu=false или DIRECT-правила — это sing-box)
block всё остальное                      (в т.ч. IPv6: TUN только IPv4)
```
Снимается в `cleanup` последним (после openconnect и цепочки). Остался после сбоя — `killSwitchStuck()` → карточка на главной → `releaseKillSwitch()` (отдельный запрос пароля); также его снимает следующий Run (`ks_release` в начале супервизора).

`repair_dns` (в начале и в конце): убирает DNS-записи закрытых utun, наши `/etc/resolver`, нашу DNS-службу, DNS Cisco, застрявший в настройках сети.

### 5.5 Windows — `WindowsBackend`
Программа сама запущена от администратора, openconnect/sing-box/цепочка — дочерние процессы, Stop завершает их (`destroy`). Обёртка — JScript (`CiscoWrapper.jscript`), DNS Cisco в системе подменяется на DNS sing-box. Kill switch нет (Cisco всё равно только через прокси).

---

## 6. Данные на диске

| Что | macOS | Windows |
|---|---|---|
| Настройки `settings.json`, `work-days.json`, `gitlab-seen.json`, `jira-ca.pem`, `device-id`, `cache/`, `rules/`, `browser/` | `~/Library/Application Support/VpnRunner/` | `%APPDATA%\VpnRunner\` |
| run/ (скрипты, конфиги, журналы, служебные файлы §5.4) | `…/VpnRunner/run/` | `C:\ProgramData\VpnRunner\run\` |
| Пароль, TOTP, токены GitLab и Jira | Связка ключей, сервис `VpnRunner` | `%APPDATA%\VpnRunner\secrets` (DPAPI) |

Часть файлов в run/ создаёт root — программа их не перезаписывает, а удаляет (`prepare()`) и создаёт заново.

Новое поле настроек = поле в data class + чтение в `AppSettings.load` (со значением по умолчанию) + запись в `save`.

---

## 7. Грабли

- **KDoc и `/*`**: Kotlin поддерживает вложенные комментарии — текст вида `com.apple/*` внутри `/** … */` открывает новый комментарий и ломает файл.
- **Shell внутри Kotlin-строк**: `$` экранируется как `\$`, кавычки `\"`; пути — только через `Platform.shQuote`. После правок генераторов — `sh -n` на результате (§8).
- **Пользовательский VPN-клиент рядом** (Happ и т.п.): перехватывает маршруты частных сетей и DNS — для этого `take_routes`, `checkCiscoRoutes`, `checkWorkDns`.
- **Смена сети при подключённом Cisco** (дом → офис, раздача с телефона): vpnc-script оставляет маршрут к серверу через шлюз прежней сети, и следующее подключение к этому адресу падает с «Can't assign requested address» — часто уже после перенаправления сервера (302 на `vpn5.…`). Убирает `drop_dead_routes`.
- **«Server certificate verify failed» в журнале — не ошибка**, если указан `--servercert`: openconnect пишет это и при совпавшем отпечатке. Настоящий отказ — «Certificate from VPN server … failed verification».
- **Нельзя `strict_route: true`** (Windows ломает маршруты Cisco); **нельзя** отдавать vpnc-script DNS (утечка всех имён в DNS компании).
- **DTLS через прокси не ходит** — из-за рубежа обязательно `--no-dtls`, иначе UDP к Cisco ушёл бы напрямую.
- **Российский сервер по названию** — эвристика; реальная страна проверяется `checkExit`. Список: `Abroad.RU_NAME`.
- Рабочее время — по Москве, праздники не учитываются.
- Не трогать работающий у пользователя VpnRunner при проверках: тестовые sing-box — на других портах или в scratch-папке.

---

## 8. Как проверять

Тестов в проекте нет; проверяем так:

```sh
./gradlew compileKotlin                     # сборка
./gradlew run                               # запуск (на JBR с JCEF — для вкладки «Браузер»)
sing-box check -c <config.json>            # схема сгенерированного конфига
sh -n <supervisor.sh>                       # синтаксис сгенерированных скриптов
pfctl -nf <killswitch.conf>                 # синтаксис правил pf (без загрузки, root не нужен)
```

Сгенерировать конфиги и скрипты без запуска VPN можно маленькой Java-программой на классах из
`build/classes/kotlin/main` + `kotlin-stdlib` из `~/.gradle/caches`: `AppSettings.Companion.load(file)`,
`SingBoxConfig.INSTANCE.build(...)` / `buildChain(...)`, `Json.INSTANCE.write(...)`;
`MacBackend.supervisorScript` (internal — имя в байткоде с суффиксом, вызывать рефлексией) даёт текст супервизора.
Цепочку можно запустить без root (у неё нет TUN): `sing-box run -c chain.json`, затем
`curl -x http://127.0.0.1:2081 https://ipinfo.io/json`.
