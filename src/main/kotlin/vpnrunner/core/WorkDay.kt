package vpnrunner.core

import java.io.File
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Рабочее время считается по Москве, независимо от часового пояса компьютера. */
object WorkClock {
    val ZONE: ZoneId = ZoneId.of("Europe/Moscow")
    val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun now(): ZonedDateTime = ZonedDateTime.now(ZONE)

    /** Праздники не учитываются — только суббота и воскресенье. */
    fun isWorkday(d: LocalDate) = d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY

    /** «9:00» и «09:00»; не разобралось — def. */
    fun parseTime(s: String, def: LocalTime): LocalTime = parseTimeOrNull(s) ?: def
    fun parseTimeOrNull(s: String): LocalTime? = runCatching { LocalTime.parse(s.trim(), DateTimeFormatter.ofPattern("H:mm")) }.getOrNull()

    /** «2 ч 05 мин» */
    fun duration(d: Duration): String {
        val m = d.toMinutes().coerceAtLeast(0)
        return if (m >= 60) "${m / 60} ч ${"%02d".format(m % 60)} мин" else "$m мин"
    }
}

/** Когда рабочий VPN (Cisco) должен быть включён. */
data class WorkHours(val enabled: Boolean, val start: LocalTime, val end: LocalTime) {
    fun isWorkday(t: ZonedDateTime) = enabled && WorkClock.isWorkday(t.toLocalDate())
    fun isWorkTime(t: ZonedDateTime) = isWorkday(t) && !t.toLocalTime().isBefore(start) && t.toLocalTime().isBefore(end)
}

/**
 * Учёт одного дня: когда Cisco впервые был подключён, когда последний раз был замечен подключённым,
 * сколько всего был подключён (пока работала программа) и сколько раз обрывался в рабочее время.
 */
data class WorkDay(
    val date: LocalDate,
    val firstUp: LocalTime? = null,
    val lastUp: LocalTime? = null,
    val upSeconds: Long = 0,
    val drops: Int = 0,
)

/** Файл с учётом последних дней (хранится 60 дней). */
class WorkLog(private val file: File) {
    fun load(): MutableMap<LocalDate, WorkDay> = runCatching {
        Json.parse(file.readText()).obj().mapNotNull { (k, v) ->
            val m = v.obj()
            val date = runCatching { LocalDate.parse(k) }.getOrNull() ?: return@mapNotNull null
            date to WorkDay(
                date = date,
                firstUp = m.str("firstUp")?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
                lastUp = m.str("lastUp")?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
                upSeconds = m.str("upSeconds")?.toLongOrNull() ?: 0,
                drops = m.str("drops")?.toIntOrNull() ?: 0,
            )
        }.toMap(HashMap())
    }.getOrDefault(HashMap())

    fun save(days: Map<LocalDate, WorkDay>) {
        val keep = days.values.sortedByDescending { it.date }.take(60)
        val m = linkedMapOf<String, Any?>()
        keep.forEach { d ->
            m[d.date.toString()] = linkedMapOf(
                "firstUp" to d.firstUp?.toString(),
                "lastUp" to d.lastUp?.toString(),
                "upSeconds" to d.upSeconds,
                "drops" to d.drops,
            )
        }
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(Json.write(m))
            tmp.renameTo(file) || run { file.delete(); tmp.renameTo(file) }
        }
    }
}
