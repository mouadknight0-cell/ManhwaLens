# Changelog

## 0.13.0 — 2026-11-19 (experimental)

### End-to-end translation latency (the #1 priority)
- Added mandatory T0→T7 instrumentation: scan initiated → capture → preprocessing → OCR → grouping → translation → first Arabic sentence → all dialogue displayed. First-sentence and full-translation latency (median/P95/worst), per-stage medians, and dialogue-volume counters are recorded in a bounded ring and appended to the pipeline report (key-free, no page text). See `PERFORMANCE.md`.
- Capture fast path: the screen mirror now renders at the working size (≤720 px, aspect preserved) instead of display resolution, and RGBA→ARGB conversion uses a branch-light swizzle — no full-res copy, alloc, or downscale per capture.
- Removed a duplicated tap-path filter pass (BlockFilter + garbage classifier ran twice with identical parameters); the single pass now lives in `CaptureOcrProcessor` with the FILTER trace records.
- Translation core: `Glossary.prepare` is memoized (one regex pass per block instead of up to three), cache keys are computed once per block, the disk cache write moved off the pipeline thread, and an O(n²) id lookup became a single map.
- Overlay: auto-fit text sizing uses binary search (~6 vs ~58 layout measures per patch, provably identical font), and the progressive painter coalesces sibling raster builds (single-flight per area, no discarded patches).
- Added a fixed-dataset orchestration benchmark (`OrchestrationBenchmarkTest`, 20-bubble sample page) that runs in CI and prints comparable timings.

### Verification
- GitHub Actions must pass the full JVM unit-test suite (including the new latency, swizzle, binary-search, memo, async-cache, and orchestration tests) and assemble the debug APK.
- No device-measured latency numbers are claimed yet — real T0→T7 figures require a physical device (issue #8). All v0.13.0 changes are output-identical by construction and covered by equivalence tests.

## 0.12.2 — 2026-11-15 (experimental)

### PaddleOCR on-device comparison build
- Include PP-OCRv5 mobile detection and Arabic/English recognition ONNX models in the APK; model assets are checksum-pinned and packaged by CI, with no runtime model download.
- Keep ML Kit as the default OCR engine and allow selecting PaddleOCR from Settings → OCR engine.
- Keep OCR comparison isolated from translation and Live scheduling so both OCR engines can be measured against identical captures.
- Restrict the phone-focused experimental APK to ARM64 and ARMv7 native libraries, excluding x86 emulator libraries to reduce download size.

### Verification
- GitHub Actions must pass unit tests, model download/checksum validation, and debug APK assembly before this build is considered ready.
- Real-device comparison of line accuracy, bubble grouping, latency, memory and RTL/mixed-script reading order remains required.

## 0.11.5 — 2026-11-14 (experimental)

### P5-A — composite overlay patches
- Compose all available translations belonging to one planned patch area into a single opaque bitmap. Progressive sibling results now rebuild the same stable patch ID instead of drawing overlapping opaque bitmaps that erase earlier Arabic.
- Added per-area generation checks so a late worker result cannot replace newer text; final reconciliation retries only areas whose latest patch build failed or was superseded.
- Keep the complete planned cover even when only some member translations are usable, while rendering only the available Arabic text.
- Added JVM tests for composite text order, one-patch-per-area behavior, and retaining the full cover when a sibling translation is missing.

### P4-C — ManhuaPlus OCR watermark
- Filter the diacritic-corrupted ManhuaPlus watermark using Unicode normalization before matching known site marks; dialogue near the mark remains eligible for OCR translation.


## 0.11.2 — 2026-11-14 (experimental)

### P4-A — opt-in OCR and translation diagnostics
- Added a bounded, local-only debug report for the tap-capture path: raw OCR lines with confidence and frame coordinates; grouped and retained bubbles; exact prepared ML Kit request text; unmodified ML Kit responses and per-call latency; and Arabic text whose overlay patch bitmap was successfully built.
- Detailed page text is collected only while **Settings → Diagnostics → Debug mode** is enabled. Generic pipeline traces no longer include OCR/translation previews or page-specific character-name spellings.
- Added JVM coverage for diagnostic contents and report-size bounds. No translation strategy, quality threshold, or engine-selection behavior was changed.

## 0.11.0 — 2026-11-13

Fixes taken from four reader screenshots: English left visible around the Arabic, smeared
overlapping Arabic at patch edges, mangled Korean/Chinese names, and meaningless blocks
(watermarks, ellipses, chapter headers) getting translated.

### Translation speed
- `PagePlan` runs before the engine in the tap and Live paths: meaningless blocks are dropped
  before they cost a request, identical strings are sent once, and the answers are fanned
  back out to every bubble that shares them.
- Live mode asks the engine only about text it has not already painted (`PagePlan.pendingEngineBlocks`).
- Cloud requests stay chunked and parallel (≤3 in flight), keep `thinkingBudget = 0` on the
  fast path, and get one missing-block second pass instead of a full retry.
- Memo cache: exact (per engine + style) plus a small fuzzy tier for strings ≤40 chars, so
  scene-to-scene repeats such as a catchphrase cost nothing.

### Translation quality and names
- `NameMemory` + `KoreanRomanizer`/`JapaneseRomanizer`/`NameScript`: Korean syllables are
  romanized (`지우` → `جي وو`) and one spelling per person is pinned for the page, so
  `Ji-woo` / `Ji Woo` / `Jiwoo` can no longer become three different Arabic names.
- A per-page scene name sheet is sent with the request, and the reply is repaired against it:
  a known name keeps its pinned spelling; titles (`Young Master`, `-ssi`) are folded onto it.
- `ArabicValidator` now rejects partial translations (Arabic + a long surviving English
  sentence), echoes, control/replacement characters, and trims translator labels and
  zero-width marks. A rejected bubble comes back EMPTY — never the English text.
- One bad bubble no longer costs the page: readable bubbles are kept, and only a reply with
  nothing usable at all reports `PARSE_ERROR` so the caller keeps the ML Kit draft.

### Meaningless output
- `MeaninglessText` drops ellipsis-only bubbles, punctuation/SFX bursts (`KRAK!`, `THUD THUD`),
  watermarks/credits (`www.`, Tapas, Discord, `Translator:`, `© 2024`), file-app chrome,
  chapter/episode/page headers, and already-Arabic text. Real short dialogue (`OK`, `NO`,
  `HELLO`) and dialogue that merely mentions a chapter survive.
- `BlockFilter` delegates to it and no longer treats `"chapter"` as a substring watermark.

### Rendering (English shown through / smeared edges)
- `PatchCoverage` + `PatchPlanner` plan ONE opaque cover per visual area from the whole page:
  every OCR box of the bubble is unioned and dilated 3 px, so no glyph tail survives outside
  the patch, and a bubble OCR split in two is erased by a single owner instead of two
  overlapping patches whose edges smeared each other's Arabic.
- Patches are painted FULLY OPAQUE. The old feathered edges faded to transparency, which let
  the original English show through the blend ring.
- The live path uses the same planner for its batch render, and the trace reports
  `patch fold: N boxes → M patch area(s)`.

### Verification
- JVM harness: 509 unit tests pass (was 471). New suites: `MeaninglessTextTest`,
  `NameMemoryTest`, `TranslationMemoTest`, `ArabicQualityGuardTest`, `PatchCoverageTest`,
  `PatchPlannerTest`, `PagePlanWiringTest`.
- `versionCode = 30`, `versionName = 0.11.0`.
- No Android SDK/Gradle in the authoring sandbox: the APK comes from GitHub Actions
  (`:app:testDebugUnitTest` + `:app:assembleDebug`). Device checks are the outstanding step.

## 0.10.0 — 2026-11-13

- Restored the Android project source from the Dropbox v0.9.14 archive into the GitHub working tree (without the archive's embedded `.git`, APK, or signing material).
- Added an opt-in LibreTranslate/Argos engine for user-hosted HTTPS servers. A page is sent as one batch; the on-device ML Kit draft continues to paint progressively and remains the fallback.
- Added an Arabic output guard for cloud/self-hosted replies to reject empty, malformed, and ordinary English-echo translations before they are cached or replace a local draft; older remote memory/disk cache entries are rechecked and purged if invalid.
- Added endpoint validation, API-key redaction, endpoint-specific cache namespaces, and a bounded response size.
- Declared Android's `INTERNET` permission, which the existing Gemini HTTP engine also requires.
- Updated English/Arabic settings labels, privacy disclosures, build version, and the manual test checklist.
- Added `OPEN_SOURCE_RESEARCH.md` to record GitHub project/licensing decisions and the next OCR benchmark step.

### Verification status

Unit tests were added for request batching, response parsing, endpoint validation, output validation, and fallbacks. This sandbox has no Java/JDK or Android SDK installed, so Gradle tests and APK builds could not be run here. Physical-device timing and real-server tests remain outstanding.
