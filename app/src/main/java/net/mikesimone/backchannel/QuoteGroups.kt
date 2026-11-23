package net.mikesimone.backchannel

import android.content.Context
import android.util.Base64
import android.util.LruCache
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId

/**
 * Private replies to group messages carry the group (DataMessage.Quote.groupId, Signal Desktop
 * 8.39 / Backchannel Web F64), and the quote header reads "Author · Group", or "Unknown group"
 * when this phone isn't in that group.
 *
 * Sending: [groupIdForOutgoing] tags a 1:1 quote whose original lives in a group chat.
 * Receiving: the group id is remembered per reply (sent time + sender) on this phone only,
 * because Signal's message table has no column for it.
 */
object QuoteGroups {

  private val TAG = Log.tag(QuoteGroups::class.java)
  private const val PREFS = "backchannel_quote_groups"
  private const val MAX_REMEMBERED = 2000

  private val labels = LruCache<Long, String>(500)
  private const val NONE = "\u0000"

  /** The group id to put on an outgoing quote, or null for a normal same-chat quote. */
  @JvmStatic
  fun groupIdForOutgoing(quoteId: Long, quoteAuthor: RecipientId, threadRecipient: Recipient): ByteArray? {
    if (threadRecipient.isGroup) return null
    return try {
      val quoted = SignalDatabase.messages.getMessageFor(quoteId, quoteAuthor) ?: return null
      val quotedThread = SignalDatabase.threads.getRecipientForThreadId(quoted.threadId) ?: return null
      if (quotedThread.id == threadRecipient.id || !quotedThread.isPushV2Group) return null
      quotedThread.requireGroupId().requireV2().decodedId
    } catch (e: Exception) {
      Log.w(TAG, "Couldn't work out the quoted group", e)
      null
    }
  }

  @JvmStatic
  fun rememberIncoming(context: Context, sentTimestamp: Long, sender: RecipientId, groupId: ByteArray) {
    if (groupId.size != 32) return
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val editor = prefs.edit()
    if (prefs.all.size >= MAX_REMEMBERED) {
      prefs.all.keys.sortedBy { it.substringBefore(':').toLongOrNull() ?: 0L }.take(MAX_REMEMBERED / 10).forEach { editor.remove(it) }
    }
    editor.putString(key(sentTimestamp, sender), Base64.encodeToString(groupId, Base64.NO_WRAP)).apply()
  }

  /** "Group name", "Unknown group", or null when the quote isn't a private reply to a group message. */
  @JvmStatic
  fun labelFor(context: Context, record: MessageRecord, threadRecipient: Recipient): String? {
    if (threadRecipient.isGroup) return null
    val quote = (record as? MmsMessageRecord)?.quote ?: return null
    labels.get(record.id)?.let { return if (it == NONE) null else it }

    val label = try {
      computeLabel(context, record, quote.id, quote.author, threadRecipient)
    } catch (e: Exception) {
      Log.w(TAG, "Couldn't label quote", e)
      null
    }
    labels.put(record.id, label ?: NONE)
    return label
  }

  private fun computeLabel(context: Context, record: MessageRecord, quoteId: Long, quoteAuthor: RecipientId, threadRecipient: Recipient): String? {
    val remembered = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(record.dateSent, record.fromRecipient.id), null)
    if (remembered != null) {
      val groupId = GroupId.push(Base64.decode(remembered, Base64.NO_WRAP))
      val title = SignalDatabase.groups.getGroup(groupId).orElse(null)?.title
      return if (title.isNullOrBlank()) context.getString(R.string.QuoteGroups__unknown_group) else title
    }

    // Our own private replies, and replies from older apps that don't send the group:
    // the original is on this phone in a group chat.
    val quoted = SignalDatabase.messages.getMessageFor(quoteId, quoteAuthor) ?: return null
    val quotedThread = SignalDatabase.threads.getRecipientForThreadId(quoted.threadId) ?: return null
    if (quotedThread.id == threadRecipient.id || !quotedThread.isGroup) return null
    return quotedThread.getDisplayName(context)
  }

  private fun key(sentTimestamp: Long, sender: RecipientId) = "$sentTimestamp:${sender.toLong()}"
}
