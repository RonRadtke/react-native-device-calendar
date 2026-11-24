package com.ronradtke.reactnativedevicecalendar

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri

/** The rows a query answers, each read by [read]; none when the provider answers nothing */
internal inline fun <T> ContentResolver.rows(
  uri: Uri,
  columns: Array<String>,
  selection: String? = null,
  args: Array<String>? = null,
  order: String? = null,
  read: Cursor.() -> T,
): List<T> = query(uri, columns, selection, args, order)?.use { cursor ->
  buildList { while (cursor.moveToNext()) add(cursor.read()) }
}.orEmpty()

internal fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))

internal fun Cursor.int(column: String): Int = getInt(getColumnIndexOrThrow(column))

internal fun Cursor.longOrNull(column: String): Long? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getLong(it) }

internal fun Cursor.intOrNull(column: String): Int? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getInt(it) }

internal fun Cursor.string(column: String): String? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }
