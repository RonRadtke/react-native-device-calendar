package com.ronradtke.reactnativedevicecalendar

import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import java.util.TimeZone
import java.util.UUID

/**
 * The events: rows of the Events table, their occurrences in the Instances table, their reminders and attendees.
 *
 * A recurring event is one row with RRULE and DURATION. One changed or deleted occurrence is an exception: a row of its own with
 * ORIGINAL_ID and ORIGINAL_INSTANCE_TIME, a deleted one with STATUS canceled. JavaScript knows every occurrence by the series' id and the
 * occurrence's start by the rule, so an exception reports its series' id, and a call for an occurrence looks for its exception first.
 */
internal class EventStore(private val context: Context) {
  private val resolver
    get() = context.contentResolver

  private val zone: TimeZone
    get() = TimeZone.getDefault()

  /** The occurrences that overlap the period, of the given calendars or of all */
  fun inPeriod(start: Long, end: Long, calendarIds: List<String>): List<EventDetails> {
    val zone = zone
    val selection = if (calendarIds.isEmpty()) null else "${Instances.CALENDAR_ID} IN (${calendarIds.joinToString(",") { "?" }})"
    // all-day occurrences lie on midnights UTC: a day more on each side finds those of the first and last local day
    val rows = instances(start - Days.MILLIS, end + Days.MILLIS, selection, calendarIds.toTypedArray().takeIf { it.isNotEmpty() })
      .filter {
        // the extra days bring occurrences outside the period; an event without duration counts at its start
        val begin = it.startDate(zone)
        begin < end && (it.endDate(zone) > start || begin >= start)
      }
    return details(rows)
  }

  /** The event, its first occurrence for a series; with [occurrence] that occurrence. Null when there is none */
  fun event(eventId: String, occurrence: Long?): EventDetails? {
    val row = row(eventId) ?: return null
    val target = if (occurrence == null) row else findOccurrence(row, occurrence) ?: return null
    return details(listOf(target)).first()
  }

  /** The row to show for an occurrence: its exception, or the series with the occurrence's times */
  fun occurrenceRow(eventId: String, occurrence: Long): EventRow =
    row(eventId)?.let { findOccurrence(it, occurrence) } ?: throw notFound(eventId)

  fun create(input: EventInput): Long {
    val notFound = CalendarException("E_NOT_FOUND", "There is no calendar ${input.calendarId}")
    val calendarId = input.calendarId.toLongOrNull() ?: throw notFound
    val (accountName, accountType) = resolver.accountOf(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId))
      ?: throw notFound
    check(input)
    val ops = arrayListOf(insertEventOp(seriesValues(input).apply { put(Events.CALENDAR_ID, calendarId) }, accountName, accountType))
    ops.setAlarmsOfInserted(0, input.alarms)
    return applyBatch(ops).insertedId(0)
  }

  /** Writes the whole event; returns the id of the row changed, which for an occurrence may be a new exception or a new series */
  fun update(eventId: String, input: EventInput, span: String, occurrence: Long?): Long {
    val row = row(eventId) ?: throw notFound(eventId)
    if (row.calendarId.toString() != input.calendarId) {
      throw CalendarException("E_CALENDAR_CHANGE", "An event cannot move to another calendar: delete it and create it there")
    }
    check(input)
    return when {
      // an exception is one occurrence, whatever rule JavaScript read along with it
      row.isException -> updateException(row, input)
      occurrence == null || span == "all" || !row.recurs -> updateAll(row, input)
      span == "this" -> updateOccurrence(row, input, occurrence)
      else -> updateFuture(row, input, occurrence)
    }
  }

  /** Deletes the event or a part of a series; false when there is no such event or occurrence */
  fun delete(eventId: String, span: String, occurrence: Long?): Boolean {
    val row = row(eventId) ?: return false
    return when {
      row.isException -> cancel(row)
      occurrence == null || span == "all" || !row.recurs -> deleteWhole(row)
      span == "this" -> deleteOccurrence(row, occurrence)
      else -> deleteFuture(row, occurrence)
    }
  }

  /** The attendees of the event, or of the occurrence's exception; none when there is no such event */
  fun attendees(eventId: String, occurrence: Long?): List<AttendeeRow> {
    val row = row(eventId) ?: return emptyList()
    val target = if (occurrence == null) row else findOccurrence(row, occurrence) ?: return emptyList()
    return resolver.rows(Attendees.CONTENT_URI, ATTENDEE_COLUMNS, "${Attendees.EVENT_ID}=?", arrayOf(target.id.toString())) {
      AttendeeRow(
        name = string(Attendees.ATTENDEE_NAME),
        email = string(Attendees.ATTENDEE_EMAIL),
        relationship = int(Attendees.ATTENDEE_RELATIONSHIP),
        type = int(Attendees.ATTENDEE_TYPE),
        status = int(Attendees.ATTENDEE_STATUS),
        ownerAccount = row.ownerAccount,
      )
    }
  }

  private fun updateAll(row: EventRow, input: EventInput): Long {
    val kept = RecurrenceRules.keptOnUpdate(row.rrule, input.rule)
    val ops = arrayListOf(updateOp(eventUri(row.id), seriesValues(input, kept)))
    // without its rule the series' occurrences are gone, and with them their exceptions
    if (row.recurs && input.rule == null && kept == null) ops += deleteOp(Events.CONTENT_URI, "${Events.ORIGINAL_ID}=?", arrayOf(row.id.toString()))
    ops.setAlarms(row.id, input.alarms)
    applyBatch(ops)
    return row.id
  }

  private fun updateException(row: EventRow, input: EventInput): Long {
    val times = input.times(zone)
    val values = baseValues(input, times).apply {
      put(Events.DTEND, times.end)
      putNull(Events.DURATION)
    }
    val ops = arrayListOf(updateOp(eventUri(row.id), values))
    ops.setAlarms(row.id, input.alarms)
    applyBatch(ops)
    return row.id
  }

  /** Changes one occurrence: its exception, or a new one */
  private fun updateOccurrence(series: EventRow, input: EventInput, occurrence: Long): Long {
    val target = findOccurrence(series, occurrence) ?: throw notFound(series.id.toString())
    if (target.isException) return updateException(target, input)
    // the provider copies the series into the exception and computes DTEND from DURATION; it refuses DTEND itself. Android shows the
    // exception right only once the series has a sync id (see syncIdOps)
    val times = input.times(zone)
    val values = baseValues(input, times).apply {
      put(Events.DURATION, times.duration)
      put(Events.ORIGINAL_INSTANCE_TIME, target.begin)
      // the provider marks a new exception tentative unless it is told otherwise
      put(Events.STATUS, series.status)
    }
    val ops = syncIdOps(series)
    val index = ops.size
    ops += insertOp(exceptionUri(series.id), values)
    // the exception starts with copies of the series' reminders
    ops.setAlarmsOfInserted(index, input.alarms)
    return applyBatch(ops).insertedId(index)
  }

  /** Changes an occurrence and those after it: the series ends before it, and a new series starts from the input */
  private fun updateFuture(series: EventRow, input: EventInput, occurrence: Long): Long {
    val time = series.instanceTime(occurrence, zone)
    if (time <= series.begin) return updateAll(series, input)
    findOccurrence(series, occurrence) ?: throw notFound(series.id.toString())
    val kept = RecurrenceRules.keptOnUpdate(series.rrule, input.rule)
    // a series that counts its occurrences hands on the ones left, unless the input gives the new series an end of its own
    val count = RecurrenceRules.count(series.rrule.orEmpty())
    val handsOnCount = kept != null || input.rule?.let { it.occurrence == count && it.endDate == null } == true
    val left = count?.takeIf { handsOnCount }?.let { it - occurrencesBefore(series, time) }
    val rule = if (left == null) input.rule else input.rule?.copy(occurrence = left)
    val storedRule = if (left == null) kept else kept?.let { RecurrenceRules.withCount(it, left) }
    val values = seriesValues(input.copy(rule = rule), storedRule).apply {
      put(Events.CALENDAR_ID, series.calendarId)
      series.organizer?.let { put(Events.ORGANIZER, it) }
    }
    val ops = syncIdOps(series)
    ops += endBefore(series, time)
    ops += deleteExceptionsFrom(series, time)
    val index = ops.size
    ops += insertEventOp(values, series.accountName, series.accountType)
    // the new series invites the same people
    attendeeValues(series.id).forEach {
      ops += ContentProviderOperation.newInsert(Attendees.CONTENT_URI).withValues(it).withValueBackReference(Attendees.EVENT_ID, index).build()
    }
    ops.setAlarmsOfInserted(index, input.alarms)
    return applyBatch(ops).insertedId(index)
  }

  /** Cancels a changed occurrence, which takes it out of its series */
  private fun cancel(exception: EventRow): Boolean {
    if (exception.status == Codes.STATUS_CANCELED) return false
    val values = ContentValues().apply { put(Events.STATUS, Codes.STATUS_CANCELED) }
    return resolver.update(eventUri(exception.id), values, null, null) > 0
  }

  /** Deletes one occurrence: cancels its exception, or adds a canceled one */
  private fun deleteOccurrence(series: EventRow, occurrence: Long): Boolean {
    val target = findOccurrence(series, occurrence) ?: return false
    if (target.isException) return cancel(target)
    val values = ContentValues().apply {
      put(Events.ORIGINAL_INSTANCE_TIME, target.begin)
      put(Events.STATUS, Codes.STATUS_CANCELED)
    }
    val ops = syncIdOps(series)
    ops += insertOp(exceptionUri(series.id), values)
    applyBatch(ops)
    return true
  }

  private fun deleteFuture(series: EventRow, occurrence: Long): Boolean {
    val time = series.instanceTime(occurrence, zone)
    if (time <= series.begin) return deleteWhole(series)
    val ops = syncIdOps(series)
    ops += endBefore(series, time)
    ops += deleteExceptionsFrom(series, time)
    applyBatch(ops)
    return true
  }

  /**
   * Deletes the event with its exceptions. Android only marks an event that has a sync id deleted, for its sync adapter to pass the
   * deletion on and remove it; as the sync adapter of a local account the app removes it itself.
   */
  private fun deleteWhole(row: EventRow): Boolean {
    if (!row.isLocal || row.syncId.isNullOrEmpty()) return resolver.delete(eventUri(row.id), null, null) > 0
    val results = applyBatch(
      arrayListOf(
        deleteOp(localAccountUri(Events.CONTENT_URI, row.accountName), "${Events.ORIGINAL_ID}=?", arrayOf(row.id.toString())),
        ContentProviderOperation.newDelete(localAccountUri(eventUri(row.id), row.accountName)).build(),
      ),
    )
    return (results[1].count ?: 0) > 0
  }

  /**
   * Android expands a series' exceptions with it only when the series has a sync id: for one without, CalendarInstancesHelper expands
   * the series alone, and an exception can neither replace nor cancel an occurrence. A series of a local account without one gets it
   * here, from the app as that account's sync adapter, and its exceptions get it as their ORIGINAL_SYNC_ID. A series of another account
   * gets its sync id from that account's sync adapter; until it has synced, Android shows its exceptions wrongly.
   */
  private fun syncIdOps(series: EventRow): ArrayList<ContentProviderOperation> {
    if (!series.isLocal || !series.syncId.isNullOrEmpty()) return arrayListOf()
    val syncId = UUID.randomUUID().toString()
    return arrayListOf(
      updateOp(localAccountUri(eventUri(series.id), series.accountName), ContentValues().apply { put(Events._SYNC_ID, syncId) }),
      ContentProviderOperation.newUpdate(localAccountUri(Events.CONTENT_URI, series.accountName))
        .withSelection("${Events.ORIGINAL_ID}=?", arrayOf(series.id.toString()))
        .withValue(Events.ORIGINAL_SYNC_ID, syncId)
        .build(),
    )
  }

  /** Inserts an event of its own row; one of a local account with a sync id, as that account's sync adapter (see [syncIdOps]) */
  private fun insertEventOp(values: ContentValues, accountName: String, accountType: String?): ContentProviderOperation =
    if (accountType == CalendarContract.ACCOUNT_TYPE_LOCAL) {
      values.put(Events._SYNC_ID, UUID.randomUUID().toString())
      insertOp(localAccountUri(Events.CONTENT_URI, accountName), values)
    } else {
      insertOp(Events.CONTENT_URI, values)
    }

  private val EventRow.isLocal: Boolean
    get() = accountType == CalendarContract.ACCOUNT_TYPE_LOCAL

  /** Ends the series with the last occurrence before [time]; with DTSTART, as the provider recomputes the occurrences only then */
  private fun endBefore(series: EventRow, time: Long) = updateOp(
    eventUri(series.id),
    ContentValues().apply {
      put(Events.RRULE, RecurrenceRules.endingBefore(series.rrule.orEmpty(), time, series.allDay))
      put(Events.DTSTART, series.begin)
    },
  )

  private fun deleteExceptionsFrom(series: EventRow, time: Long) = deleteOp(
    Events.CONTENT_URI,
    "${Events.ORIGINAL_ID}=? AND ${Events.ORIGINAL_INSTANCE_TIME}>=?",
    arrayOf(series.id.toString(), time.toString()),
  )

  /** How many occurrences the series' rule gives before [time], changed and canceled ones included, as COUNT counts them */
  private fun occurrencesBefore(series: EventRow, time: Long): Int {
    val id = series.id.toString()
    val unchanged = instances(series.begin, time - 1, "${Instances.EVENT_ID}=?", arrayOf(id)).size
    val exceptions = resolver.rows(
      Events.CONTENT_URI,
      arrayOf(Events._ID),
      "${Events.ORIGINAL_ID}=? AND ${Events.ORIGINAL_INSTANCE_TIME}<? AND ${Events.DELETED}=0",
      arrayOf(id, time.toString()),
    ) { long(Events._ID) }.size
    return unchanged + exceptions
  }

  /** The attendee rows of an event, as values for another event */
  private fun attendeeValues(eventId: Long): List<ContentValues> =
    resolver.rows(Attendees.CONTENT_URI, COPIED_ATTENDEE_COLUMNS, "${Attendees.EVENT_ID}=?", arrayOf(eventId.toString())) {
      ContentValues().also { DatabaseUtils.cursorRowToContentValues(this, it) }
    }

  /** The row of the event, unless it is deleted */
  private fun row(eventId: String): EventRow? = eventId.toLongOrNull()?.let { id ->
    resolver.rows(eventUri(id), EVENT_COLUMNS, "${Events.DELETED}=0") { eventRow(instance = false) }.firstOrNull()
  }

  /** The occurrence of [series] that starts at [occurrence] by the rule: its exception, else its instance; null when there is none or it was deleted */
  private fun findOccurrence(series: EventRow, occurrence: Long): EventRow? {
    val time = series.instanceTime(occurrence, zone)
    val exception = resolver.rows(
      Events.CONTENT_URI,
      EVENT_COLUMNS,
      "${Events.ORIGINAL_ID}=? AND ${Events.ORIGINAL_INSTANCE_TIME}=? AND ${Events.DELETED}=0",
      arrayOf(series.id.toString(), time.toString()),
    ) { eventRow(instance = false) }.firstOrNull()
    if (exception != null) return exception.takeIf { it.status != Codes.STATUS_CANCELED }
    return instances(time - Days.MILLIS, time + Days.MILLIS, "${Instances.EVENT_ID}=?", arrayOf(series.id.toString()))
      .firstOrNull { it.begin == time }
  }

  private fun instances(begin: Long, end: Long, selection: String?, args: Array<String>?): List<EventRow> {
    val uri = Instances.CONTENT_URI.buildUpon().also {
      ContentUris.appendId(it, begin)
      ContentUris.appendId(it, end)
    }.build()
    return resolver.rows(uri, INSTANCE_COLUMNS, selection, args, "${Instances.BEGIN} ASC") { eventRow(instance = true) }
  }

  /** Adds the alarms of the rows and the rules of the exceptions' series, each read in one query rather than per row */
  private fun details(rows: List<EventRow>): List<EventDetails> {
    val alarms = byIds(Reminders.CONTENT_URI, Reminders.EVENT_ID, rows.map { it.id }, REMINDER_COLUMNS) {
      // MINUTES_DEFAULT leaves the time to the calendar app, which the contract cannot express
      int(Reminders.MINUTES).takeIf { it != Reminders.MINUTES_DEFAULT }?.let { minutes ->
        long(Reminders.EVENT_ID) to Alarm(-minutes, Codes.alarmMethod(int(Reminders.METHOD)))
      }
    }.filterNotNull().groupBy({ it.first }, { it.second })
    val rules = byIds(Events.CONTENT_URI, Events._ID, rows.mapNotNull { it.originalId }, arrayOf(Events._ID, Events.RRULE)) {
      long(Events._ID) to string(Events.RRULE)
    }.toMap()
    return rows.map { EventDetails(it, it.rrule ?: it.originalId?.let(rules::get), alarms[it.id].orEmpty()) }
  }

  /** The rows whose [column] holds one of the ids, queried in chunks */
  private inline fun <T> byIds(uri: Uri, column: String, ids: List<Long>, columns: Array<String>, read: Cursor.() -> T): List<T> =
    ids.distinct().chunked(CHUNK).flatMap { chunk -> resolver.rows(uri, columns, "$column IN (${chunk.joinToString(",")})", read = read) }

  private fun check(input: EventInput) {
    if (input.alarms.any { it.relativeOffset > 0 }) throw CalendarException("E_UNSUPPORTED", "Android alarms ring before the start")
    if (!input.allDay) input.timeZone?.let(::knownZone)
  }

  /** The columns every write of an event sets */
  private fun baseValues(input: EventInput, times: EventTimes) = ContentValues().apply {
    put(Events.TITLE, input.title)
    put(Events.EVENT_LOCATION, input.location)
    put(Events.DESCRIPTION, input.notes)
    put(Events.ALL_DAY, if (input.allDay) 1 else 0)
    put(Events.AVAILABILITY, Codes.availabilityOf(input.availability))
    put(Events.DTSTART, times.start)
    put(Events.EVENT_TIMEZONE, times.timeZone)
  }

  /**
   * An event of its own row: Android requires RRULE with DURATION for a recurring one, DTEND for any other. [storedRule] is the RRULE
   * kept when the input has no rule (see [RecurrenceRules.keptOnUpdate]).
   */
  private fun seriesValues(input: EventInput, storedRule: String? = null): ContentValues {
    val zone = zone
    val times = input.times(zone)
    val rrule = input.rule?.let { RecurrenceRules.format(it, input.allDay, zone) } ?: storedRule
    return baseValues(input, times).apply {
      if (rrule == null) {
        put(Events.DTEND, times.end)
        listOf(Events.DURATION, Events.RRULE, Events.RDATE, Events.EXRULE, Events.EXDATE).forEach { putNull(it) }
      } else {
        putNull(Events.DTEND)
        put(Events.DURATION, times.duration)
        put(Events.RRULE, rrule)
      }
    }
  }

  /** Replaces the reminders of an existing event with the alarms */
  private fun ArrayList<ContentProviderOperation>.setAlarms(eventId: Long, alarms: List<Alarm>) {
    add(deleteOp(Reminders.CONTENT_URI, REMINDERS_OF_EVENT, arrayOf(eventId.toString())))
    alarms.forEach { add(ContentProviderOperation.newInsert(Reminders.CONTENT_URI).withValues(reminder(it)).withValue(Reminders.EVENT_ID, eventId).build()) }
  }

  /** Replaces the reminders of the event that the operation at [index] inserts */
  private fun ArrayList<ContentProviderOperation>.setAlarmsOfInserted(index: Int, alarms: List<Alarm>) {
    add(
      ContentProviderOperation.newDelete(Reminders.CONTENT_URI)
        .withSelection(REMINDERS_OF_EVENT, arrayOf(""))
        .withSelectionBackReference(0, index)
        .build(),
    )
    alarms.forEach {
      add(ContentProviderOperation.newInsert(Reminders.CONTENT_URI).withValues(reminder(it)).withValueBackReference(Reminders.EVENT_ID, index).build())
    }
  }

  private fun reminder(alarm: Alarm) = ContentValues().apply {
    put(Reminders.MINUTES, -alarm.relativeOffset)
    put(Reminders.METHOD, Codes.alarmMethodOf(alarm.method))
  }

  private fun applyBatch(ops: ArrayList<ContentProviderOperation>) = resolver.applyBatch(CalendarContract.AUTHORITY, ops)

  /** The id of the event that the operation at [index] inserted */
  private fun Array<ContentProviderResult>.insertedId(index: Int): Long =
    this[index].uri?.let(ContentUris::parseId) ?: throw CalendarException("E_CALENDAR", "The event was not saved")

  private fun insertOp(uri: Uri, values: ContentValues) = ContentProviderOperation.newInsert(uri).withValues(values).build()

  private fun updateOp(uri: Uri, values: ContentValues) = ContentProviderOperation.newUpdate(uri).withValues(values).build()

  private fun deleteOp(uri: Uri, selection: String, args: Array<String>) =
    ContentProviderOperation.newDelete(uri).withSelection(selection, args).build()

  private fun eventUri(id: Long) = ContentUris.withAppendedId(Events.CONTENT_URI, id)

  private fun exceptionUri(seriesId: Long) = ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, seriesId)

  private fun notFound(eventId: String) = CalendarException("E_NOT_FOUND", "There is no event $eventId")

  /** The event of the cursor's row of the Instances table or of the Events table */
  private fun Cursor.eventRow(instance: Boolean): EventRow {
    val begin = long(if (instance) Instances.BEGIN else Events.DTSTART)
    return EventRow(
      id = long(if (instance) Instances.EVENT_ID else Events._ID),
      calendarId = long(Events.CALENDAR_ID),
      title = string(Events.TITLE) ?: "",
      begin = begin,
      // a series has no end of its own but the duration of each occurrence
      end = if (instance) long(Instances.END) else longOrNull(Events.DTEND) ?: (begin + Durations.millis(string(Events.DURATION))),
      allDay = int(Events.ALL_DAY) == 1,
      location = string(Events.EVENT_LOCATION),
      notes = string(Events.DESCRIPTION),
      timeZone = string(Events.EVENT_TIMEZONE),
      availability = intOrNull(Events.AVAILABILITY),
      status = intOrNull(Events.STATUS),
      rrule = string(Events.RRULE)?.takeIf { it.isNotBlank() },
      // ORIGINAL_ID is a text column
      originalId = string(Events.ORIGINAL_ID)?.toLongOrNull(),
      originalInstanceTime = longOrNull(Events.ORIGINAL_INSTANCE_TIME),
      originalAllDay = int(Events.ORIGINAL_ALL_DAY) == 1,
      organizer = string(Events.ORGANIZER)?.takeIf { it.isNotBlank() },
      isOrganizer = int(Events.IS_ORGANIZER) == 1,
      ownerAccount = string(Events.OWNER_ACCOUNT),
      syncId = string(Events._SYNC_ID),
      accountName = string(Events.ACCOUNT_NAME) ?: "",
      accountType = string(Events.ACCOUNT_TYPE) ?: "",
    )
  }

  private companion object {
    const val CHUNK = 500
    const val REMINDERS_OF_EVENT = "${Reminders.EVENT_ID}=?"

    val SHARED_COLUMNS = arrayOf(
      Events.CALENDAR_ID,
      Events.TITLE,
      Events.ALL_DAY,
      Events.EVENT_LOCATION,
      Events.DESCRIPTION,
      Events.EVENT_TIMEZONE,
      Events.AVAILABILITY,
      Events.STATUS,
      Events.RRULE,
      Events.ORIGINAL_ID,
      Events.ORIGINAL_INSTANCE_TIME,
      Events.ORIGINAL_ALL_DAY,
      Events.ORGANIZER,
      Events.IS_ORGANIZER,
      Events.OWNER_ACCOUNT,
      Events._SYNC_ID,
      Events.ACCOUNT_NAME,
      Events.ACCOUNT_TYPE,
    )
    val EVENT_COLUMNS = arrayOf(Events._ID, Events.DTSTART, Events.DTEND, Events.DURATION) + SHARED_COLUMNS
    val INSTANCE_COLUMNS = arrayOf(Instances.EVENT_ID, Instances.BEGIN, Instances.END) + SHARED_COLUMNS
    val REMINDER_COLUMNS = arrayOf(Reminders.EVENT_ID, Reminders.MINUTES, Reminders.METHOD)
    val ATTENDEE_COLUMNS = arrayOf(
      Attendees.ATTENDEE_NAME,
      Attendees.ATTENDEE_EMAIL,
      Attendees.ATTENDEE_RELATIONSHIP,
      Attendees.ATTENDEE_TYPE,
      Attendees.ATTENDEE_STATUS,
    )
    val COPIED_ATTENDEE_COLUMNS = ATTENDEE_COLUMNS + arrayOf(Attendees.ATTENDEE_IDENTITY, Attendees.ATTENDEE_ID_NAMESPACE)
  }
}
