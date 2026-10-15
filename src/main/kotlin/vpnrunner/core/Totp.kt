package vpnrunner.core

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** TOTP по RFC 6238 — то же, что генерирует Google Authenticator (SHA1, 6 цифр, 30 секунд). */
object Totp {

    fun code(secretBase32: String, timeMillis: Long = System.currentTimeMillis(), digits: Int = 6, period: Int = 30): String {
        val key = base32Decode(cleanSecret(secretBase32))
        val counter = timeMillis / 1000 / period
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val offset = hash.last().toInt() and 0x0f
        val bin = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        var mod = 1
        repeat(digits) { mod *= 10 }
        return (bin % mod).toString().padStart(digits, '0')
    }

    /** Номер 30-секундного окна: у кодов из одного окна он одинаковый. */
    fun window(timeMillis: Long = System.currentTimeMillis(), period: Int = 30): Long = timeMillis / 1000 / period

    /** Сколько секунд осталось жить текущему коду. */
    fun secondsLeft(timeMillis: Long = System.currentTimeMillis(), period: Int = 30): Int =
        period - ((timeMillis / 1000) % period).toInt()

    /** Принимает и голый секрет, и otpauth://totp/...?secret=XXX. */
    fun cleanSecret(input: String): String {
        val s = input.trim()
        val raw = if (s.startsWith("otpauth://", true)) {
            Regex("[?&]secret=([^&]+)", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1) ?: ""
        } else s
        return raw.replace(" ", "").replace("-", "").trimEnd('=').uppercase()
    }

    fun isValid(input: String): Boolean = runCatching {
        val c = cleanSecret(input); c.isNotEmpty() && base32Decode(c).isNotEmpty()
    }.getOrDefault(false)

    private fun base32Decode(s: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0
        var bits = 0
        val out = java.io.ByteArrayOutputStream()
        for (c in s) {
            val v = alphabet.indexOf(c)
            require(v >= 0) { "Секрет TOTP содержит недопустимый символ '$c'" }
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}
