package com.ronradtke.reactnativedevicecalendar

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationsTest {
  private val hour = 3_600_000L

  @Test
  fun `durations are written in seconds or days`() {
    assertEquals("P3600S", Durations.ofSeconds(hour + 999))
    assertEquals("P2D", Durations.ofDays(2))
  }

  @Test
  fun `durations of every form are read`() {
    assertEquals(hour, Durations.millis("P3600S"))
    assertEquals(hour, Durations.millis("PT1H"))
    assertEquals(90 * 60_000L, Durations.millis("PT1H30M"))
    assertEquals(2 * Days.MILLIS, Durations.millis("P2D"))
    assertEquals(7 * Days.MILLIS, Durations.millis("P1W"))
    assertEquals(Days.MILLIS + 2 * hour + 3 * 60_000L + 4_000L, Durations.millis("P1DT2H3M4S"))
    assertEquals(-15 * 60_000L, Durations.millis("-PT15M"))
  }

  @Test
  fun `a missing or malformed duration is none`() {
    assertEquals(0L, Durations.millis(null))
    assertEquals(0L, Durations.millis("one hour"))
  }
}
