package com.ronradtke.reactnativedevicecalendar

import java.util.TimeZone

/** An event as a row of the Events table or of the Instances table, in the units Android stores */
internal data class EventRow(
  /** The row's _ID; an instance's EVENT_ID */
  val id: Long,
  val calendarId: Long,
  val title: String,
  /** DTSTART of a row, BEGIN of an instance; midnight UTC for an all-day event */
  val begin: Long,
  /** DTEND, for a series DTSTART plus DURATION; END of an instance */
  val end: Long,
  val allDay: Boolean,
  val location: String?,
  val notes: String?,
  val timeZone: String?,
  val availability: Int?,
  val status: Int?,
  val rrule: String?,
  /** Set for an exception: the series it changes one occurrence of */
  val originalId: Long?,
  val originalInstanceTime: Long?,
  val originalAllDay: Boolean,
  val organizer: String?,
  val isOrganizer: Boolean,
  val ownerAccount: String?,
  /** _SYNC_ID, which Android needs to show a series' exceptions */
  val syncId: String?,
  /** The account of the event's calendar */
  val accountName: String,
  val accountType: String,
) {
  val isException: Boolean get() = originalId != null

  val recurs: Boolean get() = !rrule.isNullOrEmpty()

  /** The id JavaScript knows the event by, the same for every occurrence: an exception reports its series */
  val eventId: Long get() = originalId ?: id

  /** Whether the rule that made the occurrence belongs to an all-day event */
  val ruleAllDay: Boolean get() = if (isException) originalAllDay else allDay

  fun startDate(zone: TimeZone): Long = local(begin, allDay, zone)

  fun endDate(zone: TimeZone): Long = local(end, allDay, zone)

  /** The start the occurrence has by the rule; null for an event that does not recur */
  fun originalStartDate(zone: TimeZone): Long? = when {
    originalInstanceTime != null -> local(originalInstanceTime, originalAllDay, zone)
    recurs -> startDate(zone)
    else -> null
  }

  /** The instance time Android knows the occurrence by that starts at [occurrence], which JavaScript gives as local midnight when all-day */
  fun instanceTime(occurrence: Long, zone: TimeZone): Long = if (allDay) Days.utcMidnightOf(occurrence, zone) else occurrence

  private fun local(millis: Long, allDay: Boolean, zone: TimeZone): Long = if (allDay) Days.localMidnightOf(millis, zone) else millis
}

/** An event with what other rows add to it: the rule of its series and its alarms */
internal data class EventDetails(val row: EventRow, val rrule: String?, val alarms: List<Alarm>)

internal data class Alarm(val relativeOffset: Int, val method: String?)

/** What JavaScript writes (NativeEventInput); an all-day event from local midnight to local midnight, the end exclusive */
internal data class EventInput(
  val calendarId: String,
  val title: String,
  val startDate: Long,
  val endDate: Long,
  val allDay: Boolean,
  val location: String?,
  val notes: String?,
  val timeZone: String?,
  val availability: String?,
  val alarms: List<Alarm>,
  val rule: RecurrenceRule?,
) {
  /** The times as Android stores them: an all-day event at midnights UTC in the zone UTC, a timed one in its zone or the device's */
  fun times(zone: TimeZone): EventTimes =
    if (allDay) {
      val start = Days.utcMidnightOf(startDate, zone)
      val end = Days.utcMidnightOf(endDate, zone)
      EventTimes(start, end, Durations.ofDays((end - start) / Days.MILLIS), "UTC")
    } else {
      EventTimes(startDate, endDate, Durations.ofSeconds(endDate - startDate), timeZone ?: zone.id)
    }
}

/** DTSTART, DTEND, the DURATION a recurring event has instead of DTEND, and EVENT_TIMEZONE */
internal data class EventTimes(val start: Long, val end: Long, val duration: String, val timeZone: String)

internal data class CalendarRow(
  val id: Long,
  val title: String,
  val color: Int,
  val accountName: String,
  val accountType: String,
  val isPrimary: Boolean,
  val accessLevel: Int,
  val isVisible: Boolean,
  val timeZone: String?,
  val allowedAvailability: String?,
)

internal data class CalendarInput(val title: String, val color: String, val accountName: String?, val timeZone: String?)

internal data class AttendeeRow(
  val name: String?,
  val email: String?,
  val relationship: Int,
  val type: Int,
  val status: Int,
  /** The OWNER_ACCOUNT of the event's calendar */
  val ownerAccount: String?,
) {
  val isCurrentUser: Boolean get() = email != null && email.equals(ownerAccount, ignoreCase = true)
}

/** The id of a time zone the device knows; Android would silently store anything else */
internal fun knownZone(id: String): String =
  id.takeIf { it in TimeZone.getAvailableIDs() } ?: throw CalendarException("E_CALENDAR", "There is no time zone $id")
