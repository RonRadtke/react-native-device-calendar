package com.ronradtke.reactnativedevicecalendar

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class DaysTest {
  private val berlin = TimeZone.getTimeZone("Europe/Berlin")
  private val auckland = TimeZone.getTimeZone("Pacific/Auckland")
  private val losAngeles = TimeZone.getTimeZone("America/Los_Angeles")

  private fun at(zone: TimeZone, year: Int, month: Int, day: Int, hour: Int = 0): Long =
    Calendar.getInstance(zone).apply {
      clear()
      set(year, month - 1, day, hour, 0)
    }.timeInMillis

  private val utc = TimeZone.getTimeZone("UTC")

  @Test
  fun `a local day becomes midnight UTC of the same date`() {
    assertEquals(at(utc, 2026, 8, 19), Days.utcMidnightOf(at(berlin, 2026, 8, 19), berlin))
    assertEquals(at(utc, 2026, 8, 19), Days.utcMidnightOf(at(berlin, 2026, 8, 19, 23), berlin))
    // east and west of UTC the local midnight lies on another UTC date
    assertEquals(at(utc, 2026, 8, 19), Days.utcMidnightOf(at(auckland, 2026, 8, 19), auckland))
    assertEquals(at(utc, 2026, 8, 19), Days.utcMidnightOf(at(losAngeles, 2026, 8, 19, 20), losAngeles))
  }

  @Test
  fun `midnight UTC becomes the local midnight of the same date`() {
    assertEquals(at(berlin, 2026, 8, 19), Days.localMidnightOf(at(utc, 2026, 8, 19), berlin))
    assertEquals(at(auckland, 2026, 8, 19), Days.localMidnightOf(at(utc, 2026, 8, 19), auckland))
    assertEquals(at(losAngeles, 2026, 8, 19), Days.localMidnightOf(at(utc, 2026, 8, 19), losAngeles))
  }

  @Test
  fun `the days survive the change to and from summer time`() {
    // Berlin leaves summer time on 25 October 2026: that day has 25 hours
    val local = at(berlin, 2026, 10, 25)
    assertEquals(local, Days.localMidnightOf(Days.utcMidnightOf(local, berlin), berlin))
    assertEquals(at(berlin, 2026, 10, 26), Days.localMidnightOf(at(utc, 2026, 10, 26), berlin))
    assertEquals(at(utc, 2026, 3, 29), Days.utcMidnightOf(at(berlin, 2026, 3, 29, 12), berlin))
  }
}
