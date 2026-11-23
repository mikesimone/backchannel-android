# Backchannel for Android: rules for agents

Read `README.md` and `backchannel/PLAY.md` first.

## The store listing changes ONLY with a production release (Mike, 2026-11-23)

"QUIT EDITING THE STORE DESCRIPTION BEFORE THE VERSION GETS PUSHED TO PROD."

Any change to the Play store listing (`fastlane/metadata/android/*/` text, images, `video.txt`)
that reaches Google while a release is in review joins that review and can restart the wait.
So:

- Editing the files in a commit is fine; **uploading them is not**, except together with a
  production release. The `Play promote` workflow uploads the listing right after it promotes
  a release to production. That is the only normal path.
- The `Play listing` workflow has no push trigger and must never get one back. It is for a
  manual upload after the app is live and nothing is in review.
- `backchannel/play_listing.py` refuses to upload while the app's public store page doesn't
  exist (before the first public release).
- Never run `play_listing.py`, `fastlane supply` with metadata, or any Play API edit that touches
  listings outside the promote step. If in doubt, ask Mike first.

Other standing rules (author identity, no AI attribution, commit and push) come from
`~/Environment/AI/GLOBAL-RULES.md`.
