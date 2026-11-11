package net.mikesimone.backchannel

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.signal.core.models.database.AttachmentId
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.util.MediaUtil
import org.thoughtcrime.securesms.util.SaveAttachmentUtil

/**
 * Save received photos and videos to the gallery (Backchannel, Mike 2026-11-11):
 * when a received photo or video finishes downloading, a copy goes into the
 * phone's gallery (Pictures/Backchannel or Movies/Backchannel), the same place
 * the manual Save action puts it.
 *
 * Why: so photos people send end up in the phone's gallery and its backups
 * without saving each one by hand.
 *
 * Off by default. One switch in Settings > Chats; each chat can say Default,
 * Always or Never in its chat settings (stored on this phone only). Never saves
 * your own sent media, view-once media, stories, stickers, GIFs, or anything
 * from a locked chat. Disappearing-message chats ARE saved (Mike 2026-11-11:
 * people turn the timer on to save phone space, not for secrecy; matches
 * Backchannel Web).
 */
object AutoSaveMedia {

  private val TAG = Log.tag(AutoSaveMedia::class.java)

  private const val PREFS = "backchannel_auto_save_media"
  private const val KEY_ALL = "save_all"
  private const val KEY_ALWAYS = "always_recipients"
  private const val KEY_NEVER = "never_recipients"

  /** A chat's own choice. [DEFAULT] follows the Settings > Chats switch. */
  enum class ChatSetting { DEFAULT, ALWAYS, NEVER }

  private data class Prefs(val all: Boolean, val always: Set<String>, val never: Set<String>)

  @Volatile
  private var cache: Prefs? = null

  private fun prefs(context: Context): Prefs {
    return cache ?: context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let {
      Prefs(
        all = it.getBoolean(KEY_ALL, false),
        always = it.getStringSet(KEY_ALWAYS, emptySet())?.toSet() ?: emptySet(),
        never = it.getStringSet(KEY_NEVER, emptySet())?.toSet() ?: emptySet()
      )
    }.also { cache = it }
  }

  private fun save(context: Context, prefs: Prefs) {
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
      .putBoolean(KEY_ALL, prefs.all)
      .putStringSet(KEY_ALWAYS, prefs.always)
      .putStringSet(KEY_NEVER, prefs.never)
      .apply()
    cache = prefs
  }

  /** The Settings > Chats switch. */
  @JvmStatic
  fun isEnabledForAll(context: Context): Boolean = prefs(context).all

  @JvmStatic
  fun setEnabledForAll(context: Context, enabled: Boolean) {
    save(context, prefs(context).copy(all = enabled))
  }

  @JvmStatic
  fun getChatSetting(context: Context, recipientId: RecipientId): ChatSetting {
    val prefs = prefs(context)
    val id = recipientId.serialize()
    return when {
      prefs.always.contains(id) -> ChatSetting.ALWAYS
      prefs.never.contains(id) -> ChatSetting.NEVER
      else -> ChatSetting.DEFAULT
    }
  }

  @JvmStatic
  fun setChatSetting(context: Context, recipientId: RecipientId, setting: ChatSetting) {
    val prefs = prefs(context)
    val id = recipientId.serialize()
    save(
      context,
      prefs.copy(
        always = if (setting == ChatSetting.ALWAYS) prefs.always + id else prefs.always - id,
        never = if (setting == ChatSetting.NEVER) prefs.never + id else prefs.never - id
      )
    )
  }

  /** Whether received photos and videos in this chat get saved: the chat's own choice, else the Settings > Chats switch. */
  @JvmStatic
  fun isEnabled(context: Context, recipientId: RecipientId): Boolean {
    return when (getChatSetting(context, recipientId)) {
      ChatSetting.ALWAYS -> true
      ChatSetting.NEVER -> false
      ChatSetting.DEFAULT -> prefs(context).all
    }
  }

  /** Called when an attachment finishes downloading. Saves it in the background if it qualifies; never throws. */
  @JvmStatic
  fun onDownloaded(context: Context, messageId: Long, attachmentId: AttachmentId) {
    val prefs = prefs(context)
    if (!prefs.all && prefs.always.isEmpty()) return

    SignalExecutors.BOUNDED.execute {
      try {
        maybeSave(context.applicationContext, messageId, attachmentId)
      } catch (t: Throwable) {
        Log.w(TAG, "[$attachmentId] Couldn't save to the gallery", t)
      }
    }
  }

  private fun maybeSave(context: Context, messageId: Long, attachmentId: AttachmentId) {
    val attachment = SignalDatabase.attachments.getAttachment(attachmentId) ?: return
    val contentType = attachment.contentType ?: return
    if (!MediaUtil.isImageOrVideoType(contentType) || MediaUtil.isGif(contentType)) return
    if (attachment.stickerLocator != null || attachment.videoGif || attachment.quote) return
    val uri = attachment.uri ?: return

    val record = SignalDatabase.messages.getMessageRecordOrNull(messageId) as? MmsMessageRecord ?: return
    if (record.isOutgoing || record.isViewOnce || record.storyType.isStory) return

    val threadRecipientId = SignalDatabase.threads.getRecipientIdForThreadId(record.threadId) ?: return
    if (ChatLock.isLocked(context, threadRecipientId) || !isEnabled(context, threadRecipientId)) return

    val date = if (record.dateSent > 0) record.dateSent else System.currentTimeMillis()
    val result = runBlocking {
      SaveAttachmentUtil.saveAttachments(setOf(SaveAttachmentUtil.SaveAttachment(uri, contentType, date, attachment.fileName)))
    }
    Log.i(TAG, "[$attachmentId] Saved to the gallery: $result")
  }
}
