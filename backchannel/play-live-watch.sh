#!/usr/bin/env bash
# Waits for Backchannel to go public on Google Play, then (and only then) turns the
# "Play listing" workflow back on and runs it, which adds the promo video
# (fastlane/metadata/android/en-US/video.txt) to the store listing.
# Mike 2026-11-23: no listing change may reach Google before the production release
# is live, or the review wait starts over. The workflow was disabled until then.
# Run by play-live-watch.timer on Six; it disables itself after the one-time job.
set -euo pipefail
URL="https://play.google.com/store/apps/details?id=net.mikesimone.backchannel&hl=en"
code=$(curl -s -o /dev/null -w '%{http_code}' -m 30 "$URL" || echo 000)
[[ "$code" == "200" ]] || { echo "not live yet ($code)"; exit 0; }

cd /home/msimone/backchannel-android
gh workflow enable play-listing.yml
gh workflow run play-listing.yml --ref main
/home/msimone/noc-in-a-box/slack-notify.sh six "🎉 Backchannel is live on Google Play: $URL
I've turned the Play listing workflow back on and sent the feature-tour video (https://youtu.be/3hVrkXUxZZU) to the store listing. Listing changes go to Google review on their own." || true
sudo -n systemctl disable --now play-live-watch.timer || true
echo "live: listing workflow enabled and run"
