package com.ronradtke.reactnativedevicecalendar

/** DURATION values (RFC 5545), which Android requires instead of DTEND for a recurring event */
internal object Durations {
  // Android also reads hours, minutes and seconds without the T, such as P3600S
  private val PATTERN = Regex("([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?")

  /** A timed event's duration in whole seconds, as Android writes it */
  fun ofSeconds(millis: Long): String = "P${millis / 1000}S"

  fun ofDays(days: Long): String = "P${days}D"

  /** The length of a duration; 0 for a missing or malformed one. Like Android's instances, a day counts 24 hours */
  fun millis(duration: String?): Long {
    val match = duration?.let { PATTERN.matchEntire(it.trim()) } ?: return 0
    val (sign, weeks, days, hours, minutes, seconds) = match.destructured
    val total = (number(weeks) * 7 + number(days)) * Days.MILLIS +
      ((number(hours) * 60 + number(minutes)) * 60 + number(seconds)) * 1000
    return if (sign == "-") -total else total
  }

  private fun number(value: String): Long = if (value.isEmpty()) 0 else value.toLong()
}
