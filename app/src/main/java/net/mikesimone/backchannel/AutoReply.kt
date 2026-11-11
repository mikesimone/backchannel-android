package net.mikesimone.backchannel

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.InputType
import android.text.format.DateFormat
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.recipients.RecipientUtil
import org.thoughtcrime.securesms.sms.MessageSender
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Auto reply (Backchannel, Mike 2026-11-11): while it's on, someone who
 * messages you in a 1:1 chat gets your away message back, at most once per
 * chat every 4 hours. Optionally only between two times of day, and
 * optionally until a date and time, after which it turns itself off.
 *
 * Why: an away message for holidays, nights or busy days, like email has.
 *
 * Never replies in groups, Note to Self, to blocked people, to message
 * requests you haven't accepted, to your own or synced messages, or to a
 * message that is itself an auto reply (auto replies carry an invisible
 * marker, shared with Backchannel Web in MARKERS.md, so two Backchannel apps
 * don't answer each other; the 4-hour limit stops loops with anything else).
 * If a linked Backchannel Web already auto replied in the chat within 4 hours
 * (its sync copy is on this phone), this phone doesn't reply again.
 *
 * Settings > Chats > Auto reply. Off by default. Stored on this phone only.
 */
object AutoReply {

  private val TAG = Log.tag(AutoReply::class.java)

  /** INVISIBLE SEPARATOR, INVISIBLE TIMES at the end of the text. Same as Backchannel Web (MARKERS.md); different from [SendSilently.MARKER]. */
  const val MARKER = "\u2063\u2062"

  private val THROTTLE_MS = TimeUnit.HOURS.toMillis(4)

  private const val PREFS = "backchannel_auto_reply"
  private const val PREFS_LAST = "backchannel_auto_reply_last"
  private const val KEY_ON = "on"
  private const val KEY_TEXT = "text"
  private const val KEY_WINDOW = "window"
  private const val KEY_START = "start_minute"
  private const val KEY_END = "end_minute"
  private const val KEY_UNTIL = "until_millis"

  private const val DEFAULT_START = 22 * 60
  private const val DEFAULT_END = 7 * 60

  data class Settings(
    val on: Boolean,
    val text: String,
    val window: Boolean,
    val startMinute: Int,
    val endMinute: Int,
    /** 0 = no end. */
    val untilMillis: Long
  )

  @Volatile
  private var cache: Settings? = null

  private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  @JvmStatic
  fun settings(context: Context): Settings {
    return cache ?: prefs(context).let {
      Settings(
        on = it.getBoolean(KEY_ON, false),
        text = it.getString(KEY_TEXT, null)?.takeIf { text -> text.isNotBlank() } ?: context.getString(R.string.AutoReply__default_text),
        window = it.getBoolean(KEY_WINDOW, false),
        startMinute = it.getInt(KEY_START, DEFAULT_START),
        endMinute = it.getInt(KEY_END, DEFAULT_END),
        untilMillis = it.getLong(KEY_UNTIL, 0L)
      )
    }.also { cache = it }
  }

  @JvmStatic
  fun save(context: Context, settings: Settings) {
    prefs(context).edit()
      .putBoolean(KEY_ON, settings.on)
      .putString(KEY_TEXT, settings.text)
      .putBoolean(KEY_WINDOW, settings.window)
      .putInt(KEY_START, settings.startMinute)
      .putInt(KEY_END, settings.endMinute)
      .putLong(KEY_UNTIL, settings.untilMillis)
      .apply()
    cache = settings
  }

  /** True if [minuteOfDay] is in [start, end). A window that wraps midnight (22:00-07:00) works; start == end means all day. */
  @JvmStatic
  fun inWindow(minuteOfDay: Int, start: Int, end: Int): Boolean {
    return when {
      start == end -> true
      start < end -> minuteOfDay in start until end
      else -> minuteOfDay >= start || minuteOfDay < end
    }
  }

  /** Whether a reply would go out at [now] with [settings]; ignores the per-chat limit. */
  @JvmStatic
  fun isActive(settings: Settings, now: Long, minuteOfDay: Int): Boolean {
    if (!settings.on) return false
    if (settings.untilMillis > 0 && now >= settings.untilMillis) return false
    return !settings.window || inWindow(minuteOfDay, settings.startMinute, settings.endMinute)
  }

  /** True if the per-chat limit allows another reply: never replied, or [throttleMs] has passed (a clock moved backwards counts as passed). */
  @JvmStatic
  fun throttleAllows(lastReply: Long, now: Long, throttleMs: Long = THROTTLE_MS): Boolean {
    return lastReply <= 0 || now - lastReply >= throttleMs || now < lastReply
  }

  /** Forgets when this chat last got an auto reply (Delete contact). */
  @JvmStatic
  fun clear(context: Context, recipientId: RecipientId) {
    context.applicationContext.getSharedPreferences(PREFS_LAST, Context.MODE_PRIVATE).edit().remove(recipientId.serialize()).apply()
  }

  @JvmStatic
  fun isAutoReply(body: CharSequence?): Boolean = body != null && body.endsWith(MARKER)

  private fun minuteOfDay(now: Long): Int {
    val calendar = Calendar.getInstance().apply { timeInMillis = now }
    return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
  }

  /**
   * Called after an incoming message from [sender] was saved in thread [threadId].
   * Cheap checks here; the rest and the send happen off this thread.
   */
  @JvmStatic
  fun onIncomingMessage(context: Context, sender: Recipient, threadRecipient: Recipient, isGroupMessage: Boolean, body: String?, threadId: Long) {
    val settings = settings(context)
    if (!settings.on) return

    val now = System.currentTimeMillis()
    if (settings.untilMillis > 0 && now >= settings.untilMillis) {
      Log.i(TAG, "Auto reply end time passed; turning it off.")
      save(context, settings.copy(on = false))
      return
    }

    if (isGroupMessage || threadRecipient.isGroup || !threadRecipient.isIndividual) return
    if (sender.isSelf || threadRecipient.isSelf || threadRecipient.isReleaseNotes) return
    if (sender.id != threadRecipient.id) return
    if (isAutoReply(body)) return
    if (!isActive(settings, now, minuteOfDay(now))) return

    val appContext = context.applicationContext
    val recipientId = threadRecipient.id
    SignalExecutors.BOUNDED.execute { replyIfAllowed(appContext, recipientId, threadId, settings.text) }
  }

  private fun replyIfAllowed(context: Context, recipientId: RecipientId, threadId: Long, text: String) {
    try {
      val recipient = Recipient.resolved(recipientId)
      if (recipient.isBlocked || !recipient.isRegistered || !RecipientUtil.isMessageRequestAccepted(threadId, recipient)) return

      val lastPrefs = context.getSharedPreferences(PREFS_LAST, Context.MODE_PRIVATE)
      val key = recipientId.serialize()
      synchronized(this) {
        val now = System.currentTimeMillis()
        if (!throttleAllows(lastPrefs.getLong(key, 0L), now)) return
        // Backchannel Web may have auto replied already (its sync copy is on this phone): first sender wins.
        if (threadId > 0 && SignalDatabase.messages.hasRecentOutgoingEndingWith(threadId, MARKER, now - THROTTLE_MS)) return
        lastPrefs.edit().putLong(key, now).commit()
      }

      val expiresIn = TimeUnit.SECONDS.toMillis(recipient.expiresInSeconds.toLong())
      val message = OutgoingMessage.text(recipient, text.trim() + MARKER, expiresIn)
      val sendThread = if (threadId > 0) threadId else SignalDatabase.threads.getOrCreateThreadIdFor(recipient)
      MessageSender.send(context, message, sendThread, MessageSender.SendType.SIGNAL, null, null)
      Log.i(TAG, "Sent an auto reply to $recipientId")
    } catch (e: Exception) {
      Log.w(TAG, "Couldn't send an auto reply", e)
    }
  }

  // region Settings screen

  private fun formatTime(context: Context, minute: Int): String {
    val calendar = Calendar.getInstance().apply {
      set(Calendar.HOUR_OF_DAY, minute / 60)
      set(Calendar.MINUTE, minute % 60)
    }
    return DateFormat.getTimeFormat(context).format(calendar.time)
  }

  private fun formatDateTime(context: Context, millis: Long): String {
    val date = java.util.Date(millis)
    return DateFormat.getMediumDateFormat(context).format(date) + " " + DateFormat.getTimeFormat(context).format(date)
  }

  /** Settings > Chats summary. */
  @JvmStatic
  fun summary(context: Context): String {
    val settings = settings(context)
    val now = System.currentTimeMillis()
    if (!settings.on || (settings.untilMillis > 0 && now >= settings.untilMillis)) return context.getString(R.string.AutoReply__summary_off)
    val parts = mutableListOf(context.getString(R.string.AutoReply__summary_on, settings.text))
    if (settings.window) parts += context.getString(R.string.AutoReply__summary_window, formatTime(context, settings.startMinute), formatTime(context, settings.endMinute))
    if (settings.untilMillis > 0) parts += context.getString(R.string.AutoReply__summary_until, formatDateTime(context, settings.untilMillis))
    return parts.joinToString(" ")
  }

  /** Shows the editor; [onSaved] gets the new summary. */
  @JvmStatic
  fun edit(context: Context, onSaved: (String) -> Unit) {
    val current = settings(context)
    var startMinute = current.startMinute
    var endMinute = current.endMinute
    var untilMillis = if (current.untilMillis > System.currentTimeMillis()) current.untilMillis else System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)

    val density = context.resources.displayMetrics.density
    val pad = (24 * density).toInt()
    val gap = (12 * density).toInt()

    val enabled = MaterialSwitch(context).apply {
      setText(R.string.AutoReply__switch)
      isChecked = current.on
    }
    val text = EditText(context).apply {
      setText(current.text)
      setHint(R.string.AutoReply__default_text)
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }
    val window = CheckBox(context).apply {
      setText(R.string.AutoReply__only_between)
      isChecked = current.window
    }
    val startButton = Button(context, null, com.google.android.material.R.attr.borderlessButtonStyle)
    val endButton = Button(context, null, com.google.android.material.R.attr.borderlessButtonStyle)
    val until = CheckBox(context).apply {
      setText(R.string.AutoReply__turn_off_at)
      isChecked = current.untilMillis > System.currentTimeMillis()
    }
    val untilButton = Button(context, null, com.google.android.material.R.attr.borderlessButtonStyle)

    fun refresh() {
      startButton.text = context.getString(R.string.AutoReply__from, formatTime(context, startMinute))
      endButton.text = context.getString(R.string.AutoReply__to, formatTime(context, endMinute))
      untilButton.text = formatDateTime(context, untilMillis)
      startButton.isEnabled = window.isChecked
      endButton.isEnabled = window.isChecked
      untilButton.isEnabled = until.isChecked
    }

    fun pickTime(initial: Int, onPicked: (Int) -> Unit) {
      TimePickerDialog(context, { _, hour, minute -> onPicked(hour * 60 + minute); refresh() }, initial / 60, initial % 60, DateFormat.is24HourFormat(context)).show()
    }

    startButton.setOnClickListener { pickTime(startMinute) { startMinute = it } }
    endButton.setOnClickListener { pickTime(endMinute) { endMinute = it } }
    window.setOnCheckedChangeListener { _, _ -> refresh() }
    until.setOnCheckedChangeListener { _, _ -> refresh() }
    untilButton.setOnClickListener {
      val calendar = Calendar.getInstance().apply { timeInMillis = untilMillis }
      DatePickerDialog(context, { _, year, month, day ->
        TimePickerDialog(context, { _, hour, minute ->
          calendar.set(year, month, day, hour, minute, 0)
          untilMillis = calendar.timeInMillis
          refresh()
        }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), DateFormat.is24HourFormat(context)).show()
      }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).apply {
        datePicker.minDate = System.currentTimeMillis() - 1000
      }.show()
    }
    refresh()

    val times = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      addView(startButton)
      addView(endButton)
    }
    val explain = TextView(context).apply {
      setText(R.string.AutoReply__explain)
      setPadding(0, 0, 0, gap)
    }
    val column = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(pad, gap, pad, 0)
      addView(explain)
      addView(enabled)
      addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = gap })
      addView(window)
      addView(times)
      addView(until)
      addView(untilButton)
    }
    val scroll = ScrollView(context).apply { addView(column) }

    MaterialAlertDialogBuilder(context)
      .setTitle(R.string.AutoReply__title)
      .setView(scroll)
      .setPositiveButton(android.R.string.ok) { _, _ ->
        val untilValue = if (until.isChecked) untilMillis else 0L
        save(
          context,
          Settings(
            on = enabled.isChecked && (untilValue == 0L || untilValue > System.currentTimeMillis()),
            text = text.text?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: context.getString(R.string.AutoReply__default_text),
            window = window.isChecked,
            startMinute = startMinute,
            endMinute = endMinute,
            untilMillis = untilValue
          )
        )
        onSaved(summary(context))
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  // endregion
}
