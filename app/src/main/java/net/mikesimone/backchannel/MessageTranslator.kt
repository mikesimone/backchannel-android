package net.mikesimone.backchannel

import android.content.ClipData
import android.content.Context
import android.icu.util.ULocale
import android.os.Build
import android.os.CancellationSignal
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextLanguage
import android.view.translation.TranslationCapability
import android.view.translation.TranslationContext
import android.view.translation.TranslationManager
import android.view.translation.TranslationRequest
import android.view.translation.TranslationRequestValue
import android.view.translation.TranslationSpec
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.signal.core.util.ServiceUtil
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.model.MessageRecord
import java.util.Locale

/**
 * Translate a message (Backchannel, Mike 2026-11-06), on the phone only.
 * Uses Android's own on-device translation service (Android 12+): the
 * language is detected on the phone and the text is translated by the
 * system's offline translator. Nothing goes to a network service; if the
 * language pack isn't on the phone, it opens Android's page to download it.
 */
object MessageTranslator {

  private val TAG = Log.tag(MessageTranslator::class.java)

  @JvmStatic
  fun canTranslate(record: MessageRecord): Boolean {
    return Build.VERSION.SDK_INT >= 31 && !record.isUpdate && !record.isRemoteDelete && record.body.isNotBlank()
  }

  @JvmStatic
  fun show(context: Context, record: MessageRecord) {
    if (Build.VERSION.SDK_INT < 31) return
    Session(context, record.body).start()
  }

  @RequiresApi(31)
  private class Session(private val context: Context, private val text: String) {

    private val main = ContextCompat.getMainExecutor(context)
    private val cancel = CancellationSignal()
    private var result: String? = null

    private val textView = TextView(context).apply {
      val pad = (24 * context.resources.displayMetrics.density).toInt()
      setPadding(pad, pad / 2, pad, 0)
      setTextIsSelectable(true)
      textSize = 16f
      setText(R.string.Translate__working)
    }

    private val dialog: AlertDialog = MaterialAlertDialogBuilder(context)
      .setTitle(R.string.Translate__title)
      .setView(ScrollView(context).apply { addView(textView) })
      .setPositiveButton(R.string.Transcribe__copy, null)
      .setNegativeButton(R.string.Transcribe__close, null)
      .create()

    fun start() {
      dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
          isEnabled = false
          setOnClickListener {
            result?.let {
              ServiceUtil.getClipboardManager(context).setPrimaryClip(ClipData.newPlainText("translation", it))
              Toast.makeText(context, R.string.Transcribe__copied, Toast.LENGTH_SHORT).show()
            }
          }
        }
      }
      dialog.setOnDismissListener { cancel.cancel() }
      dialog.show()

      SignalExecutors.BOUNDED.execute {
        val target = ULocale.forLocale(Locale.getDefault())
        val source = detect()
        main.execute {
          when {
            source == null -> fail(context.getString(R.string.Translate__unknown_language))
            source.language == target.language -> fail(context.getString(R.string.Translate__same_language, target.displayLanguage))
            else -> translate(source, target)
          }
        }
      }
    }

    private fun detect(): ULocale? {
      return try {
        val classifier = context.getSystemService(TextClassificationManager::class.java)?.textClassifier ?: return null
        val language = classifier.detectLanguage(TextLanguage.Request.Builder(text).build())
        if (language.localeHypothesisCount == 0) null else language.getLocale(0)
      } catch (e: Exception) {
        Log.w(TAG, "Couldn't detect the language", e)
        null
      }
    }

    private fun translate(source: ULocale, target: ULocale) {
      val manager = context.getSystemService(TranslationManager::class.java)
      if (manager == null) {
        fail(context.getString(R.string.Translate__unavailable))
        return
      }

      val capability = try {
        manager.getOnDeviceTranslationCapabilities(TranslationSpec.DATA_FORMAT_TEXT, TranslationSpec.DATA_FORMAT_TEXT)
          .firstOrNull { it.sourceSpec.locale.language == source.language && it.targetSpec.locale.language == target.language }
      } catch (e: Exception) {
        Log.w(TAG, "Couldn't read translation capabilities", e)
        null
      }

      when (capability?.state) {
        TranslationCapability.STATE_ON_DEVICE -> Unit
        TranslationCapability.STATE_AVAILABLE_TO_DOWNLOAD, TranslationCapability.STATE_DOWNLOADING -> {
          fail(context.getString(R.string.Translate__download, source.displayLanguage))
          manager.onDeviceTranslationSettingsActivityIntent?.let {
            try {
              it.send()
            } catch (e: Exception) {
              Log.w(TAG, "Couldn't open translation settings", e)
            }
          }
          return
        }
        else -> {
          fail(context.getString(R.string.Translate__not_supported, source.displayLanguage))
          return
        }
      }

      val supported = capability!!
      val translationContext = TranslationContext.Builder(
        TranslationSpec(supported.sourceSpec.locale, TranslationSpec.DATA_FORMAT_TEXT),
        TranslationSpec(supported.targetSpec.locale, TranslationSpec.DATA_FORMAT_TEXT)
      ).build()

      manager.createOnDeviceTranslator(translationContext, main) { translator ->
        if (translator == null) {
          fail(context.getString(R.string.Translate__unavailable))
          return@createOnDeviceTranslator
        }
        val request = TranslationRequest.Builder()
          .setTranslationRequestValues(listOf(TranslationRequestValue.forText(text)))
          .build()
        translator.translate(request, cancel, main) { response ->
          val values = response.translationResponseValues
          val translated = if (values.size() > 0) values.valueAt(0).text?.toString() else null
          translator.destroy()
          if (translated.isNullOrBlank()) {
            fail(context.getString(R.string.Translate__failed))
          } else {
            result = translated
            textView.text = context.getString(R.string.Translate__from, source.displayLanguage) + "\n\n" + translated
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
          }
        }
      }
    }

    private fun fail(message: String) {
      textView.text = message
    }
  }
}
