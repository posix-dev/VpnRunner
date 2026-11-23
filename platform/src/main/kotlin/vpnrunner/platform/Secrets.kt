package vpnrunner.platform

import vpnrunner.core.Fs
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.io.File
import java.nio.charset.StandardCharsets

/** Хранилище паролей: Связка ключей на macOS, DPAPI на Windows. */
interface SecretStore {
    fun contains(key: String): Boolean
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun delete(key: String)

    companion object {
        const val CISCO_PASSWORD = "cisco-password"
        const val CISCO_TOTP = "cisco-totp-secret"
        const val GITLAB_TOKEN = "gitlab-token"
        const val GITLAB_WRITE_TOKEN = "gitlab-write-token"
        const val JIRA_TOKEN = "jira-token"
    }
}

object MacKeychain : SecretStore {
    override fun contains(key: String) = MacKeychainApi.contains(key)
    override fun get(key: String): String? = MacKeychainApi.get(key)
    override fun set(key: String, value: String) = MacKeychainApi.set(key, value)
    override fun delete(key: String) = MacKeychainApi.delete(key)
}

/** Прямой вызов API Связки ключей: секрет не попадает в аргументы внешней команды. */
private object MacKeychainApi {
    private const val SERVICE = "VpnRunner"
    private const val UTF8 = 0x08000100
    private const val ITEM_NOT_FOUND = -25300
    private val security = NativeLibrary.getInstance("/System/Library/Frameworks/Security.framework/Security")
    private val coreFoundation = NativeLibrary.getInstance("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation")

    private fun constant(name: String) = security.getGlobalVariableAddress(name).getPointer(0)
    private fun cfConstant(name: String) = coreFoundation.getGlobalVariableAddress(name).getPointer(0)
    private fun cfFunction(name: String) = coreFoundation.getFunction(name)
    private fun secFunction(name: String) = security.getFunction(name)

    private fun cfString(value: String): Pointer {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        val memory = Memory((bytes.size + 1).toLong())
        memory.write(0, bytes, 0, bytes.size)
        memory.setByte(bytes.size.toLong(), 0)
        return cfFunction("CFStringCreateWithCString").invokePointer(arrayOf(null, memory, UTF8))
            ?: error("Не удалось подготовить атрибут Связки ключей")
    }

    private fun dictionary(entries: List<Pair<Pointer, Pointer>>, owned: List<Pointer> = emptyList()): Pointer {
        val keys = Memory((entries.size * Native.POINTER_SIZE).toLong())
        val values = Memory((entries.size * Native.POINTER_SIZE).toLong())
        entries.forEachIndexed { i, (key, value) ->
            val offset = (i * Native.POINTER_SIZE).toLong()
            keys.setPointer(offset, key)
            values.setPointer(offset, value)
        }
        val result = cfFunction("CFDictionaryCreate").invokePointer(arrayOf(
            null, keys, values, entries.size.toLong(),
            coreFoundation.getGlobalVariableAddress("kCFTypeDictionaryKeyCallBacks"),
            coreFoundation.getGlobalVariableAddress("kCFTypeDictionaryValueCallBacks"),
        )) ?: error("Не удалось подготовить запрос к Связке ключей")
        owned.forEach(::release)
        return result
    }

    private fun release(value: Pointer) { cfFunction("CFRelease").invokeVoid(arrayOf(value)) }

    private fun query(key: String, returnData: Boolean = false, returnAttributes: Boolean = false): Pointer {
        val service = cfString(SERVICE)
        val account = cfString(key)
        val entries = mutableListOf(
            constant("kSecClass") to constant("kSecClassGenericPassword"),
            constant("kSecAttrService") to service,
            constant("kSecAttrAccount") to account,
        )
        if (returnData) {
            entries += constant("kSecReturnData") to cfConstant("kCFBooleanTrue")
            entries += constant("kSecMatchLimit") to constant("kSecMatchLimitOne")
        }
        if (returnAttributes) {
            entries += constant("kSecReturnAttributes") to cfConstant("kCFBooleanTrue")
            entries += constant("kSecMatchLimit") to constant("kSecMatchLimitOne")
        }
        return dictionary(entries, listOf(service, account))
    }

    fun contains(key: String): Boolean {
        val query = query(key, returnAttributes = true)
        val result = Memory(Native.POINTER_SIZE.toLong())
        return try {
            val code = secFunction("SecItemCopyMatching").invokeInt(arrayOf(query, result))
            if (code == 0) result.getPointer(0)?.let(::release)
            code == 0
        } finally { release(query) }
    }

    fun get(key: String): String? {
        val query = query(key, returnData = true)
        val result = Memory(Native.POINTER_SIZE.toLong())
        return try {
            val code = secFunction("SecItemCopyMatching").invokeInt(arrayOf(query, result))
            if (code != 0) return null
            val data = result.getPointer(0) ?: return null
            try {
                val length = cfFunction("CFDataGetLength").invokeLong(arrayOf(data)).toInt()
                val bytes = cfFunction("CFDataGetBytePtr").invokePointer(arrayOf(data))?.getByteArray(0, length) ?: return null
                try { String(bytes, StandardCharsets.UTF_8) } finally { bytes.fill(0) }
            } finally { release(data) }
        } finally { release(query) }
    }

    fun set(key: String, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        var payload: Memory? = null
        try {
            val query = query(key)
            payload = Memory(bytes.size.toLong()).also { it.write(0, bytes, 0, bytes.size) }
            val data = cfFunction("CFDataCreate").invokePointer(arrayOf(null, payload, bytes.size.toLong()))
                ?: error("Не удалось подготовить секрет для Связки ключей")
            val attributes = dictionary(listOf(constant("kSecValueData") to data), listOf(data))
            val updateCode = try { secFunction("SecItemUpdate").invokeInt(arrayOf(query, attributes)) }
            finally { release(attributes); release(query) }
            if (updateCode == 0) return
            check(updateCode == ITEM_NOT_FOUND) { "Не удалось обновить секрет в Связке ключей (код $updateCode)" }

            val service = cfString(SERVICE)
            val account = cfString(key)
            val addData = cfFunction("CFDataCreate").invokePointer(arrayOf(null, payload, bytes.size.toLong()))
                ?: error("Не удалось подготовить секрет для Связки ключей")
            val addQuery = dictionary(listOf(
                constant("kSecClass") to constant("kSecClassGenericPassword"),
                constant("kSecAttrService") to service,
                constant("kSecAttrAccount") to account,
                constant("kSecValueData") to addData,
            ), listOf(service, account, addData))
            val addCode = try { secFunction("SecItemAdd").invokeInt(arrayOf(addQuery, null)) }
            finally { release(addQuery) }
            check(addCode == 0) { "Не удалось сохранить секрет в Связке ключей (код $addCode)" }
        } finally {
            payload?.setMemory(0, bytes.size.toLong(), 0)
            bytes.fill(0)
        }
    }

    fun delete(key: String) {
        val query = query(key)
        val code = try { secFunction("SecItemDelete").invokeInt(arrayOf(query)) }
        finally { release(query) }
        check(code == 0 || code == ITEM_NOT_FOUND) { "Не удалось удалить секрет из Связки ключей (код $code)" }
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

    override fun contains(key: String) = file(key).isFile

    override fun get(key: String): String? {
        val f = file(key)
        if (!f.exists()) return null
        val script = "\$h=[Console]::In.ReadToEnd().Trim(); \$s=ConvertTo-SecureString \$h; " +
            "\$b=[Runtime.InteropServices.Marshal]::SecureStringToBSTR(\$s); " +
            "try { [Console]::Out.Write([Runtime.InteropServices.Marshal]::PtrToStringBSTR(\$b)) } " +
            "finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR(\$b) }"
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
    override fun contains(key: String) = File(dir, key).isFile
    override fun get(key: String) = File(dir, key).takeIf { it.exists() }?.readText()
    override fun set(key: String, value: String) { File(dir, key).writeText(value); Fs.restrictToOwner(File(dir, key)) }
    override fun delete(key: String) { File(dir, key).delete() }
}
