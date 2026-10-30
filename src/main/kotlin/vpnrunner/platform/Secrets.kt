package vpnrunner.platform

import vpnrunner.core.Fs
import java.io.File

/** Хранилище паролей: Связка ключей на macOS, DPAPI на Windows. */
interface SecretStore {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun delete(key: String)

    companion object {
        const val CISCO_PASSWORD = "cisco-password"
        const val CISCO_TOTP = "cisco-totp-secret"
        const val GITLAB_TOKEN = "gitlab-token"
        const val JIRA_TOKEN = "jira-token"
    }
}

object MacKeychain : SecretStore {
    private const val SERVICE = "VpnRunner"

    override fun get(key: String): String? {
        val (code, out) = exec("/usr/bin/security", "find-generic-password", "-a", key, "-s", SERVICE, "-w")
        return if (code == 0) out.trimEnd('\n') else null
    }

    override fun set(key: String, value: String) {
        // -U обновляет существующую запись. Значение передаётся аргументом — так устроена утилита security.
        val (code, out) = exec("/usr/bin/security", "add-generic-password", "-U", "-a", key, "-s", SERVICE, "-w", value)
        check(code == 0) { "Не удалось сохранить в Связку ключей: $out" }
    }

    override fun delete(key: String) {
        exec("/usr/bin/security", "delete-generic-password", "-a", key, "-s", SERVICE)
    }
}

/**
 * Windows: строка шифруется DPAPI (ConvertFrom-SecureString) под текущего пользователя
 * и лежит в файле. Расшифровать может только этот же пользователь Windows на этом же компьютере.
 * Секрет передаётся в PowerShell через stdin, а не аргументом.
 */
class WindowsDpapi(private val dir: File) : SecretStore {
    init { dir.mkdirs(); Fs.restrictToOwner(dir) }

    private fun file(key: String) = File(dir, "$key.dpapi")

    override fun get(key: String): String? {
        val f = file(key)
        if (!f.exists()) return null
        val script = "\$h=[Console]::In.ReadToEnd().Trim(); \$s=ConvertTo-SecureString \$h; " +
            "\$b=[Runtime.InteropServices.Marshal]::SecureStringToBSTR(\$s); " +
            "[Console]::Out.Write([Runtime.InteropServices.Marshal]::PtrToStringBSTR(\$b))"
        val (code, out) = exec("powershell", "-NoProfile", "-NonInteractive", "-Command", script, stdin = f.readText())
        return if (code == 0) out else null
    }

    override fun set(key: String, value: String) {
        val script = "\$v=[Console]::In.ReadToEnd(); " +
            "[Console]::Out.Write((ConvertTo-SecureString -String \$v -AsPlainText -Force | ConvertFrom-SecureString))"
        val (code, out) = exec("powershell", "-NoProfile", "-NonInteractive", "-Command", script, stdin = value)
        check(code == 0 && out.isNotBlank()) { "Не удалось зашифровать секрет: $out" }
        file(key).writeText(out.trim())
        Fs.restrictToOwner(file(key))
    }

    override fun delete(key: String) { file(key).delete() }
}

/** Только для разработки на Linux. */
class PlainFileSecrets(private val dir: File) : SecretStore {
    init { dir.mkdirs(); Fs.restrictToOwner(dir) }
    override fun get(key: String) = File(dir, key).takeIf { it.exists() }?.readText()
    override fun set(key: String, value: String) { File(dir, key).writeText(value); Fs.restrictToOwner(File(dir, key)) }
    override fun delete(key: String) { File(dir, key).delete() }
}
