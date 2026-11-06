package net.mikesimone.backchannel

import android.content.Context
import androidx.annotation.WorkerThread
import org.signal.core.models.ServiceId
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.IdentityTable.VerifiedStatus
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.recipients.RecipientId

/**
 * Accept changed safety numbers automatically (Backchannel, Mike 2026-11-05).
 * Off by default. When on, a contact's new safety number (new phone, reinstall)
 * never blocks a send or asks you to confirm it: the new key is saved as
 * "not verified" and the chat still shows the usual "safety number changed" line.
 *
 * Stored on this phone only.
 */
object AutoAcceptKeys {

  private val TAG = Log.tag(AutoAcceptKeys::class.java)

  private const val PREFS = "backchannel_safety_numbers"
  private const val KEY_AUTO_ACCEPT = "auto_accept_changed"

  @Volatile
  private var cache: Boolean? = null

  private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  @JvmStatic
  fun isEnabled(context: Context): Boolean {
    return cache ?: prefs(context).getBoolean(KEY_AUTO_ACCEPT, false).also { cache = it }
  }

  @JvmStatic
  fun setEnabled(context: Context, enabled: Boolean) {
    prefs(context).edit().putBoolean(KEY_AUTO_ACCEPT, enabled).apply()
    cache = enabled
  }

  /** Clears changes you'd otherwise still have to accept: people whose number changed after you verified them. */
  @WorkerThread
  @JvmStatic
  fun acceptPending() {
    val identities = AppDependencies.protocolStore.aci().identities()
    for (address in SignalDatabase.identities.getUnverifiedAddresses()) {
      try {
        val serviceId = ServiceId.parseOrNull(address) ?: continue
        val record = SignalDatabase.identities.getIdentityStoreRecord(address) ?: continue
        identities.setVerified(RecipientId.from(serviceId), record.identityKey, VerifiedStatus.DEFAULT)
      } catch (e: Exception) {
        Log.w(TAG, "Couldn't accept a changed safety number", e)
      }
    }
  }
}
