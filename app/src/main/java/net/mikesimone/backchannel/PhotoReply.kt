package net.mikesimone.backchannel

import android.content.Context
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.signal.core.util.ThreadUtil
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.MediaTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.mms.QuoteModel
import org.thoughtcrime.securesms.mms.SlideFactory
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.sms.MessageSender
import kotlin.time.Duration.Companion.seconds

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
 */
object PhotoReply {

  private val TAG = Log.tag(PhotoReply::class.java)

  @JvmStatic
  fun canReply(item: MediaTable.MediaRecord): Boolean {
    return item.attachment != null && item.threadId > 0 && item.messageId > 0
  }

  /** Shows the comment box for [item]. (Emoji row removed: Mike 2026-11-06 didn't like it.) */
  @JvmStatic
  fun show(fragment: Fragment, item: MediaTable.MediaRecord) {
    val context = fragment.requireContext()
    val dialog = BottomSheetDialog(context)
    val pad = dp(context, 16)

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

    val commentRow = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }
    val input = EditText(context).apply {
      setHint(R.string.PhotoReply__comment_hint)
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
      maxLines = 4
      imeOptions = EditorInfo.IME_ACTION_SEND
    }
    val sendButton = ImageButton(context).apply {
      setImageDrawable(ContextCompat.getDrawable(context, R.drawable.symbol_send_fill_24))
      background = null
      contentDescription = context.getString(R.string.PhotoReply__send)
    }
    val sendComment = {
      val text = input.text?.toString()?.trim().orEmpty()
      if (text.isNotEmpty()) {
        dialog.dismiss()
        send(context.applicationContext, item, text)
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
    root.addView(commentRow)

    dialog.setContentView(root)
    dialog.show()
  }

  private fun send(context: Context, item: MediaTable.MediaRecord, text: String) {
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
          val author = if (record.isOutgoing) Recipient.self().id else record.fromRecipient.id
          val quote = QuoteModel(record.dateSent, author, record.body, false, attachment, null, QuoteModel.Type.NORMAL, record.messageRanges)
          val message = OutgoingMessage(
            threadRecipient = threadRecipient,
            sentTimeMillis = System.currentTimeMillis(),
            body = text,
            expiresIn = threadRecipient.expiresInSeconds.seconds.inWholeMilliseconds,
            isUrgent = true,
            isSecure = true,
            outgoingQuote = quote,
            attachments = listOf(slide.asAttachment())
          )
          MessageSender.send(context, message, item.threadId, MessageSender.SendType.SIGNAL, null, null)
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
