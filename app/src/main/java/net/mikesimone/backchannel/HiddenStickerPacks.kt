package net.mikesimone.backchannel

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.thoughtcrime.securesms.dependencies.AppDependencies

/**
 * Hide a sticker pack someone sent you from the sticker manager's "Stickers you received" list
 * (parity with Backchannel Web F65 / Signal Desktop's stickerPackHiding). On this phone only;
 * installing a hidden pack later still works from the chat where it was sent.
 */
object HiddenStickerPacks {

  private const val PREFS = "backchannel_hidden_sticker_packs"
  private const val KEY = "hidden"

  private fun prefs() = AppDependencies.application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  private val state: MutableStateFlow<Set<String>> by lazy {
    MutableStateFlow(prefs().getStringSet(KEY, emptySet())!!.toSet())
  }

  fun flow(): StateFlow<Set<String>> = state.asStateFlow()

  fun hide(packId: String) {
    val updated = (flow().value + packId)
    prefs().edit().putStringSet(KEY, updated).apply()
    state.value = updated
  }
}
