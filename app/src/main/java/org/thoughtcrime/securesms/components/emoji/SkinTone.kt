package org.thoughtcrime.securesms.components.emoji

import org.thoughtcrime.securesms.emoji.EmojiSource

/**
 * BACKCHANNEL: one default skin tone for every emoji that has tones, set once in
 * Settings > Chats instead of long-pressing each emoji.
 *
 * Tone 0 is the default yellow; 1-5 are the Fitzpatrick modifiers, light to dark.
 */
object SkinTone {
  const val NONE = 0

  private val MODIFIERS = listOf("🏻", "🏼", "🏽", "🏾", "🏿")

  /** The variation that uses [tone] and no other tone, or the plain emoji if there isn't one. */
  @JvmStatic
  fun pick(variations: List<String>, tone: Int): String {
    if (tone !in 1..MODIFIERS.size || variations.size <= 1) {
      return variations[0]
    }

    val wanted = MODIFIERS[tone - 1]
    return variations.firstOrNull { variation ->
      variation.contains(wanted) && MODIFIERS.none { it != wanted && variation.contains(it) }
    } ?: variations[0]
  }

  /** [emoji] in [tone], when [emoji] is a plain emoji that comes in tones. */
  @JvmStatic
  fun forEmoji(emoji: String, tone: Int): String {
    if (tone !in 1..MODIFIERS.size) {
      return emoji
    }

    val variations = EmojiSource.latest.canonicalToVariations[emoji] ?: return emoji
    return pick(variations, tone)
  }

  /** Every plain emoji that comes in tones. */
  @JvmStatic
  fun tonedEmoji(): Collection<String> {
    return EmojiSource.latest.canonicalToVariations.filterValues { it.size > 1 }.keys
  }
}
