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
class MarkdownBlocksTest {

  private val em = " "

  private fun blocks(input: String): String {
    val text = SpannableStringBuilder(input)
    Markdown.blocks(text)
    return text.toString()
  }

  @Test
  fun bulletsAndNesting() {
    assertEquals("• one\n$em◦ two\n$em$em▪ three", blocks("* one\n  * two\n    * three"))
  }

  @Test
  fun tasks() {
    assertEquals("• ☐ milk\n• ☑ eggs", blocks("* [ ] milk\n* [x] eggs"))
  }

  @Test
  fun numbersKeepTheirNumbers() {
    assertEquals("1. a\n${em}2. b", blocks("1. a\n  2. b"))
  }

  @Test
  fun headingQuoteRule() {
    assertEquals("Title\n▍ said\n──────────", blocks("# Title\n> said\n---"))
  }

  @Test
  fun codeFenceRemoved() {
    assertEquals("before\nx = 1\ny = 2\nafter", blocks("before\n```\nx = 1\ny = 2\n```\nafter"))
  }

  @Test
  fun inlineExtras() {
    assertEquals("a bold b and site (https://x.org)", blocks("a **bold** b and [site](https://x.org)"))
  }

  @Test
  fun pasteConvertsOnlyPastedLines() {
    val text = SpannableStringBuilder("# mine\n* a\n* b\n* tail")
    val from = text.indexOf("* a")
    Markdown.blocks(text, from, from + "* a\n* b".length)
    assertEquals("# mine\n• a\n• b\n* tail", text.toString())
  }

  @Test
  fun dashIsNotABullet() {
    assertEquals("- signed, me", blocks("- signed, me"))
  }

  @Test
  fun plainTextAndEmoticonsUntouched() {
    assertEquals("hi there >:( -5 *fix", blocks("hi there >:( -5 *fix"))
  }
}
