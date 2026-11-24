package com.ronradtke.reactnativedevicecalendar

/** The numbers CalendarContract stores for the choices of the contract, and back; each list is indexed by the stored number */
internal object Codes {
  /** Events.STATUS_CANCELED */
  const val STATUS_CANCELED = 2

  // Events.AVAILABILITY_BUSY, AVAILABILITY_FREE, AVAILABILITY_TENTATIVE
  private val AVAILABILITIES = listOf("busy", "free", "tentative")

  // Events.STATUS_TENTATIVE, STATUS_CONFIRMED, STATUS_CANCELED
  private val STATUSES = listOf("tentative", "confirmed", "canceled")

  // Reminders.METHOD_DEFAULT, METHOD_ALERT, METHOD_EMAIL, METHOD_SMS
  private val ALARM_METHODS = listOf("default", "alert", "email", "sms")

  // Attendees.ATTENDEE_STATUS_NONE, ACCEPTED, DECLINED, INVITED, TENTATIVE
  private val ATTENDEE_STATUSES = listOf("unknown", "accepted", "declined", "pending", "tentative")

  // Attendees.RELATIONSHIP_ORGANIZER; TYPE_REQUIRED, TYPE_OPTIONAL, TYPE_RESOURCE
  private const val RELATIONSHIP_ORGANIZER = 2
  private const val TYPE_REQUIRED = 1
  private const val TYPE_OPTIONAL = 2
  private const val TYPE_RESOURCE = 3

  fun availability(value: Int?): String = AVAILABILITIES.getOrNull(value ?: 0) ?: "busy"

  /** unavailable and notSupported have no value on Android and are stored as busy, as is null */
  fun availabilityOf(name: String?): Int = AVAILABILITIES.indexOf(name).coerceAtLeast(0)

  /** ALLOWED_AVAILABILITY, a comma-separated list of numbers; a calendar without it allows busy and free */
  fun allowedAvailabilities(list: String?): List<String> =
    if (list.isNullOrBlank()) {
      listOf("busy", "free")
    } else {
      list.split(',').mapNotNull { it.trim().toIntOrNull()?.let(AVAILABILITIES::getOrNull) }.distinct()
    }

  fun status(value: Int?): String = value?.let(STATUSES::getOrNull) ?: "none"

  fun alarmMethod(value: Int): String = ALARM_METHODS.getOrNull(value) ?: "default"

  /** null is the calendar app's default */
  fun alarmMethodOf(name: String?): Int = ALARM_METHODS.indexOf(name).coerceAtLeast(0)

  fun attendeeStatus(value: Int): String = ATTENDEE_STATUSES.getOrNull(value) ?: "unknown"

  /** The organizer chairs; a resource is a required attendee of the type resource */
  fun attendeeRole(relationship: Int, type: Int): String = when {
    relationship == RELATIONSHIP_ORGANIZER -> "chair"
    type == TYPE_REQUIRED || type == TYPE_RESOURCE -> "required"
    type == TYPE_OPTIONAL -> "optional"
    else -> "unknown"
  }

  fun attendeeType(type: Int): String = if (type == TYPE_RESOURCE) "resource" else "person"
}
