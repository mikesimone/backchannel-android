# Google Play: setup and Console answers

The listing lives in `fastlane/metadata/android/en-US/` (title, short and
full description, icon, feature graphic, changelog). The `Play listing`
workflow pushes it when those files change; the `Release` workflow's
`Google Play` job uploads each release's AAB to the closed testing track (`alpha`; Mike skipped internal testing).
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
4. **Closed testing** (Mike 2026-10-15: no internal testing): releases go
   to track `alpha`. Add testers (email list or Google Group)
   under Test and release > Testing > Closed testing.

## Hands-off releases (Mike 2026-10-15: "managing all aspects of it")

- Every release tag uploads to `alpha` as **completed**, so the commit sends it
  for review with nothing to click in the Console. Until Google approves the
  app once, Play takes only drafts; the job then falls back to a draft (a
  warning in the run) and Mike sends that first one for review by hand.
- Release notes: `fastlane/metadata/android/en-US/changelogs/default.txt`.
  Update it in the commit that ships a feature; if it didn't change since the
  previous tag (daily Molly merges), the job uses a generic "latest Signal and
  Molly fixes" note.
- Production is never automatic: run the `Play promote` workflow
  (alpha to production, optional staged rollout) only when Mike says go.
- Override per repo with Actions variables `PLAY_TRACK`, `PLAY_RELEASE_STATUS`.
5. **App signing**: done 2026-10-15 via PEPK; choose **use my own key**
   (export and upload with PEPK), never a Google-generated key. The key is the one
   in `~/.config/backchannel-android` on Six (SHA-256
   `19:6E:50:45:...:1F:44:62`), so GitHub, F-Droid and Play builds can
   update each other.

## App content answers

- **Privacy policy URL**: https://github.com/mikesimone/backchannel-android#privacy
- **Ads**: No ads.
- **App access**: All or some functionality is restricted. Reviewers can't
  create accounts or contact us, so they get a ready reviewer account:
  Name `Reviewer account`; Username = the reviewer number (+1...); Password =
  its Signal PIN; Other info = how to read the SMS code from the code page
  (URL + password). Box "sign in details give full access": checked (no paid
  content). Reviewer number, code page and demo chats: to set up (needs a
  Twilio or other spare SMS number from Mike).
  **Never a number Signal already uses anywhere** (not Ops Bot's
  +1 440-252-2983, not the forwarder's, not Mike's): each review registers
  the number on the reviewer's phone, which de-registers it everywhere
  else, so Ops Bot would go dead on Signal across the fleet.
- **Target audience**: 18 and over (Signal's own terms require 13+, but an
  adult-only target avoids the Families policy).
- **News app**: No. **Government app**: No. **Financial features**: None.
  **Health**: None.
- **Content rating** (IARC questionnaire): category *Communication / social*.
  Violence, sexuality, language, controlled substances, gambling: No.
  Users can interact or exchange content: **Yes**. Shares user location
  with other users: **Yes** (users can send their location in a chat).
  Digital purchases: No. Unrestricted internet: No.
- **Data safety** (corrected 2026-10-15): collects data **Yes** (the phone
  number goes to Signal's servers, a third party, to create the account).
  Encrypted in transit: Yes. Account creation: Username and other
  authentication (phone number + SMS code). Declare only Personal info >
  Phone number: collected, not shared, not ephemeral, required, for
  Account management. Deletion: in the app, Settings > Account > Delete
  account; web link README#deleting-your-account. Messages and contacts are end-to-end encrypted or hashed and not
  readable by us, so they aren't declared.
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
