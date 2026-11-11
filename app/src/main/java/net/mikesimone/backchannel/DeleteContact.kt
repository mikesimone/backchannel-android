package net.mikesimone.backchannel

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.signal.core.util.ThreadUtil
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.contacts.management.ContactsManagementRepository
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.recipients.Recipient

/**
 * Delete contact (Backchannel, Mike 2026-11-11): deletes your chat with
 * someone and removes them from your contacts in Backchannel, without
 * blocking them.
 *
 * Why: Hide only tidies the lists; Delete is for people you're done with.
 *
 * What it does, all on this phone except where Signal itself syncs:
 * - Deletes the 1:1 chat and its media the way Signal's Delete chat does
 *   (which also tells your linked devices to delete it).
 * - Signal's own Remove for that person (marks them hidden and not accepted,
 *   synced through your Signal storage like Remove is, and rotates your
 *   profile key when you share no groups, as Remove does). If they message
 *   you, it arrives as a message request.
 * - Hides them in Backchannel's pickers until they message you again.
 * - Clears Backchannel's own settings for them: linked phone contact, your
 *   photo for them, chat lock, blur and auto-save choices, auto-reply timer.
 * - It can't edit the phone's address book (no contacts write permission),
 *   so for a phone contact it offers to open the contact there to delete it.
 *
 * Never blocks, never sends anything to them. 1:1 only.
 */
object DeleteContact {

  private val TAG = Log.tag(DeleteContact::class.java)

  @JvmStatic
  fun canDelete(recipient: Recipient): Boolean = recipient.isIndividual && !recipient.isSelf && !recipient.isReleaseNotes

  /** Asks first, deletes off the main thread, then offers the phone contact and calls [onDeleted]. */
  @JvmStatic
  fun confirm(context: Context, recipient: Recipient, onDeleted: () -> Unit) {
    if (!canDelete(recipient)) return
    val name = recipient.getDisplayName(context)
    MaterialAlertDialogBuilder(context)
      .setTitle(context.getString(R.string.DeleteContact__confirm_title, name))
      .setMessage(context.getString(R.string.DeleteContact__confirm_body, name))
      .setPositiveButton(R.string.DeleteContact__delete) { _, _ -> delete(context, recipient, name, onDeleted) }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun delete(context: Context, recipient: Recipient, name: String, onDeleted: () -> Unit) {
    val appContext = context.applicationContext
    val id = recipient.id
    val phoneContact = recipient.contactUri

    SignalExecutors.BOUNDED.execute {
      val ok = try {
        val threadId = SignalDatabase.threads.getThreadIdIfExistsFor(id)
        if (threadId > 0) {
          SignalDatabase.threads.deleteConversation(threadId)
          AppDependencies.messageNotifier.updateNotification(appContext)
        }

        ContactsManagementRepository(appContext).hideContact(Recipient.resolved(id)).blockingAwait()
        HiddenContacts.markDeleted(appContext, id)

        ContactOverrides.unlink(appContext, id)
        ContactOverrides.clearCustomPhoto(appContext, id)
        ChatLock.setLocked(appContext, id, false)
        MediaBlur.clear(appContext, id)
        AutoSaveMedia.setChatSetting(appContext, id, AutoSaveMedia.ChatSetting.DEFAULT)
        AutoReply.clear(appContext, id)
        Log.i(TAG, "Deleted contact $id")
        true
      } catch (e: Exception) {
        Log.w(TAG, "Couldn't delete contact $id", e)
        false
      }

      ThreadUtil.runOnMain {
        if (!ok) {
          Toast.makeText(context, R.string.DeleteContact__failed, Toast.LENGTH_SHORT).show()
          return@runOnMain
        }
        Toast.makeText(context, context.getString(R.string.DeleteContact__deleted, name), Toast.LENGTH_SHORT).show()
        if (phoneContact != null) {
          MaterialAlertDialogBuilder(context)
            .setTitle(R.string.DeleteContact__phone_title)
            .setMessage(context.getString(R.string.DeleteContact__phone_body, name))
            .setPositiveButton(R.string.DeleteContact__phone_open) { _, _ ->
              try {
                context.startActivity(Intent(Intent.ACTION_VIEW, phoneContact))
              } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "No contacts app", e)
              }
            }
            .setNegativeButton(R.string.DeleteContact__phone_keep, null)
            .setOnDismissListener { onDeleted() }
            .show()
        } else {
          onDeleted()
        }
      }
    }
  }
}
