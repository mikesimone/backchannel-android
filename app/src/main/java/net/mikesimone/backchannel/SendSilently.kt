package net.mikesimone.backchannel

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import com.google.android.material.color.MaterialColors
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.notifications.v2.MessageNotification
import org.thoughtcrime.securesms.notifications.v2.NotificationConversation

/**
 * Send silently (Backchannel, Mike 2026-11-11): long-press Send and pick
 * "Send silently". The message goes out with an invisible marker at the end
 * of its text. When Backchannel receives a message with that marker, its
 * notification makes no sound or vibration and doesn't pop up; it still shows
 * in the notification shade and counts as unread.
 *
 * Why: for a late-night or low-priority message that shouldn't buzz anyone.
 * Only Backchannel knows the marker, so people on other Signal apps get a
 * normal notification (and the marker is invisible to them). Nothing in the
 * Signal protocol changes: the marker is just part of the message text.
 *
 * Very long messages (sent as a long-text attachment) may lose the marker and
 * notify as usual.
 */
object SendSilently {

  /** INVISIBLE SEPARATOR, INVISIBLE PLUS, INVISIBLE SEPARATOR: zero-width, and never typed by people. */
  const val MARKER = "⁣⁤⁣"

  @JvmStatic
  fun mark(body: String): String = if (isMarked(body)) body else body + MARKER

  @JvmStatic
  fun isMarked(body: CharSequence?): Boolean = body != null && body.contains(MARKER)

  /** True when every new message in this notification was sent silently. */
  @JvmStatic
  fun isSilent(conversation: NotificationConversation): Boolean {
    val newItems = conversation.notificationItems.filter { it.isNewNotification }
    return newItems.isNotEmpty() && newItems.all { it is MessageNotification && it.isSentSilently }
  }

  /** The long-press menu entry: title plus a smaller second line saying it only works with Backchannel. */
  @JvmStatic
  fun menuTitle(context: Context): CharSequence {
    val builder = SpannableStringBuilder(context.getString(R.string.SendSilently__menu))
    val start = builder.length
    builder.append('\n').append(context.getString(R.string.SendSilently__menu_explain))
    builder.setSpan(RelativeSizeSpan(0.8f), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    val secondary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)
    if (secondary != 0) {
      builder.setSpan(ForegroundColorSpan(secondary), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return builder
  }
}
