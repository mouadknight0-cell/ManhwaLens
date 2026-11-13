# Changelog

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
