package net.mikesimone.backchannel

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Annotation
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import com.google.android.material.color.MaterialColors
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.ComposeText
import org.thoughtcrime.securesms.components.spoiler.SpoilerAnnotation
import org.thoughtcrime.securesms.conversation.MessageStyler
import org.thoughtcrime.securesms.database.model.databaseprotos.BodyRangeList.BodyRange

/**
 * Formatting bar over the message box (Backchannel, Mike 2026-11-24, same as the web page).
 * Works like a word processor: with text selected a button styles the selection; with no
 * selection it turns the style on or off for what you type next. The buttons light up for the
 * styles at the cursor. Signal carries bold, italic, strikethrough, monospace and spoiler;
 * lists are plain bullet and number characters every Signal app shows.
 */
class FormatBar private constructor(
  private val compose: ComposeText,
  private val bar: HorizontalScrollView
) : TextWatcher {

  private enum class Kind { STYLE, BULLETS, NUMBERS, CLEAR }

  private class Button(val kind: Kind, val style: BodyRange.Style?, @IdRes val formatId: Int, val view: TextView)

  private val buttons = mutableListOf<Button>()

  /** Styles turned on or off for the next typed text (no selection). */
  private val on = mutableSetOf<BodyRange.Style>()
  private val off = mutableSetOf<BodyRange.Style>()

  /** Where the caret lands after our own typing bookkeeping; any other caret move resets [on]/[off]. */
  private var expectedCaret = -1
  private var insertStart = -1
  private var insertEnd = -1
  private var busy = false

  companion object {
    private val STYLES = listOf(
      BodyRange.Style.BOLD,
      BodyRange.Style.ITALIC,
      BodyRange.Style.STRIKETHROUGH,
      BodyRange.Style.MONOSPACE,
      BodyRange.Style.SPOILER
    )

    private const val INDENT = ' '
    private val BULLETS = listOf("•", "◦", "▪")
    private val LIST_PREFIX = Regex("^(\\u2003*)(?:([•◦▪]) (?:[☐☑] )?|(\\d{1,3})([.)]) )")

    @JvmStatic
    fun attach(compose: ComposeText, bar: HorizontalScrollView): FormatBar {
      val formatBar = FormatBar(compose, bar)
      formatBar.build()
      compose.addTextChangedListener(formatBar)
      compose.setBackchannelSelectionHook { formatBar.onSelectionChanged() }
      bar.viewTreeObserver.addOnGlobalFocusChangeListener { _, _ -> formatBar.updateVisibility() }
      formatBar.updateVisibility()
      formatBar.refresh()
      return formatBar
    }

    // --- pure text helpers (unit tested) ---

    /**
     * Toggles a bullet or numbered list on the lines touching [selStart, selEnd) of [text].
     * Returns the new selection.
     */
    @JvmStatic
    fun toggleList(text: Editable, selStart: Int, selEnd: Int, numbered: Boolean): Pair<Int, Int> {
      val first = if (selStart <= 0) 0 else text.lastIndexOf('\n', selStart - 1) + 1
      var last = text.indexOf('\n', selEnd.coerceAtMost(text.length))
      if (last < 0) last = text.length
      if (selEnd > selStart && selEnd > first && text.getOrNull(selEnd - 1) == '\n') {
        last = selEnd - 1
      }

      data class Line(val start: Int, val end: Int)

      val lines = mutableListOf<Line>()
      var s = first
      while (true) {
        var e = text.indexOf('\n', s)
        if (e < 0 || e > last) e = last
        lines += Line(s, e)
        if (e >= last) break
        s = e + 1
      }

      val matches = lines.map { LIST_PREFIX.find(text.subSequence(it.start, it.end)) }
      val allThisKind = matches.all { m -> m != null && (if (numbered) m.groupValues[3].isNotEmpty() else m.groupValues[2].isNotEmpty()) }

      var caretShift = 0
      var number = lines.size
      var newEnd = last
      for (i in lines.indices.reversed()) {
        val line = lines[i]
        val m = matches[i]
        val indent = m?.groupValues?.get(1) ?: leadingIndent(text, line.start, line.end)
        val oldPrefixLen = m?.value?.length ?: indent.length
        val newPrefix = when {
          allThisKind -> indent
          numbered -> "$indent$number. "
          else -> indent + BULLETS[indent.length % BULLETS.size] + " "
        }
        number--
        text.replace(line.start, line.start + oldPrefixLen, newPrefix)
        val delta = newPrefix.length - oldPrefixLen
        newEnd += delta
        if (selStart == selEnd) {
          caretShift = if (selStart - line.start < oldPrefixLen) line.start + newPrefix.length - selStart else delta
        }
      }

      return if (selStart == selEnd) {
        val c = (selStart + caretShift).coerceIn(0, text.length)
        c to c
      } else {
        first to newEnd.coerceIn(0, text.length)
      }
    }

    private fun leadingIndent(text: CharSequence, start: Int, end: Int): String {
      var i = start
      while (i < end && text[i] == INDENT) i++
      return text.subSequence(start, i).toString()
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
        is Annotation -> SpoilerAnnotation.isSpoilerAnnotation(this) && style == BodyRange.Style.SPOILER
        else -> false
      }
    }

    private fun covered(text: Spanned, style: BodyRange.Style, start: Int, end: Int): Boolean {
      if (start >= end) return false
      val ranges = text.getSpans(start, end, Any::class.java)
        .filter { it.isStyle(style) }
        .map { text.getSpanStart(it) to text.getSpanEnd(it) }
        .sortedBy { it.first }
      var at = start
      for ((a, b) in ranges) {
        if (a > at) return false
        if (b > at) at = b
        if (at >= end) return true
      }
      return at >= end
    }

    /** True when text typed at [caret] would get [style] from the spans already there. */
    private fun continuesAt(text: Spanned, style: BodyRange.Style, caret: Int): Boolean {
      return text.getSpans(caret, caret, Any::class.java).any {
        if (!it.isStyle(style)) return@any false
        val a = text.getSpanStart(it)
        val b = text.getSpanEnd(it)
        val flags = text.getSpanFlags(it) and Spanned.SPAN_POINT_MARK_MASK
        val growsAtEnd = flags == Spanned.SPAN_EXCLUSIVE_INCLUSIVE || flags == Spanned.SPAN_INCLUSIVE_INCLUSIVE
        (a < caret && b > caret) || (a < caret && b == caret && growsAtEnd)
      }
    }
  }

  // --- building the bar ---

  private fun build() {
    val context = bar.context
    val row = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(8), 0, dp(8), 0)
    }

    fun label(text: String, span: Any?): CharSequence {
      if (span == null) return text
      return SpannableString(text).apply { setSpan(span, 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    add(row, Kind.STYLE, BodyRange.Style.BOLD, R.id.edittext_bold, label("B", StyleSpan(Typeface.BOLD)), R.string.FormatBar__bold)
    add(row, Kind.STYLE, BodyRange.Style.ITALIC, R.id.edittext_italic, label("I", StyleSpan(Typeface.ITALIC)), R.string.FormatBar__italic)
    add(row, Kind.STYLE, BodyRange.Style.STRIKETHROUGH, R.id.edittext_strikethrough, label("S", StrikethroughSpan()), R.string.FormatBar__strikethrough)
    add(row, Kind.STYLE, BodyRange.Style.MONOSPACE, R.id.edittext_monospace, label("</>", TypefaceSpan("monospace")), R.string.FormatBar__monospace)
    add(row, Kind.STYLE, BodyRange.Style.SPOILER, R.id.edittext_spoiler, context.getString(R.string.FormatBar__spoiler), R.string.FormatBar__spoiler)
    add(row, Kind.BULLETS, null, 0, "•", R.string.FormatBar__bullets)
    add(row, Kind.NUMBERS, null, 0, "1.", R.string.FormatBar__numbers)
    add(row, Kind.CLEAR, null, R.id.edittext_clear_formatting, context.getString(R.string.FormatBar__clear), R.string.FormatBar__clear_description)

    bar.removeAllViews()
    bar.isHorizontalScrollBarEnabled = false
    bar.addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
  }

  private fun add(row: LinearLayout, kind: Kind, style: BodyRange.Style?, @IdRes formatId: Int, text: CharSequence, @StringRes description: Int) {
    val context = row.context
    val view = TextView(context).apply {
      this.text = text
      contentDescription = context.getString(description)
      gravity = Gravity.CENTER
      minWidth = dp(40)
      minHeight = dp(32)
      setPadding(dp(10), 0, dp(10), 0)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
      isClickable = true
      isFocusable = false
      val outValue = TypedValue()
      context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
      foreground = context.getDrawable(outValue.resourceId)
    }
    val button = Button(kind, style, formatId, view)
    view.setOnClickListener { press(button) }
    row.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginEnd = dp(2) })
    buttons += button
  }

  private fun dp(value: Int): Int = (value * bar.resources.displayMetrics.density).toInt()

  private fun updateVisibility() {
    bar.visibility = if (compose.hasFocus() && compose.isEnabled) View.VISIBLE else View.GONE
  }

  // --- pressing a button ---

  private fun press(button: Button) {
    val text = compose.text ?: return
    val start = compose.selectionStart
    val end = compose.selectionEnd
    if (start < 0) return

    when (button.kind) {
      Kind.STYLE, Kind.CLEAR -> {
        if (start < end) {
          compose.handleFormatText(button.formatId)
          compose.setSelection(start.coerceAtMost(text.length), end.coerceAtMost(text.length))
        } else if (button.kind == Kind.CLEAR) {
          on.clear()
          off.clear()
          off.addAll(STYLES.filter { continuesAt(text, it, start) })
          expectedCaret = start
        } else {
          val style = button.style!!
          if (isActive(style, text, start, end)) {
            on.remove(style)
            off.add(style)
          } else {
            off.remove(style)
            on.add(style)
          }
          expectedCaret = start
        }
      }
      Kind.BULLETS, Kind.NUMBERS -> {
        busy = true
        try {
          compose.clearComposingText()
          val (a, b) = toggleList(text, start, end, button.kind == Kind.NUMBERS)
          compose.setSelection(a, b)
          expectedCaret = if (a == b) a else -1
        } finally {
          busy = false
        }
      }
    }
    refresh()
  }

  private fun isActive(style: BodyRange.Style, text: Spanned, start: Int, end: Int): Boolean {
    return if (start < end) {
      covered(text, style, start, end)
    } else {
      when (style) {
        in on -> true
        in off -> false
        else -> continuesAt(text, style, start)
      }
    }
  }

  private fun refresh() {
    val text = compose.text ?: return
    val start = compose.selectionStart
    val end = compose.selectionEnd
    val accent = MaterialColors.getColor(bar, com.google.android.material.R.attr.colorPrimary)
    val normal = MaterialColors.getColor(bar, com.google.android.material.R.attr.colorOnSurfaceVariant)
    for (button in buttons) {
      val active = when (button.kind) {
        Kind.STYLE -> start >= 0 && isActive(button.style!!, text, start, end)
        Kind.BULLETS, Kind.NUMBERS -> start >= 0 && lineHasList(text, start, button.kind == Kind.NUMBERS)
        Kind.CLEAR -> false
      }
      button.view.setTextColor(if (active) accent else normal)
      button.view.background = if (active) {
        GradientDrawable().apply {
          cornerRadius = dp(8).toFloat()
          setColor((accent and 0x00FFFFFF) or 0x26000000)
        }
      } else {
        null
      }
      button.view.isSelected = active
    }
  }

  private fun lineHasList(text: CharSequence, caret: Int, numbered: Boolean): Boolean {
    val lineStart = if (caret == 0) 0 else text.lastIndexOf('\n', caret - 1) + 1
    var lineEnd = text.indexOf('\n', lineStart)
    if (lineEnd < 0) lineEnd = text.length
    val m = LIST_PREFIX.find(text.subSequence(lineStart, lineEnd)) ?: return false
    return if (numbered) m.groupValues[3].isNotEmpty() else m.groupValues[2].isNotEmpty()
  }

  private fun onSelectionChanged() {
    val start = compose.selectionStart
    val end = compose.selectionEnd
    if (start != end || start != expectedCaret) {
      on.clear()
      off.clear()
      expectedCaret = -1
    }
    refresh()
  }

  // --- typing ---

  override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) = Unit

  override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
    if (busy || Markdown.isApplying()) return
    if (count > 0 && (on.isNotEmpty() || off.isNotEmpty())) {
      insertStart = start
      insertEnd = start + count
      expectedCaret = insertEnd
    } else {
      insertStart = -1
      insertEnd = -1
    }
  }

  override fun afterTextChanged(s: Editable) {
    if (busy || Markdown.isApplying()) return
    if (s.isEmpty()) {
      on.clear()
      off.clear()
      expectedCaret = -1
    }
    val a = insertStart
    val b = insertEnd
    insertStart = -1
    insertEnd = -1
    if (a >= 0 && b <= s.length && a < b) {
      busy = true
      try {
        for (style in on) {
          if (!covered(s, style, a, b)) MessageStyler.toggleStyle(style, s, a, b)
        }
        for (style in off) {
          if (covered(s, style, a, b)) MessageStyler.toggleStyle(style, s, a, b)
        }
      } finally {
        busy = false
      }
    }
    refresh()
  }
}
