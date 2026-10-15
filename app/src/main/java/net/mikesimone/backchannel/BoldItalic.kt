package net.mikesimone.backchannel

import android.graphics.Typeface
import android.text.Spannable
import android.text.style.StyleSpan
import org.thoughtcrime.securesms.conversation.MessageStyler
import org.thoughtcrime.securesms.database.model.databaseprotos.BodyRangeList.BodyRange

/**
 * Bold italic in one step (Backchannel P7). If the selection is already
 * both bold and italic, both are removed; otherwise whichever is missing
 * is added, leaving the other as it is.
 */
object BoldItalic {

  @JvmStatic
  fun toggle(text: Spannable, start: Int, end: Int) {
    if (start >= end) {
      return
    }

    val bold = isCovered(text, Typeface.BOLD, start, end)
    val italic = isCovered(text, Typeface.ITALIC, start, end)

    if (bold && italic) {
      MessageStyler.toggleStyle(BodyRange.Style.BOLD, text, start, end)
      MessageStyler.toggleStyle(BodyRange.Style.ITALIC, text, start, end)
    } else {
      if (!bold) MessageStyler.toggleStyle(BodyRange.Style.BOLD, text, start, end)
      if (!italic) MessageStyler.toggleStyle(BodyRange.Style.ITALIC, text, start, end)
    }
  }

  /** True when every character in [start, end) has a [StyleSpan] of [typefaceStyle]. */
  @JvmStatic
  fun isCovered(text: Spannable, typefaceStyle: Int, start: Int, end: Int): Boolean {
    val ranges = text.getSpans(start, end, StyleSpan::class.java)
      .filter { it.style == typefaceStyle }
      .map { text.getSpanStart(it) to text.getSpanEnd(it) }
      .sortedBy { it.first }

    var covered = start
    for ((s, e) in ranges) {
      if (s > covered) {
        return false
      }
      covered = maxOf(covered, e)
      if (covered >= end) {
        return true
      }
    }
    return covered >= end
  }
}
