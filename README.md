# Backchannel for Android

_Signal for people who actually have friends._

Backchannel for Android is a Signal client for Android. It's a fork of
[Molly](https://github.com/mollyim/mollyim-android), which is itself a
hardened fork of [Signal for Android](https://github.com/signalapp/Signal-Android).
It connects to Signal's servers, so you chat with your Signal contacts as
usual, and it adds the extras from
[Backchannel](https://github.com/mikesimone/backchannel), the web client.

Backchannel is not made, endorsed or supported by Signal Messenger or by
the Molly project. "Signal" is Signal Messenger's trademark and is used
here only to say what this works with.

**Status: early.** The first builds are Molly with Backchannel's name and
icon. Backchannel's own features follow; the plan and feature list are in
[backchannel/android](https://github.com/mikesimone/backchannel/tree/main/android).

## Signing up

Either way works:

- **As your main Signal app:** register your phone number, or move over
  from Signal with a Signal backup.
- **As a linked device:** keep Signal on your phone and link Backchannel
  to it, like Signal Desktop.

## Notifications

Settings > Notifications picks how messages reach you, as in Molly:

- **Google push** (when Google Play is on the phone): Signal's server
  sends an empty wake-up through Google, and the app fetches the message
  from Signal itself. Best on battery.
- **Kept-open connection:** no Google; the app holds its own connection
  to Signal. Uses more battery; allow it to run unrestricted.
- **UnifiedPush**, through your own MollySocket server.

## Install and update

Releases are on this repo's
[Releases](https://github.com/mikesimone/backchannel-android/releases)
page. To get updates automatically, add this repo to
[Obtainium](https://github.com/ImranR98/Obtainium). There's no in-app
updater.

## How this fork is kept

- `main` is Molly plus Backchannel's changes. Every change to Molly or
  Signal code is marked `// BACKCHANNEL:`; new code lives under
  `backchannel/` and `app/src/prod/`.
- Molly's strings say "Molly"; `backchannel/rebrand_strings.py` copies
  those strings, renamed, into `app/src/prod/res`, so Molly's own string
  files stay untouched.
- The **Sync Molly** workflow merges each new Molly release daily, tags
  it `<molly tag>-bc1`, and builds a signed release. A conflict opens an
  issue instead.
- Version codes are Molly's times ten plus `backchannelRevision` from
  `app/gradle.properties`, so a Backchannel-only release on the same Molly
  version still upgrades.

To verify an APK, its signing certificate's SHA-256 fingerprint is:

```
19:6E:50:45:C7:6E:0D:E3:5D:6A:2C:B3:84:A4:67:02:2E:C5:36:07:9C:AE:AE:CF:4F:DA:F3:7F:B8:1F:44:62
```

## Building

Same as Molly: see [BUILDING.md](BUILDING.md) and
[reproducible-builds](reproducible-builds). Releases are signed with
Backchannel's own key, held only in this repo's Actions secrets
(`SECRET_KEYSTORE`, `SECRET_KEYSTORE_ALIAS`, `SECRET_KEYSTORE_PASSWORD`).

## License

GNU Affero General Public License, version 3 only
([`AGPL-3.0-only`](LICENSE)), as Molly and Signal. See [LEGAL.md](LEGAL.md).
