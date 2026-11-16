package net.mikesimone.backchannel.whisper

/** The JNI side of [WhisperEngine] (src/main/cpp/whisper_jni.cpp). */
internal object WhisperNative {

  init {
    System.loadLibrary("backchannel_whisper")
  }

  /** Called from native code, on the transcribing thread, for every finished segment. */
  fun interface SegmentListener {
    fun onSegment(text: String)
  }

  /** Loads [modelPath]; returns a handle, or 0 if the model couldn't be loaded. */
  external fun open(modelPath: String, libraryDir: String): Long

  /** {detected language, text}, or null when whisper failed or was cancelled. */
  external fun transcribe(handle: Long, samples: FloatArray, threads: Int, language: String?, listener: SegmentListener?): Array<String>?

  external fun cancel(handle: Long)

  external fun close(handle: Long)

  external fun systemInfo(): String
}
