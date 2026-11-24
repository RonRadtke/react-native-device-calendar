package com.ronradtke.reactnativedevicecalendar

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** A day of the week in a rule: [day] 1 Sunday to 7 Saturday; [weekNumber] 0 every such day, else the nth one of the month or year */
internal data class Weekday(val day: Int, val weekNumber: Int)

/** A recurrence rule as the contract carries it (NativeRecurrenceRule) */
internal data class RecurrenceRule(
  val frequency: String,
  val interval: Int = 1,
  /** The last moment an occurrence may start; for an all-day event the local midnight of its last day */
  val endDate: Long? = null,
  val occurrence: Int? = null,
  val daysOfTheWeek: List<Weekday> = emptyList(),
  val daysOfTheMonth: List<Int> = emptyList(),
  val monthsOfTheYear: List<Int> = emptyList(),
  val weeksOfTheYear: List<Int> = emptyList(),
  val daysOfTheYear: List<Int> = emptyList(),
  val setPositions: List<Int> = emptyList(),
)

/**
 * RRULE values (RFC 5545) as Android stores them. UNTIL is written as a date for all-day events and as a UTC date-time for timed ones.
 * Android reads a date, and a date-time without zone, as UTC: so does the parser.
 */
internal object RecurrenceRules {
  private val FREQUENCIES = listOf("daily", "weekly", "monthly", "yearly")
  private val DAYS = listOf("SU", "MO", "TU", "WE", "TH", "FR", "SA")
  private val WEEKDAY = Regex("([+-]?\\d{1,2})?(SU|MO|TU|WE|TH|FR|SA)")
  private val UNTIL = Regex("(\\d{4})(\\d{2})(\\d{2})(?:T(\\d{2})(\\d{2})(\\d{2})Z?)?")
  private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

  /**
   * The rule of an RRULE value, its UNTIL as JavaScript expects it for an event that is [allDay] or not. Null for a frequency the
   * contract lacks (hourly and shorter) and for a malformed rule; WKST and parts the contract lacks are left out.
   */
  fun parse(rrule: String, allDay: Boolean, zone: TimeZone): RecurrenceRule? {
    val parts = parts(rrule)
    val frequency = parts["FREQ"]?.lowercase(Locale.ROOT)?.takeIf { it in FREQUENCIES } ?: return null
    return try {
      RecurrenceRule(
        frequency = frequency,
        interval = parts["INTERVAL"]?.toInt() ?: 1,
        endDate = parts["UNTIL"]?.let { until(it, allDay, zone) },
        occurrence = parts["COUNT"]?.toInt(),
        daysOfTheWeek = list(parts["BYDAY"], ::weekday),
        daysOfTheMonth = list(parts["BYMONTHDAY"], String::toInt),
        monthsOfTheYear = list(parts["BYMONTH"], String::toInt),
        weeksOfTheYear = list(parts["BYWEEKNO"], String::toInt),
        daysOfTheYear = list(parts["BYYEARDAY"], String::toInt),
        setPositions = list(parts["BYSETPOS"], String::toInt),
      )
    } catch (e: IllegalArgumentException) {
      null
    }
  }

  /** The RRULE value of a rule; [zone] gives the local day of an all-day event's end */
  fun format(rule: RecurrenceRule, allDay: Boolean, zone: TimeZone): String {
    val parts = mutableListOf("FREQ=${rule.frequency.uppercase(Locale.ROOT)}")
    if (rule.interval != 1) parts += "INTERVAL=${rule.interval}"
    rule.endDate?.let { parts += "UNTIL=" + if (allDay) date(it, zone) else dateTime(it) }
    rule.occurrence?.let { parts += "COUNT=$it" }
    if (rule.daysOfTheWeek.isNotEmpty()) {
      parts += "BYDAY=" + rule.daysOfTheWeek.joinToString(",") { (if (it.weekNumber == 0) "" else "${it.weekNumber}") + DAYS[it.day - 1] }
    }
    listOf(
      "BYMONTHDAY" to rule.daysOfTheMonth,
      "BYMONTH" to rule.monthsOfTheYear,
      "BYWEEKNO" to rule.weeksOfTheYear,
      "BYYEARDAY" to rule.daysOfTheYear,
      "BYSETPOS" to rule.setPositions,
    ).forEach { (name, values) -> if (values.isNotEmpty()) parts += "$name=${values.joinToString(",")}" }
    return parts.joinToString(";")
  }

  /**
   * The RRULE value ending with the last occurrence before the one that starts at [start], as Android stores it (midnight UTC for an
   * all-day event): COUNT and UNTIL give way to an UNTIL a second, or for an all-day event a day, before it. Other parts stay as they are.
   */
  fun endingBefore(rrule: String, start: Long, allDay: Boolean): String =
    replacing(rrule, setOf("COUNT", "UNTIL"), "UNTIL=" + if (allDay) date(start - Days.MILLIS, UTC) else dateTime(start - 1000))

  /** The RRULE value with [count] occurrences in place of its COUNT */
  fun withCount(rrule: String, count: Int): String = replacing(rrule, setOf("COUNT"), "COUNT=$count")

  /** The COUNT of a rule; null when it ends by date or never */
  fun count(rrule: String): Int? = parts(rrule)["COUNT"]?.toIntOrNull()

  /**
   * The stored RRULE an update keeps although the input has no rule: one the contract cannot express, which JavaScript read as no rule
   * and sends back so. Such a recurrence can therefore only be removed by deleting the event.
   */
  fun keptOnUpdate(stored: String?, input: RecurrenceRule?): String? =
    stored?.takeIf { input == null && it.isNotBlank() && parse(it, false, UTC) == null }

  /** Each rule of the value without the parts of [names], and with [part] */
  private fun replacing(rrule: String, names: Set<String>, part: String): String =
    rrule.lines().filter { it.isNotBlank() }.joinToString("\n") { line ->
      val kept = line.split(';').filter { it.isNotBlank() && it.substringBefore('=').uppercase(Locale.ROOT) !in names }
      (kept + part).joinToString(";")
    }

  /** The parts of the first rule by name; Android separates several rules by line feeds */
  private fun parts(rrule: String): Map<String, String> =
    rrule.trim().lineSequence().first().uppercase(Locale.ROOT).removePrefix("RRULE:").split(';')
      .filter { '=' in it }
      .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }

  private fun <T> list(value: String?, read: (String) -> T): List<T> =
    value?.split(',')?.filter { it.isNotBlank() }?.map { read(it.trim()) }.orEmpty()

  private fun weekday(value: String): Weekday {
    val match = WEEKDAY.matchEntire(value) ?: throw IllegalArgumentException("BYDAY $value")
    val (number, day) = match.destructured
    return Weekday(DAYS.indexOf(day) + 1, number.ifEmpty { "0" }.toInt())
  }

  private fun until(value: String, allDay: Boolean, zone: TimeZone): Long {
    val match = UNTIL.matchEntire(value) ?: throw IllegalArgumentException("UNTIL $value")
    val f = match.groupValues.drop(1).map { it.ifEmpty { "0" }.toInt() }
    val utc = Calendar.getInstance(UTC).apply {
      clear()
      set(f[0], f[1] - 1, f[2], f[3], f[4], f[5])
    }.timeInMillis
    return if (allDay) Days.localMidnightOf(utc, zone) else utc
  }

  /** The date [millis] falls on in [zone], as YYYYMMDD */
  private fun date(millis: Long, zone: TimeZone): String {
    val c = Calendar.getInstance(zone).apply { timeInMillis = millis }
    return String.format(Locale.ROOT, "%04d%02d%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
  }

  /** The UTC date-time of [millis] to the second, as YYYYMMDDTHHMMSSZ */
  private fun dateTime(millis: Long): String {
    val c = Calendar.getInstance(UTC).apply { timeInMillis = millis }
    return date(millis, UTC) +
      String.format(Locale.ROOT, "T%02d%02d%02dZ", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND))
  }
}
