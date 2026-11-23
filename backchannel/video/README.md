# Feature tour video (animated mockups)

`python3 build.py ../../fastlane/metadata/android/en-US/images/icon.png` writes video.html and chapters.txt;
`node render.js tour.mp4` renders it at 1080p30 (needs Playwright + ffmpeg). Scenes, captions and timings live in build.py.
