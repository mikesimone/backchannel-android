package net.mikesimone.backchannel

import net.mikesimone.backchannel.UnicodeStyles.Piece
import net.mikesimone.backchannel.UnicodeStyles.Style
import net.mikesimone.backchannel.UnicodeStyles.StyledChar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnicodeStylesTest {

  @Test
  fun boldUsesSansSerifBold() {
    assertEquals(String(Character.toChars(0x1d5db)) + String(Character.toChars(0x1d5f6)) + " 1", UnicodeStyles.toUnicode(listOf(Piece("Hi", setOf(Style.BOLD)), Piece(" 1", emptySet()))))
  }

  @Test
  fun boldItalicDigitsUseBoldDigits() {
    assertEquals(String(Character.toChars(0x1d7ed)), UnicodeStyles.toUnicode(listOf(Piece("1", setOf(Style.BOLD, Style.ITALIC)))))
  }

  @Test
  fun italicDigitsStayPlain() {
    assertEquals("7", UnicodeStyles.toUnicode(listOf(Piece("7", setOf(Style.ITALIC)))))
  }

  @Test
  fun strikeAddsMarkAfterEachCharacterButNotNewlines() {
    assertEquals("a̶\nb̶", UnicodeStyles.toUnicode(listOf(Piece("a\nb", setOf(Style.STRIKETHROUGH)))))
  }

  @Test
  fun plainTextHasNoUnicodeStyle() {
    assertFalse(UnicodeStyles.hasUnicodeStyle(listOf(Piece("plain", emptySet()))))
    assertFalse(UnicodeStyles.hasUnicode("plain text"))
  }

  @Test
  fun roundTrip() {
    val pieces = listOf(
      Piece("Bold", setOf(Style.BOLD)),
      Piece(" and ", emptySet()),
      Piece("both", setOf(Style.BOLD, Style.ITALIC)),
      Piece(" ", emptySet()),
      Piece("code", setOf(Style.MONOSPACE)),
      Piece(" ", emptySet()),
      Piece("gone", setOf(Style.STRIKETHROUGH))
    )
    val unicode = UnicodeStyles.toUnicode(pieces)
    assertTrue(UnicodeStyles.hasUnicode(unicode))
    val chars = UnicodeStyles.fromUnicode(unicode)
    assertEquals("Bold and both code gone", chars.joinToString("") { it.ch })
    assertEquals(setOf(Style.BOLD), chars[0].styles)
    assertEquals(emptySet<Style>(), chars[4].styles)
    assertEquals(setOf(Style.BOLD, Style.ITALIC), chars[9].styles)
    assertEquals(setOf(Style.MONOSPACE), chars[14].styles)
    assertEquals(setOf(Style.STRIKETHROUGH), chars[19].styles)
  }

  @Test
  fun serifPasteAndPlanckH() {
    val serifBoldA = String(Character.toChars(0x1d400))
    assertEquals(listOf(StyledChar("A", setOf(Style.BOLD)), StyledChar("h", setOf(Style.ITALIC))), UnicodeStyles.fromUnicode(serifBoldA + "ℎ"))
  }

  @Test
  fun emojiPassesThrough() {
    assertEquals(listOf(StyledChar("😀", emptySet())), UnicodeStyles.fromUnicode("😀"))
  }
}
