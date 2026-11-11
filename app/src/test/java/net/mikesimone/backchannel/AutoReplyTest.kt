package net.mikesimone.backchannel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoReplyTest {

  private val hour = 60L * 60L * 1000L

  private fun settings(on: Boolean = true, window: Boolean = false, start: Int = 22 * 60, end: Int = 7 * 60, until: Long = 0L) =
    AutoReply.Settings(on = on, text = "away", window = window, startMinute = start, endMinute = end, untilMillis = until)

  @Test
  fun dayWindow() {
    assertTrue(AutoReply.inWindow(9 * 60, 9 * 60, 17 * 60))
    assertTrue(AutoReply.inWindow(16 * 60 + 59, 9 * 60, 17 * 60))
    assertFalse(AutoReply.inWindow(17 * 60, 9 * 60, 17 * 60))
    assertFalse(AutoReply.inWindow(8 * 60, 9 * 60, 17 * 60))
  }

  @Test
  fun overnightWindow() {
    assertTrue(AutoReply.inWindow(23 * 60, 22 * 60, 7 * 60))
    assertTrue(AutoReply.inWindow(3 * 60, 22 * 60, 7 * 60))
    assertFalse(AutoReply.inWindow(7 * 60, 22 * 60, 7 * 60))
    assertFalse(AutoReply.inWindow(12 * 60, 22 * 60, 7 * 60))
  }

  @Test
  fun sameStartAndEndIsAllDay() {
    assertTrue(AutoReply.inWindow(0, 8 * 60, 8 * 60))
    assertTrue(AutoReply.inWindow(20 * 60, 8 * 60, 8 * 60))
  }

  @Test
  fun activeRespectsSwitchWindowAndEndTime() {
    assertFalse(AutoReply.isActive(settings(on = false), 1000L, 12 * 60))
    assertTrue(AutoReply.isActive(settings(), 1000L, 12 * 60))
    assertFalse(AutoReply.isActive(settings(window = true), 1000L, 12 * 60))
    assertTrue(AutoReply.isActive(settings(window = true), 1000L, 23 * 60))
    assertTrue(AutoReply.isActive(settings(until = 2000L), 1000L, 12 * 60))
    assertFalse(AutoReply.isActive(settings(until = 2000L), 2000L, 12 * 60))
  }

  @Test
  fun fourHourLimitPerChat() {
    val now = 100 * hour
    assertTrue(AutoReply.throttleAllows(0L, now))
    assertFalse(AutoReply.throttleAllows(now - hour, now))
    assertFalse(AutoReply.throttleAllows(now - 4 * hour + 1, now))
    assertTrue(AutoReply.throttleAllows(now - 4 * hour, now))
    assertTrue(AutoReply.throttleAllows(now + hour, now))
  }

  @Test
  fun markersAreDistinctAndDetected() {
    assertFalse(AutoReply.MARKER == SendSilently.MARKER)
    assertTrue(AutoReply.isAutoReply("away" + AutoReply.MARKER))
    assertFalse(AutoReply.isAutoReply("away" + SendSilently.MARKER))
    assertFalse(AutoReply.isAutoReply("away"))
    assertFalse(AutoReply.isAutoReply(null))
    assertTrue(SendSilently.isMarked(SendSilently.mark("hi")))
    assertFalse(SendSilently.isMarked("hi" + AutoReply.MARKER))
    assertTrue(SendSilently.mark(SendSilently.mark("hi")) == SendSilently.mark("hi"))
  }
}
