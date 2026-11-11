package net.mikesimone.backchannel.immich

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import org.signal.core.util.logging.Log

/**
 * Backchannel, Mike 2026-11-11: answers Immich's app return address
 * (`app.immich:///oauth-callback?code=...&state=...`) at the end of a browser
 * sign-in ([ImmichBrowserSso]). A return is kept only when it matches the
 * sign-in in progress (same state, not expired); anything else is ignored.
 * It then brings the Immich picker back to the front, closing the browser
 * tab, and the picker finishes the sign-in.
 */
class ImmichOAuthCallbackActivity : Activity() {

  private val TAG = Log.tag(ImmichOAuthCallbackActivity::class.java)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val url = intent?.dataString
    if (url != null && ImmichClient.deliverSsoCallback(this, url)) {
      startActivity(
        Intent(this, ImmichPickerActivity::class.java)
          .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      )
    } else {
      Log.w(TAG, "Ignoring an Immich sign-in return that doesn't match a sign-in in progress")
    }
    finish()
  }
}
