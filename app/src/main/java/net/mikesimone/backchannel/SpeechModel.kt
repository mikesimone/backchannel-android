package net.mikesimone.backchannel

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import okhttp3.Call
import okhttp3.Request
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.dependencies.AppDependencies
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Backchannel's own offline speech model (whisper.cpp, multilingual "base", 5-bit; Backchannel,
 * Mike 2026-11-16), used by [VoiceTranscriber] so transcription works the same on every phone.
 *
 * Downloaded only when someone turns on automatic transcription or taps Transcribe (Mike's rule:
 * never at install, never in the background otherwise), from a pinned Hugging Face commit, through
 * the app's own HTTP client (so it follows the app's proxy settings), checked against a fixed
 * SHA-256 and kept in the app's private files. Wi-Fi (unmetered networks) only unless the person
 * ticks "Use mobile data". An interrupted download resumes where it stopped (HTTP Range).
 * Settings > Chats shows its status and can delete it.
 */
object SpeechModel {

  private val TAG = Log.tag(SpeechModel::class.java)

  /** ggerganov/whisper.cpp on Hugging Face, pinned to commit 5359861c (2024-10-29). */
  const val URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-base-q5_1.bin"
  const val SHA256 = "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"
  const val SIZE = 59_707_625L

  private const val DIR = "speech"
  private const val NAME = "ggml-base-q5_1.bin"

  private val main = Handler(Looper.getMainLooper())

  @JvmStatic
  fun file(context: Context): File = File(File(context.applicationContext.filesDir, DIR), NAME)

  private fun partFile(context: Context): File = File(File(context.applicationContext.filesDir, DIR), "$NAME.part")

  /** The model is downloaded and verified (it is only moved into place after its SHA-256 matched). */
  @JvmStatic
  fun isInstalled(context: Context): Boolean = file(context).length() == SIZE

  /** Deletes the model (and any half-finished download). */
  @JvmStatic
  fun delete(context: Context) {
    current?.cancel()
    file(context).delete()
    partFile(context).delete()
    notifyChanged()
  }

  // region Download

  sealed interface State {
    data class Running(val done: Long, val total: Long) : State
    data class Failed(val reason: Failure) : State
    data object Finished : State
    data object Cancelled : State
  }

  enum class Failure { NO_NETWORK, METERED, NETWORK, CORRUPT, STORAGE }

  /** One download. Its listeners are called on the main thread. */
  class Download internal constructor(private val context: Context, private val allowMobileData: Boolean) {
    @Volatile var state: State = State.Running(0, SIZE)
      private set

    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()

    @Volatile private var cancelled = false

    @Volatile private var call: Call? = null

    fun addListener(listener: (State) -> Unit) {
      listeners.add(listener)
      main.post { listener(state) }
    }

    fun removeListener(listener: (State) -> Unit) {
      listeners.remove(listener)
    }

    fun cancel() {
      cancelled = true
      call?.cancel()
    }

    private fun publish(new: State) {
      state = new
      main.post {
        listeners.forEach { it(new) }
        if (new !is State.Running && current === this) current = null
        changeListeners.forEach { it() }
      }
    }

    internal fun run() {
      SignalExecutors.UNBOUNDED.execute {
        publish(
          try {
            download()
          } catch (e: IOException) {
            if (cancelled) State.Cancelled else State.Failed(Failure.NETWORK).also { Log.w(TAG, "Speech model download failed", e) }
          } catch (e: Exception) {
            Log.w(TAG, "Speech model download failed", e)
            if (cancelled) State.Cancelled else State.Failed(Failure.NETWORK)
          }
        )
      }
    }

    private fun download(): State {
      val connectivity = context.getSystemService(ConnectivityManager::class.java)
      if (connectivity?.activeNetwork == null) return State.Failed(Failure.NO_NETWORK)
      if (connectivity.isActiveNetworkMetered && !allowMobileData) return State.Failed(Failure.METERED)

      val target = file(context)
      val part = partFile(context)
      target.parentFile?.mkdirs()
      if (part.length() > SIZE) part.delete()

      var offset = part.length()
      if (offset < SIZE) {
        val request = Request.Builder().url(URL).apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
        val call = AppDependencies.okHttpClient.newCall(request).also { this.call = it }
        if (cancelled) return State.Cancelled
        call.execute().use { response ->
          val append = when (response.code) {
            206 -> true
            200 -> false
            else -> throw IOException("HTTP ${response.code}")
          }
          if (!append) offset = 0
          Log.i(TAG, "Downloading the speech model from byte $offset (HTTP ${response.code})")
          val body = response.body ?: throw IOException("No body")
          try {
            FileOutputStream(part, append).use { out ->
              body.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var done = offset
                var reported = 0L
                while (true) {
                  if (cancelled) return State.Cancelled
                  val read = input.read(buffer)
                  if (read < 0) break
                  out.write(buffer, 0, read)
                  done += read
                  if (done > SIZE) throw IOException("Larger than expected")
                  if (done - reported >= 256 * 1024) {
                    reported = done
                    publish(State.Running(done, SIZE))
                  }
                }
              }
            }
          } catch (e: IOException) {
            if (context.applicationContext.filesDir.usableSpace < SIZE - part.length()) return State.Failed(Failure.STORAGE)
            throw e
          }
        }
      }

      if (part.length() != SIZE) throw IOException("Incomplete: ${part.length()} of $SIZE bytes")
      publish(State.Running(SIZE, SIZE))
      if (sha256(part) != SHA256) {
        Log.w(TAG, "Speech model checksum mismatch, deleted")
        part.delete()
        return State.Failed(Failure.CORRUPT)
      }
      if (!part.renameTo(target)) return State.Failed(Failure.STORAGE)
      Log.i(TAG, "Speech model downloaded and verified")
      return State.Finished
    }
  }

  @Volatile
  private var current: Download? = null

  /** The download in progress, if any. */
  @JvmStatic
  fun currentDownload(): Download? = current

  /** Starts downloading (or returns the download already running). */
  @JvmStatic
  fun download(context: Context, allowMobileData: Boolean): Download {
    current?.let { return it }
    return Download(context.applicationContext, allowMobileData).also {
      current = it
      notifyChanged()
      it.run()
    }
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
      val buffer = ByteArray(256 * 1024)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  // endregion

  // region Status, for Settings

  private val changeListeners = CopyOnWriteArraySet<() -> Unit>()

  /** Called on the main thread when the model is downloaded or deleted, and as a download progresses. */
  @JvmStatic
  fun addChangeListener(listener: () -> Unit) {
    changeListeners.add(listener)
  }

  @JvmStatic
  fun removeChangeListener(listener: () -> Unit) {
    changeListeners.remove(listener)
  }

  private fun notifyChanged() {
    main.post { changeListeners.forEach { it() } }
  }

  /** "Not downloaded", "Downloading, 40%" or "Downloaded (57 MB)". */
  @JvmStatic
  fun status(context: Context): String {
    val running = current?.state as? State.Running
    return when {
      running != null -> context.getString(R.string.SpeechModel__status_downloading, (running.done * 100 / running.total).toInt())
      isInstalled(context) -> context.getString(R.string.SpeechModel__status_installed, Formatter.formatShortFileSize(context, SIZE))
      else -> context.getString(R.string.SpeechModel__status_missing, Formatter.formatShortFileSize(context, SIZE))
    }
  }

  /** The Settings > Chats row: download it, or offer to delete it. */
  @JvmStatic
  fun manage(context: Context) {
    if (isInstalled(context)) {
      MaterialAlertDialogBuilder(context)
        .setTitle(R.string.SpeechModel__delete_title)
        .setMessage(context.getString(R.string.SpeechModel__delete_message, Formatter.formatShortFileSize(context, SIZE)))
        .setPositiveButton(R.string.SpeechModel__delete) { _, _ -> delete(context) }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
    } else {
      ensure(context, onReady = {}, onDeclined = {})
    }
  }

  // endregion

  // region Dialog

  /**
   * Runs [onReady] once the model is on the phone, asking to download it first (size, progress,
   * Cancel, Retry, "Use mobile data"). [onDeclined] runs if the person says not now or cancels.
   */
  @JvmStatic
  fun ensure(context: Context, onReady: () -> Unit, onDeclined: () -> Unit) {
    if (isInstalled(context)) {
      onReady()
      return
    }
    DownloadDialog(context, onReady, onDeclined).show()
  }

  private class DownloadDialog(private val context: Context, private val onReady: () -> Unit, private val onDeclined: () -> Unit) {

    private val size = Formatter.formatShortFileSize(context, SIZE)
    private val density = context.resources.displayMetrics.density
    private val message = TextView(context).apply {
      textSize = 15f
      text = context.getString(R.string.SpeechModel__explain, size)
    }
    private val mobileData = CheckBox(context).apply { setText(R.string.SpeechModel__use_mobile_data) }
    private val progress = LinearProgressIndicator(context).apply {
      max = 1000
      visibility = View.GONE
    }
    private val progressText = TextView(context).apply {
      textSize = 13f
      visibility = View.GONE
    }
    private val layout = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      val pad = (24 * density).toInt()
      setPadding(pad, (8 * density).toInt(), pad, 0)
      addView(message)
      addView(mobileData)
      addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (12 * density).toInt() })
      addView(progressText)
    }

    private var download: Download? = null
    private var finished = false
    private val listener: (State) -> Unit = { onState(it) }

    private val dialog: AlertDialog = MaterialAlertDialogBuilder(context)
      .setTitle(R.string.SpeechModel__title)
      .setView(layout)
      .setPositiveButton(R.string.SpeechModel__download, null)
      .setNegativeButton(R.string.SpeechModel__not_now, null)
      .create()

    fun show() {
      dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { start() }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
          download?.cancel()
          dialog.dismiss()
        }
        // Already downloading (from Settings, say): show its progress.
        currentDownload()?.let { attach(it) }
      }
      dialog.setOnDismissListener {
        download?.removeListener(listener)
        if (!finished) onDeclined()
      }
      dialog.show()
    }

    private fun start() {
      attach(download(context, mobileData.isChecked))
    }

    private fun attach(running: Download) {
      download?.removeListener(listener)
      download = running
      running.addListener(listener)
    }

    private fun onState(state: State) {
      val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
      val negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
      when (state) {
        is State.Running -> {
          mobileData.visibility = View.GONE
          progress.visibility = View.VISIBLE
          progressText.visibility = View.VISIBLE
          progress.setProgressCompat((state.done * 1000 / state.total).toInt(), true)
          progressText.text = if (state.done >= state.total) {
            context.getString(R.string.SpeechModel__checking)
          } else {
            context.getString(R.string.SpeechModel__progress, Formatter.formatShortFileSize(context, state.done), size)
          }
          positive?.visibility = View.GONE
          negative?.setText(android.R.string.cancel)
        }
        is State.Failed -> {
          download?.removeListener(listener)
          download = null
          progress.visibility = View.GONE
          progressText.visibility = View.VISIBLE
          progressText.text = context.getString(
            when (state.reason) {
              Failure.NO_NETWORK -> R.string.SpeechModel__error_no_network
              Failure.METERED -> R.string.SpeechModel__error_metered
              Failure.NETWORK -> R.string.SpeechModel__error_network
              Failure.CORRUPT -> R.string.SpeechModel__error_corrupt
              Failure.STORAGE -> R.string.SpeechModel__error_storage
            }
          )
          mobileData.visibility = View.VISIBLE
          positive?.visibility = View.VISIBLE
          positive?.setText(R.string.SpeechModel__retry)
          negative?.setText(R.string.SpeechModel__not_now)
        }
        State.Finished -> {
          finished = true
          dialog.dismiss()
          onReady()
        }
        State.Cancelled -> dialog.dismiss()
      }
    }
  }

  // endregion
}
