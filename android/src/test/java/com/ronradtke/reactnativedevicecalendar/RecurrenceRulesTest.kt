package com.ronradtke.reactnativedevicecalendar

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecurrenceRulesTest {
  private val utc = TimeZone.getTimeZone("UTC")
  private val berlin = TimeZone.getTimeZone("Europe/Berlin")
  private val auckland = TimeZone.getTimeZone("Pacific/Auckland")
  private val losAngeles = TimeZone.getTimeZone("America/Los_Angeles")

  private fun at(zone: TimeZone, year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Int = 0): Long =
    Calendar.getInstance(zone).apply {
      clear()
      set(year, month - 1, day, hour, minute, second)
    }.timeInMillis

  private fun roundTrip(rrule: String, rule: RecurrenceRule, allDay: Boolean = false, zone: TimeZone = berlin) {
    assertEquals(rule, RecurrenceRules.parse(rrule, allDay, zone))
    assertEquals(rrule, RecurrenceRules.format(rule, allDay, zone))
  }

  @Test
  fun `weekly rules keep their days, interval and count`() {
    roundTrip(
      "FREQ=WEEKLY;INTERVAL=2;COUNT=10;BYDAY=MO,WE,FR",
      RecurrenceRule("weekly", interval = 2, occurrence = 10, daysOfTheWeek = listOf(Weekday(2, 0), Weekday(4, 0), Weekday(6, 0))),
    )
    roundTrip("FREQ=DAILY", RecurrenceRule("daily"))
  }

  @Test
  fun `monthly and yearly rules keep the nth weekday and the other parts`() {
    roundTrip("FREQ=MONTHLY;BYDAY=1MO,-1FR", RecurrenceRule("monthly", daysOfTheWeek = listOf(Weekday(2, 1), Weekday(6, -1))))
    roundTrip("FREQ=MONTHLY;BYMONTHDAY=1,15,-1", RecurrenceRule("monthly", daysOfTheMonth = listOf(1, 15, -1)))
    roundTrip(
      "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1",
      RecurrenceRule("monthly", daysOfTheWeek = (2..6).map { Weekday(it, 0) }, setPositions = listOf(-1)),
    )
    roundTrip(
      "FREQ=YEARLY;BYMONTH=3,9;BYWEEKNO=10,-1;BYYEARDAY=100,-100",
      RecurrenceRule("yearly", monthsOfTheYear = listOf(3, 9), weeksOfTheYear = listOf(10, -1), daysOfTheYear = listOf(100, -100)),
    )
    roundTrip("FREQ=YEARLY;BYDAY=20SU,-53SA", RecurrenceRule("yearly", daysOfTheWeek = listOf(Weekday(1, 20), Weekday(7, -53))))
  }

  @Test
  fun `a timed rule ends at a UTC date-time to the second`() {
    val end = at(berlin, 2026, 12, 31, 10, 30, 15) + 500
    val rule = RecurrenceRule("daily", endDate = end)
    assertEquals("FREQ=DAILY;UNTIL=20261231T093015Z", RecurrenceRules.format(rule, false, berlin))
    assertEquals(end - 500, RecurrenceRules.parse("FREQ=DAILY;UNTIL=20261231T093015Z", false, berlin)?.endDate)
  }

  @Test
  fun `an all-day rule ends at the date of its last local day`() {
    for (zone in listOf(berlin, auckland, losAngeles)) {
      val lastDay = at(zone, 2026, 12, 31)
      assertEquals("FREQ=DAILY;UNTIL=20261231", RecurrenceRules.format(RecurrenceRule("daily", endDate = lastDay), true, zone))
      // any moment of that day stands for it
      assertEquals("FREQ=DAILY;UNTIL=20261231", RecurrenceRules.format(RecurrenceRule("daily", endDate = lastDay + 23 * 3_600_000L), true, zone))
      assertEquals(lastDay, RecurrenceRules.parse("FREQ=DAILY;UNTIL=20261231", true, zone)?.endDate)
      // a date-time on the last day, as some sync adapters write it, means the same day
      assertEquals(lastDay, RecurrenceRules.parse("FREQ=DAILY;UNTIL=20261231T235959Z", true, zone)?.endDate)
    }
  }

  @Test
  fun `a date or a time without zone counts as UTC, as Android reads it`() {
    assertEquals(at(utc, 2026, 12, 31), RecurrenceRules.parse("FREQ=DAILY;UNTIL=20261231", false, berlin)?.endDate)
    assertEquals(at(utc, 2026, 12, 31, 12), RecurrenceRules.parse("FREQ=DAILY;UNTIL=20261231T120000", false, berlin)?.endDate)
  }

  @Test
  fun `the parser ignores WKST, case and the RRULE prefix, and reads only the first rule`() {
    val rule = RecurrenceRule("weekly", daysOfTheWeek = listOf(Weekday(3, 0)))
    assertEquals(rule, RecurrenceRules.parse("FREQ=WEEKLY;WKST=SU;BYDAY=TU", false, berlin))
    assertEquals(rule, RecurrenceRules.parse("rrule:freq=weekly;byday=tu", false, berlin))
    assertEquals(rule, RecurrenceRules.parse("FREQ=WEEKLY;BYDAY=TU\nFREQ=DAILY", false, berlin))
    assertEquals(RecurrenceRule("monthly", daysOfTheWeek = listOf(Weekday(3, 2))), RecurrenceRules.parse("FREQ=MONTHLY;BYDAY=+2TU", false, berlin))
  }

  @Test
  fun `rules the contract cannot express are none`() {
    assertNull(RecurrenceRules.parse("FREQ=HOURLY;INTERVAL=2", false, berlin))
    assertNull(RecurrenceRules.parse("BYDAY=MO", false, berlin))
    assertNull(RecurrenceRules.parse("FREQ=WEEKLY;BYDAY=XX", false, berlin))
    assertNull(RecurrenceRules.parse("FREQ=DAILY;COUNT=ten", false, berlin))
    assertNull(RecurrenceRules.parse("FREQ=DAILY;UNTIL=2026", false, berlin))
  }

  @Test
  fun `a series ends before an occurrence with an UNTIL in place of its COUNT or UNTIL`() {
    val start = at(utc, 2026, 3, 4, 9)
    assertEquals("FREQ=DAILY;WKST=SU;UNTIL=20260304T085959Z", RecurrenceRules.endingBefore("FREQ=DAILY;COUNT=10;WKST=SU", start, false))
    assertEquals("FREQ=WEEKLY;BYDAY=MO;UNTIL=20260304T085959Z", RecurrenceRules.endingBefore("FREQ=WEEKLY;UNTIL=20261231T000000Z;BYDAY=MO", start, false))
    // an all-day occurrence starts at midnight UTC: the series ends with the day before
    assertEquals("FREQ=DAILY;UNTIL=20260303", RecurrenceRules.endingBefore("FREQ=DAILY", at(utc, 2026, 3, 4), true))
  }

  @Test
  fun `the count of a rule, and a rule with another count`() {
    assertEquals(10, RecurrenceRules.count("FREQ=DAILY;COUNT=10"))
    assertNull(RecurrenceRules.count("FREQ=DAILY;UNTIL=20261231"))
    assertEquals("FREQ=HOURLY;INTERVAL=4;COUNT=7", RecurrenceRules.withCount("FREQ=HOURLY;COUNT=20;INTERVAL=4", 7))
  }

  @Test
  fun `an update without a rule keeps only a stored rule JavaScript could not read`() {
    assertEquals("FREQ=HOURLY;INTERVAL=4", RecurrenceRules.keptOnUpdate("FREQ=HOURLY;INTERVAL=4", null))
    assertEquals("FREQ=WEEKLY;BYDAY=XX", RecurrenceRules.keptOnUpdate("FREQ=WEEKLY;BYDAY=XX", null))
    // a rule JavaScript read and sent back as none was removed on purpose
    assertNull(RecurrenceRules.keptOnUpdate("FREQ=WEEKLY;BYDAY=MO", null))
    // a rule in the input replaces any stored one
    assertNull(RecurrenceRules.keptOnUpdate("FREQ=HOURLY", RecurrenceRule("daily")))
    assertNull(RecurrenceRules.keptOnUpdate(null, null))
  }
}
