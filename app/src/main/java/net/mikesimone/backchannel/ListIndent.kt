package net.mikesimone.backchannel

import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.LeadingMarginSpan

/**
 * Hanging indents for list lines in message bubbles (Backchannel, Mike
 * 2026-11-03, same as the web page's F42): a wrapped list item lines up under
 * its text, not under its bullet or number. Display only; Signal can't carry it.
 */
object ListIndent {

  private val ITEM = Regex("^[\\u2003\\t ]*(?:[•◦▪*-]|\\d{1,3}[.)])[ \\u2003]+(?:[☐☑] )?")

  @JvmStatic
  fun apply(text: CharSequence, paint: TextPaint): CharSequence {
    if (text.isEmpty()) return text
    var out: Spannable? = null
    var start = 0
    while (start < text.length) {
      var end = indexOf(text, '\n', start)
      val paragraphEnd = if (end < 0) text.length else end + 1
      if (end < 0) end = text.length
      val match = ITEM.find(text.subSequence(start, end))
      if (match != null && match.value.length < end - start) {
        val width = paint.measureText(text, start, start + match.value.length).toInt()
        if (width > 0) {
          val target = out ?: (if (text is Spannable) text else SpannableString(text)).also { out = it }
          target.setSpan(LeadingMarginSpan.Standard(0, width), start, paragraphEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
      }
      start = paragraphEnd
    }
    return out ?: text
  }

  private fun indexOf(text: CharSequence, c: Char, from: Int): Int {
    for (i in from until text.length) if (text[i] == c) return i
    return -1
  }
}
