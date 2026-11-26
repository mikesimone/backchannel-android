/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.mutiselect.forward

import org.signal.core.models.media.Media

sealed interface MultiselectForwardBottomBarEvent {
  data class AddMessageUpdate(val message: String) : MultiselectForwardBottomBarEvent
  data object SendClick : MultiselectForwardBottomBarEvent

  // BACKCHANNEL: leave one photo or video out of the forward
  data class RemoveMedia(val media: Media) : MultiselectForwardBottomBarEvent
}
