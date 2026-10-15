package vpnrunner.platform

import vpnrunner.core.Fs
import java.io.File
import java.util.concurrent.TimeUnit

enum class Os { MAC, WINDOWS, OTHER }

object Platform {
    val os: Os = System.getProperty("os.name").lowercase().let {
        when {
            it.contains("mac") || it.contains("darwin") -> Os.MAC
            it.contains("win") -> Os.WINDOWS
            else -> Os.OTHER
        }
    }

    private val home = File(System.getProperty("user.home"))

    /** Настройки пользователя. */
    val appDir: File = when (os) {
        Os.MAC -> File(home, "Library/Application Support/VpnRunner")
        Os.WINDOWS -> File(System.getenv("APPDATA") ?: home.path, "VpnRunner")
        Os.OTHER -> File(home, ".config/vpnrunner")
    }.also { it.mkdirs(); Fs.restrictToOwner(it) }

    /** Рабочая папка запуска: конфиги, логи, скрипты. Без пробелов в пути на Windows — так проще с cscript. */
    val runDir: File = when (os) {
        Os.WINDOWS -> File(System.getenv("ProgramData") ?: "C:\\ProgramData", "VpnRunner\\run")
        else -> File(appDir, "run")
    }

    val settingsFile = File(appDir, "settings.json")

    val secrets: SecretStore = when (os) {
        Os.MAC -> MacKeychain
        Os.WINDOWS -> WindowsDpapi(File(appDir, "secrets"))
        Os.OTHER -> PlainFileSecrets(File(appDir, "secrets"))
    }

    /**
     * Заголовки устройства для скачивания подписок. HWID — случайный, создаётся один раз и хранится
     * в папке настроек, так что сервис видит этот компьютер всегда как одно и то же устройство.
     */
    fun deviceHeaders(): Map<String, String> {
        val f = File(appDir, "device-id")
        val hwid = runCatching { f.readText().trim() }.getOrNull()?.takeIf { it.length >= 16 }
            ?: java.util.UUID.randomUUID().toString().replace("-", "").also { runCatching { f.writeText(it) } }
        return linkedMapOf(
            "x-hwid" to hwid,
            "x-device-os" to when (os) { Os.MAC -> "macOS"; Os.WINDOWS -> "Windows"; Os.OTHER -> "Linux" },
            "x-ver-os" to System.getProperty("os.version").orEmpty().filter { it.code in 32..126 },
            "x-device-model" to "VpnRunner",
        )
    }

    fun backend(): VpnBackend = when (os) {
        Os.MAC -> MacBackend(runDir)
        Os.WINDOWS -> WindowsBackend(runDir)
        Os.OTHER -> throw UnsupportedOperationException("Поддерживаются macOS и Windows")
    }

    // ---- поиск программ ----

    fun findOpenconnect(): String? = firstExisting(
        when (os) {
            Os.MAC -> listOf("/opt/homebrew/bin/openconnect", "/usr/local/bin/openconnect")
            Os.WINDOWS -> listOf(
                "C:\\Program Files\\OpenConnect\\openconnect.exe",
                "C:\\Program Files (x86)\\OpenConnect\\openconnect.exe",
                "C:\\Program Files\\OpenConnect-GUI\\openconnect.exe",
                File(appDir, "bin\\openconnect.exe").path,
            )
            Os.OTHER -> listOf("/usr/sbin/openconnect", "/usr/bin/openconnect")
        }
    )

    fun findVpncScript(openconnect: String?): String? = firstExisting(
        when (os) {
            Os.MAC -> listOf(
                "/opt/homebrew/etc/vpnc/vpnc-script",
                "/usr/local/etc/vpnc/vpnc-script",
                "/opt/homebrew/opt/vpnc-scripts/etc/vpnc/vpnc-script",
            )
            Os.WINDOWS -> listOfNotNull(openconnect?.let { File(File(it).parentFile, "vpnc-script-win.js").path })
            Os.OTHER -> listOf("/usr/share/vpnc-scripts/vpnc-script", "/etc/vpnc/vpnc-script")
        }
    )

    fun findSingBox(): String? = firstExisting(
        when (os) {
            Os.MAC -> listOf("/opt/homebrew/bin/sing-box", "/usr/local/bin/sing-box", File(appDir, "bin/sing-box").path)
            Os.WINDOWS -> listOf(
                File(appDir, "bin\\sing-box.exe").path,
                appInstallDir()?.let { File(it, "sing-box.exe").path } ?: "",
                "C:\\Program Files\\sing-box\\sing-box.exe",
            )
            Os.OTHER -> listOf("/usr/bin/sing-box", "/usr/local/bin/sing-box")
        }
    )

    private fun appInstallDir(): File? = runCatching {
        ProcessHandle.current().info().command().orElse(null)?.let { File(it).parentFile }
    }.getOrNull()

    private fun firstExisting(paths: List<String>): String? {
        paths.firstOrNull { it.isNotBlank() && File(it).isFile }?.let { return it }
        // Не нашли в стандартных местах — ищем по PATH (имя файла из первого варианта).
        val exe = paths.firstOrNull { it.isNotBlank() }?.let { File(it).name } ?: return null
        val path = (System.getenv("PATH") ?: "").split(File.pathSeparator) +
            if (os == Os.MAC) listOf("/opt/homebrew/bin", "/usr/local/bin", "/opt/local/bin") else emptyList()
        return path.map { File(it, exe) }.firstOrNull { it.isFile }?.path
    }

    // ---- Windows: права администратора ----

    fun isWindowsAdmin(): Boolean {
        if (os != Os.WINDOWS) return true
        return runCatching {
            val p = ProcessBuilder("net", "session").redirectErrorStream(true).start()
            p.inputStream.readAllBytes()
            p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
        }.getOrDefault(false)
    }

    /** Перезапускает программу с запросом UAC. Возвращает true, если запрос отправлен. */
    fun relaunchAsAdmin(): Boolean {
        val cmd = ProcessHandle.current().info().command().orElse(null) ?: return false
        val args = ProcessHandle.current().info().arguments().orElse(emptyArray())
        val argList = if (args.isEmpty()) "" else " -ArgumentList " + args.joinToString(",") { psQuote(it) }
        val ps = "Start-Process -FilePath ${psQuote(cmd)}$argList -Verb RunAs"
        return runCatching {
            ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps).start()
                .waitFor(60, TimeUnit.SECONDS)
        }.getOrDefault(false)
    }

    fun psQuote(s: String) = "'" + s.replace("'", "''") + "'"
    /** Подключённые VPN других программ (macOS: системные VPN-подключения — Happ, Karing и т.п.). */
    fun connectedVpns(): List<String> = if (os != Os.MAC) emptyList() else runCatching {
        exec("/usr/sbin/scutil", "--nc", "list").second.lines()
            .filter { it.contains("(Connected)") }
            .mapNotNull { Regex("\"([^\"]+)\"").find(it)?.groupValues?.get(1)?.trim() }
    }.getOrDefault(emptyList())

    /** Через какой интерфейс система отправит пакеты на этот адрес (только macOS, иначе null). */
    fun routeInterface(ip: String): String? = if (os != Os.MAC) null else runCatching {
        Regex("interface:\\s*(\\S+)").find(exec("/sbin/route", "-n", "get", ip).second)?.groupValues?.get(1)
    }.getOrNull()

    fun shQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}

/** Запуск внешней команды с захватом вывода. */
internal fun exec(vararg cmd: String, stdin: String? = null, timeoutSec: Long = 30): Pair<Int, String> {
    val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
    if (stdin != null) p.outputStream.use { it.write(stdin.toByteArray(Charsets.UTF_8)) } else p.outputStream.close()
    val out = p.inputStream.readAllBytes().toString(Charsets.UTF_8)
    if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); return -1 to out }
    return p.exitValue() to out
}
