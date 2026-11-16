package net.mikesimone.backchannel.whisper

import android.content.Context
import java.io.Closeable
import java.io.File

/**
 * Backchannel's own offline speech recognizer: whisper.cpp with a multilingual model (Backchannel,
 * Mike 2026-11-16). It works the same on every phone, unlike Android's on-device recognizer, which
 * Samsung phones route to Samsung's engine or to none.
 *
 * Load a model with [open], call [transcribe] off the main thread (it blocks for as long as the
 * recognition takes), then [close] to free the model's native memory. [cancel] may be called from
 * any thread and makes a running [transcribe] return null.
 */
class WhisperEngine private constructor(private var handle: Long) : Closeable {

  /** What [transcribe] found: the text and the language whisper detected (ISO 639-1, "en"). */
  data class Result(val text: String, val language: String)

  private val lock = Any()

  /**
   * Transcribes 16 kHz mono audio in [-1, 1]. [language] null detects it. [onSegment] gets each
   * finished piece of text as it comes, on this thread. Returns null if cancelled or failed.
   */
  fun transcribe(samples: FloatArray, language: String? = null, threads: Int = defaultThreads(), onSegment: ((String) -> Unit)? = null): Result? {
    synchronized(lock) {
      check(handle != 0L) { "closed" }
      val listener = onSegment?.let { callback -> WhisperNative.SegmentListener { callback(it) } }
      val out = WhisperNative.transcribe(handle, samples, threads, language, listener) ?: return null
      return Result(out[1].trim(), out[0])
    }
  }

  /** As [transcribe], for 16-bit PCM. */
  fun transcribe(pcm16: ShortArray, language: String? = null, threads: Int = defaultThreads(), onSegment: ((String) -> Unit)? = null): Result? {
    return transcribe(toFloat(pcm16), language, threads, onSegment)
  }

  fun cancel() {
    val current = handle
    if (current != 0L) WhisperNative.cancel(current)
  }

  override fun close() {
    cancel()
    synchronized(lock) {
      if (handle != 0L) {
        WhisperNative.close(handle)
        handle = 0L
      }
    }
  }

  companion object {
    /** The rate [transcribe] expects. */
    const val SAMPLE_RATE = 16000

    /** Loads [model] (a ggml whisper model file). Slow (reads the whole file): not on the main thread. Null if it isn't a usable model. */
    @JvmStatic
    fun open(context: Context, model: File): WhisperEngine? {
      if (!model.isFile) return null
      val handle = WhisperNative.open(model.absolutePath, context.applicationInfo.nativeLibraryDir)
      return if (handle == 0L) null else WhisperEngine(handle)
    }

    /** Whisper gets slower, not faster, past about four threads on phone CPUs (big.LITTLE). */
    @JvmStatic
    fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    /** ggml's view of this phone's CPU (which features the loaded backend uses), for diagnostics. */
    @JvmStatic
    fun systemInfo(): String = WhisperNative.systemInfo()

    @JvmStatic
    fun toFloat(pcm16: ShortArray): FloatArray = FloatArray(pcm16.size) { pcm16[it] / 32768f }
  }
}
