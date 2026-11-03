package net.mikesimone.backchannel

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.R

/**
 * "What's new" pop-up (Backchannel, Mike 2026-11-03): shown once after each
 * install or update, from the chat list. For now it lists everything since the
 * first release, newest first; the text lives in R.string.WhatsNew__body.
 */
object WhatsNew {

  private const val PREFS = "backchannel_whats_new"
  private const val KEY_SEEN = "seen_version_code"

  @JvmStatic
  fun shouldShow(context: Context): Boolean {
    return prefs(context).getInt(KEY_SEEN, 0) < BuildConfig.VERSION_CODE
  }

  @JvmStatic
  fun show(activity: AppCompatActivity) {
    // Mark it seen first, so it can't come back on rotation or a crash.
    prefs(activity).edit().putInt(KEY_SEEN, BuildConfig.VERSION_CODE).apply()
    MaterialAlertDialogBuilder(activity)
      .setTitle(R.string.WhatsNew__title)
      .setMessage(activity.getText(R.string.WhatsNew__body))
      .setPositiveButton(android.R.string.ok, null)
      .show()
  }

  private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
