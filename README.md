# ManhwaLens

ManhwaLens is an Android floating overlay that reads English dialogue from a visible manhwa page and renders Arabic patches in place. The v0.11.0 source baseline was restored from the Dropbox archive `ManhwaLens-v0.9.14-source.zip`; the debug APK and signing keystore are intentionally not included in Git.

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

Debug APK: GitHub Actions artifact `ManhwaLens-debug-apk` (run on the branch, workflow
*Android build*). No Android SDK/Gradle is available in the authoring sandbox, so the APK is
built in CI only.

## What this update adds

- **Self-hosted open-source translation option:** configure a trusted HTTPS LibreTranslate server using an installed Argos Translate English→Arabic package. The app sends one page-wide batch of OCR text and an optional server key; it does not bundle or operate a server/model.
- **Fast first paint:** ML Kit runs locally first. The selected remote engine runs in parallel and can replace the draft only after the reply passes the Arabic output guard.
- **Quality guard:** blocks empty replies, malformed output, and ordinary English echoes before they can be cached or overwrite a readable draft. A single unfamiliar character name may remain unchanged.
- **Network and privacy fixes:** the Android `INTERNET` permission is declared for optional network engines; LibreTranslate URLs must use HTTPS and cannot contain credentials or query parameters.
- Existing offline ML Kit, glossary, one-sentence bubble normalization, translation caches, Gemini, overlay, and Live-mode features are retained.

## Translation engines

1. **ML Kit** — on-device OCR/translation path; works offline after model download.
2. **Gemini** — optional cloud translation with the user's own API key; the app paints an ML Kit draft first.
3. **LibreTranslate / Argos** — optional self-hosted alternative. Enter the base HTTPS URL or the full `/translate` endpoint under Settings → Translation. Only recognized text is sent; screenshots are not sent for text translation. Output quality and speed depend on the server and installed Argos model.

The default remains ML Kit. Network engines are opt-in, and the user controls the remote endpoint and keys.

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

See [`OPEN_SOURCE_RESEARCH.md`](OPEN_SOURCE_RESEARCH.md) for the GitHub projects reviewed, licensing/suitability notes, and why PaddleOCR and manga-image-translator are not bundled in this Android release. The app implements a small LibreTranslate REST client only; it does not copy server source or model weights.

## Privacy

OCR and filtering run on-device. If you choose Gemini, recognized text goes to Google; if you choose LibreTranslate, recognized text goes to the HTTPS endpoint you configured. Optional AI cleanup can send cropped bubbles to Gemini. No analytics or telemetry are sent. Details: [`PRIVACY.md`](PRIVACY.md) and [`privacy-policy.html`](privacy-policy.html).
