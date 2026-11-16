package net.mikesimone.backchannel

import android.app.Activity
import android.media.MediaExtractor
import android.os.Build
import android.os.Bundle
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.attachments.UriAttachment
import org.thoughtcrime.securesms.crypto.MasterSecretUtil
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies

/**
 * Debug builds only: runs voice-message transcription on a bundled voice note, the way the app
 * does, without a Signal account. The note is stored as a real (encrypted) attachment and handed
 * to [VoiceTranscriber.Recognition] by its attachment URI, exactly like a received voice message.
 *
 *   adb shell pm grant <package> android.permission.RECORD_AUDIO
 *   adb shell am start -n <package>/net.mikesimone.backchannel.TranscribeProbeActivity --es asset voice-44k.aac
 *   adb logcat -s TranscribeProbeActivity VoiceTranscriber
 *
 * Bundled notes (app/src/debug/assets/backchannel-transcribe): espeak-ng reading "The quick brown
 * fox jumps over the lazy dog. Please call me back this afternoon.", as ADTS AAC at 44.1 kHz (what
 * Signal for Android records) and as m4a at 48 kHz (what Signal for iOS sends).
 */
class TranscribeProbeActivity : Activity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (Build.VERSION.SDK_INT < 33) {
      Log.w(TAG, "RESULT needs Android 13")
      finish()
      return
    }
    val asset = intent.getStringExtra("asset") ?: "voice-44k.aac"
    val contentType = if (asset.endsWith(".m4a")) "audio/mp4" else "audio/aac"

    SignalExecutors.BOUNDED.execute {
      // A fresh install is still locked: unlock it the way the first app start does.
      if (!AppDependencies.isInitialized) {
        val secret = if (MasterSecretUtil.isPassphraseInitialized(this)) {
          MasterSecretUtil.getMasterSecret(this, MasterSecretUtil.getUnencryptedPassphrase())
        } else {
          MasterSecretUtil.generateMasterSecret(this, MasterSecretUtil.getUnencryptedPassphrase())
        }
        runOnUiThread {
          KeyCachingService.setMasterSecret(secret)
          (application as ApplicationContext).onUnlock()
        }
        var waited = 0
        while (!AppDependencies.isInitialized && waited < 30_000) {
          Thread.sleep(200)
          waited += 200
        }
      }
      val bytes = assets.open("backchannel-transcribe/$asset").use { it.readBytes() }
      val blob = AppDependencies.blobs.forData(bytes).createForSingleSessionInMemory()
      val attachment = UriAttachment(
        uri = blob,
        contentType = contentType,
        transferState = AttachmentTable.TRANSFER_PROGRESS_DONE,
        size = bytes.size.toLong(),
        fileName = asset,
        voiceNote = true,
        borderless = false,
        videoGif = false,
        quote = false,
        quoteTargetContentType = null,
        caption = null,
        stickerLocator = null,
        blurHash = null,
        audioHash = null,
        transformProperties = null
      )
      val uri = SignalDatabase.attachments.insertAttachmentForPreUpload(attachment).uri!!
      Log.i(TAG, "Stored $asset (${bytes.size} bytes) as $uri")

      // What bc16 and earlier did with that URI.
      try {
        MediaExtractor().apply { setDataSource(this@TranscribeProbeActivity, uri, null) }.release()
        Log.i(TAG, "Plain MediaExtractor opened $uri")
      } catch (e: Exception) {
        Log.i(TAG, "Plain MediaExtractor can't open $uri: $e")
      }

      runOnUiThread {
        VoiceTranscriber.Recognition(
          this,
          uri,
          object : VoiceTranscriber.Listener {
            override fun onProgress(text: String) {
              Log.i(TAG, "Progress: $text")
            }

            override fun onDone(text: String, error: String?, complete: Boolean) {
              Log.i(TAG, "RESULT asset=$asset complete=$complete error=$error text='$text'")
              finish()
            }
          },
          askForModel = intent.getBooleanExtra("download", true)
        ).also { recognition = it }.start()
      }
    }
  }

  private var recognition: VoiceTranscriber.Recognition? = null

  override fun onDestroy() {
    recognition?.diagnostics?.let { Log.i(TAG, "Diagnostics:\n$it") }
    super.onDestroy()
  }

  companion object {
    private val TAG = Log.tag(TranscribeProbeActivity::class.java)
  }
}
