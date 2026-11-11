package net.mikesimone.backchannel

import android.content.Context
import android.graphics.Bitmap
import com.bumptech.glide.load.MultiTransformation
import com.bumptech.glide.load.Transformation
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import org.signal.core.models.database.AttachmentId
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.mms.Slide
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.util.BlurTransformation
import java.util.concurrent.ConcurrentHashMap

/**
 * Blur media until tapped (Backchannel, Mike 2026-11-11): received photos,
 * videos and GIFs show blurred in the chat and in its All media gallery until
 * you tap one. A tapped item stays clear until the app is closed. Notifications
 * for a blurred chat leave out the image preview.
 *
 * Why: so a photo someone sends doesn't land on screen before you choose to
 * look at it. Your own sent media is never blurred.
 *
 * On or off for every chat in Settings > Chats; each chat can override that in
 * its chat settings. Both are stored on this phone only; nothing is sent.
 */
object MediaBlur {

  private const val PREFS = "backchannel_media_blur"
  private const val KEY_ALL = "blur_all"
  private const val KEY_ON = "blur_on_recipients"
  private const val KEY_OFF = "blur_off_recipients"

  /** Attachments the chat screen asked to blur, by attachment id. */
  private val candidates: MutableMap<AttachmentId, Boolean> = ConcurrentHashMap()

  /** Attachments tapped open this session. */
  private val revealed: MutableSet<AttachmentId> = ConcurrentHashMap.newKeySet()

  @Volatile
  private var cache: Prefs? = null

  @Volatile
  private var transformation: Transformation<Bitmap>? = null

  private data class Prefs(val all: Boolean, val on: Set<String>, val off: Set<String>)

  private fun prefs(context: Context): Prefs {
    return cache ?: context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let {
      Prefs(
        all = it.getBoolean(KEY_ALL, false),
        on = it.getStringSet(KEY_ON, emptySet())?.toSet() ?: emptySet(),
        off = it.getStringSet(KEY_OFF, emptySet())?.toSet() ?: emptySet()
      )
    }.also { cache = it }
  }

  private fun save(context: Context, prefs: Prefs) {
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
      .putBoolean(KEY_ALL, prefs.all)
      .putStringSet(KEY_ON, prefs.on)
      .putStringSet(KEY_OFF, prefs.off)
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

  /** Whether media in this chat is blurred: the chat's own choice, else the Settings > Chats switch. */
  @JvmStatic
  fun isEnabled(context: Context, recipientId: RecipientId): Boolean {
    val prefs = prefs(context)
    val id = recipientId.serialize()
    return when {
      prefs.on.contains(id) -> true
      prefs.off.contains(id) -> false
      else -> prefs.all
    }
  }

  /** The chat settings switch. Stores an explicit choice for this chat, so it holds whatever Settings > Chats says. */
  @JvmStatic
  fun setEnabled(context: Context, recipientId: RecipientId, enabled: Boolean) {
    val prefs = prefs(context)
    val id = recipientId.serialize()
    save(
      context,
      prefs.copy(
        on = if (enabled) prefs.on + id else prefs.on - id,
        off = if (enabled) prefs.off - id else prefs.off + id
      )
    )
  }

  /** Called by the chat screen before it shows a message's media. */
  @JvmStatic
  fun bind(context: Context, record: MessageRecord, threadRecipientId: RecipientId) {
    if (record !is MmsMessageRecord) return
    val blur = !record.isOutgoing && isEnabled(context, threadRecipientId)
    for (slide in record.slideDeck.thumbnailSlides) {
      val attachment = slide.asAttachment() as? DatabaseAttachment ?: continue
      bind(attachment, blur)
    }
  }

  /** Called by the All media gallery before it shows an item. */
  @JvmStatic
  fun bind(context: Context, slide: Slide, threadRecipientId: RecipientId, isOutgoing: Boolean) {
    val attachment = slide.asAttachment() as? DatabaseAttachment ?: return
    bind(attachment, !isOutgoing && isEnabled(context, threadRecipientId))
  }

  private fun bind(attachment: DatabaseAttachment, blur: Boolean) {
    if (blur && attachment.stickerLocator == null) {
      candidates[attachment.attachmentId] = true
    } else {
      candidates.remove(attachment.attachmentId)
    }
  }

  @JvmStatic
  fun shouldBlur(slide: Slide?): Boolean {
    val attachment = slide?.asAttachment() as? DatabaseAttachment ?: return false
    return candidates[attachment.attachmentId] == true && !revealed.contains(attachment.attachmentId)
  }

  @JvmStatic
  fun reveal(slide: Slide?) {
    val attachment = slide?.asAttachment() as? DatabaseAttachment ?: return
    revealed.add(attachment.attachmentId)
  }

  /** Center-crop, then a strong blur. One shared instance so Glide's memory cache can reuse results. */
  @JvmStatic
  fun transformation(context: Context): Transformation<Bitmap> {
    return transformation ?: synchronized(this) {
      transformation ?: MultiTransformation(CenterCrop(), BlurTransformation(context.applicationContext, 0.25f, BlurTransformation.MAX_RADIUS)).also { transformation = it }
    }
  }
}
