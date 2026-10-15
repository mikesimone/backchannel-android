package net.mikesimone.backchannel

import android.app.Activity
import org.signal.core.util.ThreadUtil
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.conversation.ConversationIntents
import org.thoughtcrime.securesms.conversation.ConversationMessage
import org.thoughtcrime.securesms.database.DraftTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.mms.QuoteId
import org.thoughtcrime.securesms.recipients.Recipient

/**
 * Reply privately (Backchannel P5): from someone's message in a group, open
 * your 1:1 chat with them with that message already quoted. The quote is
 * saved as the 1:1 chat's quote draft; Signal's draft loader looks quoted
 * messages up by timestamp and author in any chat, so it shows up when the
 * chat opens, and sends like any other quote.
 */
object PrivateReply {

  private val TAG = Log.tag(PrivateReply::class.java)

  @JvmStatic
  fun canReplyPrivately(conversationRecipient: Recipient, conversationMessage: ConversationMessage): Boolean {
    val record = conversationMessage.messageRecord
    val author = record.fromRecipient
    return conversationRecipient.isGroup &&
      !record.isOutgoing &&
      !record.isUpdate &&
      !record.isRemoteDelete &&
      !record.isViewOnce &&
      !author.isSelf &&
      author.isRegistered &&
      !author.isBlocked
  }

  @JvmStatic
  fun open(activity: Activity, conversationMessage: ConversationMessage) {
    val record = conversationMessage.messageRecord
    val authorId = record.fromRecipient.id

    SignalExecutors.BOUNDED.execute {
      val author = Recipient.resolved(authorId)
      val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(author)
      val quote = DraftTable.Draft(DraftTable.Draft.QUOTE, QuoteId(record.dateSent, authorId).serialize())
      val drafts = SignalDatabase.drafts.getDrafts(threadId).filter { it.type != DraftTable.Draft.QUOTE && it.type != DraftTable.Draft.MESSAGE_EDIT }
      SignalDatabase.drafts.replaceDrafts(threadId, drafts + quote)
      Log.i(TAG, "Replying privately to $authorId in thread $threadId")

      ThreadUtil.runOnMain {
        if (!activity.isFinishing) {
          activity.startActivity(ConversationIntents.createBuilderSync(activity, authorId, threadId).build())
        }
      }
    }
  }
}
