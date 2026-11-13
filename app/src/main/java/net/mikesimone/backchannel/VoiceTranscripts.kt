package net.mikesimone.backchannel

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.View
import android.widget.TextView
import androidx.annotation.RequiresApi
import org.signal.core.models.database.AttachmentId
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.dependencies.AppDependencies
import java.util.concurrent.ConcurrentHashMap

/**
 * Voice message transcripts under the bubble (Backchannel, Mike 2026-11-11).
 *
 * Every transcript made on this phone (long-press > Transcribe, or automatically)
 * is kept and shown as text under its voice message, so it is never worked out
 * twice. Tap the text to expand or collapse a long one.
 *
 * Settings > Chats > "Transcribe voice messages automatically" (ON by default:
 * Mike 2026-11-11 chose "auto, under each note"; fixed 2026-11-13) transcribes incoming voice messages in the background once they have
 * downloaded, one at a time, with the same on-device recognizer as [VoiceTranscriber].
 * If the phone has no on-device recognizer it skips them; audio never leaves the
 * phone and no network recognizer is ever used. Android 13 and up.
 *
 * Storage: a small table in Signal's encrypted database, keyed by attachment id.
 * A trigger removes a transcript when its attachment is deleted (message deleted
 * or expired), and the table is created on first use, so there is no migration.
 */
object VoiceTranscripts {

  private val TAG = Log.tag(VoiceTranscripts::class.java)

  private const val PREFS = "backchannel_voice_transcripts"
  private const val KEY_AUTO = "auto"

  private const val TABLE = "backchannel_voice_transcript"
  private const val ATTACHMENT_ID = "attachment_id"
  private const val MESSAGE_ID = "message_id"
  private const val TEXT = "text"

  /** Longest one recognition may take before the queue gives up on it and moves on. */
  private const val TIMEOUT_MS = 5 * 60 * 1000L
  private const val COLLAPSED_LINES = 3

  /** Marks "looked up, no transcript" in [cache]. */
  private const val NONE = "\u0000"

  private val cache = ConcurrentHashMap<Long, String>()
  private val expanded = HashSet<Long>()

  @Volatile
  private var tableReady = false

  @Volatile
  private var auto: Boolean? = null

  // The automatic queue. Touched on the main thread only.
  private val main = Handler(Looper.getMainLooper())
  private val pending = LinkedHashMap<Long, Pair<Long, Uri>>()
  private val attempted = HashSet<Long>()
  private var running: Any? = null
  // The first automatic run in a session may ask Android to download the offline speech model.
  private var modelRequested = false

  /** The Settings > Chats switch. */
  @JvmStatic
  fun isAutoEnabled(context: Context): Boolean {
    return auto ?: context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO, true).also { auto = it }
  }

  @JvmStatic
  fun setAutoEnabled(context: Context, enabled: Boolean) {
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO, enabled).apply()
    auto = enabled
  }

  // region Storage

  private fun ensureTable() {
    if (tableReady) return
    synchronized(this) {
      if (tableReady) return
      val db = SignalDatabase.rawDatabase
      db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE ($ATTACHMENT_ID INTEGER PRIMARY KEY, $MESSAGE_ID INTEGER NOT NULL, $TEXT TEXT NOT NULL)")
      db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS ${TABLE}_cleanup AFTER DELETE ON ${AttachmentTable.TABLE_NAME} " +
          "BEGIN DELETE FROM $TABLE WHERE $ATTACHMENT_ID = old.${AttachmentTable.ID}; END"
      )
      tableReady = true
    }
  }

  /** The stored transcript ("" when no words were recognized), or null if there is none. Not on the main thread. */
  private fun load(attachmentId: Long): String? {
    cache[attachmentId]?.let { return if (it == NONE) null else it }
    ensureTable()
    val text = SignalDatabase.rawDatabase.rawQuery("SELECT $TEXT FROM $TABLE WHERE $ATTACHMENT_ID = ?", arrayOf(attachmentId.toString())).use { cursor ->
      if (cursor.moveToFirst()) cursor.getString(0) else null
    }
    cache[attachmentId] = text ?: NONE
    return text
  }

  /** Stores a finished transcript and refreshes the message so it shows under the bubble. */
  @JvmStatic
  fun save(context: Context, attachmentId: AttachmentId, messageId: Long, text: String) {
    cache[attachmentId.id] = text
    SignalExecutors.BOUNDED.execute {
      try {
        ensureTable()
        SignalDatabase.rawDatabase.execSQL(
          "INSERT OR REPLACE INTO $TABLE ($ATTACHMENT_ID, $MESSAGE_ID, $TEXT) VALUES (?, ?, ?)",
          arrayOf<Any>(attachmentId.id, messageId, text)
        )
      } catch (t: Throwable) {
        Log.w(TAG, "[$attachmentId] Couldn't store the transcript", t)
      }
      AppDependencies.databaseObserver.notifyMessageUpdateObservers(MessageId(messageId))
    }
  }

  // endregion

  // region Under the bubble

  /**
   * Shows [record]'s transcript in [view] (or hides it). Called from ConversationItem on every bind.
   * [textColor] matches the bubble's body text; [longClick] keeps long-press selecting the message.
   */
  @JvmStatic
  fun bind(view: TextView?, record: MessageRecord, textColor: Int, longClick: View.OnLongClickListener?) {
    if (view == null) return
    val slide = (record as? MmsMessageRecord)?.slideDeck?.audioSlide
    val attachment = slide?.asAttachment() as? DatabaseAttachment
    if (attachment == null) {
      view.tag = null
      view.visibility = View.GONE
      return
    }

    val id = attachment.attachmentId.id
    view.tag = id
    view.setTextColor(textColor)
    view.setOnLongClickListener(longClick)

    val cached = cache[id]
    if (cached != null) {
      show(view, id, if (cached == NONE) null else cached)
    } else {
      view.visibility = View.GONE
      val context = view.context.applicationContext
      SignalExecutors.BOUNDED.execute {
        val text = try {
          load(id)
        } catch (t: Throwable) {
          Log.w(TAG, "[$id] Couldn't read the transcript", t)
          null
        }
        main.post { if (view.tag == id) show(view, id, text) }
        if (text == null) maybeQueue(context, record, attachment)
      }
      return
    }

    if (cached == NONE) maybeQueue(view.context.applicationContext, record, attachment)
  }

  private fun show(view: TextView, id: Long, text: String?) {
    if (text.isNullOrEmpty()) {
      view.visibility = View.GONE
      return
    }
    view.text = text
    view.visibility = View.VISIBLE
    applyExpanded(view, expanded.contains(id))
    view.setOnClickListener {
      val now = !expanded.contains(id)
      if (now) expanded.add(id) else expanded.remove(id)
      applyExpanded(view, now)
    }
  }

  private fun applyExpanded(view: TextView, isExpanded: Boolean) {
    view.maxLines = if (isExpanded) Int.MAX_VALUE else COLLAPSED_LINES
    view.ellipsize = if (isExpanded) null else TextUtils.TruncateAt.END
  }

  // endregion

  // region Automatic transcription

  /** Called when an attachment finishes downloading. Queues it if it's an incoming voice message and the switch is on; never throws. */
  @JvmStatic
  fun onDownloaded(context: Context, messageId: Long, attachmentId: AttachmentId) {
    if (Build.VERSION.SDK_INT < 33 || !isAutoEnabled(context)) return
    SignalExecutors.BOUNDED.execute {
      try {
        val attachment = SignalDatabase.attachments.getAttachment(attachmentId) ?: return@execute
        val record = SignalDatabase.messages.getMessageRecordOrNull(messageId) ?: return@execute
        if (load(attachmentId.id) == null) maybeQueue(context.applicationContext, record, attachment)
      } catch (t: Throwable) {
        Log.w(TAG, "[$attachmentId] Couldn't queue the transcript", t)
      }
    }
  }

  private fun maybeQueue(context: Context, record: MessageRecord, attachment: DatabaseAttachment) {
    if (Build.VERSION.SDK_INT < 33 || !isAutoEnabled(context)) return
    if (record.isOutgoing || !attachment.voiceNote || attachment.transferState != AttachmentTable.TRANSFER_PROGRESS_DONE) return
    if ((record as? MmsMessageRecord)?.storyType?.isStory == true) return
    val uri = attachment.uri ?: return
    val id = attachment.attachmentId.id
    main.post {
      if (attempted.contains(id) || pending.containsKey(id)) return@post
      pending[id] = record.id to uri
      next(context)
    }
  }

  private fun next(context: Context) {
    if (Build.VERSION.SDK_INT < 33 || running != null) return
    val entry = pending.entries.firstOrNull() ?: return
    pending.remove(entry.key)
    attempted.add(entry.key)
    run(context, AttachmentId(entry.key), entry.value.first, entry.value.second)
  }

  @RequiresApi(33)
  private fun run(context: Context, attachmentId: AttachmentId, messageId: Long, uri: Uri) {
    var recognition: VoiceTranscriber.Recognition? = null
    val timeout = Runnable {
      Log.w(TAG, "[$attachmentId] Transcription timed out")
      recognition?.cancel()
      running = null
      next(context)
    }

    recognition = VoiceTranscriber.Recognition(
      context,
      uri,
      object : VoiceTranscriber.Listener {
        override fun onDone(text: String, error: String?, complete: Boolean) {
          main.removeCallbacks(timeout)
          if (complete && text.isNotEmpty()) {
            save(context, attachmentId, messageId, text)
          } else if (complete) {
            // Nothing recognized: don't store an empty result, so it's tried again next time the app starts.
            Log.i(TAG, "[$attachmentId] No words recognized")
          } else {
            Log.i(TAG, "[$attachmentId] Not transcribed: ${error ?: "unknown"}")
          }
          // Post, so the finished recognizer is torn down before the next one starts.
          main.post {
            running = null
            next(context)
          }
        }
      },
      askForModel = !modelRequested
    )
    modelRequested = true
    running = recognition
    main.postDelayed(timeout, TIMEOUT_MS)
    try {
      recognition.start()
    } catch (t: Throwable) {
      Log.w(TAG, "[$attachmentId] Couldn't start the recognizer", t)
      main.removeCallbacks(timeout)
      running = null
      main.post { next(context) }
    }
  }

  // endregion
}
