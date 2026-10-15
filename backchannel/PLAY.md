# Google Play: setup and Console answers

The listing lives in `fastlane/metadata/android/en-US/` (title, short and
full description, icon, feature graphic, changelog). The `Play listing`
workflow pushes it when those files change; the `Release` workflow's
`Google Play` job uploads each release's AAB to the internal testing track.
Both run only once the `PLAY_SERVICE_ACCOUNT_JSON` secret is set.

## One-time steps in Play Console (Mike)

1. **Create app**: name Backchannel, English (US), App, Free; accept the
   declarations. **Turn off "automatic protection"** (the installer check):
   it would tell GitHub/Obtainium/F-Droid users to reinstall from Play,
   which loses their messages, and it changes the app's code.
2. **Users and permissions**: the Play API service account Chatshot uses
   (`claude-app-dev@cloudflare-oauth-505105...`): Manage > App permissions,
   add Backchannel with access to Backchannel: releases and store listing.
3. **Secret**, on Six:
   `gh secret set PLAY_SERVICE_ACCOUNT_JSON -R mikesimone/backchannel-android < ~/.chatshot-toolchain/play-credentials.json`
4. **First AAB by hand**: Play accepts API uploads only after one upload in
   the Console. Test and release > Internal testing > Create release, and
   upload the `.aab` from the latest GitHub release.
5. **App signing**: at that first upload choose **use my own key** (export
   and upload with PEPK), never a Google-generated key. The key is the one
   in `~/.config/backchannel-android` on Six (SHA-256
   `19:6E:50:45:...:1F:44:62`), so GitHub, F-Droid and Play builds can
   update each other.

## App content answers

- **Privacy policy URL**: https://github.com/mikesimone/backchannel-android#privacy
- **Ads**: No ads.
- **App access**: All functionality needs an account. Reviewer note: "Sign up
  with any phone number that can receive an SMS or call, or link to an
  existing Signal account from Settings > Linked devices in Signal."
- **Target audience**: 18 and over (Signal's own terms require 13+, but an
  adult-only target avoids the Families policy).
- **News app**: No. **Government app**: No. **Financial features**: None.
  **Health**: None.
- **Content rating** (IARC questionnaire): category *Communication / social*.
  Violence, sexuality, language, controlled substances, gambling: No.
  Users can interact or exchange content: **Yes**. Shares user location
  with other users: **Yes** (users can send their location in a chat).
  Digital purchases: No. Unrestricted internet: No.
- **Data safety**:
  - Collects data: **No.** Backchannel's developer receives nothing. Messages,
    contacts and profile data go only to the Signal service, end-to-end
    encrypted, which Play treats as not "collected" when the developer can't
    read it.
  - Shares data: **No.**
  - Encrypted in transit: **Yes.**
  - Deletion: users can delete their account from Settings > Account.
- **Permissions declarations**:
  - Foreground service type `phoneCall`: "Keeps voice and video calls
    running while the app is in the background."
  - Full-screen intent: "Shows incoming calls on the lock screen, like a
    phone call."
  - Contacts (`READ_CONTACTS`/`WRITE_CONTACTS`): "Optional. Finds which
    contacts use Signal; numbers are hashed before they leave the phone."
  - Other foreground service types in the manifest, each with its own
    declaration: `camera` and `microphone` (video and voice calls),
    `mediaProjection` (screen sharing in calls), `mediaPlayback` (voice
    notes and video), `dataSync` (backups and attachment transfers),
    `remoteMessaging` (receiving messages without Google push).
  - Battery optimization exemption: "Without Google Play services, a kept-open
    connection is the only way messages arrive."
  - `USE_EXACT_ALARM` is policy-restricted to alarm and calendar apps. If
    Play rejects it, drop it from the store build (keep
    `SCHEDULE_EXACT_ALARM`) rather than arguing the declaration.
  - The store build doesn't request SMS or call-log permissions; if Play
    flags one, check the merged manifest before declaring anything.

## Still to do

- Phone screenshots (2 to 8, 9:16) in
  `fastlane/metadata/android/en-US/images/phoneScreenshots/`, made from a
  test account with no real names in view.
