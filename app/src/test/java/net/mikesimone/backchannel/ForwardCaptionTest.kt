package net.mikesimone.backchannel

import org.junit.Assert.assertEquals
import org.junit.Test
import org.thoughtcrime.securesms.database.model.Mention
import org.thoughtcrime.securesms.recipients.RecipientId

class ForwardCaptionTest {

  @Test
  fun wordsAloneBecomeTheCaption() {
    assertEquals("look at this" to 0, ForwardCaption.combine("look at this", null))
    assertEquals("look at this" to 0, ForwardCaption.combine("look at this", ""))
  }

  @Test
  fun wordsGoAboveAnExistingCaption() {
    assertEquals("look\n\nbeach day" to 6, ForwardCaption.combine("look", "beach day"))
  }

  @Test
  fun typedMentionsDontDoublePickedOnes() {
    val ann = RecipientId.from(1)
    val bob = RecipientId.from(2)
    val picked = listOf(Mention(ann, 0, 4))
    val typed = listOf(Mention(ann, 0, 4), Mention(bob, 10, 4))
    val merged = ForwardMentions.mergeTyped(picked, typed)
    assertEquals(2, merged.size)
    assertEquals(bob, merged[1].recipientId)
  }
}
