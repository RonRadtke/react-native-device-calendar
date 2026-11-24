package com.ronradtke.reactnativedevicecalendar

import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import java.util.Locale
import java.util.TimeZone

/** Turns the maps JavaScript sends into records, and records into the maps it expects (NativeReactNativeDeviceCalendar.ts) */
internal object Mapper {
  fun calendarInput(map: ReadableMap) = CalendarInput(
    title = map.getString("title") ?: "",
    color = map.getString("color") ?: "",
    accountName = map.optString("accountName"),
    timeZone = map.optString("timeZone"),
  )

  fun eventInput(map: ReadableMap) = EventInput(
    calendarId = map.getString("calendarId") ?: "",
    title = map.getString("title") ?: "",
    startDate = map.getDouble("startDate").toLong(),
    endDate = map.getDouble("endDate").toLong(),
    allDay = map.getBoolean("allDay"),
    location = map.optString("location"),
    notes = map.optString("notes"),
    timeZone = map.optString("timeZone"),
    availability = map.optString("availability"),
    alarms = map.getArray("alarms").maps().map { Alarm(it.getInt("relativeOffset"), it.optString("method")) },
    rule = map.getMap("recurrenceRule")?.let(::readRule),
  )

  fun strings(array: ReadableArray): List<String> = (0 until array.size()).mapNotNull { array.getString(it) }

  fun sources(sources: List<Pair<String, String>>): WritableArray = array(sources) { (type, name) ->
    pushMap(Arguments.createMap().apply {
      putString("id", sourceId(type, name))
      putString("name", name)
      putString("type", type)
      putBoolean("isLocal", type == CalendarContract.ACCOUNT_TYPE_LOCAL)
    })
  }

  fun calendars(calendars: List<CalendarRow>): WritableArray = array(calendars) { row ->
    pushMap(Arguments.createMap().apply {
      putString("id", row.id.toString())
      putString("title", row.title)
      putString("color", String.format(Locale.ROOT, "#%06X", 0xFFFFFF and row.color))
      putString("sourceId", sourceId(row.accountType, row.accountName))
      putString("source", row.accountName)
      putString("sourceType", row.accountType)
      putBoolean("isPrimary", row.isPrimary)
      putBoolean("allowsModifications", row.accessLevel >= Calendars.CAL_ACCESS_CONTRIBUTOR)
      putBoolean("isVisible", row.isVisible)
      putString("timeZone", row.timeZone)
      putArray("allowedAvailabilities", array(Codes.allowedAvailabilities(row.allowedAvailability)) { pushString(it) })
    })
  }

  fun events(events: List<EventDetails>, zone: TimeZone): WritableArray = array(events) { pushMap(event(it, zone)) }

  fun event(details: EventDetails, zone: TimeZone): WritableMap = Arguments.createMap().apply {
    val row = details.row
    putString("id", row.eventId.toString())
    putString("calendarId", row.calendarId.toString())
    putString("title", row.title)
    putDouble("startDate", row.startDate(zone).toDouble())
    putDouble("endDate", row.endDate(zone).toDouble())
    putBoolean("allDay", row.allDay)
    putString("location", row.location)
    putString("notes", row.notes)
    putNull("url")
    putString("timeZone", if (row.allDay) null else row.timeZone)
    putString("availability", Codes.availability(row.availability))
    putString("status", Codes.status(row.status))
    putArray("alarms", array(details.alarms) { alarm ->
      pushMap(Arguments.createMap().apply {
        putInt("relativeOffset", alarm.relativeOffset)
        putString("method", alarm.method)
      })
    })
    putMap("recurrenceRule", details.rrule?.let { RecurrenceRules.parse(it, row.ruleAllDay, zone) }?.let(::writeRule))
    val originalStart = row.originalStartDate(zone)
    if (originalStart == null) putNull("originalStartDate") else putDouble("originalStartDate", originalStart.toDouble())
    putBoolean("isDetached", row.isException)
    putMap("organizer", row.organizer?.let { organizer(it, row) })
    putNull("creationDate")
    putNull("lastModifiedDate")
  }

  fun attendees(attendees: List<AttendeeRow>): WritableArray = array(attendees) { row ->
    pushMap(Arguments.createMap().apply {
      putString("name", row.name?.takeIf { it.isNotEmpty() })
      putString("email", row.email?.takeIf { it.isNotEmpty() })
      putString("role", Codes.attendeeRole(row.relationship, row.type))
      putString("status", Codes.attendeeStatus(row.status))
      putString("type", Codes.attendeeType(row.type))
      putBoolean("isCurrentUser", row.isCurrentUser)
    })
  }

  /** Android cannot tell what the person did in the calendar app: always done */
  fun dialogResult(id: String?): WritableMap = Arguments.createMap().apply {
    putString("action", "done")
    putString("id", id)
  }

  private fun readRule(map: ReadableMap) = RecurrenceRule(
    frequency = map.getString("frequency") ?: "daily",
    interval = map.getInt("interval"),
    endDate = map.optLong("endDate"),
    occurrence = map.optLong("occurrence")?.toInt(),
    daysOfTheWeek = map.getArray("daysOfTheWeek").maps().map { Weekday(it.getInt("day"), it.getInt("weekNumber")) },
    daysOfTheMonth = map.getArray("daysOfTheMonth").ints(),
    monthsOfTheYear = map.getArray("monthsOfTheYear").ints(),
    weeksOfTheYear = map.getArray("weeksOfTheYear").ints(),
    daysOfTheYear = map.getArray("daysOfTheYear").ints(),
    setPositions = map.getArray("setPositions").ints(),
  )

  private fun writeRule(rule: RecurrenceRule): WritableMap = Arguments.createMap().apply {
    putString("frequency", rule.frequency)
    putInt("interval", rule.interval)
    if (rule.endDate == null) putNull("endDate") else putDouble("endDate", rule.endDate.toDouble())
    if (rule.occurrence == null) putNull("occurrence") else putInt("occurrence", rule.occurrence)
    putArray("daysOfTheWeek", array(rule.daysOfTheWeek) { day ->
      pushMap(Arguments.createMap().apply {
        putInt("day", day.day)
        putInt("weekNumber", day.weekNumber)
      })
    })
    putArray("daysOfTheMonth", ints(rule.daysOfTheMonth))
    putArray("monthsOfTheYear", ints(rule.monthsOfTheYear))
    putArray("weeksOfTheYear", ints(rule.weeksOfTheYear))
    putArray("daysOfTheYear", ints(rule.daysOfTheYear))
    putArray("setPositions", ints(rule.setPositions))
  }

  /** The organizer as an attendee; it is the person using the device when the provider says so or it owns the calendar */
  private fun organizer(email: String, row: EventRow): WritableMap = Arguments.createMap().apply {
    putNull("name")
    putString("email", email)
    putString("role", "chair")
    putString("status", "unknown")
    putString("type", "person")
    putBoolean("isCurrentUser", row.isOrganizer || email.equals(row.ownerAccount, ignoreCase = true))
  }

  /** Android names an account by its type and name; neither holds a line feed */
  private fun sourceId(type: String, name: String) = "$type\n$name"

  private fun ints(values: List<Int>): WritableArray = array(values) { pushInt(it) }

  private inline fun <T> array(items: List<T>, push: WritableArray.(T) -> Unit): WritableArray =
    Arguments.createArray().apply { items.forEach { push(it) } }

  private fun ReadableArray?.maps(): List<ReadableMap> = if (this == null) emptyList() else (0 until size()).mapNotNull { getMap(it) }

  private fun ReadableArray?.ints(): List<Int> = if (this == null) emptyList() else (0 until size()).map { getInt(it) }
}

internal fun ReadableMap.optString(key: String): String? = if (hasKey(key) && !isNull(key)) getString(key) else null

internal fun ReadableMap.optLong(key: String): Long? = if (hasKey(key) && !isNull(key)) getDouble(key).toLong() else null
