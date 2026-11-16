package net.mikesimone.backchannel

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.signal.core.util.ServiceUtil
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.media.DecryptableUriMediaInput
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Transcribe a voice message (Backchannel, Mike 2026-11-06). Uses only
 * Android's on-device speech recognizer: the audio is decoded on the phone and
 * fed to the offline recognizer, never to a network service. If the phone has
 * no offline recognizer, or no offline model for the language, it says so and
 * asks the system to download the model; it never falls back to a cloud
 * service. Needs Android 13 (file audio input for the recognizer).
 *
 * Transcripts are kept and shown under the voice message ([VoiceTranscripts],
 * Backchannel, Mike 2026-11-11), which can also transcribe incoming ones on its own.
 */
object VoiceTranscriber {

  private val TAG = Log.tag(VoiceTranscriber::class.java)
  private const val TARGET_RATE = 16000
  private const val SUPPORT_TIMEOUT_MS = 5_000L

  @JvmStatic
  fun canTranscribe(record: MessageRecord): Boolean {
    return Build.VERSION.SDK_INT >= 33 && record is MmsMessageRecord && record.slideDeck.audioSlide?.uri != null
  }

  @JvmStatic
  fun show(context: Context, record: MessageRecord) {
    if (Build.VERSION.SDK_INT < 33) return
    val slide = (record as? MmsMessageRecord)?.slideDeck?.audioSlide ?: return
    val uri = slide.uri ?: return
    val attachmentId = (slide.asAttachment() as? DatabaseAttachment)?.attachmentId
    Session(context, uri) { text -> if (attachmentId != null) VoiceTranscripts.save(context, attachmentId, record.id, text) }.start()
  }

  /** What a [Recognition] reports back, on the main thread. */
  interface Listener {
    /** The words so far (final segments plus the current partial guess). */
    fun onProgress(text: String) = Unit

    /**
     * Recognition ended. [text] is the transcript (empty when no words were recognized).
     * [error] is a message for the user when it failed; a failed run has [complete] false
     * and should not be stored, so it can be tried again.
     */
    fun onDone(text: String, error: String?, complete: Boolean)
  }

  /**
   * One on-device recognition run over a voice message file. Start and use it on the main
   * thread. Never uses a network recognizer: [start] fails if the phone has no on-device one.
   */
  @RequiresApi(33)
  class Recognition(
    private val context: Context,
    private val audio: Uri,
    private val listener: Listener,
    private val askForModel: Boolean
  ) : RecognitionListener {

    private val finalText = StringBuilder()
    private var partial = ""
    private var done = false
    private var recognizer: SpeechRecognizer? = null
    private var readSide: ParcelFileDescriptor? = null
    private val main = Handler(Looper.getMainLooper())

    /** Set by the decoder thread before it closes the pipe, so the recognizer's "nothing heard" can't hide a decode failure. */
    @Volatile private var decodeFailed = false

    private val language = Locale.getDefault().toLanguageTag()

    /**
     * What this run found out about the phone's offline recognizer, for the dialog and the log:
     * whether there is one, which languages it has installed / can download, and how it ended.
     */
    var diagnostics: String = ""
      private set

    private fun note(line: String) {
      Log.i(TAG, "Diagnostics: $line")
      diagnostics = if (diagnostics.isEmpty()) line else "$diagnostics\n$line"
    }

    fun start() {
      val available = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
      note("Offline recognizer: ${if (available) "yes" else "no"}; language $language")
      if (!available) {
        finish(context.getString(R.string.Transcribe__no_offline), complete = false)
        return
      }

      // Check the offline model first: without it the recognizer reports "no match", which
      // looked like "No words were recognized" (Backchannel, 2026-11-16). The same recognizer is
      // then used to listen: destroying a separate checker right before listening disconnected
      // the recognition service about half the time (error 11, seen on the emulator).
      val checker = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
      recognizer = checker
      var answered = false
      val proceed = { support: RecognitionSupport? ->
        if (!answered) {
          answered = true
          if (!done) onSupport(support)
        }
      }
      try {
        checker.checkRecognitionSupport(
          recognitionIntent(),
          { main.post(it) },
          object : RecognitionSupportCallback {
            override fun onSupportResult(recognitionSupport: RecognitionSupport) = proceed(recognitionSupport)
            override fun onError(error: Int) {
              Log.w(TAG, "checkRecognitionSupport failed: $error")
              note("Language check failed: error $error")
              proceed(null)
            }
          }
        )
        // Some recognizers never answer; don't wait on them forever.
        main.postDelayed({
          if (!answered) note("Language check: no answer")
          proceed(null)
        }, SUPPORT_TIMEOUT_MS)
      } catch (e: Exception) {
        Log.w(TAG, "checkRecognitionSupport threw", e)
        note("Language check failed: ${e.javaClass.simpleName}")
        proceed(null)
      }
    }

    private fun onSupport(support: RecognitionSupport?) {
      if (support != null) {
        val installed = support.installedOnDeviceLanguages.any { matches(it) }
        val pending = support.pendingOnDeviceLanguages.any { matches(it) }
        val supported = support.supportedOnDeviceLanguages.any { matches(it) }
        note("Installed: ${support.installedOnDeviceLanguages.ifEmpty { listOf("none") }.joinToString()}")
        if (support.pendingOnDeviceLanguages.isNotEmpty()) note("Downloading: ${support.pendingOnDeviceLanguages.joinToString()}")
        note("Downloadable: ${support.supportedOnDeviceLanguages.size} languages, ${if (supported) "including" else "not including"} $language")
        if (!installed) {
          val name = Locale.getDefault().displayLanguage
          when {
            pending -> finish(context.getString(R.string.Transcribe__pending, name), complete = false)
            supported -> {
              if (askForModel) {
                note("Asked Android to download the $language model")
                downloadModel(context)
              }
              finish(context.getString(R.string.Transcribe__downloading, name), complete = false)
            }
            else -> finish(context.getString(R.string.Transcribe__unsupported_language, name), complete = false)
          }
          return
        }
      }
      listen()
    }

    /** The model for [tag] serves this phone's language (en-US for en-US, or plain "en"). */
    private fun matches(tag: String): Boolean {
      return tag.equals(language, ignoreCase = true) || Locale.forLanguageTag(tag).language == Locale.getDefault().language
    }

    private fun recognitionIntent(): Intent {
      return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
    }

    private fun listen() {

      val pipe = try {
        ParcelFileDescriptor.createPipe()
      } catch (e: IOException) {
        Log.w(TAG, "Couldn't open a pipe", e)
        finish(context.getString(R.string.Transcribe__failed), complete = false)
        return
      }
      readSide = pipe[0]
      val writeSide = pipe[1]

      val intent = recognitionIntent().apply {
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, TARGET_RATE)
        putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
      }

      (recognizer ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { recognizer = it }).apply {
        setRecognitionListener(this@Recognition)
        startListening(intent)
      }

      SignalExecutors.UNBOUNDED.execute {
        try {
          val bytes = FileOutputStream(writeSide.fileDescriptor).use { decodeToPcm(context, audio, it) }
          main.post { note("Decoded ${bytes / 2 / TARGET_RATE} s of audio") }
          if (bytes == 0L) throw IOException("No audio decoded")
        } catch (e: Exception) {
          Log.w(TAG, "Couldn't decode the voice message", e)
          decodeFailed = true
          main.post { note("Couldn't decode the audio: ${e.javaClass.simpleName}") }
          main.post { finish(context.getString(R.string.Transcribe__failed), complete = false) }
        } finally {
          try {
            writeSide.close()
          } catch (e: IOException) {
          }
        }
      }
    }

    /** Stops without reporting anything further. */
    fun cancel() {
      done = true
      stop()
    }

    private fun stop() {
      recognizer?.destroy()
      recognizer = null
      try {
        readSide?.close()
      } catch (e: IOException) {
      }
    }

    private fun finish(error: String?, complete: Boolean) {
      if (done) return
      done = true
      stop()
      val text = finalText.toString().trim()
      if (decodeFailed && text.isEmpty()) {
        listener.onDone(text, context.getString(R.string.Transcribe__failed), complete = false)
      } else {
        listener.onDone(text, error, complete)
      }
    }

    private fun append(results: Bundle?) {
      val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
      if (!best.isNullOrEmpty()) {
        if (finalText.isNotEmpty()) finalText.append(' ')
        finalText.append(best)
      }
      partial = ""
    }

    private fun progress() {
      if (!done) listener.onProgress((finalText.toString() + " " + partial).trim())
    }

    override fun onSegmentResults(segmentResults: Bundle) {
      append(segmentResults)
      progress()
    }

    override fun onEndOfSegmentedSession() = finish(null, complete = true)

    override fun onResults(results: Bundle?) {
      append(results)
      finish(null, complete = true)
    }

    override fun onPartialResults(partialResults: Bundle?) {
      partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
      progress()
    }

    override fun onError(error: Int) {
      Log.w(TAG, "Recognizer error $error")
      if (done) return
      note("Recognizer error $error")
      when (error) {
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
          if (askForModel) downloadModel(context)
          finish(context.getString(R.string.Transcribe__downloading, Locale.getDefault().displayLanguage), complete = false)
        }
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> finish(context.getString(R.string.Transcribe__no_permission), complete = false)
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> finish(null, complete = true)
        else -> finish(context.getString(R.string.Transcribe__failed), complete = false)
      }
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit
  }

  /**
   * Called when someone turns on automatic transcription: if the phone's offline recognizer can
   * download the model for this phone's language but doesn't have it, ask Android to (it shows its
   * own confirmation with the size). Nothing is downloaded unless transcription is turned on or used.
   */
  @JvmStatic
  fun requestModelIfMissing(context: Context) {
    if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return
    val main = Handler(Looper.getMainLooper())
    main.post {
      val language = Locale.getDefault().toLanguageTag()
      val base = Locale.getDefault().language
      fun serves(tag: String) = tag.equals(language, ignoreCase = true) || Locale.forLanguageTag(tag).language == base
      try {
        val checker = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        checker.checkRecognitionSupport(
          Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, language),
          { main.post(it) },
          object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
              checker.destroy()
              val missing = support.installedOnDeviceLanguages.none(::serves) && support.pendingOnDeviceLanguages.none(::serves)
              Log.i(TAG, "Offline model for $language on enable: installed=${support.installedOnDeviceLanguages} missing=$missing")
              if (missing && support.supportedOnDeviceLanguages.any(::serves)) downloadModel(context)
            }

            override fun onError(error: Int) {
              checker.destroy()
              Log.w(TAG, "checkRecognitionSupport failed on enable: $error")
            }
          }
        )
      } catch (e: Exception) {
        Log.w(TAG, "Couldn't check the offline model", e)
      }
    }
  }

  @RequiresApi(33)
  private fun downloadModel(context: Context) {
    try {
      val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
      SpeechRecognizer.createOnDeviceSpeechRecognizer(context).apply {
        triggerModelDownload(intent)
        destroy()
      }
    } catch (e: Exception) {
      Log.w(TAG, "Couldn't ask for the offline model", e)
    }
  }

  /** The long-press > Transcribe dialog. A finished transcript also goes to [onTranscript] (stored under the bubble). */
  @RequiresApi(33)
  private class Session(private val context: Context, audio: Uri, private val onTranscript: (String) -> Unit) : Listener {

    private var finalText = ""

    private val textView = TextView(context).apply {
      val pad = (24 * context.resources.displayMetrics.density).toInt()
      setPadding(pad, pad / 2, pad, 0)
      setTextIsSelectable(true)
      textSize = 16f
      setText(R.string.Transcribe__working)
    }

    private val dialog: AlertDialog = MaterialAlertDialogBuilder(context)
      .setTitle(R.string.Transcribe__title)
      .setView(ScrollView(context).apply { addView(textView) })
      .setPositiveButton(R.string.Transcribe__copy, null)
      .setNegativeButton(R.string.Transcribe__close, null)
      .create()

    private val recognition = Recognition(context, audio, this, askForModel = true)

    fun start() {
      dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
          isEnabled = false
          setOnClickListener {
            ServiceUtil.getClipboardManager(context).setPrimaryClip(ClipData.newPlainText("transcript", finalText))
            Toast.makeText(context, R.string.Transcribe__copied, Toast.LENGTH_SHORT).show()
          }
        }
      }
      dialog.setOnDismissListener { recognition.cancel() }
      dialog.show()
      recognition.start()
    }

    override fun onProgress(text: String) {
      textView.text = if (text.isEmpty()) context.getString(R.string.Transcribe__working) else text
    }

    override fun onDone(text: String, error: String?, complete: Boolean) {
      finalText = text
      textView.text = when {
        text.isNotEmpty() -> text
        else -> {
          // Say what the phone's recognizer reported, so a failure on one phone can be told apart from another.
          val message = error ?: context.getString(R.string.Transcribe__nothing)
          if (recognition.diagnostics.isEmpty()) message else "$message\n\n${context.getString(R.string.Transcribe__diagnostics)}\n${recognition.diagnostics}"
        }
      }
      dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = text.isNotEmpty()
      if (complete) onTranscript(text)
    }
  }

  /**
   * Decodes [uri] and writes 16 kHz mono 16-bit little-endian PCM to [out]; returns the bytes written.
   *
   * [uri] is a Signal attachment URI (content://<app>/part/N, or a blob), which no content provider
   * serves: it has to be opened through [DecryptableUriMediaInput], which decrypts the attachment on
   * the fly. Opening it with MediaExtractor.setDataSource(context, uri) failed on every voice message,
   * so the recognizer got no audio and said "no match" (Backchannel, 2026-11-16).
   */
  @RequiresApi(33)
  @JvmStatic
  fun decodeToPcm(context: Context, uri: Uri, out: OutputStream): Long {
    DecryptableUriMediaInput.createForUri(context, uri).use { input ->
      val extractor = input.createExtractor()
      try {
        return decode(extractor, out)
      } finally {
        extractor.release()
      }
    }
  }

  private fun decode(extractor: MediaExtractor, out: OutputStream): Long {
    val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
      ?: throw IOException("No audio track")
    extractor.selectTrack(track)
    val format = extractor.getTrackFormat(track)
    val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
    codec.configure(format, null, null, 0)
    codec.start()

    var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    val info = MediaCodec.BufferInfo()
    var inputDone = false
    var outputDone = false
    val resampler = Resampler(TARGET_RATE)
    var written = 0L

    try {
      while (!outputDone) {
        if (!inputDone) {
          val inIndex = codec.dequeueInputBuffer(10_000)
          if (inIndex >= 0) {
            val buffer = codec.getInputBuffer(inIndex)!!
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) {
              codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
              inputDone = true
            } else {
              codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
              extractor.advance()
            }
          }
        }

        val outIndex = codec.dequeueOutputBuffer(info, 10_000)
        when {
          outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
            val newFormat = codec.outputFormat
            rate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
          }
          outIndex >= 0 -> {
            val buffer = codec.getOutputBuffer(outIndex)!!
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            val shorts = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val mono = downmix(ShortArray(shorts.remaining()).also { shorts.get(it) }, channels)
            codec.releaseOutputBuffer(outIndex, false)

            val pcm = resampler.process(mono, rate)
            if (pcm.isNotEmpty()) {
              val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
              bytes.asShortBuffer().put(pcm)
              out.write(bytes.array())
              written += bytes.capacity()
            }

            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
              outputDone = true
            }
          }
        }
      }
      out.flush()
    } finally {
      codec.stop()
      codec.release()
    }
    return written
  }

  /** Averages interleaved [channels] into one. */
  @JvmStatic
  fun downmix(interleaved: ShortArray, channels: Int): ShortArray {
    if (channels <= 1) return interleaved
    return ShortArray(interleaved.size / channels) { f ->
      var sum = 0
      for (c in 0 until channels) sum += interleaved[f * channels + c]
      (sum / channels).toShort()
    }
  }

  /** Streaming linear resampler to [targetRate]; keeps the last input sample so buffer boundaries join up. */
  class Resampler(private val targetRate: Int) {
    private var position = 0.0 // next output position, in input frames, relative to the current buffer (-1 = the carried sample)
    private var last: Short? = null

    fun process(input: ShortArray, rate: Int): ShortArray {
      if (input.isEmpty()) return ShortArray(0)
      if (rate == targetRate) return input
      val step = rate.toDouble() / targetRate
      val prev = last
      // Sample at index i, where -1 is the sample carried over from the previous buffer.
      fun at(i: Int): Int = if (i < 0) (prev ?: input[0]).toInt() else input[i].toInt()
      if (prev == null && position < 0) position = 0.0
      val out = ShortArray(maxOf(0, ((input.size - 1 - position) / step).toInt() + 1))
      var n = 0
      while (position <= input.size - 1 && n < out.size) {
        val i = kotlin.math.floor(position).toInt()
        val frac = position - i
        val a = at(i)
        val b = if (i + 1 <= input.size - 1) at(i + 1) else a
        out[n++] = (a + (b - a) * frac).toInt().toShort()
        position += step
      }
      position -= input.size
      last = input[input.size - 1]
      return if (n == out.size) out else out.copyOf(n)
    }
  }
}
