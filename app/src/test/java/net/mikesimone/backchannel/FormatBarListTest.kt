package net.mikesimone.backchannel

import android.app.Application
import android.text.SpannableStringBuilder
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class FormatBarListTest {

  private fun toggle(input: String, start: Int, end: Int, numbered: Boolean): Pair<String, Pair<Int, Int>> {
    val text = SpannableStringBuilder(input)
    val sel = FormatBar.toggleList(text, start, end, numbered)
    return text.toString() to sel
  }

  @Test
  fun bulletOnCaretLine() {
    assertEquals("one\n• two" to (9 to 9), toggle("one\ntwo", 7, 7, false))
  }

  @Test
  fun bulletOnEmptyText() {
    assertEquals("• " to (2 to 2), toggle("", 0, 0, false))
  }

  @Test
  fun bulletOffAgain() {
    assertEquals("one\ntwo" to (7 to 7), toggle("one\n• two", 9, 9, false))
  }

  @Test
  fun numbersOverSelectedLines() {
    assertEquals("1. a\n2. b\n3. c" to (0 to 14), toggle("a\nb\nc", 0, 5, true))
  }

  @Test
  fun bulletsBecomeNumbers() {
    assertEquals("1. a\n2. b" to (0 to 9), toggle("• a\n• b", 0, 7, true))
  }

  @Test
  fun caretInsideOldPrefixLandsAfterNewOne() {
    assertEquals("1. x" to (3 to 3), toggle("• x", 1, 1, true))
  }

  @Test
  fun selectionEndingAtLineBreakLeavesNextLineAlone() {
    assertEquals("• a\nb" to (0 to 3), toggle("a\nb", 0, 2, false))
  }
}
