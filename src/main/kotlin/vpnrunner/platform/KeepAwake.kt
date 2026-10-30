package vpnrunner.platform

/**
 * Не даёт компьютеру и экрану засыпать, пока включён VPN: во сне Cisco отключается, и на работе это видно.
 * macOS — штатный `caffeinate`, Windows — SetThreadExecutionState из PowerShell.
 * Вспомогательный процесс следит за нашим PID и сам завершается вместе с программой, даже если её убили.
 * Закрытая крышка ноутбука без внешнего монитора всё равно усыпляет Mac — это решает сама macOS.
 */
object KeepAwake {
    private var proc: Process? = null

    val active: Boolean @Synchronized get() = proc?.isAlive == true

    @Synchronized
    fun set(on: Boolean, log: (String) -> Unit = {}) {
        if (on == active) return
        if (!on) {
            proc?.destroy()
            proc = null
            log("Компьютеру снова можно засыпать")
            return
        }
        val pid = ProcessHandle.current().pid()
        val cmd = when (Platform.os) {
            // -d экран, -i простой, -m диск, -s сон от сети; -w — завершиться вместе с программой
            Os.MAC -> listOf("/usr/bin/caffeinate", "-dims", "-w", pid.toString())
            // ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED действует, пока жив этот поток
            // Команда — через -EncodedCommand: кавычки внутри обычной строки Windows передаёт ненадёжно.
            Os.WINDOWS -> {
                val script = "Add-Type -Name P -Namespace W -MemberDefinition '[DllImport(\"kernel32.dll\")] public static extern uint SetThreadExecutionState(uint f);'; " +
                    "[W.P]::SetThreadExecutionState([uint32]2147483651) | Out-Null; Wait-Process -Id $pid"
                val encoded = java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
                listOf("powershell", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
            }
            Os.OTHER -> return
        }
        proc = runCatching { ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start() }
            .onFailure { log("Не удалось запретить сон: ${it.message}") }
            .getOrNull()
        if (proc != null) log("Пока VPN включён, компьютер и экран не будут засыпать")
    }
}
