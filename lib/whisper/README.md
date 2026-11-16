# lib/whisper — Backchannel's offline speech engine

Backchannel's voice-message transcription runs whisper.cpp on the phone (`WhisperEngine`).
Built from source by Gradle (`externalNativeBuild`, CMake) for armeabi-v7a, arm64-v8a and x86_64;
no prebuilt binaries are committed.

## Vendored whisper.cpp

- Upstream: https://github.com/ggml-org/whisper.cpp
- Tag **v1.9.5**, commit `d1be6fde11ac6e0407606b4e42fe72d34add8037` (2026-10-06)
- License: MIT, `whisper.cpp/LICENSE` (ggml included under the same license)
- Copied unmodified, trimmed to what the Android library build needs: `CMakeLists.txt`, `cmake/`,
  `include/`, `src/` (without `coreml/`, `openvino/`, `vitisai/`), and `ggml/` with only the core
  sources and the CPU backend (`ggml/src/ggml-cpu`). Examples, tests, bindings, models and the
  GPU/accelerator backends were left out.

To update: check out the new tag, copy the same set of files over `whisper.cpp/`, update this note,
then build and run the transcription probe (`TranscribeProbeActivity`, debug builds) on an emulator.

## Build

- NDK: `libs.versions.ndk` (same as the app and the reproducible-build Dockerfile).
- CMake: `libs.versions.cmake` (3.31.6, from the SDK) locally. The reproducible-build Docker image is
  Alpine, where the SDK's glibc CMake doesn't run, so it uses Alpine's CMake and ninja and sets
  `backchannel.cmakeVersion` to that version (4.1.3 as of 2026-11-16). Two Docker builds produce
  byte-identical libraries.
- ggml loads its CPU backend at run time from the app's native library directory. On arm64 it
  builds one backend per SoC generation (`GGML_CPU_ALL_VARIANTS`: armv8.0 up to armv9.2) and
  picks the best one the phone supports; x86_64 gets an SSE4.2 baseline, armeabi-v7a NEON-vfpv4.

## Model

Not bundled. `SpeechModel` (app) downloads the multilingual `ggml-base-q5_1.bin` (59,707,625 bytes,
SHA-256 `422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898`) from Hugging Face
`ggerganov/whisper.cpp` at commit `5359861c739e955e79d9a303bcbc70fb988958b1`, only once the user
turns transcription on or taps Transcribe.
