package net.mikesimone.backchannel

import android.graphics.Typeface
import android.text.Annotation
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
import net.mikesimone.backchannel.UnicodeStyles.Style
import org.thoughtcrime.securesms.components.mention.MentionAnnotation
import org.thoughtcrime.securesms.conversation.MessageStyler
import org.thoughtcrime.securesms.database.model.databaseprotos.BodyRangeList.BodyRange

/**
 * Markdown shortcuts and Unicode copies (Backchannel P9, same rules as the
 * web page's F33).
 *
 * Typing *bold*, _italic_, ~strikethrough~ or `monospace` applies Signal's
 * own style as the closing mark goes in; nested marks combine, so _*both*_
 * is bold italic. Backspace right after takes it back to the typed marks.
 * Marks already in text (pasted, or left at send) get the same treatment.
 * Copying a styled message puts Unicode-styled text on the clipboard, and
 * pasting such text turns it back into Signal's styles.
 */
object Markdown {

  private val MARKS = mapOf(
    '*' to BodyRange.Style.BOLD,
    '_' to BodyRange.Style.ITALIC,
    '~' to BodyRange.Style.STRIKETHROUGH,
    '`' to BodyRange.Style.MONOSPACE
  )

  private val LINK = Regex("https?://|www\\.", RegexOption.IGNORE_CASE)

  /** Set while this object edits text, so [Watcher] ignores those edits. */
  private var applying = false

  // --- reading the text -------------------------------------------------------

  private fun isMention(text: Spanned, i: Int): Boolean {
    return text.getSpans(i, i + 1, Annotation::class.java).any {
      MentionAnnotation.isMentionAnnotation(it) && text.getSpanStart(it) <= i && text.getSpanEnd(it) > i
    }
  }

  private fun isMark(text: Spanned, i: Int): Boolean {
    return i in 0 until text.length && MARKS.containsKey(text[i]) && !isMention(text, i)
  }

  private fun isWordChar(text: Spanned, i: Int): Boolean {
    return i in 0 until text.length && Character.isLetterOrDigit(text[i]) && !isMention(text, i)
  }

  private fun Any.isStyle(style: BodyRange.Style): Boolean {
    return when (this) {
      is StyleSpan -> when (this.style) {
        Typeface.BOLD -> style == BodyRange.Style.BOLD
        Typeface.ITALIC -> style == BodyRange.Style.ITALIC
        Typeface.BOLD_ITALIC -> style == BodyRange.Style.BOLD || style == BodyRange.Style.ITALIC
        else -> false
      }
      is StrikethroughSpan -> style == BodyRange.Style.STRIKETHROUGH
      is TypefaceSpan -> this.family == MessageStyler.MONOSPACE && style == BodyRange.Style.MONOSPACE
      else -> false
    }
  }

  private fun hasStyleAt(text: Spanned, style: BodyRange.Style, i: Int): Boolean {
    return text.getSpans(i, i + 1, Any::class.java).any {
      it.isStyle(style) && text.getSpanStart(it) <= i && text.getSpanEnd(it) > i
    }
  }

  private fun stylesAt(text: Spanned, i: Int): Set<Style> {
    return buildSet {
      if (hasStyleAt(text, BodyRange.Style.BOLD, i)) add(Style.BOLD)
      if (hasStyleAt(text, BodyRange.Style.ITALIC, i)) add(Style.ITALIC)
      if (hasStyleAt(text, BodyRange.Style.STRIKETHROUGH, i)) add(Style.STRIKETHROUGH)
      if (hasStyleAt(text, BodyRange.Style.MONOSPACE, i)) add(Style.MONOSPACE)
    }
  }

  private fun inLink(text: CharSequence, i: Int): Boolean {
    var a = i
    while (a > 0 && !Character.isWhitespace(text[a - 1])) a--
    var b = i
    while (b < text.length && !Character.isWhitespace(text[b])) b++
    return LINK.containsMatchIn(text.subSequence(a, b))
  }

  // --- styling ----------------------------------------------------------------

  /** Adds [style] over [start, end) unless every character already has it. */
  private fun addStyle(text: Spannable, style: BodyRange.Style, start: Int, end: Int) {
    if (start >= end) return
    if ((start until end).all { hasStyleAt(text, style, it) }) return
    MessageStyler.toggleStyle(style, text, start, end)
  }

  private fun Style.toBodyRangeStyle(): BodyRange.Style {
    return when (this) {
      Style.BOLD -> BodyRange.Style.BOLD
      Style.ITALIC -> BodyRange.Style.ITALIC
      Style.STRIKETHROUGH -> BodyRange.Style.STRIKETHROUGH
      Style.MONOSPACE -> BodyRange.Style.MONOSPACE
    }
  }

  /** Text typed right after styled text starts plain: style spans ending at [caret] stop growing. */
  private fun stopStylesAt(text: Spannable, caret: Int) {
    for (span in text.getSpans(caret, caret, Any::class.java)) {
      if (MARKS.values.any { span.isStyle(it) } && text.getSpanEnd(span) == caret) {
        text.setSpan(span, text.getSpanStart(span), caret, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      }
    }
  }

  private inline fun edit(text: Editable, block: () -> Unit) {
    val was = applying
    applying = true
    try {
      BaseInputConnection.removeComposingSpans(text)
      block()
    } finally {
      applying = was
    }
  }

  /**
   * The mark just typed before [caret] closes a pair: style the text between
   * and drop both marks. Returns false when it doesn't close a pair.
   */
  @JvmStatic
  fun applyTyped(text: Editable, caret: Int): Boolean {
    val close = caret - 1
    if (!isMark(text, close)) return false
    val mark = text[close]
    var open = close - 1
    while (open >= 0 && text[open] != '\n' && text[open] != mark) open--
    if (open < 0 || text[open] != mark || isMention(text, open)) return false
    if (close - open < 2 ||
      Character.isWhitespace(text[open + 1]) ||
      Character.isWhitespace(text[close - 1]) ||
      isWordChar(text, open - 1)
    ) {
      return false
    }

    edit(text) {
      text.delete(close, close + 1)
      text.delete(open, open + 1)
      addStyle(text, MARKS.getValue(mark), open, close - 1)
      stopStylesAt(text, close - 1)
    }
    return true
  }

  /**
   * Marks already in [from, to): the same rules, innermost pair first, so
   * _*x*_ and *_x_* both work. Text in a link or already monospace is left
   * as it is.
   */
  @JvmStatic
  fun pass(text: Editable, from: Int = 0, to: Int = text.length) {
    edit(text) {
      var end = minOf(to, text.length)
      var i = maxOf(from, 0)
      while (i < end) {
        if (!isMark(text, i) || hasStyleAt(text, BodyRange.Style.MONOSPACE, i) || isWordChar(text, i - 1)) {
          i++
          continue
        }
        val mark = text[i]
        var close = i + 1
        while (close < end && text[close] != '\n' && text[close] != mark) close++
        if (close >= end ||
          text[close] != mark ||
          isMention(text, close) ||
          close - i < 2 ||
          Character.isWhitespace(text[i + 1]) ||
          Character.isWhitespace(text[close - 1]) ||
          isWordChar(text, close + 1) ||
          inLink(text, i)
        ) {
          i++
          continue
        }
        text.delete(close, close + 1)
        text.delete(i, i + 1)
        addStyle(text, MARKS.getValue(mark), i, close - 1)
        end -= 2
        // Look again from here: the text inside may hold another pair.
      }
    }
  }

  /** Called with the compose text just before it's sent. */
  @JvmStatic
  fun passBeforeSend(text: Editable): Editable {
    pass(text)
    return text
  }

  /** After a paste into [start, end): Unicode-styled text becomes Signal's styles, then marks apply. */
  @JvmStatic
  fun afterPaste(text: Editable, start: Int, end: Int) {
    if (start < 0 || end > text.length || start >= end) return
    var to = end
    val pasted = text.subSequence(start, end)
    if (UnicodeStyles.hasUnicode(pasted)) {
      val chars = UnicodeStyles.fromUnicode(pasted)
      edit(text) {
        text.replace(start, end, chars.joinToString("") { it.ch })
        var at = start
        for (c in chars) {
          for (style in c.styles) {
            addStyle(text, style.toBodyRangeStyle(), at, at + c.ch.length)
          }
          at += c.ch.length
        }
        to = at
      }
    }
    pass(text, start, to)
  }

  /** What Copy puts on the clipboard: Unicode-styled text when the messages have styles, else the text as is. */
  @JvmStatic
  fun forCopy(text: CharSequence): CharSequence {
    if (text !is Spanned) return text
    val pieces = mutableListOf<UnicodeStyles.Piece>()
    var i = 0
    while (i < text.length) {
      val styles = stylesAt(text, i)
      var j = i + 1
      while (j < text.length && stylesAt(text, j) == styles) j++
      pieces += UnicodeStyles.Piece(text.subSequence(i, j).toString(), styles)
      i = j
    }
    return if (UnicodeStyles.hasUnicodeStyle(pieces)) UnicodeStyles.toUnicode(pieces) else text
  }

  /** Watches the compose box: converts as the closing mark is typed; Backspace right after undoes. */
  class Watcher(private val editText: EditText) : TextWatcher {

    private class Undo(val saved: CharSequence, val caret: Int, val restore: Int)

    private var undo: Undo? = null
    private var typedCaret = -1
    private var undoPending = false
    private var busy = false

    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) = Unit

    override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
      if (busy || applying) return
      typedCaret = -1
      undoPending = false
      val u = undo
      if (before == 0 && count == 1 && MARKS.containsKey(s[start])) {
        typedCaret = start + 1
      } else if (before == 1 && count == 0 && u != null && start == u.caret - 1) {
        undoPending = true
      } else if (before != 0 || count != 0) {
        undo = null
      }
    }

    override fun afterTextChanged(s: Editable) {
      if (busy || applying) return
      busy = true
      try {
        val u = undo
        if (undoPending && u != null) {
          undo = null
          edit(s) { s.replace(0, s.length, u.saved) }
          editText.setSelection(minOf(u.restore, s.length))
        } else if (typedCaret >= 0 && editText.selectionStart == typedCaret && editText.selectionEnd == typedCaret) {
          val saved = SpannableStringBuilder(s)
          if (applyTyped(s, typedCaret)) {
            val caret = typedCaret - 2
            editText.setSelection(caret)
            undo = Undo(saved, caret, typedCaret)
          } else {
            undo = null
          }
        }
      } finally {
        busy = false
        typedCaret = -1
        undoPending = false
      }
    }
  }
}
