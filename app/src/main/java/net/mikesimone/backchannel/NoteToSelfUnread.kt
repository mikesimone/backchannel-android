package net.mikesimone.backchannel

import org.signal.core.util.ThreadUtil
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.notifications.v2.ConversationId
import org.thoughtcrime.securesms.recipients.Recipient

/**
 * Note to Self as a reminder (Backchannel, Mike 2026-11-06): a new note leaves
 * Note to Self unread until you open it again, on this phone whichever device
 * wrote the note. Signal instead treats your own notes as read.
 *
 * A note written here marks the chat unread when you leave it. A note from
 * another device marks it unread right away, unless Note to Self is open.
 */
object NoteToSelfUnread {

  private val TAG = Log.tag(NoteToSelfUnread::class.java)

  @Volatile
  private var openedAt = 0L

  private fun isNoteToSelf(threadId: Long): Boolean {
    return threadId > 0 && SignalDatabase.threads.getRecipientIdForThreadId(threadId) == Recipient.self().id
  }

  /** Called when any message is saved into a chat. */
  @JvmStatic
  fun onMessageInserted(threadId: Long) {
    try {
      if (!isNoteToSelf(threadId)) return
      val visible = AppDependencies.messageNotifier.visibleThread.orElse(null)
      if (visible == ConversationId.forConversation(threadId)) return
      SignalExecutors.BOUNDED.execute { SignalDatabase.threads.setForcedUnread(listOf(threadId)) }
    } catch (e: Exception) {
      Log.w(TAG, "Couldn't mark Note to Self unread", e)
    }
  }

  /** Called when a chat opens. */
  @JvmStatic
  fun onConversationOpened(threadId: Long) {
    openedAt = System.currentTimeMillis()
  }

  /** Called when a chat is left. If a note was added during the visit, Note to Self goes unread. */
  @JvmStatic
  fun onConversationLeft(threadId: Long) {
    val since = openedAt
    // Wait for Signal's own mark-as-read on leaving to finish first.
    ThreadUtil.runOnMainDelayed({
      SignalExecutors.BOUNDED.execute {
        try {
          if (!isNoteToSelf(threadId)) return@execute
          val latest = SignalDatabase.threads.getThreadRecord(threadId)?.date ?: return@execute
          if (latest >= since) {
            SignalDatabase.threads.setForcedUnread(listOf(threadId))
          }
        } catch (e: Exception) {
          Log.w(TAG, "Couldn't mark Note to Self unread", e)
        }
      }
    }, 1500)
  }
}
