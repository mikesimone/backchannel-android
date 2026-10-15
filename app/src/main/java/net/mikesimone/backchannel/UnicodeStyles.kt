package net.mikesimone.backchannel

/**
 * Unicode's mathematical letters: styled text that survives being pasted
 * anywhere, an SMS included (Backchannel P9, same tables as the web page).
 * Copies use the sans-serif set, which reads like the text around it;
 * pastes also understand the serif set. Pure Kotlin, no Android types.
 */
object UnicodeStyles {

  enum class Style { BOLD, ITALIC, STRIKETHROUGH, MONOSPACE }

  /** A run of text and the styles on all of it. */
  data class Piece(val text: String, val styles: Set<Style>)

  /** One plain character (a code point) and its styles. */
  data class StyledChar(val ch: String, val styles: Set<Style>)

  private class CharSet(val styles: Set<Style>, val upper: Int, val lower: Int, val digit: Int?)

  private val SETS = listOf(
    CharSet(setOf(Style.BOLD), 0x1d5d4, 0x1d5ee, 0x1d7ec),
    CharSet(setOf(Style.ITALIC), 0x1d608, 0x1d622, null),
    CharSet(setOf(Style.BOLD, Style.ITALIC), 0x1d63c, 0x1d656, null),
    CharSet(setOf(Style.MONOSPACE), 0x1d670, 0x1d68a, 0x1d7f6),
    CharSet(emptySet(), 0x1d5a0, 0x1d5ba, 0x1d7e2),
    CharSet(setOf(Style.BOLD), 0x1d400, 0x1d41a, 0x1d7ce),
    CharSet(setOf(Style.ITALIC), 0x1d434, 0x1d44e, null),
    CharSet(setOf(Style.BOLD, Style.ITALIC), 0x1d468, 0x1d482, null)
  )

  /** Combining long stroke overlay: follows each struck character. */
  const val STRIKE_MARK = '\u0336'

  private val FROM_UNICODE: Map<Int, StyledChar> = buildMap {
    put(0x210e, StyledChar("h", setOf(Style.ITALIC)))
    // The first set listed wins where two share a letter.
    for (set in SETS) {
      for (i in 0 until 26) {
        putIfAbsent(set.upper + i, StyledChar(('A' + i).toString(), set.styles))
        putIfAbsent(set.lower + i, StyledChar(('a' + i).toString(), set.styles))
      }
      if (set.digit != null) {
        for (i in 0 until 10) {
          putIfAbsent(set.digit + i, StyledChar(('0' + i).toString(), set.styles))
        }
      }
    }
  }

  private fun setFor(styles: Set<Style>): CharSet? {
    return when {
      Style.MONOSPACE in styles -> SETS[3]
      Style.BOLD in styles && Style.ITALIC in styles -> SETS[2]
      Style.BOLD in styles -> SETS[0]
      Style.ITALIC in styles -> SETS[1]
      else -> null
    }
  }

  @JvmStatic
  fun hasUnicodeStyle(pieces: List<Piece>): Boolean {
    return pieces.any { it.text.isNotEmpty() && (setFor(it.styles) != null || Style.STRIKETHROUGH in it.styles) }
  }

  /** Styled pieces to plain text with the styles written in. */
  @JvmStatic
  fun toUnicode(pieces: List<Piece>): String {
    val out = StringBuilder()
    for ((text, styles) in pieces) {
      val set = setFor(styles)
      for (ch in text) {
        when {
          set != null && ch in 'A'..'Z' -> out.appendCodePoint(set.upper + (ch - 'A'))
          set != null && ch in 'a'..'z' -> out.appendCodePoint(set.lower + (ch - 'a'))
          set != null && ch in '0'..'9' -> {
            // Bold italic uses bold digits; italic has none.
            val digit = set.digit ?: if (Style.BOLD in styles) SETS[0].digit else null
            if (digit != null) out.appendCodePoint(digit + (ch - '0')) else out.append(ch)
          }
          else -> out.append(ch)
        }
        if (Style.STRIKETHROUGH in styles && ch != '\n' && !Character.isHighSurrogate(ch)) {
          out.append(STRIKE_MARK)
        }
      }
    }
    return out.toString()
  }

  /** True when [text] has any mathematical letter or strike mark to convert. */
  @JvmStatic
  fun hasUnicode(text: CharSequence): Boolean {
    var i = 0
    while (i < text.length) {
      val cp = Character.codePointAt(text, i)
      if (cp == STRIKE_MARK.code || FROM_UNICODE.containsKey(cp)) {
        return true
      }
      i += Character.charCount(cp)
    }
    return false
  }

  /** Pasted text: mathematical letters and struck characters become plain characters with styles. */
  @JvmStatic
  fun fromUnicode(text: CharSequence): List<StyledChar> {
    val chars = mutableListOf<StyledChar>()
    var i = 0
    while (i < text.length) {
      val cp = Character.codePointAt(text, i)
      i += Character.charCount(cp)
      if (cp == STRIKE_MARK.code && chars.isNotEmpty()) {
        val last = chars.removeAt(chars.lastIndex)
        chars += last.copy(styles = last.styles + Style.STRIKETHROUGH)
        continue
      }
      chars += FROM_UNICODE[cp] ?: StyledChar(String(Character.toChars(cp)), emptySet())
    }
    return chars
  }
}
