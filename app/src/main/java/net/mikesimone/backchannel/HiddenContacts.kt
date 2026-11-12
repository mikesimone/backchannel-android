package net.mikesimone.backchannel

import android.content.Context
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.signal.core.util.ThreadUtil
import org.signal.core.util.concurrent.SignalExecutors
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId

/**
 * Hidden contacts (Backchannel, Mike 2026-11-11): hide someone from
 * Backchannel's contact lists and pickers (new chat, forward and share,
 * adding group members) without blocking them. Their messages still arrive
 * and an existing chat with them stays in the chat list.
 *
 * Why: to tidy the contact picker of people you never message, without the
 * side effects of blocking or of Signal's own Remove (which syncs).
 *
 * Hide from a contact's long-press menu in New chat, or with the switch in
 * that person's chat settings; Settings > Chats > Hidden contacts lists them
 * and unhides them. Stored on this phone only. It never changes the phone's
 * own address book.
 *
 * Deleting a contact (see [DeleteContact]) also hides them; unlike a plain
 * hide, that one ends as soon as they message you again.
 */
object HiddenContacts {

  private const val PREFS = "backchannel_hidden_contacts"
  private const val KEY_HIDDEN = "hidden_recipients"
  private const val KEY_DELETED = "deleted_recipients"

  @Volatile
  private var cache: Set<Long>? = null

  private fun hidden(context: Context): Set<Long> {
    return cache ?: (
      context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_HIDDEN, emptySet())
        ?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
      ).also { cache = it }
  }

  /** Everyone hidden, as recipient ids. */
  @JvmStatic
  fun hiddenIds(context: Context): Set<Long> = hidden(context)

  @JvmStatic
  fun isHidden(context: Context, recipientId: RecipientId): Boolean = hidden(context).contains(recipientId.toLong())

  @JvmStatic
  fun setHidden(context: Context, recipientId: RecipientId, hide: Boolean) {
    val updated = hidden(context).toMutableSet()
    if (hide) updated.add(recipientId.toLong()) else updated.remove(recipientId.toLong())
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
      .putStringSet(KEY_HIDDEN, updated.map { it.toString() }.toSet())
      .apply()
    cache = updated
  }

  @JvmStatic
  fun count(context: Context): Int = hidden(context).size

  private fun deletedPrefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  /** Hidden because you deleted them: they come back when they message you. */
  @JvmStatic
  fun markDeleted(context: Context, recipientId: RecipientId) {
    setHidden(context, recipientId, true)
    val deleted = deletedPrefs(context).getStringSet(KEY_DELETED, emptySet())?.toMutableSet() ?: mutableSetOf()
    deleted.add(recipientId.serialize())
    deletedPrefs(context).edit().putStringSet(KEY_DELETED, deleted).apply()
  }

  /** Called for every new 1:1 message from [recipientId]: a deleted contact who messages you shows up again. */
  @JvmStatic
  fun onMessageFrom(context: Context, recipientId: RecipientId) {
    if (!isHidden(context, recipientId)) return
    val deleted = deletedPrefs(context).getStringSet(KEY_DELETED, emptySet()) ?: emptySet()
    if (!deleted.contains(recipientId.serialize())) return
    deletedPrefs(context).edit().putStringSet(KEY_DELETED, deleted - recipientId.serialize()).apply()
    setHidden(context, recipientId, false)
  }

  /**
   * SQL to add to a picker query's WHERE clause: " AND [idColumn] NOT IN (...)",
   * or "" when nobody is hidden. The ids are numbers, so they are inlined.
   */
  @JvmStatic
  fun sqlFilter(idColumn: String): String {
    val ids = hidden(org.thoughtcrime.securesms.dependencies.AppDependencies.application)
    return if (ids.isEmpty()) "" else " AND $idColumn NOT IN (${ids.joinToString(",")})"
  }

  /** Settings summary: how many are hidden. */
  @JvmStatic
  fun summary(context: Context): String {
    val count = count(context)
    return if (count == 0) context.getString(R.string.HiddenContacts__summary_none) else context.resources.getQuantityString(R.plurals.HiddenContacts__summary_count, count, count)
  }

  /** Hides [recipient] and says so with a toast. */
  @JvmStatic
  fun hideWithToast(context: Context, recipient: Recipient) {
    setHidden(context, recipient.id, true)
    Toast.makeText(context, context.getString(R.string.HiddenContacts__hidden_toast, recipient.getDisplayName(context)), Toast.LENGTH_SHORT).show()
  }

  /** Settings > Chats > Hidden contacts: a checklist; unticking someone unhides them. [onChanged] gets the new summary. */
  @JvmStatic
  fun showManager(context: Context, onChanged: (String) -> Unit) {
    val ids = hidden(context).toList()
    if (ids.isEmpty()) {
      MaterialAlertDialogBuilder(context)
        .setTitle(R.string.HiddenContacts__title)
        .setMessage(R.string.HiddenContacts__empty)
        .setPositiveButton(android.R.string.ok, null)
        .show()
      return
    }

    SignalExecutors.BOUNDED.execute {
      val people = ids.map { RecipientId.from(it) }
        .map { it to Recipient.resolved(it).getDisplayName(context) }
        .sortedBy { it.second.lowercase() }

      ThreadUtil.runOnMain {
        val checked = BooleanArray(people.size) { true }
        MaterialAlertDialogBuilder(context)
          .setTitle(R.string.HiddenContacts__title)
          .setMultiChoiceItems(people.map { it.second }.toTypedArray(), checked) { _, which, isChecked -> checked[which] = isChecked }
          .setPositiveButton(R.string.HiddenContacts__save) { _, _ ->
            people.forEachIndexed { index, person ->
              if (!checked[index]) setHidden(context, person.first, false)
            }
            onChanged(summary(context))
          }
          .setNegativeButton(android.R.string.cancel, null)
          .show()
      }
    }
  }
}
