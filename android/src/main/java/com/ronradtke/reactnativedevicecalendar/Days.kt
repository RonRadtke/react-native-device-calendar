package com.ronradtke.reactnativedevicecalendar

import java.util.Calendar
import java.util.TimeZone

/**
 * Android stores an all-day event from midnight UTC of its first day to midnight UTC after its last day, whatever the device's time zone;
 * the library hands such events to JavaScript as local midnights. These convert between the two for the same calendar day.
 */
internal object Days {
  const val MILLIS = 24L * 60 * 60 * 1000

  private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

  /** Midnight UTC of the calendar day that [millis] falls on in [zone] */
  fun utcMidnightOf(millis: Long, zone: TimeZone): Long = sameDay(millis, zone, UTC)

  /** Midnight in [zone] of the calendar day that [millis] falls on in UTC */
  fun localMidnightOf(millis: Long, zone: TimeZone): Long = sameDay(millis, UTC, zone)

  private fun sameDay(millis: Long, from: TimeZone, to: TimeZone): Long {
    val day = Calendar.getInstance(from).apply { timeInMillis = millis }
    return Calendar.getInstance(to).apply {
      clear()
      set(day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
  }
}
