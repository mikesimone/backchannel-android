# Backchannel message markers (shared with Backchannel Web)

Invisible trailing characters that Backchannel apps (Android and Web) recognize.
Other Signal apps render nothing for them. Receivers strip the marker before display.

| Meaning       | Body ends with   | Receiver does                                   |
|---------------|------------------|-------------------------------------------------|
| Send silently | U+2063 U+2064    | strip it; show the message, no sound/vibration/notification |
| Auto-reply    | U+2063 U+2062    | strip it; never auto-reply to an auto-reply     |

Double auto-reply guard (Mike can enable auto-reply on Android and Web at once):
before sending, skip if this account already sent a message ending in the
auto-reply marker in that chat within the cooldown, including sync transcripts
from linked devices. First sender wins.

Agreed 2026-11-11 between the Android and Web threads (v14 forum features).
