package net.mikesimone.backchannel

import org.thoughtcrime.securesms.database.model.Mention
import org.thoughtcrime.securesms.database.model.databaseprotos.BodyRangeList
import org.thoughtcrime.securesms.sharing.MultiShareArgs
import org.thoughtcrime.securesms.util.MediaUtil

/**
 * Words typed in the forward sheet ride on the forwarded photo or video as its caption
 * instead of arriving as a separate message (Mike 2026-11-26, same as Share). If the photo
 * already has a caption, the typed words go first and the original caption follows after a
 * blank line. Forwards with no photo or video still send the words as their own message.
 */
object ForwardCaption {

  /** Index of the forwarded item that takes the typed words as its caption, or -1. */
  @JvmStatic
  fun target(args: List<MultiShareArgs>): Int {
    return args.indexOfFirst { arg ->
      val hasVisualMedia = arg.media.isNotEmpty() ||
        (arg.dataUri != null && MediaUtil.isImageOrVideoType(arg.dataType))
      hasVisualMedia && !arg.isViewOnce && arg.stickerLocator == null && arg.poll == null && !arg.isTextStory
    }
  }

  /** The caption text and how far the original caption's styles and mentions move. */
  @JvmStatic
  fun combine(words: String, original: String?): Pair<String, Int> {
    if (original.isNullOrEmpty()) return words to 0
    val prefix = "$words\n\n"
    return (prefix + original) to prefix.length
  }

  @JvmStatic
  fun withWords(arg: MultiShareArgs, words: String, wordMentions: List<Mention>): MultiShareArgs {
    val (text, shift) = combine(words, arg.draftText)
    val shiftedMentions = arg.mentions.map { Mention(it.recipientId, it.start + shift, it.length) }
    val shiftedRanges = arg.bodyRanges?.let { ranges ->
      BodyRangeList(ranges = ranges.ranges.map { it.copy(start = it.start + shift) })
    }
    return arg.buildUpon()
      .withDraftText(text)
      .withMentions(wordMentions + shiftedMentions)
      .withBodyRanges(shiftedRanges)
      .build()
  }
}
