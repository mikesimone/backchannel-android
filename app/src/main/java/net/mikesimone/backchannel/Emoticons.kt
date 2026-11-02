package net.mikesimone.backchannel

import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText

/**
 * Typed emoticons become emoji (Backchannel, Mike 2026-11-02).
 *
 * Emoticons that end in punctuation, such as :) ;) :( , change as soon as
 * the last character goes in. Ones that end in a letter or digit, such as
 * :D :P <3 , change when a space or new line follows, so words like ":Pizza"
 * are left alone. An emoticon only counts when it stands on its own, after a
 * space, a new line or the start of the text. Backspace right after an
 * instant change puts the typed characters back.
 */
object Emoticons {

  private val INSTANT: Map<String, String> = mapOf(
    ":)" to "😃",
    ":-)" to "😃",
    "=)" to "😃",
    ":(" to "😞",
    ":-(" to "😞",
    ";)" to "😉",
    ";-)" to "😉",
    ":'(" to "😢",
    ":|" to "😐",
    ":-|" to "😐",
    ">:(" to "😠",
    "O:)" to "😇",
    "o:)" to "😇",
    ":-/" to "😕",
    ":-*" to "😘"
  )

  private val ON_SPACE: Map<String, String> = mapOf(
    ":D" to "😁",
    ":-D" to "😁",
    ":P" to "😛",
    ":p" to "😛",
    ":-P" to "😛",
    ":-p" to "😛",
    ";P" to "😜",
    ";p" to "😜",
    ":O" to "😮",
    ":o" to "😮",
    ":-O" to "😮",
    ":-o" to "😮",
    "XD" to "😆",
    "xD" to "😆",
    "<3" to "❤️",
    "</3" to "💔",
    ":*" to "😘"
  )

  data class Match(val start: Int, val end: Int, val emoji: String, val instant: Boolean)

  /**
   * The emoticon finished by the character just typed at [caret] - 1, if any.
   * For an on-space emoticon, [Match.end] is where the space starts.
   */
  @JvmStatic
  fun match(text: CharSequence, caret: Int): Match? {
    if (caret <= 0 || caret > text.length) {
      return null
    }

    val typed = text[caret - 1]
    if (typed == ' ' || typed == '\n') {
      val end = caret - 1
      val start = wordStart(text, end)
      val emoji = ON_SPACE[text.substring(start, end)] ?: return null
      return Match(start, end, emoji, instant = false)
    }

    val start = wordStart(text, caret)
    val emoji = INSTANT[text.substring(start, caret)] ?: return null
    return Match(start, caret, emoji, instant = true)
  }

  private fun wordStart(text: CharSequence, end: Int): Int {
    var start = end
    while (start > 0 && !Character.isWhitespace(text[start - 1])) {
      start--
    }
    return start
  }

  class Watcher(private val editText: EditText) : TextWatcher {

    private class Undo(val start: Int, val end: Int, val typed: String)

    private var undo: Undo? = null
    private var insertedAt = -1
    private var undoPending = false
    private var busy = false

    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) = Unit

    override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
      if (busy) return
      insertedAt = -1
      undoPending = false
      val u = undo
      if (before == 0 && count == 1) {
        insertedAt = start + 1
        undo = null
      } else if (count == 0 && before > 0 && u != null && start >= u.start && start + before == u.end) {
        undoPending = true
      } else if (before != 0 || count != 0) {
        undo = null
      }
    }

    override fun afterTextChanged(s: Editable) {
      if (busy) return
      busy = true
      try {
        val u = undo
        if (undoPending && u != null) {
          undo = null
          val start = minOf(u.start, s.length)
          val end = minOf(maxOf(start, editText.selectionStart), s.length)
          s.replace(start, end, u.typed)
          editText.setSelection(start + u.typed.length)
        } else if (insertedAt >= 0 && editText.selectionStart == insertedAt && editText.selectionEnd == insertedAt) {
          val m = match(s, insertedAt)
          if (m != null) {
            val typed = s.subSequence(m.start, m.end).toString()
            s.replace(m.start, m.end, m.emoji)
            val end = m.start + m.emoji.length
            editText.setSelection(if (m.instant) end else end + 1)
            undo = if (m.instant) Undo(m.start, end, typed) else null
          }
        }
      } finally {
        busy = false
        insertedAt = -1
        undoPending = false
      }
    }
  }
}
