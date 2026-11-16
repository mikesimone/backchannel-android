package org.thoughtcrime.securesms.keyboard.sticker

import android.net.Uri
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.nullIfBlank
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.StickerTable
import org.thoughtcrime.securesms.database.StickerTable.StickerPackRecordReader
import org.thoughtcrime.securesms.database.StickerTable.StickerRecordReader
import org.thoughtcrime.securesms.database.model.StickerPackRecord
import org.thoughtcrime.securesms.database.model.StickerRecord
import java.util.function.Consumer

private const val RECENT_LIMIT = 24
private const val RECENT_PACK_ID = "RECENT"
private const val FAVORITE_PACK_ID = "BACKCHANNEL_FAVORITES" // BACKCHANNEL

class StickerKeyboardRepository(private val stickerTable: StickerTable) {
  fun getStickerPacks(consumer: Consumer<List<KeyboardStickerPack>>) {
    SignalExecutors.BOUNDED.execute {
      val packs: MutableList<KeyboardStickerPack> = mutableListOf()

      StickerPackRecordReader(stickerTable.getInstalledStickerPacks()).use { reader ->
        var pack: StickerPackRecord? = reader.getNext()
        while (pack != null) {
          packs += KeyboardStickerPack(packId = pack.packId, title = pack.title.nullIfBlank(), coverUri = pack.cover.uri)
          pack = reader.getNext()
        }
      }

      val fullPacks: MutableList<KeyboardStickerPack> = packs.map { p ->
        val stickers: MutableList<StickerRecord> = mutableListOf()

        StickerRecordReader(stickerTable.getStickersForPack(p.packId)).use { reader ->
          var sticker: StickerRecord? = reader.getNext()
          while (sticker != null) {
            stickers.add(sticker)
            sticker = reader.getNext()
          }
        }

        p.copy(stickers = stickers)
      }.toMutableList()

      val recentStickerPack: KeyboardStickerPack = getRecentStickerPack()
      if (recentStickerPack.stickers.isNotEmpty()) {
        fullPacks.add(0, recentStickerPack)
      }
      // BACKCHANNEL: Favorites tab first (FavoriteStickers)
      val favoriteStickerPack: KeyboardStickerPack = getFavoriteStickerPack()
      if (favoriteStickerPack.stickers.isNotEmpty()) {
        fullPacks.add(0, favoriteStickerPack)
      }
      consumer.accept(fullPacks)
    }
  }

  // BACKCHANNEL: favorite stickers that are still installed, newest first
  private fun getFavoriteStickerPack(): KeyboardStickerPack {
    val context = org.thoughtcrime.securesms.dependencies.AppDependencies.application
    val stickers = net.mikesimone.backchannel.FavoriteStickers.list(context).mapNotNull { (packId, stickerId) ->
      stickerTable.getSticker(packId, stickerId, false)
    }
    return KeyboardStickerPack(
      packId = FAVORITE_PACK_ID,
      title = null,
      titleResource = R.string.FavoriteStickers__title,
      coverUri = null,
      coverResource = R.drawable.symbol_star_24,
      stickers = stickers
    )
  }

  private fun getRecentStickerPack(): KeyboardStickerPack {
    val recentStickers: MutableList<StickerRecord> = mutableListOf()

    StickerRecordReader(stickerTable.getRecentlyUsedStickers(RECENT_LIMIT)).use { reader ->
      var recentSticker: StickerRecord? = reader.getNext()
      while (recentSticker != null) {
        recentStickers.add(recentSticker)
        recentSticker = reader.getNext()
      }
    }

    return KeyboardStickerPack(
      packId = RECENT_PACK_ID,
      title = null,
      titleResource = R.string.StickerKeyboard__recently_used,
      coverUri = null,
      coverResource = R.drawable.ic_recent_20,
      stickers = recentStickers
    )
  }

  data class KeyboardStickerPack(
    val packId: String,
    val title: String?,
    val titleResource: Int? = null,
    val coverUri: Uri?,
    val coverResource: Int? = null,
    val stickers: List<StickerRecord> = emptyList()
  )
}
