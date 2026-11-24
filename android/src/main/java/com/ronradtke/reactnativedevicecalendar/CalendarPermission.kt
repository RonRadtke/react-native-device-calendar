package com.ronradtke.reactnativedevicecalendar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.modules.core.PermissionAwareActivity
import com.facebook.react.modules.core.PermissionListener

/**
 * Reading and writing the calendars. Android tells a refusal for good from a person not yet asked only by whether it would still show
 * the request, and only after the app asked once: the app remembers that it was refused.
 */
internal class CalendarPermission(private val context: ReactApplicationContext) {
  private val preferences
    get() = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

  fun status(): String = when {
    granted() -> "granted"
    blocked() -> "denied"
    else -> "undetermined"
  }

  /** Asks unless the app has the permission or the system would no longer ask */
  fun request(promise: Promise) {
    if (granted()) return promise.resolve("granted")
    if (blocked()) return promise.resolve("denied")
    val activity = context.currentActivity as? PermissionAwareActivity
      ?: return promise.reject("E_NO_ACTIVITY", "There is no activity to ask for the calendar permission")
    val listener = PermissionListener { requestCode, _, results ->
      if (requestCode != REQUEST_CODE) return@PermissionListener false
      val all = results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }
      preferences.edit().putBoolean(REFUSED, !all).apply()
      promise.resolve(if (all) "granted" else "denied")
      true
    }
    UiThreadUtil.runOnUiThread { activity.requestPermissions(PERMISSIONS, REQUEST_CODE, listener) }
  }

  /** Whether the app may use the calendars; once it may, an earlier refusal no longer counts */
  private fun granted(): Boolean {
    val granted = PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    if (granted) preferences.edit().remove(REFUSED).apply()
    return granted
  }

  /** Refused before, and the system would not show the request again */
  private fun blocked(): Boolean {
    val activity = context.currentActivity ?: return false
    return preferences.getBoolean(REFUSED, false) && !activity.shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR)
  }

  private companion object {
    const val PREFERENCES = "com.ronradtke.reactnativedevicecalendar"
    const val REFUSED = "permissionRefused"
    const val REQUEST_CODE = 0xCA1
    val PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
  }
}
