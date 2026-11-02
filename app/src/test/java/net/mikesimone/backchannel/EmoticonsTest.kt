package net.mikesimone.backchannel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmoticonsTest {

  @Test
  fun smileChangesAsSoonAsItIsTyped() {
    assertEquals(Emoticons.Match(3, 5, "😃", true), Emoticons.match("hi :)", 5))
  }

  @Test
  fun smileAtStartOfText() {
    assertEquals(Emoticons.Match(0, 2, "😉", true), Emoticons.match(";)", 2))
  }

  @Test
  fun letterEmoticonWaitsForSpace() {
    assertNull(Emoticons.match("ok :P", 5))
    assertEquals(Emoticons.Match(3, 5, "😛", false), Emoticons.match("ok :P ", 6))
  }

  @Test
  fun wordsThatStartLikeEmoticonsAreLeftAlone() {
    assertNull(Emoticons.match(":Pizza ", 7))
  }

  @Test
  fun emoticonInsideAWordIsLeftAlone() {
    assertNull(Emoticons.match("a(b:)", 5))
    assertNull(Emoticons.match("http://", 7))
  }

  @Test
  fun longestFormWins() {
    assertEquals(Emoticons.Match(0, 3, "😠", true), Emoticons.match(">:(", 3))
  }
}
