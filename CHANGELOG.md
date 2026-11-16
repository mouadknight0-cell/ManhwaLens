## 0.12.11 — cache integrity and PaddleOCR capture telemetry
- Sanitize legacy memory, persistent-disk and memo cache hits through the same deterministic output cleaner used for fresh engine results before character-name repair, source-exact corrections and the Arabic quality guard.
- Persist corrected source-exact cache results back to the persistent cache so old mistranslations and unclean labels do not reappear after an app restart.
- Treat blank/label-only legacy cache values as invalid misses; remove the stale entry and allow a fresh nonblank translation to replace it. A blank cache value cannot be turned into text by a character-name override.
- Keep the original cached candidate separate from final visible Arabic, and report the actual source tier for cache hits (in-memory exact, persistent exact, exact memo or fuzzy memo) without changing existing trace status codes.
- Add regression coverage for corrected disk-cache values surviving process recreation, legacy cached output sanitation, blank-entry recovery and trace provenance. The user-visible translation-quality diagnostic continues to show raw, cleaned and final-visible results separately.
- Include opt-in PaddleOCR capture telemetry: detector/recognizer stage timings, cold model-load time, tensor shapes, candidate/low-confidence counts, effective configuration, and per-line OCR confidence plus bounding boxes alongside grouped and kept blocks.
- Keep ML Kit as the default offline translator; Bergamot remains optional and experimental. OCR thresholds and bubble-grouping geometry are unchanged pending labeled screenshot evidence.

## 0.12.10 — offline model reliability and Arabic dialogue corrections
- Allow the ML Kit English→Arabic model to download over Wi-Fi or mobile data during both first-use translation and service warm-up; remove the hidden Wi-Fi-only restriction.
- Add conservative source-exact Arabic corrections for observed short dialogue/SFX failures: “No.” → “لا.”, “Run!” → “اهرب!”, and “KRAK” → “طَق!”.
- Apply source-aware corrections consistently after character-name repair across fresh engine results, cache hits and progressive overlay output.
- Persist the final corrected cache value so older wrong short-line translations are not repeatedly served.
- Keep ML Kit as the default offline translator; Bergamot remains optional and experimental.

## 0.12.9 — Arabic rendering and native OCR memory lifecycle
- Preserve complete Arabic RTL bubble text: force RTL paragraph layout, remove the four-line rendering cap, and retain the original artwork rather than drawing a truncated translation when the complete text cannot fit.
- Serialize PaddleOCR inference and ONNX session teardown; release shared OCR sessions safely under memory pressure and service shutdown.
- Establish the stable future APK signing baseline with the pinned CI key and enforce package, versionCode and signer checks before publishing updates.
- Publish the v0.12.9 APK as a GitHub prerelease. The one-time historical signing transition from v0.12.8 is documented; later releases must retain the v0.12.9 signer.

## 0.12.8 — isolate and clarify offline engine diagnostics
- Rename the original diagnostic to **ML Kit batching benchmark** and state explicitly that it runs ML Kit regardless of the selected offline engine.
- Make its side effect explicit: it may update ML Kit's saved batching policy, but never changes the selected offline translator or Bergamot settings.
- Add a **Bergamot-only benchmark** using the same fixed 20-case EN→AR corpus. It invokes the native Bergamot adapter only, without ML Kit calls, fallback, app translation cache or preference changes; reports first-pass startup separately from warm passes and includes per-case quality results.
- Correct the warm-up reason labels so an ML Kit batching benchmark cannot be mistaken for an engine comparison.
- Make the paired comparison use the saved ML Kit page dispatch strategy (validated joined request when safe, otherwise the saved parallel level), with targeted retry of only missing ML Kit bubbles.
- Report actual ML Kit dispatch, raw-engine/no-fallback semantics, per-case output, and the distinction between page-batch latency and isolated sentence latency.
- Add JVM coverage for Bergamot-only isolation, joined/parallel dispatch, map validation, concurrency limits and selective retry. Keep ML Kit as the default and Bergamot experimental.

## 0.12.7 — isolate engine comparison timing
- Prevent the ML Kit background separator self-test from running concurrently with the explicit ML Kit vs Bergamot diagnostic, which shares the process-wide translator and would distort measured latency.
- Add a benchmark-only ML Kit warm-up that verifies/downloads the model and loads the translator without scheduling a new batching self-test.
- Hold the same process-wide diagnostic lock across ML Kit benchmark warm-up and all measured passes, preventing pending or running batching self-tests from contaminating timing.
- Keep the comparator cache-free and read-only with respect to the selected translation engine and batching preferences.
- ML Kit remains the default; Bergamot remains optional and experimental.

## 0.12.6 — offline translation quality benchmark
- Extend Settings → Diagnostics with a fixed 20-case English→Arabic reference corpus spanning dialogue, narration, short replies, names and sound effects.
- Report Arabic quality-guard acceptance/rejection, exact normalized reference matches, elapsed time and mean Arabic token-overlap F1; explicitly label token overlap as a lexical diagnostic, not semantic accuracy.
- Keep quality evaluation separate from runtime batching policy: reference scores never change engine selection or translation behavior.
- Add JVM tests for corpus integrity, exact matches, English echoes, empty/failing output and lexical scoring.
- ML Kit remains the default engine; Bergamot remains experimental and its offline fallback is unchanged.

# Changelog

## 0.12.5 — offline quality fallback for Bergamot
- Keep Bergamot as the selected primary engine, but route only bubbles rejected by the shared Arabic quality guard to the already-installed on-device ML Kit translator.
- If Bergamot fails for the whole page, retry the page with offline ML Kit; never invoke a cloud engine for this recovery path.
- Preserve accepted Bergamot bubbles and their order; merge fallback translations by stable bubble ID.
- Record fallback request count, number of recovered bubbles and fallback latency in Pipeline Trace.
- Add JVM tests for selective fallback, no-fallback success, whole-page engine failure and fallback failure.
- ML Kit remains the default engine; Bergamot remains experimental.

## 0.12.4 — Bergamot performance and lifecycle follow-up
- Add adaptive sequential native batching: ordinary pages remain one JNI batch; unusually large pages are split by bubble count and total characters to bound temporary buffers.
- Add timings for text preparation, each JNI batch, total translation time, duplicate reduction and native batch sizes in Pipeline Trace.
- Add a per-page rule-based acceptance summary (accepted/rejected/empty/missing); explicitly label it as a non-semantic score.
- Extract OCR normalization and adaptive batching into pure Kotlin helpers with JVM tests for wrapped lines, hyphenation, zero-width artifacts, ordering and batch bounds.
- Cancel queued overlay work before releasing Bergamot native resources on service teardown.
- Improve OCR cleanup for zero-width artifacts and words split across visual lines by a hyphen.
- Release the process-scoped Bergamot native model/service on low-memory callbacks and overlay-service teardown; the next use reloads it lazily.
- Retain shared Arabic output cleanup, quality guard and per-series name consistency; ML Kit remains the default.


## 0.12.4 — 2026-11-15 (experimental)

### Optional offline Bergamot translation
- Add Mozilla Firefox Translations (Bergamot) as an optional English→Arabic on-device engine; ML Kit remains the default.
- Download the model on demand (~23.8 MB compressed, ~35.6 MB installed) and verify SHA-256 for both compressed and unpacked files.
- Fetch the ARM64 JNI runtime from a pinned upstream commit during CI; runtime use is limited to Android 9+ 64-bit devices.
- Package the pinned native runtime for ARM64 only; keep the language model on-demand and out of the APK. Include MPL-2.0 and third-party notices.
- Add a Settings model downloader/status card and an explicit engine selector. The Live/tap pipelines route explicit Bergamot selection through the non-progressive page translator while preserving ML Kit's progressive path for its default engine.
- Keep one native service/model per process to avoid repeated model loading and native-memory leaks.
- Simplify Settings: remove the Automation, Ignore list, Character names and Names & terms sections; keep Live mode control in the floating lens menu; add a clearly labelled translation-pair log reset action.

### Verification
- CI unit tests and debug APK assembly passed on the prototype branch.
- Real-device JNI loading, translation quality, latency, RAM consumption and full offline behavior still require validation before merging or making Bergamot a recommended default.

## 0.12.3 — 2026-11-15 (experimental)

### Simplified offline-first engine setup
- Make bundled PaddleOCR PP-OCRv5 Arabic/English the only OCR backend; remove the OCR-engine selector and ML Kit text-recognition dependency.
- Keep ML Kit English→Arabic as the on-device translation engine and Gemini as the optional cloud engine.
- Remove the LibreTranslate/Argos option, endpoint/API-key settings, factory path, implementation and tests. Existing saved LibreTranslate selection safely falls back to ML Kit.
- Remove the separate OCR engine settings section so users no longer confuse OCR selection with translation selection.
- Simplify Settings: remove Automation, Ignore list, Character names, and Names & terms sections; Live mode is controlled from the floating lens menu.

### Verification
- CI build and unit tests pending for this branch; real-device PaddleOCR accuracy and translation latency still require measurement.

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


## v0.12.15 (unreleased)
- Add exact-source Arabic repairs for two OCR variants observed in the Solo Leveling chapter-4 phone report (duplicated “THE” in rank dialogue and “WOULO'VE” recognition error).
- Keep ML Kit batching on parallel x6: joined ` | ` was fastest on the fixed 20-bubble benchmark, but its prior real-page mapping mismatch still disqualifies it as the saved default.
- Verification and update-compatible prerelease build pending.


## v0.12.16 (unreleased)
- Add exact-source Arabic repairs for Solo Leveling chapter-5 lines reported on-device: tunnel/gate narration, rank explanation, healers, statue dialogue and short responses.
- Keep ML Kit batching at parallel x2. The chapter-5 fixed benchmark mapped all 20 bubbles under parallel x2/x3/x4, but joined pipe remains banned after a real-page mismatch.
- Unit tests and update-compatible prerelease build pending.
