package net.mikesimone.backchannel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import net.mikesimone.backchannel.immich.ImmichPickerActivity
import org.signal.core.util.ThreadUtil
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.signal.glide.decryptableuri.DecryptableUri
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.attachments.Attachment
import org.thoughtcrime.securesms.database.MediaTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.StickerTable
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.database.model.StickerRecord
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.mms.PartAuthority
import org.thoughtcrime.securesms.mms.QuoteModel
import org.thoughtcrime.securesms.mms.SlideFactory
import org.thoughtcrime.securesms.mms.StickerSlide
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.sms.MessageSender
import org.thoughtcrime.securesms.stickers.StickerLocator
import org.thoughtcrime.securesms.util.MediaUtil
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import org.signal.core.ui.R as CoreUiR

/**
 * Reply to one photo or video of a multi-photo message (Backchannel, Mike
 * 2026-11-04). Signal can only react to or quote a whole message. From the
 * photo viewer, this sends a reply that quotes the original message and
 * carries that one photo again, with your comment as its text, so
 * every Signal app shows which photo it was about.
 *
 * The quote names the chosen photo as its thumbnail too. Signal-Android
 * receivers replace that with the message's first photo, which is why the
 * photo itself is attached as well.
 *
 * Like a normal reply (Mike 2026-11-06): add photos, videos, GIFs or files
 * (picker, Immich, or a GIF/sticker straight from the keyboard), or one of
 * your Signal stickers. Photos, videos and GIFs ride in the same message after
 * the chosen photo. A sticker or a file can't share a message with photos, so
 * each follows as its own message that replies to the photo reply (same as
 * Backchannel web).
 */
object PhotoReply {

  private val TAG = Log.tag(PhotoReply::class.java)

  private val KEYBOARD_TYPES = arrayOf("image/*", "video/*")

  private data class Extra(val uri: Uri, val mimeType: String)

  @JvmStatic
  fun canReply(item: MediaTable.MediaRecord): Boolean {
    return item.attachment != null && item.threadId > 0 && item.messageId > 0
  }

  /** Shows the comment box for [item]. (Emoji row removed: Mike 2026-11-06 didn't like it.) */
  @JvmStatic
  fun show(fragment: Fragment, item: MediaTable.MediaRecord) {
    val context = fragment.requireContext()
    val appContext = context.applicationContext
    val registry = fragment.requireActivity().activityResultRegistry
    val dialog = BottomSheetDialog(context)
    val pad = dp(context, 16)

    val extras = mutableListOf<Extra>()
    var sticker: StickerRecord? = null

    val root = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(pad)
    }

    root.addView(
      TextView(context).apply {
        setText(R.string.PhotoReply__title)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setPadding(0, 0, 0, pad / 2)
      }
    )

    val added = TextView(context).apply {
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
      setPadding(0, 0, 0, pad / 2)
      visibility = android.view.View.GONE
    }
    val refreshAdded = {
      val parts = mutableListOf<String>()
      if (extras.isNotEmpty()) parts += context.resources.getQuantityString(R.plurals.PhotoReply__files, extras.size, extras.size)
      if (sticker != null) parts += context.getString(R.string.PhotoReply__sticker)
      if (parts.isEmpty()) {
        added.visibility = android.view.View.GONE
      } else {
        added.text = context.getString(R.string.PhotoReply__adding, parts.joinToString(", "))
        added.visibility = android.view.View.VISIBLE
      }
    }
    added.setOnClickListener {
      extras.clear()
      sticker = null
      refreshAdded()
    }

    val addUris = { uris: List<Uri> ->
      if (uris.isNotEmpty()) {
        SignalExecutors.BOUNDED.execute {
          val copied = uris.mapNotNull { copyToBlob(appContext, it) }
          ThreadUtil.runOnMain {
            if (copied.size < uris.size) Toast.makeText(context, R.string.PhotoReply__cant_add, Toast.LENGTH_SHORT).show()
            extras += copied
            refreshAdded()
          }
        }
      }
    }

    val launchers = mutableListOf<ActivityResultLauncher<*>>()
    val pickFiles = registry.register("photo_reply_files_" + UUID.randomUUID(), ActivityResultContracts.GetMultipleContents()) { addUris(it) }
    val pickImmich = registry.register("photo_reply_immich_" + UUID.randomUUID(), ImmichPickerActivity.Contract) { addUris(it) }
    launchers += pickFiles
    launchers += pickImmich

    val commentRow = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }
    val input = AppCompatEditText(context).apply {
      setHint(R.string.PhotoReply__comment_hint)
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
      maxLines = 4
      imeOptions = EditorInfo.IME_ACTION_SEND
    }
    // GIFs and stickers from the keyboard (Gboard etc.) become attachments.
    ViewCompat.setOnReceiveContentListener(input, KEYBOARD_TYPES) { _, payload ->
      val split = payload.partition { it.uri != null }
      val content = split.first
      if (content != null) {
        val clip = content.clip
        addUris((0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri })
      }
      split.second
    }

    val sendButton = ImageButton(context).apply {
      setImageDrawable(ContextCompat.getDrawable(context, R.drawable.symbol_send_fill_24))
      background = null
      contentDescription = context.getString(R.string.PhotoReply__send)
    }
    val sendComment = {
      val text = input.text?.toString()?.trim().orEmpty()
      if (text.isNotEmpty() || extras.isNotEmpty() || sticker != null) {
        dialog.dismiss()
        send(appContext, item, text, extras.toList(), sticker)
      }
    }
    sendButton.setOnClickListener { sendComment() }
    input.setOnEditorActionListener { _, actionId, _ ->
      if (actionId == EditorInfo.IME_ACTION_SEND) {
        sendComment()
        true
      } else {
        false
      }
    }
    commentRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    commentRow.addView(sendButton, LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)))

    val buttonRow = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }
    fun button(icon: Int, label: Int, onClick: () -> Unit) {
      buttonRow.addView(
        ImageButton(context).apply {
          setImageDrawable(ContextCompat.getDrawable(context, icon))
          background = null
          contentDescription = context.getString(label)
          setOnClickListener { onClick() }
        },
        LinearLayout.LayoutParams(dp(context, 48), dp(context, 48))
      )
    }
    button(CoreUiR.drawable.symbol_plus_24, R.string.PhotoReply__attach) { pickFiles.launch("*/*") }
    button(R.drawable.symbol_album_24, R.string.AttachmentKeyboard_immich) { pickImmich.launch(Unit) }
    button(CoreUiR.drawable.symbol_sticker_24, R.string.PhotoReply__sticker) {
      pickSticker(context) {
        sticker = it
        refreshAdded()
      }
    }

    root.addView(added)
    root.addView(commentRow)
    root.addView(buttonRow)

    dialog.setOnDismissListener { launchers.forEach { it.unregister() } }
    dialog.setContentView(root)
    dialog.show()
  }

  /** A simple grid of your installed Signal stickers, recently used first. */
  private fun pickSticker(context: Context, onPicked: (StickerRecord) -> Unit) {
    SignalExecutors.BOUNDED.execute {
      val stickers = loadStickers()
      ThreadUtil.runOnMain {
        if (stickers.isEmpty()) {
          Toast.makeText(context, R.string.PhotoReply__no_stickers, Toast.LENGTH_SHORT).show()
          return@runOnMain
        }
        val size = dp(context, 72)
        var dialog: AlertDialog? = null
        val list = RecyclerView(context).apply {
          layoutManager = GridLayoutManager(context, 4)
          adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
              val image = ImageView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, size)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(context, 6))
              }
              return object : RecyclerView.ViewHolder(image) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
              val record = stickers[position]
              val image = holder.itemView as ImageView
              image.contentDescription = record.emoji
              Glide.with(image).load(DecryptableUri(record.uri)).into(image)
              image.setOnClickListener {
                onPicked(record)
                dialog?.dismiss()
              }
            }

            override fun getItemCount(): Int = stickers.size
          }
          setPadding(dp(context, 8))
        }
        dialog = MaterialAlertDialogBuilder(context)
          .setTitle(R.string.PhotoReply__sticker)
          .setView(list)
          .setNegativeButton(android.R.string.cancel, null)
          .show()
      }
    }
  }

  private fun loadStickers(): List<StickerRecord> {
    val seen = HashSet<Long>()
    val out = mutableListOf<StickerRecord>()
    fun read(cursor: android.database.Cursor) {
      StickerTable.StickerRecordReader(cursor).use { reader ->
        while (true) {
          val record = reader.getNext() ?: break
          if (seen.add(record.rowId)) out += record
        }
      }
    }
    try {
      read(SignalDatabase.stickers.getRecentlyUsedStickers(24))
      val packIds = mutableListOf<String>()
      SignalDatabase.stickers.getInstalledStickerPacks().use { cursor ->
        val column = cursor.getColumnIndexOrThrow(StickerTable.PACK_ID)
        while (cursor.moveToNext()) packIds += cursor.getString(column)
      }
      packIds.forEach { read(SignalDatabase.stickers.getStickersForPack(it)) }
    } catch (e: Exception) {
      Log.w(TAG, "Couldn't read stickers", e)
    }
    return out
  }

  /** Copies a picked file into Backchannel's own storage right away, so a keyboard's or app's temporary access can't expire before sending. */
  private fun copyToBlob(context: Context, uri: Uri): Extra? {
    return try {
      if (PartAuthority.isLocalUri(uri)) {
        val type = PartAuthority.getAttachmentContentType(context, uri) ?: MediaUtil.getMimeType(context, uri) ?: MediaUtil.UNKNOWN
        return Extra(uri, type)
      }
      val resolver = context.contentResolver
      val type = resolver.getType(uri) ?: MediaUtil.getMimeType(context, uri) ?: MediaUtil.UNKNOWN
      var name: String? = null
      var size = 0L
      resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
          name = cursor.getString(0)
          size = if (cursor.isNull(1)) 0L else cursor.getLong(1)
        }
      }
      val stream = resolver.openInputStream(uri) ?: return null
      val blob = stream.use {
        if (size > 0) {
          AppDependencies.blobs.forData(it, size).withMimeType(type).withFileName(name).createForSingleSessionOnDisk(context)
        } else {
          // Keyboard GIFs often don't report a size; they're small, so read them whole.
          val bytes = it.readBytes()
          AppDependencies.blobs.forData(bytes).withMimeType(type).withFileName(name ?: "file").createForSingleSessionInMemory()
        }
      }
      Extra(blob, type)
    } catch (e: Exception) {
      Log.w(TAG, "Couldn't add that file", e)
      null
    }
  }

  private fun isVisual(mimeType: String): Boolean {
    return MediaUtil.isImageType(mimeType) || MediaUtil.isVideoType(mimeType)
  }

  private fun send(context: Context, item: MediaTable.MediaRecord, text: String, extras: List<Extra>, sticker: StickerRecord?) {
    val attachment = item.attachment ?: return
    SignalExecutors.BOUNDED.execute {
      val sent = try {
        val record = SignalDatabase.messages.getMessageRecordOrNull(item.messageId) as? MmsMessageRecord
        val threadRecipient = SignalDatabase.threads.getRecipientForThreadId(item.threadId)
        val slide = SlideFactory.getSlide(context, attachment.contentType, attachment.uri ?: throw IllegalStateException("No data for this photo"), attachment.width, attachment.height, null)
        if (record == null || threadRecipient == null || slide == null) {
          Log.w(TAG, "Missing message, chat or photo data; not sending")
          false
        } else {
          val expiresIn = threadRecipient.expiresInSeconds.seconds.inWholeMilliseconds
          val author = if (record.isOutgoing) Recipient.self().id else record.fromRecipient.id
          val quote = QuoteModel(record.dateSent, author, record.body, false, attachment, null, QuoteModel.Type.NORMAL, record.messageRanges)

          val visual = extras.filter { isVisual(it.mimeType) }
          val files = extras.filterNot { isVisual(it.mimeType) }
          val attachments = mutableListOf<Attachment>(slide.asAttachment())
          visual.forEach { extra ->
            SlideFactory.getSlide(context, extra.mimeType, extra.uri, 0, 0, null)?.let { attachments += it.asAttachment() }
          }

          val sentAt = System.currentTimeMillis()
          val message = OutgoingMessage(
            threadRecipient = threadRecipient,
            sentTimeMillis = sentAt,
            body = text,
            expiresIn = expiresIn,
            isUrgent = true,
            isSecure = true,
            outgoingQuote = quote,
            attachments = attachments
          )
          MessageSender.send(context, message, item.threadId, MessageSender.SendType.SIGNAL, null, null)

          // Stickers and files follow as replies to the photo reply, so their quote shows the chosen photo.
          val followQuote = QuoteModel(sentAt, Recipient.self().id, text, false, attachment, null, QuoteModel.Type.NORMAL, null)
          val followUps = mutableListOf<Attachment>()
          files.forEach { extra ->
            SlideFactory.getSlide(context, extra.mimeType, extra.uri, 0, 0, null)?.let { followUps += it.asAttachment() }
          }
          sticker?.let {
            val locator = StickerLocator(it.packId, it.packKey, it.stickerId, it.emoji)
            followUps += StickerSlide(context, it.uri, it.size, locator, it.contentType.takeIf { type -> type.isNotBlank() } ?: MediaUtil.IMAGE_WEBP).asAttachment()
            SignalDatabase.stickers.updateStickerLastUsedTime(it.rowId, System.currentTimeMillis())
          }
          followUps.forEachIndexed { index, followUp ->
            val follow = OutgoingMessage(
              threadRecipient = threadRecipient,
              sentTimeMillis = sentAt + index + 1,
              body = "",
              expiresIn = expiresIn,
              isUrgent = true,
              isSecure = true,
              outgoingQuote = followQuote,
              attachments = listOf(followUp)
            )
            MessageSender.send(context, follow, item.threadId, MessageSender.SendType.SIGNAL, null, null)
          }
          true
        }
      } catch (e: Exception) {
        Log.w(TAG, "Couldn't send a photo reply", e)
        false
      }
      ThreadUtil.runOnMain {
        Toast.makeText(context, if (sent) R.string.PhotoReply__sent else R.string.PhotoReply__failed, Toast.LENGTH_SHORT).show()
      }
    }
  }

  private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
