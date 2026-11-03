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


  // --- block markdown (Mike 2026-11-03): lists, indents, headings, quotes, code blocks ---
  //
  // Signal only carries bold, italic, strikethrough, monospace and spoiler, so
  // block markdown becomes plain characters every Signal app shows: bullets,
  // em-space indents, a quote bar, a rule. Headings turn bold, code blocks
  // monospace, [text](url) becomes "text (url)".

  private const val INDENT = " "
  private val BULLETS = listOf("•", "◦", "▪")
  private const val QUOTE_BAR = "▍ "
  private const val RULE = "──────────"

  private val FENCE = Regex("^\\s*```.*$")
  private val HEADING = Regex("^#{1,6}\\s+(.*)$")
  private val RULE_LINE = Regex("^\\s*(?:-{3,}|\\*{3,}|_{3,})\\s*$")
  private val BULLET_LINE = Regex("^([ \\t\\u2003]*)[-*+•◦▪]\\s+(\\[[ xX]\\]\\s+)?")
  private val NUMBER_LINE = Regex("^([ \\t\\u2003]*)(\\d{1,3}[.)])\\s+")
  private val QUOTE_LINE = Regex("^>(?: |$)")
  private val LEAD = Regex("^[ \\t\\u2003]+")
  private val DOUBLE = Regex("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1")
  private val MD_LINK = Regex("\\[([^\\]\\n]+)]\\((https?://[^)\\s]+)\\)")

  /** Indent level of leading whitespace: a tab, two spaces or an em space is one level. */
  private fun level(lead: String): Int {
    var n = 0
    var spaces = 0
    for (c in lead) {
      when (c) {
        '\t', ' ' -> { n++; spaces = 0 }
        ' ' -> { spaces++; if (spaces == 2) { n++; spaces = 0 } }
      }
    }
    return n
  }

  /** Turns block markdown in [text] into what every Signal app can show. */
  @JvmStatic
  fun blocks(text: Editable) {
    edit(text) {
      var start = 0
      var inCode = false
      var codeStart = -1
      while (start <= text.length) {
        var end = text.indexOf('\n', start)
        if (end < 0) end = text.length
        val line = text.subSequence(start, end).toString()

        if (FENCE.matches(line)) {
          // Drop the fence line with one of its line breaks; style what was between.
          val atEnd = end >= text.length
          if (!atEnd) text.delete(start, end + 1) else text.delete(if (start > 0) start - 1 else start, end)
          val here = if (atEnd) text.length else start
          if (inCode) {
            val codeEnd = if (atEnd) here else here - 1
            if (codeEnd > codeStart) addStyle(text, BodyRange.Style.MONOSPACE, codeStart, codeEnd)
            inCode = false
          } else {
            inCode = true
            codeStart = here
          }
          start = here
          if (atEnd) break
          continue
        }

        var next: Int
        if (inCode) {
          next = end + 1
        } else {
          next = convertLine(text, start, end, line)
        }
        if (next > text.length) break
        start = next
      }
      if (inCode && codeStart < text.length) addStyle(text, BodyRange.Style.MONOSPACE, codeStart, text.length)

      // Inline extras: **bold**, __bold__, [text](url)
      var m = DOUBLE.find(text)
      while (m != null) {
        val s = m.range.first
        val inner = m.groupValues[2]
        if (!hasStyleAt(text, BodyRange.Style.MONOSPACE, s)) {
          text.replace(s, m.range.last + 1, inner)
          addStyle(text, BodyRange.Style.BOLD, s, s + inner.length)
          m = DOUBLE.find(text, s + inner.length)
        } else {
          m = DOUBLE.find(text, m.range.last + 1)
        }
      }
      var l = MD_LINK.find(text)
      while (l != null) {
        val s = l.range.first
        val shown = "${l.groupValues[1]} (${l.groupValues[2]})"
        text.replace(s, l.range.last + 1, shown)
        l = MD_LINK.find(text, s + shown.length)
      }
    }
  }

  /** Converts one line in [start, end); returns where the next line starts. */
  private fun convertLine(text: Editable, start: Int, end: Int, line: String): Int {
    HEADING.matchEntire(line)?.let { m ->
      val body = m.groupValues[1]
      text.replace(start, end, body)
      addStyle(text, BodyRange.Style.BOLD, start, start + body.length)
      return start + body.length + 1
    }
    if (RULE_LINE.matches(line)) {
      text.replace(start, end, RULE)
      return start + RULE.length + 1
    }
    BULLET_LINE.find(line)?.let { m ->
      val lvl = level(m.groupValues[1])
      val box = m.groupValues[2].trim()
      val prefix = INDENT.repeat(lvl) + BULLETS[lvl % BULLETS.size] + " " +
        when {
          box.isEmpty() -> ""
          box[1] == ' ' -> "☐ "
          else -> "☑ "
        }
      text.replace(start, start + m.value.length, prefix)
      return end - m.value.length + prefix.length + 1
    }
    NUMBER_LINE.find(line)?.let { m ->
      val prefix = INDENT.repeat(level(m.groupValues[1])) + m.groupValues[2] + " "
      text.replace(start, start + m.value.length, prefix)
      return end - m.value.length + prefix.length + 1
    }
    QUOTE_LINE.find(line)?.let { m ->
      text.replace(start, start + m.value.length, QUOTE_BAR)
      val newEnd = end - m.value.length + QUOTE_BAR.length
      addStyle(text, BodyRange.Style.ITALIC, start + QUOTE_BAR.length, newEnd)
      return newEnd + 1
    }
    LEAD.find(line)?.let { m ->
      val lvl = level(m.value)
      if (lvl > 0) {
        val prefix = INDENT.repeat(lvl)
        text.replace(start, start + m.value.length, prefix)
        return end - m.value.length + prefix.length + 1
      }
    }
    return end + 1
  }

  /** Typing "- " or "* " at the start of a line makes a bullet right away. */
  private fun bulletTyped(text: Editable, caret: Int): Int {
    val lineStart = text.lastIndexOf('\n', caret - 2) + 1
    val before = text.subSequence(lineStart, caret - 1).toString()
    val m = Regex("^([ \\t\\u2003]*)[-*+]$").matchEntire(before) ?: return -1
    val lvl = level(m.groupValues[1])
    val prefix = INDENT.repeat(lvl) + BULLETS[lvl % BULLETS.size] + " "
    edit(text) { text.replace(lineStart, caret, prefix) }
    return lineStart + prefix.length
  }

  private val LIST_PREFIX = Regex("^([\\u2003]*)(?:([•◦▪]) (?:[☐☑] )?|(\\d{1,3})([.)]) )")

  /** Enter on a list line continues the list; Enter on an empty item ends it. Returns the new caret or -1. */
  private fun newlineTyped(text: Editable, newline: Int): Int {
    val lineStart = text.lastIndexOf('\n', newline - 1) + 1
    val line = text.subSequence(lineStart, newline).toString()
    val m = LIST_PREFIX.find(line) ?: return -1
    if (line.length == m.value.length) {
      // Empty item: remove its marker and the new line break.
      edit(text) { text.delete(lineStart, newline + 1) }
      return lineStart
    }
    val number = m.groupValues[3]
    val prefix = if (number.isNotEmpty()) {
      m.groupValues[1] + (number.toInt() + 1) + m.groupValues[4] + " "
    } else {
      m.value.replace("☑", "☐")
    }
    edit(text) { text.insert(newline + 1, prefix) }
    return newline + 1 + prefix.length
  }

  /** Called with the compose text just before it's sent. */
  @JvmStatic
  fun passBeforeSend(text: Editable): Editable {
    blocks(text)
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
    private var spaceCaret = -1
    private var newlineAt = -1
    private var undoPending = false
    private var busy = false

    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) = Unit

    override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
      if (busy || applying) return
      typedCaret = -1
      spaceCaret = -1
      newlineAt = -1
      undoPending = false
      val u = undo
      if (before == 0 && count == 1 && s[start] == ' ') {
        spaceCaret = start + 1
      } else if (before == 0 && count == 1 && s[start] == '\n') {
        newlineAt = start
      }
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
        val caretAt = editText.selectionStart
        if (spaceCaret >= 0 && caretAt == spaceCaret && editText.selectionEnd == caretAt) {
          val saved = SpannableStringBuilder(s)
          val caret = bulletTyped(s, spaceCaret)
          if (caret >= 0) {
            editText.setSelection(minOf(caret, s.length))
            undo = Undo(saved, caret, spaceCaret)
            return
          }
        }
        if (newlineAt >= 0 && caretAt == newlineAt + 1 && editText.selectionEnd == caretAt) {
          val caret = newlineTyped(s, newlineAt)
          if (caret >= 0) {
            editText.setSelection(minOf(caret, s.length))
            undo = null
            return
          }
        }
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
        spaceCaret = -1
        newlineAt = -1
        undoPending = false
      }
    }
  }
}
