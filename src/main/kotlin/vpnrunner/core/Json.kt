package vpnrunner.core

/**
 * Минимальный JSON без внешних зависимостей.
 * Значения: null, Boolean, Number, String, List<Any?>, Map<String, Any?>.
 */
object Json {

    fun write(value: Any?, pretty: Boolean = true): String {
        val sb = StringBuilder()
        writeValue(sb, value, if (pretty) 0 else -1)
        return sb.toString()
    }

    private fun indent(sb: StringBuilder, level: Int) {
        if (level < 0) return
        sb.append('\n')
        repeat(level) { sb.append("  ") }
    }

    private fun writeValue(sb: StringBuilder, v: Any?, level: Int) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v)
            is Number -> {
                val d = v.toDouble()
                if (d == Math.floor(d) && !d.isInfinite()) sb.append(d.toLong()) else sb.append(d)
            }
            is String -> writeString(sb, v)
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    indent(sb, if (level < 0) -1 else level + 1)
                    writeString(sb, k.toString())
                    sb.append(if (level < 0) ":" else ": ")
                    writeValue(sb, value, if (level < 0) -1 else level + 1)
                }
                indent(sb, level)
                sb.append('}')
            }
            is Iterable<*> -> {
                val list = v.toList()
                if (list.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                list.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    indent(sb, if (level < 0) -1 else level + 1)
                    writeValue(sb, item, if (level < 0) -1 else level + 1)
                }
                indent(sb, level)
                sb.append(']')
            }
            is Array<*> -> writeValue(sb, v.toList(), level)
            else -> writeString(sb, v.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.readValue()
        p.skipWs()
        if (!p.eof()) throw IllegalArgumentException("Лишние символы в JSON на позиции ${p.pos}")
        return v
    }

    private class Parser(val s: String) {
        var pos = 0
        fun eof() = pos >= s.length
        fun skipWs() { while (pos < s.length && s[pos].isWhitespace()) pos++ }
        fun err(msg: String): Nothing = throw IllegalArgumentException("JSON: $msg на позиции $pos")

        fun readValue(): Any? {
            skipWs()
            if (eof()) err("неожиданный конец")
            return when (val c = s[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else err("неожиданный символ '$c'")
            }
        }

        fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) err("ожидалось $word")
            pos += word.length
            return value
        }

        fun readNumber(): Number {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val t = s.substring(start, pos)
            return if (t.any { it in ".eE" }) t.toDouble() else t.toLongOrNull() ?: t.toDouble()
        }

        fun readString(): String {
            pos++ // "
            val sb = StringBuilder()
            while (true) {
                if (eof()) err("незакрытая строка")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (eof()) err("незакрытая строка")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) err("плохой \\u")
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar()); pos += 4
                            }
                            else -> err("плохой escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun readArray(): List<Any?> {
            pos++
            val list = ArrayList<Any?>()
            skipWs()
            if (!eof() && s[pos] == ']') { pos++; return list }
            while (true) {
                list.add(readValue())
                skipWs()
                if (eof()) err("незакрытый массив")
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return list
                    else -> err("ожидалась , или ]")
                }
            }
        }

        fun readObject(): Map<String, Any?> {
            pos++
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (!eof() && s[pos] == '}') { pos++; return map }
            while (true) {
                skipWs()
                if (eof() || s[pos] != '"') err("ожидался ключ")
                val k = readString()
                skipWs()
                if (eof() || s[pos++] != ':') err("ожидалось :")
                map[k] = readValue()
                skipWs()
                if (eof()) err("незакрытый объект")
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return map
                    else -> err("ожидалась , или }")
                }
            }
        }
    }
}

/** Удобные геттеры для разобранного JSON. */
@Suppress("UNCHECKED_CAST")
fun Any?.obj(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()
fun Any?.arr(): List<Any?> = this as? List<Any?> ?: emptyList()
fun Map<String, Any?>.str(key: String): String? = this[key]?.let { if (it is Number && it.toDouble() == Math.floor(it.toDouble())) it.toLong().toString() else it.toString() }
fun Map<String, Any?>.bool(key: String, def: Boolean = false): Boolean = when (val v = this[key]) {
    is Boolean -> v
    is String -> v.equals("true", true)
    else -> def
}
