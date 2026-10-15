package net.mikesimone.backchannel

import org.junit.Assert.assertEquals
import org.junit.Test
import org.thoughtcrime.securesms.recipients.RecipientId

class ForwardMentionsTest {

  private val ann = RecipientId.from(1)
  private val annLee = RecipientId.from(2)
  private val names = listOf("Ann Lee" to annLee, "Ann" to ann)

  @Test
  fun longestNameWins() {
    val mentions = ForwardMentions.matches("hi @Ann Lee!", names)
    assertEquals(1, mentions.size)
    assertEquals(annLee, mentions[0].recipientId)
    assertEquals(3, mentions[0].start)
    assertEquals(8, mentions[0].length)
  }

  @Test
  fun matchNeedsWordBoundaries() {
    assertEquals(0, ForwardMentions.matches("mail ann@Annex", names).size)
    assertEquals(0, ForwardMentions.matches("@Annabel", names).size)
  }

  @Test
  fun caseInsensitiveAndSeveral() {
    val mentions = ForwardMentions.matches("@ann and @ANN LEE", names)
    assertEquals(listOf(ann, annLee), mentions.map { it.recipientId })
  }
}
