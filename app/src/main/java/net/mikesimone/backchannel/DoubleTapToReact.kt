package net.mikesimone.backchannel

import android.view.GestureDetector
import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView

/**
 * Double-tap a message to react with your first reaction (❤️ unless you changed it).
 * Watches the whole conversation list, so it works on every kind of message, and
 * never consumes touches, so single taps, long presses and swipes behave as before.
 */
class DoubleTapToReact(
  recyclerView: RecyclerView,
  private val onDoubleTap: (adapterPosition: Int) -> Unit
) : RecyclerView.SimpleOnItemTouchListener() {

  private val detector = GestureDetector(
    recyclerView.context,
    object : GestureDetector.SimpleOnGestureListener() {
      override fun onDoubleTap(e: MotionEvent): Boolean {
        val child = recyclerView.findChildViewUnder(e.x, e.y) ?: return false
        val position = recyclerView.getChildAdapterPosition(child)
        if (position != RecyclerView.NO_POSITION) {
          onDoubleTap(position)
        }
        return false
      }
    }
  )

  override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
    detector.onTouchEvent(e)
    return false
  }
}
