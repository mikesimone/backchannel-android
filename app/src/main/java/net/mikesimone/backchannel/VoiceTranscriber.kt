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
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
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

    fun start() {
      if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
        finish(context.getString(R.string.Transcribe__no_offline), complete = false)
        return
      }

      val pipe = try {
        ParcelFileDescriptor.createPipe()
      } catch (e: IOException) {
        Log.w(TAG, "Couldn't open a pipe", e)
        finish(context.getString(R.string.Transcribe__failed), complete = false)
        return
      }
      readSide = pipe[0]
      val writeSide = pipe[1]

      val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, TARGET_RATE)
        putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
      }

      recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also {
        it.setRecognitionListener(this)
        it.startListening(intent)
      }

      SignalExecutors.UNBOUNDED.execute {
        try {
          FileOutputStream(writeSide.fileDescriptor).use { decodeToPcm(context, audio, it) }
        } catch (e: Exception) {
          Log.w(TAG, "Couldn't decode the voice message", e)
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
      listener.onDone(finalText.toString().trim(), error, complete)
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
        error != null -> error
        else -> context.getString(R.string.Transcribe__nothing)
      }
      dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = text.isNotEmpty()
      if (complete) onTranscript(text)
    }
  }

  /** Decodes [uri] and writes 16 kHz mono 16-bit little-endian PCM to [out]. */
  private fun decodeToPcm(context: Context, uri: Uri, out: OutputStream) {
    val extractor = MediaExtractor()
    extractor.setDataSource(context, uri, null)
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
    var position = 0.0 // resampler position, in input frames

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
            val frames = shorts.remaining() / channels
            val mono = ShortArray(frames) { f ->
              var sum = 0
              for (c in 0 until channels) sum += shorts.get(f * channels + c)
              (sum / channels).toShort()
            }
            codec.releaseOutputBuffer(outIndex, false)

            // Linear resample to TARGET_RATE.
            val step = rate.toDouble() / TARGET_RATE
            val outSamples = ArrayList<Short>()
            while (position < frames - 1) {
              val i = position.toInt()
              val frac = position - i
              outSamples += (mono[i] * (1 - frac) + mono[i + 1] * frac).toInt().toShort()
              position += step
            }
            position -= frames
            if (position < 0) position = 0.0

            val bytes = ByteBuffer.allocate(outSamples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            outSamples.forEach { bytes.putShort(it) }
            out.write(bytes.array())

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
      extractor.release()
    }
  }
}
