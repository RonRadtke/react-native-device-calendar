package com.ronradtke.reactnativedevicecalendar

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordsTest {
  private val utc = TimeZone.getTimeZone("UTC")
  private val berlin = TimeZone.getTimeZone("Europe/Berlin")

  private fun at(zone: TimeZone, year: Int, month: Int, day: Int, hour: Int = 0): Long =
    Calendar.getInstance(zone).apply {
      clear()
      set(year, month - 1, day, hour, 0)
    }.timeInMillis

  private val single = EventRow(
    id = 7, calendarId = 1, title = "Meeting", begin = at(berlin, 2026, 8, 19, 10), end = at(berlin, 2026, 8, 19, 11), allDay = false,
    location = null, notes = null, timeZone = "Europe/Berlin", availability = 0, status = null, rrule = null, originalId = null,
    originalInstanceTime = null, originalAllDay = false, organizer = null, isOrganizer = false, ownerAccount = null, syncId = null,
    accountName = "Device", accountType = "LOCAL",
  )

  private fun input(allDay: Boolean, start: Long, end: Long, timeZone: String? = null) = EventInput(
    calendarId = "1", title = "Holiday", startDate = start, endDate = end, allDay = allDay, location = null, notes = null,
    timeZone = timeZone, availability = null, alarms = emptyList(), rule = null,
  )

  @Test
  fun `an event that does not recur has no original start`() {
    assertEquals(7L, single.eventId)
    assertNull(single.originalStartDate(berlin))
    assertFalse(single.isException)
  }

  @Test
  fun `an occurrence of a series starts by its rule`() {
    val occurrence = single.copy(rrule = "FREQ=DAILY")
    assertEquals(occurrence.begin, occurrence.originalStartDate(berlin))
    val allDay = occurrence.copy(allDay = true, begin = at(utc, 2026, 8, 19), end = at(utc, 2026, 8, 20))
    assertEquals(at(berlin, 2026, 8, 19), allDay.startDate(berlin))
    assertEquals(at(berlin, 2026, 8, 20), allDay.endDate(berlin))
    assertEquals(at(berlin, 2026, 8, 19), allDay.originalStartDate(berlin))
  }

  @Test
  fun `an exception reports its series and the start it had by the rule`() {
    // the occurrence of an all-day series on 19 August, moved to a timed event on the 20th
    val exception = single.copy(
      id = 9, begin = at(berlin, 2026, 8, 20, 10), originalId = 7, originalInstanceTime = at(utc, 2026, 8, 19), originalAllDay = true,
    )
    assertTrue(exception.isException)
    assertEquals(7L, exception.eventId)
    assertTrue(exception.ruleAllDay)
    assertEquals(at(berlin, 2026, 8, 19), exception.originalStartDate(berlin))
  }

  @Test
  fun `an all-day series knows its occurrences by midnight UTC`() {
    val series = single.copy(allDay = true, rrule = "FREQ=DAILY")
    assertEquals(at(utc, 2026, 8, 19), series.instanceTime(at(berlin, 2026, 8, 19), berlin))
    assertEquals(at(berlin, 2026, 8, 19, 10), single.instanceTime(at(berlin, 2026, 8, 19, 10), berlin))
  }

  @Test
  fun `an all-day event is stored at midnights UTC with a duration in days`() {
    val times = input(true, at(berlin, 2026, 8, 19), at(berlin, 2026, 8, 21)).times(berlin)
    assertEquals(EventTimes(at(utc, 2026, 8, 19), at(utc, 2026, 8, 21), "P2D", "UTC"), times)
  }

  @Test
  fun `a timed event keeps its zone, the device's when it has none`() {
    val start = at(berlin, 2026, 8, 19, 10)
    assertEquals(EventTimes(start, start + 5_400_000, "P5400S", "Asia/Tokyo"), input(false, start, start + 5_400_000, "Asia/Tokyo").times(berlin))
    assertEquals("Europe/Berlin", input(false, start, start, null).times(berlin).timeZone)
  }

  @Test
  fun `only known time zones pass`() {
    assertEquals("Europe/Berlin", knownZone("Europe/Berlin"))
    val error = assertThrows(CalendarException::class.java) { knownZone("Mars/Olympus") }
    assertEquals("E_CALENDAR", error.code)
  }

  @Test
  fun `an attendee is the current user when it owns the calendar`() {
    val attendee = AttendeeRow("Ron", "Ron@Example.com", 1, 1, 1, "ron@example.com")
    assertTrue(attendee.isCurrentUser)
    assertFalse(attendee.copy(email = null).isCurrentUser)
    assertFalse(attendee.copy(ownerAccount = null).isCurrentUser)
  }
}
