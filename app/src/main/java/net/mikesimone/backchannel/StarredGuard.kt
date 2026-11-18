package net.mikesimone.backchannel

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.SignalDatabase

/**
 * Backchannel: starred messages are never deleted until they're unstarred, including by the
 * user's own delete actions (messages, attachments, whole chats). Retention trims and
 * disappearing timers skip them in MessageTable / ExpiringMessageManager.
 */
object StarredGuard {

  @JvmStatic
  fun messagesStarred(messageIds: Collection<Long>): Boolean = SignalDatabase.messages.anyStarred(messageIds)

  @JvmStatic
  fun threadsHaveStarred(threadIds: Collection<Long>): Boolean = SignalDatabase.messages.hasStarredInThreads(threadIds)

  /** Shows "Unstar it first". [chat] picks the wording for deleting whole chats. */
  @JvmStatic
  fun showUnstarFirst(context: Context, chat: Boolean) {
    MaterialAlertDialogBuilder(context)
      .setTitle(R.string.StarredGuard__title)
      .setMessage(if (chat) R.string.StarredGuard__chat_body else R.string.StarredGuard__message_body)
      .setPositiveButton(android.R.string.ok, null)
      .show()
  }
}
