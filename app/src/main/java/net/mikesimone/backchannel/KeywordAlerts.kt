package net.mikesimone.backchannel

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.dependencies.AppDependencies
import java.util.regex.Pattern

/**
 * Keyword alerts (Backchannel, Mike 2026-11-06): a muted chat still notifies
 * you when a new message contains one of your words, matched as whole words,
 * ignoring case. Stored on this phone only.
 */
object KeywordAlerts {

  private const val PREFS = "backchannel_keywords"
  private const val KEY_WORDS = "words"

  @Volatile
  private var cache: Pair<List<String>, List<Pattern>>? = null

  private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  @JvmStatic
  fun words(context: Context): List<String> = loaded(context).first

  private fun loaded(context: Context): Pair<List<String>, List<Pattern>> {
    return cache ?: build(parse(prefs(context).getString(KEY_WORDS, "") ?: "")).also { cache = it }
  }

  private fun parse(raw: String): List<String> = raw.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

  private fun build(words: List<String>): Pair<List<String>, List<Pattern>> {
    val patterns = words.map { Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(it) + "(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE) }
    return words to patterns
  }

  @JvmStatic
  fun setWords(context: Context, raw: String) {
    val words = parse(raw)
    prefs(context).edit().putString(KEY_WORDS, words.joinToString(", ")).apply()
    cache = build(words)
  }

  /** True if [text] contains one of your keywords. */
  @JvmStatic
  fun matches(text: CharSequence?): Boolean {
    if (text.isNullOrEmpty()) return false
    val patterns = loaded(AppDependencies.application).second
    return patterns.any { it.matcher(text).find() }
  }

  /** Settings summary: the words, or a hint when there are none. */
  @JvmStatic
  fun summary(context: Context): String {
    val words = words(context)
    return if (words.isEmpty()) context.getString(R.string.KeywordAlerts__summary_none) else words.joinToString(", ")
  }

  /** Shows the editor; [onSaved] gets the new summary. */
  @JvmStatic
  fun edit(context: Context, onSaved: (String) -> Unit) {
    val input = EditText(context).apply {
      setText(words(context).joinToString(", "))
      setHint(R.string.KeywordAlerts__hint)
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
      setSelection(text.length)
    }
    val pad = (24 * context.resources.displayMetrics.density).toInt()
    val frame = FrameLayout(context).apply {
      setPadding(pad, pad / 2, pad, 0)
      addView(input)
    }
    MaterialAlertDialogBuilder(context)
      .setTitle(R.string.KeywordAlerts__title)
      .setMessage(R.string.KeywordAlerts__explain)
      .setView(frame)
      .setPositiveButton(android.R.string.ok) { _, _ ->
        setWords(context, input.text?.toString().orEmpty())
        onSaved(summary(context))
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }
}
