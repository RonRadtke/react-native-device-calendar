package com.ronradtke.reactnativedevicecalendar

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import java.util.TimeZone

/** The calendars of the device and the accounts that hold them */
internal class CalendarStore(private val context: Context) {
  private val resolver
    get() = context.contentResolver

  /** Each account that holds a calendar once, as its type and name */
  fun sources(): List<Pair<String, String>> =
    resolver.rows(Calendars.CONTENT_URI, arrayOf(Calendars.ACCOUNT_TYPE, Calendars.ACCOUNT_NAME)) {
      (string(Calendars.ACCOUNT_TYPE) ?: "") to (string(Calendars.ACCOUNT_NAME) ?: "")
    }.distinct()

  fun calendars(): List<CalendarRow> = resolver.rows(Calendars.CONTENT_URI, COLUMNS) {
    CalendarRow(
      id = long(Calendars._ID),
      title = string(Calendars.CALENDAR_DISPLAY_NAME) ?: "",
      color = int(Calendars.CALENDAR_COLOR),
      accountName = string(Calendars.ACCOUNT_NAME) ?: "",
      accountType = string(Calendars.ACCOUNT_TYPE) ?: "",
      isPrimary = int(Calendars.IS_PRIMARY) == 1,
      accessLevel = int(Calendars.CALENDAR_ACCESS_LEVEL),
      isVisible = int(Calendars.VISIBLE) == 1,
      timeZone = string(Calendars.CALENDAR_TIME_ZONE),
      allowedAvailability = string(Calendars.ALLOWED_AVAILABILITY),
    )
  }

  fun create(calendar: CalendarInput): Long {
    val account = calendar.accountName ?: appName()
    val values = ContentValues().apply {
      put(Calendars.ACCOUNT_NAME, account)
      put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
      put(Calendars.NAME, calendar.title)
      put(Calendars.CALENDAR_DISPLAY_NAME, calendar.title)
      put(Calendars.CALENDAR_COLOR, Color.parseColor(calendar.color))
      put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
      put(Calendars.OWNER_ACCOUNT, account)
      put(Calendars.VISIBLE, 1)
      put(Calendars.SYNC_EVENTS, 1)
      put(Calendars.CALENDAR_TIME_ZONE, calendar.timeZone?.let(::knownZone) ?: TimeZone.getDefault().id)
    }
    val uri = resolver.insert(localAccountUri(Calendars.CONTENT_URI, account), values)
      ?: throw CalendarException("E_CALENDAR", "The calendar was not created")
    return ContentUris.parseId(uri)
  }

  /** Renames or recolours a calendar; one of a local account as its sync adapter, any other as an app may */
  fun update(calendarId: String, title: String?, color: String?) {
    val notFound = CalendarException("E_NOT_FOUND", "There is no calendar $calendarId")
    val uri = calendarId.toLongOrNull()?.let { ContentUris.withAppendedId(Calendars.CONTENT_URI, it) } ?: throw notFound
    val (name, type) = resolver.accountOf(uri) ?: throw notFound
    val local = type == CalendarContract.ACCOUNT_TYPE_LOCAL
    val values = ContentValues().apply {
      if (title != null) {
        put(Calendars.CALENDAR_DISPLAY_NAME, title)
        if (local) put(Calendars.NAME, title)
      }
      if (color != null) put(Calendars.CALENDAR_COLOR, Color.parseColor(color))
    }
    if (values.size() > 0) resolver.update(if (local) localAccountUri(uri, name) else uri, values, null, null)
  }

  // only a calendar of a local account belongs to the apps on the device; the others belong to their accounts
  fun delete(calendarId: String): Boolean {
    val uri = calendarId.toLongOrNull()?.let { ContentUris.withAppendedId(Calendars.CONTENT_URI, it) } ?: return false
    val (name, type) = resolver.accountOf(uri) ?: return false
    return type == CalendarContract.ACCOUNT_TYPE_LOCAL && resolver.delete(localAccountUri(uri, name), null, null) > 0
  }

  private fun appName(): String = context.applicationInfo.loadLabel(context.packageManager).toString()

  private companion object {
    val COLUMNS = arrayOf(
      Calendars._ID,
      Calendars.CALENDAR_DISPLAY_NAME,
      Calendars.CALENDAR_COLOR,
      Calendars.ACCOUNT_NAME,
      Calendars.ACCOUNT_TYPE,
      Calendars.IS_PRIMARY,
      Calendars.CALENDAR_ACCESS_LEVEL,
      Calendars.VISIBLE,
      Calendars.CALENDAR_TIME_ZONE,
      Calendars.ALLOWED_AVAILABILITY,
    )
  }
}

/** The account name and type of the calendar at [calendarUri]; null when there is no such calendar */
internal fun ContentResolver.accountOf(calendarUri: Uri): Pair<String, String?>? =
  rows(calendarUri, arrayOf(Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE)) {
    (string(Calendars.ACCOUNT_NAME) ?: "") to string(Calendars.ACCOUNT_TYPE)
  }.firstOrNull()

/**
 * The URI as the sync adapter of a local account. Apps may create and delete calendars only so; local accounts have no sync adapter of
 * their own, so the app also writes the sync ids of their events so.
 */
internal fun localAccountUri(uri: Uri, account: String): Uri = uri.buildUpon()
  .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
  .appendQueryParameter(Calendars.ACCOUNT_NAME, account)
  .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
  .build()
