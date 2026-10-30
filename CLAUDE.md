# VpnRunner — заметки для агентов

Десктоп на Kotlin + Compose Desktop (macOS, Windows): одной кнопкой Run — рабочий Cisco (openconnect) + VPN по подписке (sing-box) с раздельным туннелированием; режим «из-за рубежа» — сначала российский VPN, Cisco через него, kill switch.

**Сначала прочитай [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** (оглавление всей документации — [docs/README.md](docs/README.md); поля настроек и файлы — [docs/SETTINGS.md](docs/SETTINGS.md); ошибки — [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)) — там каждая вкладка, потоки Run/Stop, файлы-сигналы супервизора, kill switch и грабли. Пользовательская сторона — [README.md](README.md).

## Где что
- `core/` — без ОС и UI: настройки (`Models.kt`), генератор конфигов sing-box (`SingBoxConfig.kt`), режим из-за рубежа (`Abroad.kt`), подписки, TOTP, рабочее время.
- `platform/` — `VpnController` (оркестрация), `Backends.kt` (macOS: супервизор от root + pf; Windows: дочерние процессы), `CiscoWrapper` (обёртка vpnc-script).
- `ui/` — `AppState` (состояние и действия), `Screens.kt` (вкладки), `BrowserTab.kt`.

## Правила
- Весь текст интерфейса, журналов, ошибок и комментариев — **по-русски**, простым языком для пользователя; комментарии объясняют «почему».
- Новое поле настроек: data class + `AppSettings.load` (с умолчанием) + `save`.
- Скрипты (супервизор, обёртка, pf) генерируются из Kotlin-строк: `\$` и `\"`, пути через `Platform.shQuote`.
- В KDoc не писать `/*` (например `com.apple/*`) — вложенный комментарий ломает компиляцию.
- Не отдавать DNS системы ни vpnc-script, ни Cisco: DNS Cisco — только для рабочих доменов. Не включать `strict_route`. Из-за рубежа openconnect — только `--proxy` + `--no-dtls`.
- `/etc/pf.conf` не менять — kill switch живёт в якоре `com.apple/250.VpnRunner`.
- У пользователя может работать VpnRunner: не трогать `~/Library/Application Support/VpnRunner/run/`, тестовые sing-box — в scratch-папке.

## Проверка (тестов нет)
```sh
./gradlew compileKotlin
sing-box check -c <сгенерированный config.json>
sh -n <сгенерированный supervisor.sh>
pfctl -nf <killswitch.conf>
```
Как сгенерировать конфиги/скрипты без запуска VPN — ARCHITECTURE.md §8. После изменения поведения обнови README (для пользователя), ARCHITECTURE.md, SETTINGS.md и TROUBLESHOOTING.md.
