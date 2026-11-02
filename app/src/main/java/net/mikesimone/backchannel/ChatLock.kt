package net.mikesimone.backchannel

import android.app.KeyguardManager
import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import org.signal.core.util.AppForegroundObserver
import org.thoughtcrime.securesms.recipients.RecipientId
import java.util.concurrent.ConcurrentHashMap

/**
 * Locked chats (Backchannel, Mike 2026-11-02): a chat can require your
 * fingerprint or screen lock to open. Once opened, it stays open until the
 * app goes to the background. Locked chats hide their last message in the
 * chat list and their message text in notifications.
 *
 * Which chats are locked is stored on this phone only.
 */
object ChatLock : AppForegroundObserver.Listener {

  private const val PREFS = "backchannel_chat_lock"
  private const val KEY_LOCKED = "locked_recipients"
  private const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

  private val unlocked: MutableSet<String> = ConcurrentHashMap.newKeySet()

  @Volatile
  private var cache: Set<String>? = null

  @Volatile
  private var listening = false

  private fun locked(context: Context): Set<String> {
    return cache ?: (context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_LOCKED, emptySet())?.toSet() ?: emptySet()).also { cache = it }
  }

  @JvmStatic
  fun isLocked(context: Context, recipientId: RecipientId): Boolean {
    return locked(context).contains(recipientId.serialize())
  }

  @JvmStatic
  fun setLocked(context: Context, recipientId: RecipientId, lock: Boolean) {
    val updated = locked(context).toMutableSet()
    if (lock) updated.add(recipientId.serialize()) else updated.remove(recipientId.serialize())
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_LOCKED, updated).apply()
    cache = updated
    if (lock) {
      markUnlocked(recipientId)
    }
  }

  @JvmStatic
  fun needsUnlock(context: Context, recipientId: RecipientId): Boolean {
    return isLocked(context, recipientId) && !unlocked.contains(recipientId.serialize()) && canAuthenticate(context)
  }

  @JvmStatic
  fun markUnlocked(recipientId: RecipientId) {
    if (!listening) {
      listening = true
      AppForegroundObserver.addListener(this)
    }
    unlocked.add(recipientId.serialize())
  }

  override fun onBackground() {
    unlocked.clear()
  }

  /** True when the phone has a fingerprint, face or screen lock to check against. */
  @JvmStatic
  fun canAuthenticate(context: Context): Boolean {
    val keyguard = context.getSystemService(KeyguardManager::class.java)
    return BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS ||
      keyguard?.isDeviceSecure == true
  }

  /** Shows the system fingerprint / screen lock prompt over [fragment]. */
  @JvmStatic
  fun authenticate(fragment: Fragment, title: String, onSuccess: () -> Unit, onCancel: () -> Unit) {
    val prompt = BiometricPrompt(
      fragment,
      ContextCompat.getMainExecutor(fragment.requireContext()),
      object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onCancel()
      }
    )

    prompt.authenticate(
      BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setAllowedAuthenticators(AUTHENTICATORS)
        .build()
    )
  }
}
