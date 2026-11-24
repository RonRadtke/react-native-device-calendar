package com.ronradtke.reactnativedevicecalendar

import org.junit.Assert.assertEquals
import org.junit.Test

class CodesTest {
  @Test
  fun `availabilities map both ways, those Android lacks to busy`() {
    assertEquals(listOf("busy", "free", "tentative"), listOf(0, 1, 2).map(Codes::availability))
    assertEquals("busy", Codes.availability(null))
    assertEquals(listOf(0, 1, 2, 0, 0, 0), listOf("busy", "free", "tentative", "unavailable", "notSupported", null).map(Codes::availabilityOf))
  }

  @Test
  fun `allowed availabilities default to busy and free`() {
    assertEquals(listOf("busy", "free"), Codes.allowedAvailabilities(null))
    assertEquals(listOf("busy", "free", "tentative"), Codes.allowedAvailabilities("0,1,2"))
    assertEquals(listOf("free"), Codes.allowedAvailabilities(" 1 ,9"))
  }

  @Test
  fun `statuses and alarm methods`() {
    assertEquals(listOf("tentative", "confirmed", "canceled", "none"), listOf(0, 1, 2, null).map(Codes::status))
    assertEquals(listOf("default", "alert", "email", "sms", "default"), listOf(0, 1, 2, 3, 4).map(Codes::alarmMethod))
    assertEquals(listOf(0, 1, 2, 3, 0), listOf("default", "alert", "email", "sms", null).map(Codes::alarmMethodOf))
  }

  @Test
  fun `attendees get role, type and status`() {
    // organizer, then required, optional, resource and none
    assertEquals("chair", Codes.attendeeRole(2, 1))
    assertEquals(listOf("required", "optional", "required", "unknown"), listOf(1, 2, 3, 0).map { Codes.attendeeRole(1, it) })
    assertEquals(listOf("person", "person", "resource"), listOf(0, 1, 3).map(Codes::attendeeType))
    assertEquals(listOf("unknown", "accepted", "declined", "pending", "tentative"), (0..4).map(Codes::attendeeStatus))
  }
}
