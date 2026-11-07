package net.mikesimone.backchannel

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import java.util.WeakHashMap
import kotlin.math.max

/**
 * BACKCHANNEL (Mike 2026-11-06): no input box may ever sit behind the keyboard.
 *
 * On targetSdk 36 every window is edge-to-edge and adjustResize/adjustPan are
 * ignored, so each screen has to move out of the keyboard's way itself, and
 * several don't (the photo caption box in bc8, the share sheet and the
 * forward sheet's "Add a message" box). This is the app-wide safety net: for
 * every activity window and every DialogFragment / bottom sheet window, once
 * the keyboard has settled it checks the focused input, and if its bottom is
 * covered it slides the window's content up just far enough (what adjustPan
 * used to do). Screens that already keep their input above the keyboard (the
 * chat composer) have no overlap, so it never touches them.
 *
 * Not covered: Compose-only dialogs and plain AlertDialogs (their own windows,
 * not DialogFragments), and full-screen Compose screens, whose focused view is
 * the whole screen; those use Modifier.imePadding() instead.
 */
object KeyboardGuard {

  private const val SETTLE_MS = 200L
  private val watched = WeakHashMap<Window, Unit>()

  @JvmStatic
  fun install(app: Application) {
    app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
      override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        (activity as? FragmentActivity)?.supportFragmentManager?.registerFragmentLifecycleCallbacks(dialogWatcher, true)
      }

      override fun onActivityStarted(activity: Activity) = watch(activity.window)
      override fun onActivityResumed(activity: Activity) = Unit
      override fun onActivityPaused(activity: Activity) = Unit
      override fun onActivityStopped(activity: Activity) = Unit
      override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
      override fun onActivityDestroyed(activity: Activity) = Unit
    })
  }

  private val dialogWatcher = object : FragmentManager.FragmentLifecycleCallbacks() {
    override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
      if (f is DialogFragment) f.dialog?.window?.let(::watch)
    }
  }

  private fun watch(window: Window) {
    if (watched.containsKey(window)) return
    val decor = window.peekDecorView() ?: return
    val content = decor.findViewById<View>(android.R.id.content) ?: return
    watched[window] = Unit

    val guard = Guard(window, decor, content)
    decor.viewTreeObserver.addOnGlobalLayoutListener(guard)
    decor.viewTreeObserver.addOnGlobalFocusChangeListener { _, _ -> guard.schedule() }
    ViewCompat.setWindowInsetsAnimationCallback(
      content,
      object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
        override fun onProgress(insets: WindowInsetsCompat, running: MutableList<WindowInsetsAnimationCompat>) = insets
        override fun onEnd(animation: WindowInsetsAnimationCompat) = guard.schedule()
      }
    )
  }

  private class Guard(val window: Window, val decor: View, val content: View) : ViewTreeObserver.OnGlobalLayoutListener, Runnable {
    /** How far we have slid the content up, in px (0 = untouched). */
    private var applied = 0

    override fun onGlobalLayout() = schedule()

    fun schedule() {
      decor.removeCallbacks(this)
      if (imeBottom() == 0) reset() else decor.postDelayed(this, SETTLE_MS)
    }

    override fun run() {
      val ime = imeBottom()
      val focus = window.currentFocus
      if (ime == 0 || focus == null || !focus.isShown || focus.height > decor.height / 2) {
        reset()
        return
      }
      val loc = IntArray(2)
      focus.getLocationInWindow(loc)
      val bottomUnshifted = loc[1] + focus.height + applied
      val margin = (8 * decor.resources.displayMetrics.density).toInt()
      val target = max(0, bottomUnshifted + margin - (decor.height - ime))
      if (target != applied) {
        applied = target
        content.translationY = -target.toFloat()
      }
    }

    private fun reset() {
      if (applied != 0) {
        applied = 0
        content.translationY = 0f
      }
    }

    private fun imeBottom(): Int =
      ViewCompat.getRootWindowInsets(decor)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
  }
}
