package com.ronradtke.reactnativedevicecalendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.util.Calendar
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The library against the real calendar provider of the device: what Android stores and what it expands into instances. The tests
 * work in a calendar of their own local account, created before and deleted after each test.
 */
@RunWith(AndroidJUnit4::class)
class CalendarProviderTest {
  @get:Rule
  val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
  private val calendars = CalendarStore(context)
  private val events = EventStore(context)
  private val zone: TimeZone = TimeZone.getDefault()
  private lateinit var calendarId: String

  /** A local time of the device's time zone; the tests use a week in summer and the end of summer time */
  private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
    Calendar.getInstance(zone).apply {
      clear()
      set(year, month - 1, day, hour, minute)
    }.timeInMillis

  private fun input(
    title: String,
    start: Long,
    end: Long,
    allDay: Boolean = false,
    alarms: List<Alarm> = emptyList(),
    rule: RecurrenceRule? = null,
  ) = EventInput(calendarId, title, start, end, allDay, null, null, null, null, alarms, rule)

  /** The titles and starts of the instances in the week of 17 to 24 August 2026, ordered by start */
  private fun week(): List<Pair<String, Long>> =
    events.inPeriod(at(2026, 8, 17), at(2026, 8, 24), listOf(calendarId))
      .map { it.row.title to it.row.startDate(zone) }
      .sortedBy { it.second }

  @Before
  fun createCalendar() {
    calendarId = calendars.create(CalendarInput("Library test", "#1565C0", ACCOUNT, null)).toString()
  }

  @After
  fun deleteCalendar() {
    calendars.delete(calendarId)
  }

  @Test
  fun aTimedEventKeepsItsFieldsAndAlarms() {
    val id = events.create(
      input("Planning", at(2026, 8, 19, 9), at(2026, 8, 19, 10), alarms = listOf(Alarm(-15, "alert"))).copy(location = "Room 2"),
    )

    val read = events.event(id.toString(), null)
    assertNotNull(read)
    assertEquals("Planning", read!!.row.title)
    assertEquals("Room 2", read.row.location)
    assertEquals(at(2026, 8, 19, 9), read.row.startDate(zone))
    assertEquals(at(2026, 8, 19, 10), read.row.endDate(zone))
    assertEquals(listOf(Alarm(-15, "alert")), read.alarms)
  }

  @Test
  fun anAllDayEventIsStoredInUtcAndReadBackAsLocalDays() {
    // the end of summer time lies inside: 25 October has 25 hours in Europe, 23 elsewhere or none
    val id = events.create(input("Holiday", at(2026, 10, 24), at(2026, 10, 27), allDay = true))

    val stored = context.contentResolver.query(
      ContentUris.withAppendedId(Events.CONTENT_URI, id),
      arrayOf(Events.DTSTART, Events.DTEND, Events.EVENT_TIMEZONE),
      null,
      null,
      null,
    )!!.use { cursor ->
      cursor.moveToFirst()
      Triple(cursor.getLong(0), cursor.getLong(1), cursor.getString(2))
    }
    val utc = TimeZone.getTimeZone("UTC")
    val utcMidnight = { day: Int -> Calendar.getInstance(utc).apply { clear(); set(2026, 9, day) }.timeInMillis }
    assertEquals(Triple(utcMidnight(24), utcMidnight(27), "UTC"), stored)

    val read = events.event(id.toString(), null)!!
    assertTrue(read.row.allDay)
    assertEquals(at(2026, 10, 24), read.row.startDate(zone))
    assertEquals(at(2026, 10, 27), read.row.endDate(zone))
  }

  @Test
  fun changingOneOccurrenceKeepsTheOtherOccurrences() {
    val series = events.create(
      input("Stand-up", at(2026, 8, 17, 8), at(2026, 8, 17, 8, 15), rule = RecurrenceRule("daily", occurrence = 5)),
    )
    assertEquals(5, week().size)

    // the regression of the provider: an exception of a series without _SYNC_ID wiped the series' other instances
    val exception = events.update(
      series.toString(),
      input("Stand-up (later)", at(2026, 8, 18, 9), at(2026, 8, 18, 9, 15)),
      "this",
      at(2026, 8, 18, 8),
    )

    assertTrue(exception != series)
    assertEquals(
      listOf(
        "Stand-up" to at(2026, 8, 17, 8),
        "Stand-up (later)" to at(2026, 8, 18, 9),
        "Stand-up" to at(2026, 8, 19, 8),
        "Stand-up" to at(2026, 8, 20, 8),
        "Stand-up" to at(2026, 8, 21, 8),
      ),
      week(),
    )
    // the occurrence is found by its original start and reports the series' id
    val occurrence = events.event(series.toString(), at(2026, 8, 18, 8))!!
    assertEquals("Stand-up (later)", occurrence.row.title)
    assertEquals(series, occurrence.row.eventId)
  }

  @Test
  fun changingTheFutureOccurrencesSplitsTheSeriesAndKeepsItsCount() {
    val series = events.create(
      input("Stand-up", at(2026, 8, 17, 8), at(2026, 8, 17, 8, 15), rule = RecurrenceRule("daily", occurrence = 5)),
    )

    val renamed = events.update(
      series.toString(),
      input("Daily sync", at(2026, 8, 19, 8), at(2026, 8, 19, 8, 15), rule = RecurrenceRule("daily", occurrence = 5)),
      "future",
      at(2026, 8, 19, 8),
    )

    assertTrue(renamed != series)
    assertEquals(
      listOf("Stand-up", "Stand-up", "Daily sync", "Daily sync", "Daily sync"),
      week().map { it.first },
    )
  }

  @Test
  fun deletingOneOccurrenceAndTheFutureOnesLeavesTheRest() {
    val series = events.create(
      input("Stand-up", at(2026, 8, 17, 8), at(2026, 8, 17, 8, 15), rule = RecurrenceRule("daily", occurrence = 5)),
    ).toString()

    assertTrue(events.delete(series, "this", at(2026, 8, 18, 8)))
    assertEquals(listOf(at(2026, 8, 17, 8), at(2026, 8, 19, 8), at(2026, 8, 20, 8), at(2026, 8, 21, 8)), week().map { it.second })
    assertNull(events.event(series, at(2026, 8, 18, 8)))

    assertTrue(events.delete(series, "future", at(2026, 8, 20, 8)))
    assertEquals(listOf(at(2026, 8, 17, 8), at(2026, 8, 19, 8)), week().map { it.second })
  }

  @Test
  fun aWeeklyRuleWithDaysIsStoredAsRruleAndReadBack() {
    val rule = RecurrenceRule("weekly", interval = 2, daysOfTheWeek = listOf(Weekday(2, 0), Weekday(4, 0)), occurrence = 4)
    val id = events.create(input("Gym", at(2026, 8, 17, 18), at(2026, 8, 17, 19), rule = rule))

    val read = events.event(id.toString(), null)!!
    assertEquals(rule, RecurrenceRules.parse(read.rrule!!, false, zone))
    // Monday and Wednesday of every second week: the 17th and 19th, then the 31st and 2 September
    val starts = events.inPeriod(at(2026, 8, 17), at(2026, 9, 7), listOf(calendarId)).map { it.row.startDate(zone) }.sorted()
    assertEquals(listOf(at(2026, 8, 17, 18), at(2026, 8, 19, 18), at(2026, 8, 31, 18), at(2026, 9, 2, 18)), starts)
  }

  @Test
  fun deletingTheCalendarTakesItsEventsWith() {
    val id = events.create(input("Planning", at(2026, 8, 19, 9), at(2026, 8, 19, 10)))
    assertTrue(calendars.delete(calendarId))

    val left = context.contentResolver.query(
      Instances.CONTENT_URI.buildUpon().also {
        ContentUris.appendId(it, at(2026, 8, 17))
        ContentUris.appendId(it, at(2026, 8, 24))
      }.build(),
      arrayOf(Instances.EVENT_ID),
      "${Instances.EVENT_ID}=?",
      arrayOf(id.toString()),
      null,
    )!!.use { it.count }
    assertEquals(0, left)
    assertFalse(calendars.delete(calendarId))
    // the next test creates its own calendar
    calendarId = calendars.create(CalendarInput("Library test", "#1565C0", ACCOUNT, null)).toString()
  }

  private companion object {
    const val ACCOUNT = "DeviceCalendarTest"
  }
}
