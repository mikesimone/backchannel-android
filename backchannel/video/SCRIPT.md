# Backchannel feature video: script and scene list

YouTube, 1080p landscape, about 3-4 minutes. Phone screen (real app on the Six
emulator, see "Recording") centered on a branded background, a title card per
section, narration over each scene. Chapters go in the YouTube description.

**Rule (Mike 2026-11-23):** the video is added to the Play listing by Mike, and
only AFTER the production release is approved, so the listing change can't restart
Google's review. Nothing in this folder touches Play.

## Cast
- **Demo phone:** the emulator, registered to the reviewer test number only after
  production approval (registering it earlier would log out Google's reviewer).
  Keep the number's existing PIN (vault `REVIEW_SIGNAL_PIN`) so later reviewers still get in.
- **"Alex":** the Ops Bot Signal account, driven through signal-cli-api to send
  messages, photos, voice notes and group changes. On the demo phone it is renamed
  "Alex" (nickname) with a made-up contact photo. Ops Bot's registration and profile
  are never changed; its listener ignores everyone but Mike. No real people appear.

## Scenes (narration in quotes)

0. **Intro** (title card) - "This is Backchannel: Signal, with the features you keep wishing it had. Same Signal network, same encryption, same friends."
1. **Conversations**
   - Reply privately to a group message - "Long-press a group message and reply privately: it opens a one-on-one chat with their message quoted."
   - Reply to one photo of an album - "Reply to just one photo of a multi-photo message, and attach photos, a GIF or a sticker to your reply."
   - Forward to many chats with @mentions; one poll to many chats.
   - Remind me; Note to Self stays unread as a reminder.
   - Send silently - "Long-press Send to send silently: people using Backchannel get no sound or buzz."
   - Auto reply - "Turn on an away message for one-on-one chats, optionally only during set hours."
   - Double-tap to react; starred messages; offline translate.
2. **Writing** - Markdown (lists, headings, quotes, code), bold italic, :) to emoji, default skin tone.
3. **Photos** - blur until tapped (per chat), save to gallery (per chat: Default / Always / Never), full-size sticker preview.
4. **Immich** - attach from your own server, album search and sort, home address used automatically.
5. **Contacts and privacy** - hide or delete contacts, link anyone to a phone contact, your own photo for anyone, lock a chat behind your fingerprint.
6. **Groups and notifications** - hide group updates from regular members; keyword alerts in muted chats.
7. **Backchannel 2** - a second copy for a second number.
8. **Transcription** - ONLY if Mike has confirmed it works on his phone; otherwise cut.
9. **Outro** - "Backchannel is free and open source. Get it on Google Play or GitHub."

## Recording
- AVD `bc-transcribe-35` on Six (see Environment/Infrastructure/sixofone.md), release
  APK of the current production build, `adb shell screenrecord` per scene (3 min cap
  per file), driven by adb input / uiautomator so takes are repeatable.
- Assembly: ffmpeg; title cards rendered from HTML with headless Chromium; narration
  from Mike's approved Chatterbox voice pack v4.1 (~/Chatterbox/out) unless Mike picks
  captions + music instead.
