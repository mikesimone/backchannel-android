package net.mikesimone.backchannel

import android.content.Context
import org.thoughtcrime.securesms.database.model.StickerRecord
import org.thoughtcrime.securesms.dependencies.AppDependencies

/**
 * Favorite stickers (Backchannel, 2026-11-16; parity with Backchannel Web F61).
 *
 * Why: Signal Desktop 8.36 added a Favorites tab to its sticker picker; Signal
 * Android has none. Long-press a sticker in the picker and tap Add to favorites,
 * and it shows first in a Favorites tab.
 *
 * Stored on this phone only, newest first. Signal Android has no favorites sync,
 * so these don't sync with Desktop or Backchannel Web.
 */
object FavoriteStickers {

  private const val PREFS = "backchannel_favorite_stickers"
  private const val KEY = "favorites"
  private const val SEPARATOR = "\n"

  @Volatile
  private var cache: List<String>? = null

  private fun key(packId: String, stickerId: Int) = "$packId:$stickerId"

  /** Favorites as (packId, stickerId), newest first. */
  @JvmStatic
  fun list(context: Context): List<Pair<String, Int>> {
    return keys(context).mapNotNull { k ->
      val i = k.lastIndexOf(':')
      val id = k.substring(i + 1).toIntOrNull()
      if (i <= 0 || id == null) null else k.substring(0, i) to id
    }
  }

  @JvmStatic
  fun isFavorite(context: Context, sticker: StickerRecord): Boolean = keys(context).contains(key(sticker.packId, sticker.stickerId))

  /** Adds or removes [sticker]; returns whether it is now a favorite. */
  @JvmStatic
  fun toggle(context: Context, sticker: StickerRecord): Boolean {
    val k = key(sticker.packId, sticker.stickerId)
    val current = keys(context)
    val now = !current.contains(k)
    val updated = if (now) listOf(k) + current else current - k
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
      .putString(KEY, updated.joinToString(SEPARATOR))
      .apply()
    cache = updated
    AppDependencies.databaseObserver.notifyStickerObservers()
    return now
  }

  private fun keys(context: Context): List<String> {
    return cache ?: context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "")
      .orEmpty()
      .split(SEPARATOR)
      .filter { it.isNotBlank() }
      .also { cache = it }
  }
}
