package org.thoughtcrime.securesms.components

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.LayoutRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import org.thoughtcrime.securesms.R

/**
 * Fullscreen Dialog Fragment which will dismiss itself when the keyboard is closed
 */
abstract class KeyboardEntryDialogFragment(@LayoutRes contentLayoutId: Int) :
  DialogFragment(contentLayoutId),
  KeyboardAwareLinearLayout.OnKeyboardShownListener,
  KeyboardAwareLinearLayout.OnKeyboardHiddenListener {

  private var hasShown = false

  protected open val withDim: Boolean = false

  protected open val themeResId: Int = R.style.Theme_Signal_RoundedBottomSheet

  override fun onCreate(savedInstanceState: Bundle?) {
    setStyle(STYLE_NORMAL, themeResId)
    super.onCreate(savedInstanceState)
  }

  @Suppress("DEPRECATION")
  override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
    val dialog = super.onCreateDialog(savedInstanceState)

    if (!withDim) {
      dialog.window?.setDimAmount(0f)
    }

    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)

    return dialog
  }

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
    hasShown = false

    val view = super.onCreateView(inflater, container, savedInstanceState)
    if (view != null) keepAboveKeyboard(view)
    return if (view is KeyboardAwareLinearLayout) {
      view.addOnKeyboardShownListener(this)
      view.addOnKeyboardHiddenListener(this)
      view
    } else {
      throw IllegalStateException("Expected parent of view hierarchy to be keyboard aware.")
    }
  }

  /**
   * BACKCHANNEL: on targetSdk 36 every window is edge-to-edge: the theme's
   * windowOptOutEdgeToEdgeEnforcement and adjustResize are ignored, and so was
   * setDecorFitsSystemWindows(true) (the bc4 attempt). The caption box under a
   * photo or video sat behind the keyboard. Pad the content by the system bars
   * and by the keyboard, whichever is taller at the bottom.
   */
  private fun keepAboveKeyboard(view: View) {
    ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
      val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
      v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = maxOf(bars.bottom, ime.bottom))
      WindowInsetsCompat.CONSUMED
    }
    ViewCompat.requestApplyInsets(view)
  }

  override fun onPause() {
    super.onPause()
    hasShown = false
  }

  override fun onKeyboardShown() {
    hasShown = true
  }

  override fun onKeyboardHidden() {
    if (hasShown) {
      dismissAllowingStateLoss()
    }
  }
}
