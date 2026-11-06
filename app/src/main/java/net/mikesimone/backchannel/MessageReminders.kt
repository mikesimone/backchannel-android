package net.mikesimone.backchannel

import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.DateFormat
import android.widget.Toast
import androidx.annotation.WorkerThread
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.conversation.ConversationIntents
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.service.ExportedBroadcastReceiver
import org.thoughtcrime.securesms.util.ServiceUtil
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Remind me about this message (Backchannel, Mike 2026-11-06). Long-press a
 * message, pick a time, and a notification brings you back to it. Stored on
 * this phone only; nothing is sent.
 */
object MessageReminders {

  private val TAG = Log.tag(MessageReminders::class.java)

  private const val PREFS = "backchannel_reminders"
  private const val KEY_LIST = "reminders"
  private const val CHANNEL_ID = "backchannel_reminders"

  private data class Reminder(val id: Long, val messageId: Long, val threadId: Long, val at: Long)

  private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  @Synchronized
  private fun load(context: Context): MutableList<Reminder> {
    val json = prefs(context).getString(KEY_LIST, null) ?: return mutableListOf()
    return try {
      val array = JSONArray(json)
      MutableList(array.length()) { i ->
        val o = array.getJSONObject(i)
        Reminder(o.getLong("id"), o.getLong("messageId"), o.getLong("threadId"), o.getLong("at"))
      }
    } catch (e: Exception) {
      Log.w(TAG, "Couldn't read reminders", e)
      mutableListOf()
    }
  }

  @Synchronized
  private fun save(context: Context, list: List<Reminder>) {
    val array = JSONArray()
    list.forEach { array.put(JSONObject().put("id", it.id).put("messageId", it.messageId).put("threadId", it.threadId).put("at", it.at)) }
    prefs(context).edit().putString(KEY_LIST, array.toString()).apply()
  }

  /** Asks when to be reminded about [messageId], then saves the reminder. */
  @JvmStatic
  fun pick(context: Context, messageId: Long, threadId: Long) {
    val now = Calendar.getInstance()
    val options = mutableListOf<Pair<String, Long>>()
    options += context.getString(R.string.Reminders__in_1_hour) to now.timeInMillis + TimeUnit.HOURS.toMillis(1)
    options += context.getString(R.string.Reminders__in_3_hours) to now.timeInMillis + TimeUnit.HOURS.toMillis(3)
    val tonight = (now.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 20); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    if (tonight.timeInMillis - now.timeInMillis > TimeUnit.MINUTES.toMillis(30)) {
      options += context.getString(R.string.Reminders__tonight) to tonight.timeInMillis
    }
    val tomorrow = (tonight.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 9) }
    options += context.getString(R.string.Reminders__tomorrow) to tomorrow.timeInMillis
    val custom = context.getString(R.string.Reminders__pick_time)

    MaterialAlertDialogBuilder(context)
      .setTitle(R.string.Reminders__title)
      .setItems((options.map { it.first } + custom).toTypedArray()) { _, which ->
        if (which < options.size) {
          add(context, messageId, threadId, options[which].second)
        } else {
          pickCustom(context, messageId, threadId)
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun pickCustom(context: Context, messageId: Long, threadId: Long) {
    val start = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 1) }
    DatePickerDialog(context, { _, year, month, day ->
      TimePickerDialog(context, { _, hour, minute ->
        val at = Calendar.getInstance().apply { set(year, month, day, hour, minute, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        if (at <= System.currentTimeMillis()) {
          Toast.makeText(context, R.string.Reminders__in_past, Toast.LENGTH_SHORT).show()
        } else {
          add(context, messageId, threadId, at)
        }
      }, start.get(Calendar.HOUR_OF_DAY), start.get(Calendar.MINUTE), DateFormat.is24HourFormat(context)).show()
    }, start.get(Calendar.YEAR), start.get(Calendar.MONTH), start.get(Calendar.DAY_OF_MONTH)).apply {
      datePicker.minDate = System.currentTimeMillis() - 1000
    }.show()
  }

  private fun add(context: Context, messageId: Long, threadId: Long, at: Long) {
    val app = context.applicationContext
    SignalExecutors.BOUNDED.execute {
      val list = load(app)
      list += Reminder(System.currentTimeMillis(), messageId, threadId, at)
      save(app, list)
      schedule(app)
    }
    val whenText = DateFormat.format(if (DateFormat.is24HourFormat(context)) "EEE HH:mm" else "EEE h:mm a", at)
    Toast.makeText(context, context.getString(R.string.Reminders__set, whenText), Toast.LENGTH_SHORT).show()
  }

  /** Arms the alarm for the next reminder. Called at app start and after every change. */
  @JvmStatic
  @WorkerThread
  fun schedule(context: Context) {
    val app = context.applicationContext
    val alarmManager = ServiceUtil.getAlarmManager(app)
    val pending = PendingIntent.getBroadcast(app, 0, Intent(app, Alarm::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    alarmManager.cancel(pending)

    val next = load(app).minByOrNull { it.at } ?: return
    try {
      if (Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()) {
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at, pending)
      } else {
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at, pending)
      }
    } catch (e: Exception) {
      Log.w(TAG, "Couldn't set an exact alarm; using an inexact one", e)
      alarmManager.set(AlarmManager.RTC_WAKEUP, next.at, pending)
    }
  }

  @WorkerThread
  private fun fireDue(context: Context) {
    val now = System.currentTimeMillis()
    val list = load(context)
    val (due, later) = list.partition { it.at <= now + 1000 }
    save(context, later)
    due.forEach { notify(context, it) }
    schedule(context)
  }

  @WorkerThread
  private fun notify(context: Context, reminder: Reminder) {
    val record = SignalDatabase.messages.getMessageRecordOrNull(reminder.messageId)
    val recipient = SignalDatabase.threads.getRecipientForThreadId(reminder.threadId)
    if (record == null || recipient == null) {
      Log.i(TAG, "Reminder's message or chat is gone; skipping")
      return
    }

    val manager = NotificationManagerCompat.from(context)
    if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL_ID) == null) {
      manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(R.string.Reminders__channel), NotificationManager.IMPORTANCE_HIGH))
    }

    val position = try {
      SignalDatabase.messages.getMessagePositionInConversation(reminder.threadId, record.dateReceived)
    } catch (e: Exception) {
      -1
    }
    val builder = ConversationIntents.createBuilderSync(context, recipient.id, reminder.threadId)
    if (position >= 0) builder.withStartingPosition(position)
    val open = PendingIntent.getActivity(context, reminder.id.toInt(), builder.build(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    val chat = recipient.getDisplayName(context)
    val text = if (ChatLock.isLocked(context, recipient.id)) {
      context.getString(R.string.Reminders__locked_text)
    } else {
      record.getDisplayBody(context).toString().ifBlank { context.getString(R.string.Reminders__attachment) }
    }

    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(context.getString(R.string.Reminders__notification_title, chat))
      .setContentText(text)
      .setStyle(NotificationCompat.BigTextStyle().bigText(text))
      .setContentIntent(open)
      .setAutoCancel(true)
      .setCategory(NotificationCompat.CATEGORY_REMINDER)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .build()

    try {
      manager.notify(CHANNEL_ID, reminder.id.toInt(), notification)
    } catch (e: SecurityException) {
      Log.w(TAG, "No notification permission", e)
    }
  }

  class Alarm : ExportedBroadcastReceiver() {
    override fun onReceiveUnlock(context: Context, intent: Intent?) {
      val pending = goAsync()
      val app = context.applicationContext
      SignalExecutors.BOUNDED.execute {
        try {
          fireDue(app)
        } finally {
          pending.finish()
        }
      }
    }
  }
}
