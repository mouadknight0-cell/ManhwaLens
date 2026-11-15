# ManhwaLens

ManhwaLens is an Android floating overlay that reads English dialogue from a visible manhwa page and renders Arabic patches in place. Current experimental release: [v0.12.8](https://github.com/mouadknight0-cell/ManhwaLens/releases/tag/v0.12.8).

## v0.11.0 — what changed for the reader

- **No English behind the Arabic.** Every patch is painted fully opaque over the union of the
  bubble's OCR boxes (dilated 3 px), so glyph tails and shadows cannot peek out, and a
  rejected translation is left blank instead of echoing the English.
- **No smeared Arabic.** One bubble = one patch area, decided from the whole page, so two OCR
  lines of the same bubble can no longer paint over each other.
- **Names hold still.** Korean/Chinese names are romanized and pinned to one Arabic spelling
  per page; the name sheet travels with the request.
- **Meaningless text is dropped, not translated.** Ellipses, SFX, watermarks, chapter
  headers and our own UI never reach the engine.
- **Faster.** The plan drops duplicates and noise before the network call; Live only asks
  about text it has not painted yet.

**Download:** [ManhwaLens v0.12.8 APK](https://github.com/mouadknight0-cell/ManhwaLens/releases/download/v0.12.8/ManhwaLens-v0.12.8-debug.apk) · [All GitHub Releases](https://github.com/mouadknight0-cell/ManhwaLens/releases).

The APK is attached to a published GitHub Release; Actions artifacts are CI outputs, not the normal download path. CI retains a stable experimental debug-signing key and increases `versionCode` for each release. Actual install-over-existing-app behavior still requires physical-device verification.

## What this update adds

- **PaddleOCR on-device recognition:** PP-OCRv5 Arabic/English models are bundled in the APK. PaddleOCR is the sole OCR backend and runs locally without a runtime model download.
- **Offline translation:** ML Kit English→Arabic remains the default local engine. Optional Mozilla Bergamot EN→AR can also run fully on-device after its one-time model download; Offline mode blocks every cloud feature.
- **Fast first paint:** ML Kit runs locally first. The selected remote engine runs in parallel and can replace the draft only after the reply passes the Arabic output guard.
- **Quality guard:** blocks empty replies, malformed output, and ordinary English echoes before they can be cached or overwrite a readable draft. A single unfamiliar character name may remain unchanged.
- **Optional cloud translation:** Gemini remains available with the user's own API key; the local ML Kit result remains the offline path.
- Existing offline ML Kit, glossary, one-sentence bubble normalization, translation caches, Gemini, overlay, and Live-mode features are retained.

## Translation engines

1. **PaddleOCR** — sole on-device OCR engine for English/Arabic text detection and recognition.
2. **ML Kit Translation** — default on-device English→Arabic translation; offline after the translation model is downloaded.
3. **Bergamot (experimental)** — optional Mozilla Firefox Translations EN→AR engine; about 23.8 MB compressed / 35.6 MB installed, downloaded on demand, ARM64 + Android 9+ only.
4. **Gemini** — optional cloud translation with the user's own API key; disabled by Offline mode.

OCR and translation are separate stages: PaddleOCR extracts text; ML Kit or Bergamot translates locally, while Gemini is optional cloud translation. Bergamot is experimental and must be benchmarked on a real phone before being preferred over ML Kit.

## Build

Requirements: JDK 17, Android SDK 36, Android build tools, and network access for Gradle dependencies.

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Build/overlay behavior still needs validation on a physical Android device. See `MANUAL_TESTS.md`.

Project status, the engineering record (phases, commits, CI runs, open findings) and the roadmap live in
[`REPOSITORY_REPORT.md`](REPOSITORY_REPORT.md) and [`PLAN.md`](PLAN.md).

## Open-source review

See [`OPEN_SOURCE_RESEARCH.md`](OPEN_SOURCE_RESEARCH.md) for research and licensing notes. PaddleOCR model assets are bundled for offline OCR; ML Kit's translation model is downloaded once by Google Play services.

## Privacy

PaddleOCR and filtering run on-device. If you choose Gemini, recognized text goes to Google for translation. Optional AI cleanup can send cropped bubbles to Gemini. Offline mode blocks cloud requests. No analytics or telemetry are sent. Details: [`PRIVACY.md`](PRIVACY.md) and [`privacy-policy.html`](privacy-policy.html).
