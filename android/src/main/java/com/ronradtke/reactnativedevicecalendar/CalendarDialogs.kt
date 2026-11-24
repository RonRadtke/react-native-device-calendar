package com.ronradtke.reactnativedevicecalendar

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap

/** The calendar app's screens, opened by intents; Android tells nothing of what the person does there */
internal class CalendarDialogs(private val context: ReactApplicationContext) {
  /** Shows the event, or with [occurrence] (its exception or instance) that occurrence */
  fun view(eventId: String, occurrence: EventRow?) = start(eventIntent(Intent.ACTION_VIEW, eventId, occurrence))

  fun edit(eventId: String, occurrence: EventRow?) = start(eventIntent(Intent.ACTION_EDIT, eventId, occurrence))

  /** The editor for a new event, with the fields of NativeDialogEvent that are set; needs no permission */
  fun create(event: ReadableMap) = start(
    Intent(Intent.ACTION_INSERT, Events.CONTENT_URI).apply {
      event.optString("title")?.let { putExtra(Events.TITLE, it) }
      event.optString("location")?.let { putExtra(Events.EVENT_LOCATION, it) }
      event.optString("notes")?.let { putExtra(Events.DESCRIPTION, it) }
      event.optLong("startDate")?.let { putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
      event.optLong("endDate")?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
      if (event.hasKey("allDay") && !event.isNull("allDay")) putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, event.getBoolean("allDay"))
      event.optString("calendarId")?.toLongOrNull()?.let { putExtra(Events.CALENDAR_ID, it) }
    },
  )

  private fun eventIntent(action: String, eventId: String, occurrence: EventRow?): Intent {
    val uri = Events.CONTENT_URI.buildUpon().appendPath(occurrence?.id?.toString() ?: eventId).build()
    return Intent(action, uri).apply {
      if (occurrence != null) {
        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, occurrence.begin)
        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, occurrence.end)
      }
    }
  }

  /** Starts the calendar app from the app's activity, or as a task of its own without one */
  private fun start(intent: Intent) {
    val activity = context.currentActivity
    try {
      if (activity != null) activity.startActivity(intent) else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
      throw CalendarException("E_UNSUPPORTED", "There is no calendar app for this")
    }
  }
}
