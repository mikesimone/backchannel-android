package net.mikesimone.backchannel.immich

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/**
 * Backchannel, Mike 2026-11-11: lets the Immich app itself be the picker, but
 * only when the installed Immich app says it can pick (answers GET_CONTENT or
 * PICK for photos and videos). If it doesn't, the attach menu doesn't show the
 * option and [ImmichPickerActivity] stays the way to reach Immich.
 */
object ImmichAppPicker {

  const val PACKAGE = "app.alextran.immich"

  private fun candidates(): List<Intent> = listOf(Intent.ACTION_GET_CONTENT, Intent.ACTION_PICK).map { action ->
    Intent(action).apply {
      setPackage(PACKAGE)
      type = "image/*"
      putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
      putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    }
  }

  /** The intent the Immich app answers, or null if it isn't installed or can't pick. */
  fun pickIntent(context: Context): Intent? {
    val pm = context.packageManager
    return try {
      candidates().firstOrNull { it.resolveActivity(pm) != null }
    } catch (e: Exception) {
      null
    }
  }

  @JvmStatic
  fun isAvailable(context: Context): Boolean = pickIntent(context) != null

  /** Returns the picked items' content URIs, like any other photo app. */
  object Contract : ActivityResultContract<Unit, List<Uri>>() {
    override fun createIntent(context: Context, input: Unit): Intent = pickIntent(context) ?: throw ActivityNotFoundException("Immich can't pick")

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
      if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
      val clip = intent.clipData
      if (clip != null && clip.itemCount > 0) return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
      return listOfNotNull(intent.data)
    }
  }
}
