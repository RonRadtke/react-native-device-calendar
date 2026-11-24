package com.ronradtke.reactnativedevicecalendar

/** A failure with the code JavaScript sees: E_NOT_FOUND, E_CALENDAR_CHANGE, E_UNSUPPORTED or E_CALENDAR */
internal class CalendarException(val code: String, message: String) : Exception(message)
