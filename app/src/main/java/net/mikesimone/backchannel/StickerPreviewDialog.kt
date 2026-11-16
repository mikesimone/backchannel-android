package net.mikesimone.backchannel

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.bumptech.glide.RequestManager
import org.signal.glide.decryptableuri.DecryptableUri
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.model.StickerRecord

/**
 * Long-press a sticker in the sticker picker (Backchannel, Mike 2026-11-16; parity
 * with Backchannel Web F62): the sticker fills the window, with Send, Add to (or
 * Remove from) favorites, and Close. Tapping the dimmed background also closes it.
 */
object StickerPreviewDialog {

  @JvmStatic
  fun show(context: Context, requestManager: RequestManager, sticker: StickerRecord, onSend: () -> Unit) {
    val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    val density = context.resources.displayMetrics.density
    val pad = (24 * density).toInt()

    val emoji = TextView(context).apply {
      text = sticker.emoji
      textSize = 22f
      gravity = Gravity.CENTER
      setPadding(0, 0, 0, pad / 3)
    }

    val image = ImageView(context).apply {
      scaleType = ImageView.ScaleType.FIT_CENTER
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    }
    requestManager.load(DecryptableUri(sticker.uri)).fitCenter().into(image)

    fun favoriteLabel() = context.getString(if (FavoriteStickers.isFavorite(context, sticker)) R.string.FavoriteStickers__remove else R.string.FavoriteStickers__add)

    val buttons = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER
      setPadding(0, pad / 2, 0, 0)
    }

    fun button(label: String, onClick: (Button) -> Unit) = Button(context).apply {
      text = label
      setTextColor(Color.WHITE)
      setBackgroundColor(Color.TRANSPARENT)
      setOnClickListener { onClick(this) }
      buttons.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    button(context.getString(R.string.FavoriteStickers__send)) {
      dialog.dismiss()
      onSend()
    }
    button(favoriteLabel()) {
      FavoriteStickers.toggle(context, sticker)
      it.text = favoriteLabel()
    }
    button(context.getString(R.string.FavoriteStickers__close)) { dialog.dismiss() }

    val root = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      setBackgroundColor(0xCC000000.toInt())
      setPadding(pad, pad * 2, pad, pad)
      addView(emoji)
      addView(image)
      addView(buttons)
      setOnClickListener { dialog.dismiss() }
    }
    emoji.setTextColor(Color.WHITE)

    dialog.setContentView(root)
    dialog.setCanceledOnTouchOutside(true)
    dialog.show()
  }
}
