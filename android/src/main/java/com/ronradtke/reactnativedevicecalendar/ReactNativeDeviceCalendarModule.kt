package com.ronradtke.reactnativedevicecalendar

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import java.util.TimeZone

/** The device calendars through CalendarContract (NativeReactNativeDeviceCalendar.ts) */
class ReactNativeDeviceCalendarModule(reactContext: ReactApplicationContext) :
  NativeReactNativeDeviceCalendarSpec(reactContext) {

  private val permission = CalendarPermission(reactContext)
  private val calendars = CalendarStore(reactContext)
  private val events = EventStore(reactContext)
  private val dialogs = CalendarDialogs(reactContext)

  override fun getPermissionStatus(promise: Promise) = promise.resolve(permission.status())

  // write-only access exists on iOS only
  override fun requestPermission(writeOnly: Boolean, promise: Promise) = permission.request(promise)

  override fun getSources(promise: Promise) = guarded(promise) {
    promise.resolve(Mapper.sources(calendars.sources()))
  }

  override fun getCalendars(promise: Promise) = guarded(promise) {
    promise.resolve(Mapper.calendars(calendars.calendars()))
  }

  // Android has no default calendar; isPrimary marks the main calendar of each account
  override fun getDefaultCalendar(promise: Promise) = promise.resolve(null)

  override fun createCalendar(calendar: ReadableMap, promise: Promise) = guarded(promise) {
    promise.resolve(calendars.create(Mapper.calendarInput(calendar)).toString())
  }

  override fun updateCalendar(calendarId: String, changes: ReadableMap, promise: Promise) = guarded(promise) {
    calendars.update(calendarId, changes.optString("title"), changes.optString("color"))
    promise.resolve(null)
  }

  override fun deleteCalendar(calendarId: String, promise: Promise) = guarded(promise) {
    promise.resolve(calendars.delete(calendarId))
  }

  override fun getEvents(startDate: Double, endDate: Double, calendarIds: ReadableArray, promise: Promise) = guarded(promise) {
    val found = events.inPeriod(startDate.toLong(), endDate.toLong(), Mapper.strings(calendarIds))
    promise.resolve(Mapper.events(found, TimeZone.getDefault()))
  }

  override fun getEvent(eventId: String, occurrence: Double?, promise: Promise) = guarded(promise) {
    promise.resolve(events.event(eventId, occurrence?.toLong())?.let { Mapper.event(it, TimeZone.getDefault()) })
  }

  override fun createEvent(event: ReadableMap, promise: Promise) = guarded(promise) {
    promise.resolve(events.create(Mapper.eventInput(event)).toString())
  }

  override fun updateEvent(eventId: String, event: ReadableMap, span: String, occurrence: Double?, promise: Promise) = guarded(promise) {
    promise.resolve(events.update(eventId, Mapper.eventInput(event), span, occurrence?.toLong()).toString())
  }

  override fun deleteEvent(eventId: String, span: String, occurrence: Double?, promise: Promise) = guarded(promise) {
    promise.resolve(events.delete(eventId, span, occurrence?.toLong()))
  }

  override fun getAttendees(eventId: String, occurrence: Double?, promise: Promise) = guarded(promise) {
    promise.resolve(Mapper.attendees(events.attendees(eventId, occurrence?.toLong())))
  }

  // Android leaves editing to the calendar app
  override fun openEvent(eventId: String, occurrence: Double?, allowsEditing: Boolean, promise: Promise) = guarded(promise) {
    dialogs.view(eventId, occurrence?.let { events.occurrenceRow(eventId, it.toLong()) })
    promise.resolve("done")
  }

  override fun createEventWithDialog(event: ReadableMap, promise: Promise) = guarded(promise) {
    dialogs.create(event)
    promise.resolve(Mapper.dialogResult(null))
  }

  override fun editEventWithDialog(eventId: String, occurrence: Double?, promise: Promise) = guarded(promise) {
    dialogs.edit(eventId, occurrence?.let { events.occurrenceRow(eventId, it.toLong()) })
    promise.resolve(Mapper.dialogResult(eventId))
  }

  /** Runs a call and turns a failure into a rejection with its code */
  private inline fun guarded(promise: Promise, block: () -> Unit) {
    try {
      block()
    } catch (e: CalendarException) {
      promise.reject(e.code, e.message, e)
    } catch (e: SecurityException) {
      promise.reject("E_PERMISSION", "The app has no access to the calendars", e)
    } catch (e: Exception) {
      promise.reject("E_CALENDAR", e.message ?: e.toString(), e)
    }
  }

  companion object {
    const val NAME = NativeReactNativeDeviceCalendarSpec.NAME
  }
}
