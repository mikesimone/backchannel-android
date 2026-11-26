package net.mikesimone.backchannel

import android.content.Context
import androidx.annotation.WorkerThread
import org.thoughtcrime.securesms.database.model.Mention
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId

/**
 * @mentions in a forward's message (Backchannel P8). Type "@Name" in the
 * forward sheet's message; each "@Name" that matches a member of one of
 * the chosen groups becomes a mention. Mentions are only kept for groups
 * the person is in, so every other chat gets plain "@Name" text, the same
 * rule as Backchannel's web page.
 */
object ForwardMentions {

  @JvmStatic
  @WorkerThread
  fun find(context: Context, text: String, destinations: Collection<RecipientId>): List<Mention> {
    if (text.indexOf('@') < 0) {
      return emptyList()
    }

    val names: List<Pair<String, RecipientId>> = destinations
      .map { Recipient.resolved(it) }
      .filter { it.isPushV2Group }
      .flatMap { it.participantIds }
      .distinct()
      .map { Recipient.resolved(it) }
      .filterNot { it.isSelf }
      .map { it.getMentionDisplayName(context) to it.id }
      .filter { it.first.isNotBlank() }
      .sortedByDescending { it.first.length }

    return matches(text, names)
  }

  /** Longest name wins at each "@"; a match must end at a word boundary. */
  @JvmStatic
  fun matches(text: String, names: List<Pair<String, RecipientId>>): List<Mention> {
    val mentions = mutableListOf<Mention>()
    var i = 0
    while (i < text.length) {
      val at = text.indexOf('@', i)
      if (at < 0) break
      if (at > 0 && text[at - 1].isLetterOrDigit()) {
        i = at + 1
        continue
      }
      val match = names.firstOrNull { (name, _) ->
        val end = at + 1 + name.length
        text.regionMatches(at + 1, name, 0, name.length, ignoreCase = true) &&
          (end == text.length || !text[end].isLetterOrDigit())
      }
      if (match != null) {
        val length = 1 + match.first.length
        mentions += Mention(match.second, at, length)
        i = at + length
      } else {
        i = at + 1
      }
    }
    return mentions
  }

  /** Adds [found] mentions that don't overlap one already in [existing] (picked from the list). */
  @JvmStatic
  fun mergeTyped(existing: List<Mention>, found: List<Mention>): List<Mention> {
    if (found.isEmpty()) return existing
    val extra = found.filter { f ->
      existing.none { e -> f.start < e.start + e.length && e.start < f.start + f.length }
    }
    return (existing + extra).sortedBy { it.start }
  }

  /**
   * The mentions [recipient] should get: only in an active group, and only for its members.
   * Sharing to several chats sends one set of mentions to all of them (Mike 2026-11-26).
   */
  @JvmStatic
  fun validFor(recipient: Recipient, mentions: List<Mention>): List<Mention> {
    if (mentions.isEmpty() || !recipient.isPushV2Group || !recipient.isActiveGroup) return emptyList()
    val members = recipient.participantIds.toSet()
    return mentions.filter { it.recipientId in members }
  }
}
