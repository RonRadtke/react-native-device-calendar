package com.ronradtke.reactnativedevicecalendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.PermissionAwareActivity
import com.facebook.react.modules.core.PermissionListener
import java.util.Locale
import java.util.TimeZone

/** The device calendars through CalendarContract */
class ReactNativeDeviceCalendarModule(reactContext: ReactApplicationContext) :
  NativeReactNativeDeviceCalendarSpec(reactContext) {

  private val resolver
    get() = reactApplicationContext.contentResolver

  private fun granted(): Boolean =
    PERMISSIONS.all { reactApplicationContext.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

  // Android tells a refusal from a person not yet asked only by asking, so everything but "granted" is "undetermined" here
  override fun getPermissionStatus(promise: Promise) {
    promise.resolve(if (granted()) "granted" else "undetermined")
  }

  override fun requestPermission(promise: Promise) {
    if (granted()) return promise.resolve("granted")
    val activity = reactApplicationContext.currentActivity as? PermissionAwareActivity
      ?: return promise.reject("E_NO_ACTIVITY", "There is no activity to ask for the calendar permission")
    val listener = PermissionListener { requestCode, _, results ->
      if (requestCode != REQUEST_CODE) return@PermissionListener false
      val all = results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }
      promise.resolve(if (all) "granted" else "denied")
      true
    }
    UiThreadUtil.runOnUiThread { activity.requestPermissions(PERMISSIONS, REQUEST_CODE, listener) }
  }

  override fun getCalendars(promise: Promise) = guarded(promise) {
    val calendars = Arguments.createArray()
    resolver.query(Calendars.CONTENT_URI, CALENDAR_COLUMNS, null, null, null)?.use { cursor ->
      while (cursor.moveToNext()) calendars.pushMap(calendarOf(cursor))
    }
    promise.resolve(calendars)
  }

  override fun createCalendar(calendar: ReadableMap, promise: Promise) = guarded(promise) {
    val title = calendar.getString("title") ?: ""
    val account = optString(calendar, "accountName") ?: appName()
    val values = ContentValues().apply {
      put(Calendars.ACCOUNT_NAME, account)
      put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
      put(Calendars.NAME, title)
      put(Calendars.CALENDAR_DISPLAY_NAME, title)
      put(Calendars.CALENDAR_COLOR, Color.parseColor(calendar.getString("color")))
      put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
      put(Calendars.OWNER_ACCOUNT, account)
      put(Calendars.VISIBLE, 1)
      put(Calendars.SYNC_EVENTS, 1)
      put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
    }
    val uri = resolver.insert(asLocalAccount(Calendars.CONTENT_URI, account), values)
      ?: return@guarded promise.reject("E_CALENDAR", "The calendar was not created")
    promise.resolve(uri.lastPathSegment)
  }

  // only a calendar of a local account belongs to the apps on the device; the others belong to their accounts
  override fun deleteCalendar(calendarId: String, promise: Promise) = guarded(promise) {
    val id = calendarId.toLongOrNull() ?: return@guarded promise.resolve(false)
    val uri = ContentUris.withAppendedId(Calendars.CONTENT_URI, id)
    val account = resolver.query(uri, arrayOf(Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE), null, null, null)?.use { cursor ->
      if (cursor.moveToFirst() && cursor.getString(1) == CalendarContract.ACCOUNT_TYPE_LOCAL) cursor.getString(0) else null
    } ?: return@guarded promise.resolve(false)
    promise.resolve(resolver.delete(asLocalAccount(uri, account), null, null) > 0)
  }

  override fun getEvents(startDate: Double, endDate: Double, calendarIds: ReadableArray, promise: Promise) = guarded(promise) {
    val start = startDate.toLong()
    val end = endDate.toLong()
    val zone = TimeZone.getDefault()
    // all-day occurrences lie on midnights UTC: a day more on each side finds those of the first and last local day
    val uri = Instances.CONTENT_URI.buildUpon().also {
      ContentUris.appendId(it, start - DAY)
      ContentUris.appendId(it, end + DAY)
    }.build()
    val ids = (0 until calendarIds.size()).mapNotNull { calendarIds.getString(it) }
    val selection = if (ids.isEmpty()) null else "${Instances.CALENDAR_ID} IN (${ids.joinToString(",") { "?" }})"
    val events = Arguments.createArray()
    resolver.query(uri, INSTANCE_COLUMNS, selection, if (ids.isEmpty()) null else ids.toTypedArray(), "${Instances.BEGIN} ASC")?.use { cursor ->
      while (cursor.moveToNext()) {
        val allDay = cursor.getInt(5) == 1
        val begin = if (allDay) Days.localMidnightOf(cursor.getLong(3), zone) else cursor.getLong(3)
        val finish = if (allDay) Days.localMidnightOf(cursor.getLong(4), zone) else cursor.getLong(4)
        // the extra days bring occurrences outside the period; an event without duration counts at its start
        if (begin >= end || (finish <= start && begin < start)) continue
        events.pushMap(Arguments.createMap().apply {
          putString("id", cursor.getLong(0).toString())
          putString("calendarId", cursor.getLong(1).toString())
          putString("title", cursor.getString(2) ?: "")
          putDouble("startDate", begin.toDouble())
          putDouble("endDate", finish.toDouble())
          putBoolean("allDay", allDay)
          putOptString("location", cursor, 6)
          putOptString("notes", cursor, 7)
        })
      }
    }
    promise.resolve(events)
  }

  override fun createEvent(event: ReadableMap, promise: Promise) = guarded(promise) {
    val calendarId = event.getString("calendarId")?.toLongOrNull()
      ?: return@guarded promise.reject("E_NOT_FOUND", "There is no calendar ${event.getString("calendarId")}")
    val values = eventValues(event).apply { put(Events.CALENDAR_ID, calendarId) }
    val uri = resolver.insert(Events.CONTENT_URI, values)
      ?: return@guarded promise.reject("E_CALENDAR", "The event was not created")
    promise.resolve(uri.lastPathSegment)
  }

  override fun updateEvent(eventId: String, event: ReadableMap, promise: Promise) = guarded(promise) {
    val uri = eventId.toLongOrNull()?.let { ContentUris.withAppendedId(Events.CONTENT_URI, it) }
    // the calendar of the event, and whether it recurs
    val existing = uri?.let {
      resolver.query(it, arrayOf(Events.CALENDAR_ID, Events.DELETED, Events.RRULE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && cursor.getInt(1) == 0) cursor.getLong(0).toString() to !cursor.getString(2).isNullOrEmpty() else null
      }
    } ?: return@guarded promise.reject("E_NOT_FOUND", "There is no event $eventId")
    if (existing.first != event.getString("calendarId")) {
      return@guarded promise.reject("E_CALENDAR_CHANGE", "An event cannot move to another calendar: delete it and create it there")
    }
    if (existing.second) return@guarded promise.reject("E_RECURRING", "A recurring event cannot be changed")
    resolver.update(uri, eventValues(event), null, null)
    promise.resolve(null)
  }

  override fun deleteEvent(eventId: String, promise: Promise) = guarded(promise) {
    val id = eventId.toLongOrNull() ?: return@guarded promise.resolve(false)
    promise.resolve(resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, id), null, null) > 0)
  }

  /** Runs a call and turns a failure into a rejection, a missing permission into its own code */
  private inline fun guarded(promise: Promise, block: () -> Unit) {
    try {
      block()
    } catch (e: SecurityException) {
      promise.reject("E_PERMISSION", "The app has no access to the calendars", e)
    } catch (e: Exception) {
      promise.reject("E_CALENDAR", e.message ?: e.toString(), e)
    }
  }

  private fun calendarOf(cursor: Cursor): WritableMap = Arguments.createMap().apply {
    putString("id", cursor.getLong(0).toString())
    putString("title", cursor.getString(1) ?: "")
    putString("color", String.format(Locale.ROOT, "#%06X", 0xFFFFFF and cursor.getInt(2)))
    putString("source", cursor.getString(3) ?: "")
    putString("sourceType", cursor.getString(4) ?: "")
    putBoolean("isPrimary", cursor.getInt(5) == 1)
    putBoolean("allowsModifications", cursor.getInt(6) >= Calendars.CAL_ACCESS_CONTRIBUTOR)
  }

  /** The columns of an event; an all-day event from midnight UTC of its first day to midnight UTC after its last, as Android requires */
  private fun eventValues(event: ReadableMap) = ContentValues().apply {
    val allDay = event.getBoolean("allDay")
    val start = event.getDouble("startDate").toLong()
    val end = event.getDouble("endDate").toLong()
    put(Events.TITLE, event.getString("title"))
    put(Events.EVENT_LOCATION, optString(event, "location"))
    put(Events.DESCRIPTION, optString(event, "notes"))
    put(Events.ALL_DAY, if (allDay) 1 else 0)
    if (allDay) {
      val zone = TimeZone.getDefault()
      put(Events.DTSTART, Days.utcMidnightOf(start, zone))
      put(Events.DTEND, Days.utcMidnightOf(end, zone))
      put(Events.EVENT_TIMEZONE, "UTC")
    } else {
      put(Events.DTSTART, start)
      put(Events.DTEND, end)
      put(Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
    }
  }

  private fun optString(map: ReadableMap, key: String): String? =
    if (map.hasKey(key) && !map.isNull(key)) map.getString(key) else null

  private fun WritableMap.putOptString(key: String, cursor: Cursor, column: Int) {
    if (cursor.isNull(column)) putNull(key) else putString(key, cursor.getString(column))
  }

  private fun appName(): String =
    reactApplicationContext.applicationInfo.loadLabel(reactApplicationContext.packageManager).toString()

  /** The URI as the sync adapter of a local account, the only way an app may create and delete calendars */
  private fun asLocalAccount(uri: Uri, account: String): Uri = uri.buildUpon()
    .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
    .appendQueryParameter(Calendars.ACCOUNT_NAME, account)
    .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
    .build()

  companion object {
    const val NAME = NativeReactNativeDeviceCalendarSpec.NAME
    private const val REQUEST_CODE = 0xCA1
    private const val DAY = 24L * 60 * 60 * 1000
    private val PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    private val CALENDAR_COLUMNS = arrayOf(
      Calendars._ID,
      Calendars.CALENDAR_DISPLAY_NAME,
      Calendars.CALENDAR_COLOR,
      Calendars.ACCOUNT_NAME,
      Calendars.ACCOUNT_TYPE,
      Calendars.IS_PRIMARY,
      Calendars.CALENDAR_ACCESS_LEVEL,
    )
    private val INSTANCE_COLUMNS = arrayOf(
      Instances.EVENT_ID,
      Instances.CALENDAR_ID,
      Instances.TITLE,
      Instances.BEGIN,
      Instances.END,
      Instances.ALL_DAY,
      Instances.EVENT_LOCATION,
      Instances.DESCRIPTION,
    )
  }
}
