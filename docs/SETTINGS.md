# Настройки и файлы — справочник

Все поля `settings.json`, все служебные файлы и все порты. Как это используется — [ARCHITECTURE.md](ARCHITECTURE.md).

## settings.json

macOS: `~/Library/Application Support/VpnRunner/settings.json`, Windows: `%APPDATA%\VpnRunner\settings.json`.
Читается в `AppSettings.load`, пишется в `AppSettings.save` (через `.tmp` + rename, права 600) при каждом изменении в интерфейсе (`AppState.update`). Отсутствующее поле — значение по умолчанию.

| Поле | Тип / умолчание | Вкладка | Что делает |
|---|---|---|---|
| `subscriptions[]` | `{name, url}` | Подписки | Подписки. `url` = `https://…` или `manual:<имя>` (вставлено вручную, серверы только в кэше) |
| `selectedServer` | `"auto"` | Главная | id сервера (`<подписка>/<имя>`) или `auto` — самый быстрый (urltest) |
| `russiaDirect` | `true` | Главная | Российские сайты (.ru/.su/.рф…, geosite-ru, geoip-ru) — напрямую. Только дома |
| `rules[]` | `{domain, mode: DIRECT\|PROXY}` | Правила | Свои сайты; действуют и на поддомены |
| `cisco.enabled` | `true` | Cisco | Подключать Cisco при Run |
| `cisco.server` | `""` | Cisco | Адрес как в AnyConnect |
| `cisco.user`, `cisco.authGroup` | `""` | Cisco | Логин и группа (`--authgroup`) |
| `cisco.workSubnets[]` | `[]` | Cisco | Что пускать через Cisco. Пусто — что прислал сервер |
| `cisco.workDomains[]` | `[]` | Cisco | Рабочие домены: их (и только их) DNS-запросы — в DNS Cisco. Дополняют присланные сервером |
| `cisco.serverCert` | `""` | Cisco | `pin-sha256:…` для самоподписанного сертификата (`--servercert`) |
| `cisco.otpCombined` | `false` | Cisco | Код TOTP дописывается к паролю в том же поле |
| `openconnectPath`, `vpncScriptPath`, `singboxPath` | `""` | Cisco → Дополнительно | Пути; пусто — автопоиск (`Platform.find*`: Homebrew, PATH, `appDir/bin`) |
| `browserUrl` | `""` | Браузер | Последний открытый адрес |
| `workHoursEnabled` | `true` | Cisco | Следить за рабочим временем |
| `workStart`, `workEnd` | `"09:00"`, `"18:00"` | Cisco | Рабочие часы, **по Москве**, будни |
| `keepAwake` | `true` | Cisco | Пока VPN включён (Run), компьютер и экран не засыпают |
| `abroad.enabled` | `false` | Из-за рубежа | Режим «я за рубежом» |
| `abroad.ruServer` | `"auto"` | Из-за рубежа | id российского сервера или `auto` (по названию: 🇷🇺, RU, MSK, Россия, Москва, СПб…) |
| `abroad.allViaRu` | `true` | Из-за рубежа | Весь трафик через российский VPN; `false` — только Cisco и российские сайты |
| `gitlab.enabled` | `false` | GitLab | Уведомлять о новых merge request |
| `gitlab.urls[]` | `[]` | GitLab | Ссылки на списки MR из браузера вместе с фильтрами (`…/-/merge_requests?draft=no&…`) |
| `jira.enabled` | `false` | Jira | Списание времени в Jira включено |
| `jira.boardUrl` | `""` | Jira | Ссылка на доску из браузера (`…/secure/RapidBoard.jspa?rapidView=…`) |
| `jira.dayHours` | `8` | Jira | Норма списания за день, часов (вместе с контейнером) |
| `jira.containerHours` | `2` | Jira | Сколько часов в рабочий день само списывается на контейнер спринта |
| `jira.containerOnlyCisco` | `true` | Jira | Автосписание на контейнер только в дни, когда был включён Cisco; `false` — во все будни с дня отметки |
| `jira.container` | `null` | Jira | Контейнер спринта: `sprintId`, `sprintName`, `issueKey`, `issueId`, `since` (yyyy-MM-dd — с какого дня списывать) |
| `jira.attributes` | `{}` | Jira | Последние значения обязательных атрибутов Tempo (ключ атрибута → значение) — для формы и автосписания |
| `jira.extraIssues[]` | `[]` | Jira | Задачи, добавленные вручную полем «Другая задача» (ключи, не больше 20) — показываются в «Вне спринта», пока их не уберут |
| `abroad.killSwitch` | `true` | Из-за рубежа | Блокировка интернета без российского VPN (macOS) |

**Не в settings.json** — секреты (`SecretStore`): `cisco-password`, `cisco-totp-secret`, `gitlab-token`, `jira-token`. macOS — Связка ключей (сервис `VpnRunner`, утилита `security`), Windows — `%APPDATA%\VpnRunner\secrets\` (DPAPI, PowerShell).

## Папка настроек (appDir)

| Файл / папка | Что |
|---|---|
| `settings.json` | настройки (выше) |
| `work-days.json` | учёт рабочих дней, 60 дней (`WorkLog`) |
| `gitlab-seen.json` | уже показанные merge request (`проект!iid`) и проверенные ссылки — чтобы не уведомлять повторно |
| `jira-ca.pem` | сертификат, которому пользователь доверился для Jira (корневой сертификат компании); удалить — программа спросит снова |
| `device-id` | случайный HWID для заголовка `x-hwid` подписок (создаётся один раз) |
| `cache/<hash>.json` | последняя удачная копия серверов каждой подписки |
| `cache/ru-hosts.json` | из-за рубежа: имя российского сервера → IPv4 (на случай, если DoH недоступен) |
| `rules/geosite-ru.srs`, `rules/geoip-ru.srs` | списки российских сайтов и IP, обновляются раз в 3 дня |
| `browser/` | кэш и куки встроенного браузера |
| `secrets/` | только Windows: зашифрованные секреты |

## Папка запуска (runDir)

macOS: `appDir/run/`, Windows: `C:\ProgramData\VpnRunner\run\`. Очищается в `prepare()` перед каждым Run.

| Файл | Кто создаёт | Что |
|---|---|---|
| `supervisor.sh` | программа | скрипт-супервизор (macOS, выполняется от root) |
| `cisco-wrapper.sh` / `.js` | программа | обёртка vpnc-script |
| `token.secret` | программа | TOTP-секрет для openconnect; стирается сразу после подключения |
| `openconnect.stdin` | программа | FIFO для пароля (macOS) |
| `config.json` | программа | конфиг основного sing-box |
| `chain.json` | программа | конфиг цепочки (из-за рубежа) |
| `cache.db` | sing-box | кэш основного sing-box |
| `openconnect.log`, `sing-box.log`, `chain.log` | супервизор / процессы | журналы (видны во вкладке «Журнал») |
| `cisco.state` | обёртка | `TUNDEV`, `DNS`, `DOMAINS`, `ROUTES`, `SERVER_FULL_TUNNEL` |
| `cisco-dns.known` | обёртка | DNS Cisco — чтобы `repair_dns` убрал его после сбоя |
| `routes-taken`, `route-fix.log` | обёртка / супервизор | маршруты, забранные у другого VPN; журнал этого и удалённых мёртвых маршрутов к серверам Cisco |
| `dns-repair.log` | супервизор | что починил `repair_dns` |
| `chain.ok`, `singbox.ready`, `reload`, `stop`, `stopped` | см. ARCHITECTURE.md §5.4 | сигналы между программой и супервизором |
| `*.pid` | супервизор | pid openconnect, sing-box, цепочки |
| `killswitch.tables`, `killswitch.rules` | программа | части правил pf |
| `killswitch.conf`, `.on`, `.token`, `.log` | супервизор | итоговые правила, «включён», токен `pfctl -E`, журнал |

Вне папок: `/etc/resolver/<рабочий домен>` (macOS, с пометкой `# VpnRunner`), служба DNS `State:/Network/Service/VpnRunner` в SystemConfiguration, якорь pf `com.apple/250.VpnRunner`. Всё это убирается при Stop и чинится `repair_dns` / `ks_release` при следующем Run.

## Сеть и порты

| Адрес | Что |
|---|---|
| `127.0.0.1:2080` | mixed-прокси основного sing-box (HTTP+SOCKS) — для программ, которым нужен прокси |
| `127.0.0.1:9090` | Clash API основного sing-box (переключение сервера, «через какой сервер сейчас») |
| `127.0.0.1:2081` | из-за рубежа: прокси цепочки — через него и только через него ходит openconnect |
| `127.0.0.1:9091` | из-за рубежа: Clash API цепочки (проверка, что запустилась) |
| `172.19.0.1/30` | TUN основного sing-box; `172.19.0.2` — его DNS, основной DNS системы |
| `1.1.1.1` (DoH) | DNS для всего, кроме рабочих доменов (через VPN); из-за рубежа также для «напрямую» |
| `77.88.8.8` (UDP) | дома: DNS российских и «напрямую» сайтов |
| ipinfo.io, ifconfig.co, ip-api.com | из-за рубежа: проверка страны выхода через цепочку |
| 1.1.1.1 / 8.8.8.8 (DoH JSON) | из-за рубежа: адреса российских серверов до запуска |
