package org.thoughtcrime.securesms.conversation;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

import org.thoughtcrime.securesms.R;

public enum AttachmentKeyboardButton {

  GALLERY(R.string.AttachmentKeyboard_gallery, R.drawable.symbol_album_tilt_24),
  // BACKCHANNEL: pick photos/videos through another app (Google Photos, gallery apps)
  PHOTO_APPS(R.string.AttachmentKeyboard_photo_apps, R.drawable.symbol_open_24),
  // BACKCHANNEL: pick photos/videos straight from the user's Immich server
  IMMICH(R.string.AttachmentKeyboard_immich, R.drawable.symbol_album_24),
  // BACKCHANNEL: the Immich app as the picker, shown only when it can pick (ImmichAppPicker)
  IMMICH_APP(R.string.AttachmentKeyboard_immich_app, R.drawable.symbol_open_24),
  FILE(R.string.AttachmentKeyboard_file, R.drawable.symbol_file_24),
  CONTACT(R.string.AttachmentKeyboard_contact, org.signal.core.ui.R.drawable.symbol_person_circle_24),
  LOCATION(R.string.AttachmentKeyboard_location, R.drawable.symbol_location_circle_24),
  POLL(R.string.AttachmentKeyboard_poll, R.drawable.symbol_poll_24);

  private final int titleRes;
  private final int iconRes;

  AttachmentKeyboardButton(@StringRes int titleRes, @DrawableRes int iconRes) {
    this.titleRes = titleRes;
    this.iconRes = iconRes;
  }

  public @StringRes int getTitleRes() {
    return titleRes;
  }

  public @DrawableRes int getIconRes() {
    return iconRes;
  }
}
