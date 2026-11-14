# ManhwaLens — Phase P0 Architecture Audit

**Repository:** `mouadknight0-cell/ManhwaLens` · **Branch:** `arena/8a718184-manhwalens` · **Baseline:** tag `v0.11.0` (`b8e37de`, versionCode 30)
**Method:** read-only source inspection (no builds, no Gradle tasks, no dependency changes, no file modifications inside the repo).
**Scope read:** 103 main Kotlin files (~20,043 LOC), 59 JVM test files, manifest, Gradle/version catalog, all root docs.
**Status:** P0 only — no code was changed. Section 10 is a proposal list awaiting approval.

---

## 1. Architecture Overview

### 1.1 Shape

Single-module Android app (`:app`), single process, **no DI framework, no Room/SQLite, no DataStore, no coroutines in the processing path, no HTTP client library** (`HttpURLConnection` directly).

```
┌──────────── UI (Compose) ────────────┐   MainActivity + SettingsActivity + settings screens
│                                     │   ui/, ui/settings/
└───────────────┬─────────────────────┘
                │ intents / prefs
┌───────────────▼──────────────────────────────────────────────────────────┐
│ FloatingButtonService  (4464 LOC, foreground service, TYPE_APPLICATION_OVERLAY) │
│  owns: overlay windows · MediaProjection session · ImageReader · live loop      │
│        capture orchestration · translation relay · patch painting               │
└───┬──────────────┬───────────────┬───────────────┬──────────────┬─────────┘
    │              │               │               │              │
  ocr/          translate/       render/         live/         data/ + util/
 (ML Kit        (ML Kit,         (PatchPlanner   (LiveLoop     (SharedPrefs,
  Latin)         Gemini,          PatchSurface    Driver, gates, PipelineTracer,
                 Libre, cache)    AutoFit)        DiffMask)     ErrorLog)
    │
 capture/ (ManhwaAutoCaptureService — AccessibilityService, read-only)

Storage: 3 SharedPreferences files (OverlayPrefs / SettingsPrefs / TranslationPrefs)
         filesDir/translation_cache.json  (~2000 entries, full-file rewrite)
         filesDir/manhwalens-last-capture.jpg, ErrorLog, startup_crash_counter
```

### 1.2 Threading model (hand-rolled, no structured concurrency)

| Executor / thread | Where | Used for |
|---|---|---|
| `ioExecutor` (single thread) | `FloatingButtonService:232` | pre-warm, screen-on re-warm, **liveTranslate**, jpg save, **AI cleanup**, keepWarm |
| `remoteExecutor` (fixed 2) | `:239` | Gemini/Libre remote translate + live upgrade |
| `captureExecutor` (single) | `:245` | `doCapture` (OCR + ML Kit translate + patch build all live here) |
| `liveHandler` (`HandlerThread`) | `:341` | live tick loop only |
| `captureHandler` | `:270` | ImageReader `onImageAvailable` callback |
| `mainHandler` | `:2550`+ | overlay upserts, toasts, status pill |

**Assessment.** Package boundaries (`ocr`, `translate`, `render`, `live`) are genuinely well factored and unit-testable — `live/LiveLoopDriver` is even a pure state machine over injected `FrameSource`/`Sink`. The problem is the layer *above* them: `FloatingButtonService` is a 4,464-line orchestrator that owns nine responsibilities (see §3.3). Every feature added since v0.9.x landed in that one file, so the audit's structural risk is concentrated there, not in the engines.

### 1.3 Dependency / platform facts

- AGP 8.13.2, Kotlin 2.4.21, `compileSdk`/`targetSdk` 36, `minSdk` 26.
- Only two ML deps: `mlkit-text-recognition` 16.0.1 (**Latin script only**) and `mlkit-translate` 17.0.3 (`app/build.gradle.kts:96-97`).
- Permissions: `INTERNET`, `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE*`, `MEDIA_PROJECTION`, `PACKAGE_USAGE_STATS`, `POST_NOTIFICATIONS`. Manifest has `supportsRtl="true"`.
- No `androidTest` source set exists.

---

## 2. Actual Processing Pipeline

### 2.1 Tap path (`Translate now` / lens tap)

| # | Stage | Code | Thread | Notes / failure mode |
|---|---|---|---|---|
| 1 | Gate + tap decision | `onTap` `:602`, `translateNow` `:2139` | main | consent, standby resume, "capture in flight" toast |
| 2 | Grab frame | `doCapture` `:2511` → `acquireTapFrame` `:1149` | capture | hides own windows, waits for post-hide mirror frame, falls back to newest buffered frame + publishes `tapOwnedRects` |
| 3 | Image → Bitmap | `imageToBitmap` `:4303` | capture | RGBA_8888, row-stride copy, `isNearlyBlack` + `refetchFrameAfterBlack` guard |
| 4 | Downscale + persist | `capBitmapWidth(…, 720)` | capture | ~720 px wide working copy; jpg written async on `ioExecutor` (`:2591`) |
| 5 | Crop letterbox bars | `cropBarsForOcr` `:886` | capture | returns offset used to map boxes back |
| 6 | OCR | `TextRecognizerClient.recognize` (`ocr/TextRecognizerClient.kt:68`) | capture | ML Kit Latin, internal 1600 px long-edge cap, 20 s timeout |
| 7 | Text detection / grouping | `BubbleGrouper.group`, `BlockFilter`, `OcrGarbageClassifier` (`recognizeAndFilterBlocks` `:3075`) | capture | union-find bubble merge; drops SFX/watermark/own-window/own-string |
| 8 | Dedupe + priority | `PagePlan.prepare`, `BlockPriority` | capture | normalized-key dedupe, short→largest→central ordering |
| 9a | **Local translate (draft)** | `TranslatorFactory.createProgressive` `:2826` | capture | ML Kit EN→AR, ≤2 in flight, `onBlockReady` paints immediately |
| 9b | **Remote relay (optional)** | `remoteExecutor` + `startLiveRemote` `:3543` | remote | Gemini/Libre page batch launched *in parallel with* 9a; epoch-guarded; on success `painter.onRemote` wins per bubble (`remoteWins`) |
| 10 | Text removal + Arabic render | `PatchPlanner.areas/renders` → `PatchSurface.build` → `PatchOverlayWindow.upsert` `:3978`, `render/PatchSurface.kt:280` | capture (build) / main (attach) | opaque cover rect per visual area; Arabic drawn in each block's own band |
| 11 | Optional AI cleanup | `maybeScheduleAiCleanup` `:4029` | **ioExecutor** | ≤3 largest bubbles re-rendered by Gemini image edit; local patch stays on any failure |

**Key design point (good):** the local ML Kit draft is never blocked by the remote path — the remote result replaces an already-visible patch. That satisfies "Gemini must never block local results" *at the design level* (see §7.2 for the thread-scheduling violation of it).

### 2.2 Live path

`liveTickInner` `:1439` → `LiveGates.evaluate` → `LiveLoopDriver.tick` `live/LiveLoopDriver.kt:326`:

1. acquire newest mirror frame → 64×128 luma thumbnail (`thumbnailGray` `:1355`).
2. `LiveStabilityTracker` over masked diff (`DiffMask.cellMaskForRects`) → Changing / Settling / Stable.
3. Changing + `RowHashShift.estimate` confident → `shiftPatchesBy(dy)` so existing Arabic scrolls with the page.
4. Stable + not same page (`samePage` vs last attempted/translated thumbnail) → `acquireCleanFrame` (`:503`): use clean stash, else hide overlay and wait ≤ timeout for a fresh frame; a **burned-in** frame is never OCR'd.
5. `liveTranslate` `:3148` on `ioExecutor`: band-only OCR (reuse `RowHashShift` region), `PagePlan.prepare`, ML Kit dual draft painted, then Gemini/Libre upgrade via `LiveUpgrade` + `contentEpoch`.

Live never retains frames (only the review mode does), and the clean-capture protocol is the strongest part of the codebase — it explicitly refuses to OCR our own burned-in Arabic.

---

## 3. Important Classes and Responsibilities

### 3.1 Engines (healthy)

| Class | File | Responsibility | Risk |
|---|---|---|---|
| `TextRecognizerClient` | `ocr/TextRecognizerClient.kt` | ML Kit Latin singleton, downscale 1600 px, inverse-scale boxes | Latin-only (§6.3) |
| `BubbleGrouper` | `ocr/BubbleGrouper.kt` | union-find bubble merge (gap 0.9, overlap 0.2, height 0.40, center 0.75) | low |
| `BlockFilter` / `MeaninglessText` / `OcrGarbageClassifier` | `ocr/` | drop edge, watermark, SFX, own windows/strings, consonant soup | low |
| `MlKitTranslator` | `translate/mlkit/MlKitTranslator.kt` | on-device EN→AR, chunking, joined vs parallel, scene-name pinning | **C2, C3** |
| `GeminiTranslator` / `GeminiWire` / `HttpUrlConnectionGeminiApi` | `translate/gemini/` | batched cloud translate, JSON + legacy parse, retry ladder | **C1** |
| `LibreTranslateTranslator` | `translate/libre/` | self-hosted HTTPS endpoint, single batch | low |
| `CachingTranslator` | `translate/CachingTranslator.kt` | 3-tier cache + post-process + quality guard | **C2** (guard scope) |
| `NameMemory` / `SceneNames` / `Glossary` / `NameScript` / `*Romanizer` | `translate/` | name detection, pinning, transliteration | §4 |
| `PatchPlanner` / `PatchCoverage` / `PatchSurface` / `AutoFitTextSizer` / `StaticTextMeasurer` | `render/` | cover geometry, opaque patch, Arabic layout | **R1–R4, P4** |
| `LiveLoopDriver` + `LiveGates` + `DiffMask` + `LiveFrameMath` | `live/` | pure live state machine | low |

### 3.2 Orchestration (the risk)

`overlay/FloatingButtonService.kt` (4,464 lines) currently owns:

1. lens button + menu + status pill + debug panel windows;
2. MediaProjection consent, VirtualDisplay/ImageReader lifecycle, Android-14 one-display-per-token recovery;
3. tap capture, clean-frame protocol, frame ownership/recycling;
4. OCR → filter → translate → paint pipeline;
5. progressive painter with `PaintLedger`/`remoteWins` arbitration;
6. live loop wiring (`FrameSource`/`Sink`);
7. Gemini/Libre relay, circuit breaker, AI cleanup;
8. settings/prefs reads, review mode, benchmark UI hooks;
9. foreground-app gating via `UsageStatsManager`.

Consequences: no unit-testable seam for any of 1–8 (zero tests reference this class), and ownership of bitmaps + flags (`captureInFlight`, `liveJobRunning`, `attached`, epochs, latches) is spread across ~4,400 lines. This is the single biggest maintainability and regression risk in the project and the main thing P1 should address — *extract*, not rewrite.

---

## 4. Current Name Management Implementation

### 4.1 Layers, as built

| Layer | File | What it does |
|---|---|---|
| Built-in glossary | `translate/Glossary.kt` | ~128 curated names (Chinese/Korean/Japanese → Arabic), user entries `English = عربي`, `prepare()` normalizes + one-lines + applies longest-first whole-word replacement |
| Transliteration | `Glossary.transliterateLatin`, `looksChinese` | pinyin syllable table (INITIALS+FINALS+WHOLE) for unknown Chinese names |
| Script detection | `translate/NameScript.kt` | KOREAN / CHINESE / JAPANESE / UNKNOWN grading |
| Romanizers | `translate/KoreanRomanizer.kt`, `JapaneseRomanizer.kt` | syllable-level conversion to Arabic |
| Name detection | `translate/NameMemory.kt` | casing + position scan, STOP list, canonical form, `plan()` → pinned table with source grading (USER / BUILT_IN / PAGE_AI / TRANSLITERATION) |
| Gemini delivery | `NameMemory.sheetForModel` → `GeminiWire.buildFastBody` | textual "name sheet" appended to the prompt, capped at 20 names |
| ML Kit delivery | `SceneNames.apply(Glossary.prepare(text), pageNames)` (`MlKitTranslator.kt:70`) | names are substituted **in the source text** before the engine runs (ML Kit can't follow a prompt) |
| Cache key | `TranslationCache.key` = `engineId|style|Glossary.cacheText(source)` | glossary changes invalidate cache — verified correct |

### 4.2 Findings

**F4.1 — Gemini name sheet is dropped on the primary request path (confirmed bug, see C1).**
`GeminiTranslator.translateChunk:214` calls `buildRequestBody(chunk, THINKING_LEVEL, sheet, jsonReply = false)`; the router at `GeminiWire.kt:204-215` sends `jsonReply == false` to the legacy overload `buildRequestBody(blocks, thinkingLevel)` (`:173`), which calls `promptFor(blocks)` — **the `nameSheet` parameter is never read**. The sheet only survives on the 400-retry (`buildFastBody`). Impact: on every successful first attempt (the normal case) Gemini receives no page name table and falls back to `Glossary.promptHints` only, so page-local names (e.g. an unnamed side character appearing twice on that page) are spelled inconsistently. Fix: make the legacy overload accept and append the sheet, or force `jsonReply = true`; add a wire test asserting the sheet text appears in the first request body.

**F4.2 — Names are page-scoped only; nothing persists per series.**
`MlKitTranslator` builds `pageNames` per `translatePage` and clears it at `:218`. There is no per-series/per-app store (the foreground package *is* known — `liveForegroundPackage` — but unused for this). Consequence: "Han Ji-woo" can be spelled differently on page 3 vs page 4; only `Glossary` (curated/user) survives. The disk cache accidentally mitigates this (identical English → cached Arabic), which is why the defect is intermittent rather than obvious. Recommendation: a `series → nameTable` prefs/JSON store keyed by foreground package + detected title, consulted before `plan()` and merged after it.

**F4.3 — Cache hit bypasses page pinning.**
`CachingTranslator:63` checks the cache before the engine, and the key (`TranslationCache:18-19`) contains only `Glossary.prepare(source)` — not `SceneNames`/`pageNames`. So a line cached under page 1's pinned spelling is served verbatim on page 2 even when page 2's plan pinned a different spelling. Either include a name-table hash in the key (consistent, but fragments the cache) or make the pinned table authoritative by re-applying it to cache hits (cheaper, keeps consistency). Today's behaviour is "first writer wins" by accident.

**F4.4 — `NameMemory.plan` runs twice per page (`GeminiTranslator:78` and `:113`).**
Wasted CPU on a hot path, and the two calls can disagree if the glossary is refreshed between them (drift between the sheet and the pinned table). Compute once and pass the result.

---

## 5. Translation Engine Analysis

### 5.1 Contract

`Translator.translatePage(blocks): PageTranslation` — blocking, never throws, returns `Success(blocks, engineId, elapsedMs, cacheHits, cacheMisses)` or `Failure(reason, message, engineId)` with a `FailureReason` enum. This is a good, testable seam; `FakeArabicEngine` + `CachingTranslatorTest` exercise it.

### 5.2 Engines

| Engine | Path | Behaviour | Verdict |
|---|---|---|---|
| **ML Kit EN→AR** | `translate/mlkit/MlKitTranslator.kt` | Singleton client shared with warm-up + benchmark; one-time ~30 MB model download (`Tasks.await`, 30 s); joined batch only when `BatchPolicy` proved a separator on this device, else per-block ≤2-3 in flight; >400-char blocks chunked ≤380 chars; `SceneNames` pinning; 15 s translate timeout | Good design, two defects (C2, C3) |
| **Gemini** | `translate/gemini/*` | `gemini-3.8-flash` (alt `3.5-flash-lite`), `thinkingLevel=low`, ≤18 blocks / ≤2600 chars per request, ≤3 parallel; JSON reply + legacy numbered-lines parse; 400 → fast body; 404 → alternate model; 401/403/429 mapped; circuit 5 min (30 s for "short" failures); 10 s connect / 30 s read; key in `x-goog-api-key` header only | Correctly optional and well-guarded, one prompt defect (C1) |
| **LibreTranslate** | `translate/libre/*` | self-hosted, HTTPS-only endpoint validation, single batch `q` array, exact-count parse, same circuit | Fine; needs a server, so "offline" only on LAN |
| **Cache decorator** | `translate/CachingTranslator.kt` | memory LRU 1000 → disk 2000 → memo (exact 1500 + fuzzy 600); post-process `clean → polishArabic → tidyDialogue`; per-bubble quality rejection | Guard applied to `gemini` / `libre_translate` / `ml_kit_en_ar` (ML Kit added in P0/C2) |

### 5.3 Batching / auto-tuning (unusually thorough)

`BatchSplitter` (8 separator strategies, 5 validation rules), `BatchAutoSelect` (self-test with 4 strings, `RUNS=2`, `CONFIRM=10`), `BatchPolicy` (per-device proof, 7-day separator ban), `BenchmarkRunner` (20-bubble median/worst, `recommend()`). This is the most sophisticated part of the codebase and it is covered by tests. No action needed in P1 beyond keeping it.

---

## 6. Offline Capability Analysis

### 6.1 What works offline — verified

- After the one-time EN→AR model download, the **entire tap and live pipeline runs with no network**: ML Kit OCR (bundled), ML Kit translate (on-device), caching, naming, rendering.
- Gemini/Libre are additive: the draft is painted first, remote failure maps to `FailureReason.NO_INTERNET` and is silently ignored, circuit breaker prevents retry storms, timeouts are bounded (10 s / 30 s translate, 15 s live upgrade).
- No analytics, no telemetry, nothing leaves the device except the user's own Gemini/Libre calls.

### 6.2 Offline gaps

| Gap | Evidence | Impact |
|---|---|---|
| **Fresh install is not offline-capable.** First translation needs the ~30 MB model; if offline, `MODEL_DOWNLOAD_FAILED` → nothing painted, and there is no other engine that works without network | `MlKitTranslator.kt:82-103`; `SettingsScreen.kt:547` ("needs internet once") | A user who installs and goes offline gets a completely non-functional app with only a generic failure. **Violates "local translation must work without internet" for the first run.** |
| **No explicit "prepare offline" onboarding step.** Download is triggered lazily by first use / settings warm-up; no progress UI, no Wi-Fi-only policy, no "ready for offline" indicator on the home screen | `preWarmTranslators` `:350`, `TranslationEngineCard` | Users cannot know whether they are offline-ready; first tap can block up to 30 s. |
| **OCR is Latin-only** | `TextRecognizerClient.kt:68` `TextRecognizerOptions.DEFAULT_OPTIONS`; only `text-recognition` in deps | Raw Korean/Chinese/Japanese pages produce garbage or nothing. The app fundamentally requires an **English scanlation**. Worth stating explicitly in product docs, and it means `KoreanRomanizer`/`NameScript` only ever see romanized names. |
| **Translate model is EN→AR only** | `mlkit-translate` + fixed EN→AR client | Even with a Korean OCR model, KO→AR would need a second model (~30 MB). Out of P0 scope; note it. |

### 6.3 Verdict

Offline-first **holds after setup**, not **on first run**. The one change that most improves the stated requirement is making "model present" a first-class, visible, resumable state (P1 item, §10).

---

## 7. RAM and CPU Bottlenecks

### 7.1 RAM

| Item | Evidence | Size | Verdict |
|---|---|---|---|
| **Persistent mirror ImageReader, 3 × RGBA_8888 full-screen** | `FloatingButtonService.kt:678` `ImageReader.newInstance(w, h, RGBA_8888, 3)` | 1080×2400×4 ≈ 10.4 MB/buffer → **~31 MB native** held for the whole service lifetime | Largest single allocation. `maxImages = 3` is unnecessary: `onFrameAvailable` keeps exactly one (`:660-672`), so 2 is sufficient → saves ~10 MB. |
| Working frame copies | `lastReviewFrame` (~4.6 MB, retained even when review is unused and only freed on service destroy `:497`), live `stashFrame` (~4.6 MB), `openReviewMode` clone (`:4286`), `CaptureDebugWindow.shownBitmap` | up to ~4 concurrent 720 px frames ≈ 18 MB | Bounded but wasteful: retain the review frame **only when review mode is enabled**. |
| Patch bitmaps | one ARGB_8888 bitmap per visual area (`PatchSurface.build`) | ~0.3–1 MB each, 6–10 per page | Fine; recycled on replace/clear (verified `PatchOverlayWindow.kt:334-352`, `:214-225`). |
| Caches | `TranslationCache` 1000, `PersistentTranslationCache` 2000, `TranslationMemo` 1500 + 600 | few MB max | Correctly bounded. |

Bitmap recycling discipline is genuinely good — 15 explicit recycle sites with clear ownership (`frame` handed to `LiveUpgrade` recycles itself; `ocrBitmap` recycled only when `!== frame`). No leak found.

### 7.2 CPU / scheduling

**P7.1 — One `ioExecutor` thread serves both the live pipeline and Gemini AI cleanup (structural, high impact).**
`ioExecutor` is a **single-thread** executor (`:232`) used by `liveTranslate` (`:1774`), the jpg encode (`:2591`), warm-up (`:350`, `:957`, `:4219`) and `maybeScheduleAiCleanup` (`:4053`). AI cleanup performs up to 3 sequential Gemini image edits with a **15 s read timeout each** (`AI_CLEANUP_READ_TIMEOUT_MS`). With cleanup enabled and a slow network, the live loop's `liveTranslate` task sits behind up to ~45 s of network I/O while `liveJobRunning` stays `true`, so `LiveGates` returns WAIT and its 20 s / 30 s watchdogs can fire "job stuck" heals. This is a real violation of "Gemini must never block local results" — not of the translation *result*, but of the *scheduler*. Fix: dedicated executor (or `remoteExecutor`) for AI cleanup; never the live thread.

**P7.2 — `AutoFitTextSizer.fit` is a linear 1 px descent (`render/AutoFitTextSizer.kt:37-51`).**
From `maxPx` (≈ `innerH × 0.62`, commonly 40–60 px) down to `minPx = 9`, each step builds a **full `StaticLayout`** (shaping + wrapping). Worst case ≈ 50 layouts per bubble; ~8 bubbles per page → up to ~400 layout builds per capture, plus the measuring layout and the drawing layout per patch. This is on the critical path to first patch (`PatchSurface.build` on the capture worker). A binary search over `[minPx, maxPx]` gives the same answer within 1 px in ~6 builds (~8× less work) and is a pure, easily tested change.

**P7.3 — Layout configuration is built three times per patch, with one inconsistent copy.**
`drawArabicText` constructs `StaticTextMeasurer(context)` per patch (`PatchSurface.kt:294`; `ResourcesCompat.getFont` again), the measurer builds a layout **without `setMaxLines(4)`** (`StaticTextMeasurer.kt:31-35`), and the renderer builds layouts *with* `setMaxLines(4)` (text + outline). The measurer therefore predicts more lines than the renderer can display → it shrinks the font further than needed and reports `fits = false` (`PatchSurface.kt:298-300`) for text the renderer would have clipped anyway.

**P7.4 — Persistent mirror churn at display refresh rate.**
`onFrameAvailable` (`:660`) calls `reader.acquireLatestImage()` + `close()` for **every** composited frame (≈60/s) for the whole service lifetime, even when Live is off and no capture is pending, and signals `frameSignal` each time. Plus, per live tick, a full-screen RGBA→Bitmap copy (~10 MB alloc) is downscaled to 720 px then to a 64×128 thumbnail. Actual measured impact is unknown (no profiling run in P0) but the alloc rate is ~30 MB/s while Live is on. Cheap mitigation: skip the acquire/close cycle when no consumer is waiting (flag check), or keep one buffered frame and stop draining.

**P7.5 — `RowHashShift.estimate` (`live/LiveFrameMath.kt:133-180`) is O(maxShift × h × w)** — 64×128 thumbnail, ±64 rows ≈ ~0.5–1 M byte comparisons, executed on every `Changing` verdict (up to ~3/s). Tolerable, but it runs on the live thread ahead of capture decisions; a downsampled row profile or coarse-then-fine search would cut it ~4×.

**P7.6 — `PersistentTranslationCache.save()` rewrites the whole JSON on every page** (`:99-115`, called from `put`/`putAll`/`remove` at `:66`, `:80`, `:85`). With 2000 entries that is ~100–150 KB serialized + atomic rename per translated page, on the translation thread. Correct and safe, but the write should be debounced (e.g. 2 s idle) since a crash only costs cache warmth.

**P7.7 — Minor**: `SettingsPrefs(applicationContext)` / `TranslationPrefs(...)` are constructed per live tick and per paint; `sampleBackground` does a `getPixels` per bubble; `SurfaceView`-free overlay invalidates the whole canvas on each fade frame. None are individually significant; listed so they are not re-discovered in P1.

---

## 8. Arabic Rendering Problems

Bundled fonts exist and are used consistently (`res/font/cairo.ttf`, `cairo_bold.ttf`; `StaticTextMeasurer` uses `ResourcesCompat.getFont`; `CaptureDebugWindow` uses the same family via Compose). Patch geometry, opaque fill and text-color selection are sound. The defects are all in **layout direction and bidi**, plus the fit mismatch from §7.3.

**R1 — No explicit RTL text direction (medium, real).**
`PatchSurface.kt:316-323` and `StaticTextMeasurer.kt:32` set `ALIGN_NORMAL` but never `setTextDirection(...)`. `StaticLayout.Builder` defaults to **FIRSTSTRONG_LTR**, so the paragraph direction is resolved from the first strong character *on each line/paragraph*. Arabic-only text resolves RTL and right-aligns correctly — but any line that begins with a digit, a Latin run, or leading neutral punctuation (`«…`, `"…"`, `- …`) resolves **LTR and left-aligns inside an otherwise RTL bubble**. That case is common: untranslated names, chapter numbers, "؟!" openers, and the C2 English-echo bug all produce Latin/digit-leading lines. Fix: `.setTextDirection(TextDirectionHeuristics.RTL)` in all three layout builders (measurer, text, outline).

**R2 — Mixed-direction runs are not isolated (low/medium).**
No `BidiFormatter.unicodeWrap`, no RLM (`\u200F`) insertion, no bidi-aware handling of Latin names that `Glossary` could not transliterate. In an RTL paragraph a trailing Latin run or a number adjacent to punctuation can render in a visually surprising order. Combined with R1 this is the main "looks wrong" class of bug. Fix: wrap residual Latin runs and numerals at post-process time (`ArabicValidator`/`BubbleTextRules`), and append RLM at string ends.

**R3 — Silent clipping instead of a fit signal (medium UX).**
`AutoFitTextSizer` bottoms out at `MIN_TEXT_PX`; `PatchSurface` then logs `"text did not fit at min size — clipped"` and clips via `canvas.clipRect`. The user sees a bubble whose last line is cut with no indication. Because of §7.3 the measurement is pessimistic, so this fires more often than necessary. Fix: (a) reconcile measurer/renderer config, (b) allow one extra line or a slight font floor reduction before clipping, (c) if still overflowing, prefer truncating with `…` over hard clipping.

**R4 — Band-anchored stacking assumes band order = reading order (low).**
For a multi-block bubble, the owner draws over the whole cover but each block's Arabic is anchored to the top of *its own OCR band* (`PatchSurface.kt:232-241`, `anchored = band != null`). If `PatchPlanner` folds two blocks whose vertical bands were detected out of reading order, the Arabic fragments stack in the wrong order. Worth a targeted test with a synthetic 2-block bubble.

**R5 — Outline and text layouts are two independent builders (maintainability).**
`drawArabicText` builds the outline `StaticLayout` separately with duplicated config. Any fix to R1/R3 must be applied in three places. Recommend a single private `buildLayout(text, paint, width, maxLines)` used by measurer + text + outline.

**Positive:** `ALIGN_NORMAL` (not `ALIGN_OPPOSITE`) is the correct choice, `includePad(false)` + `lineSpacing(1.15)` are fine, Cairo Bold has full Arabic coverage, and the text color is chosen by measured luminance (`ColorSampler.bestTextColorOn`) with an outline on mid-luma backgrounds — this is better than most overlay translators.

---

## 9. Confirmed Bugs vs Potential Problems

### 9.1 Confirmed defects (evidence-backed)

| ID | Defect | File / function | Evidence | Impact |
|---|---|---|---|---|
| **C1** *(fixed in P0-A — see [P0A_GEMINI_NAME_SHEET.md](P0A_GEMINI_NAME_SHEET.md))* | Gemini page **name sheet discarded** on the primary request | `translate/gemini/GeminiWire.kt:204-215` router → `:173 buildRequestBody(blocks, thinkingLevel)`; caller `GeminiTranslator.kt:214` passes `jsonReply = false` | 4-arg overload ignores `nameSheet` when `jsonReply == false`; only the 400-retry path (`buildFastBody`) includes it | Page-local names spelled inconsistently under the default engine; contradicts §4's stated guarantee. No test covers the sheet in a request body. |
| **C2** *(fix implemented in P0/C2 — see [P0B_MLKIT_ENGLISH_ECHO.md](P0B_MLKIT_ENGLISH_ECHO.md); status flipped to fixed once CI verified the run)* | **English can be painted as Arabic** | `translate/mlkit/MlKitTranslator.kt:246` (`arabicById[b.id] ?: if (b.english.isBlank()) "" else b.english`) and `:276-279` (progressive callback blank-checked only), painted via `FloatingButtonService.kt:3844-3846` (only blank-checked); guard scoped to remote engines in `CachingTranslator.kt:51-52` | The quality guard is `engineId == "gemini" || "libre_translate"`; ML Kit's echo fallback bypasses it entirely | An untranslated English bubble is drawn with Cairo Bold, right-aligned, in an RTL paragraph — visibly broken and exactly what the guard exists to prevent. |
| **C3** | `pageNames` not cleared on early-failure returns | `MlKitTranslator.kt:118` (set) vs `:218` (clear, success path only); failure returns at `:204-208` | Not in a `try/finally` | *Latent:* leaks a stale name table into the next page when the same instance is reused after a per-block failure. Low blast radius today (instances are created per capture) but free to fix. |
| **C4** | AI cleanup occupies the live pipeline's only thread | `FloatingButtonService.kt:4053` vs `:1774`, single-thread `ioExecutor:232` | up to 3 × 15 s network calls queued ahead of `liveTranslate` | Live translation stalls while cleanup runs; live watchdogs can fire. Direct violation of "Gemini must never block local results" at the scheduling level. |
| **C5** | `NameMemory.plan` computed twice per Gemini page | `GeminiTranslator.kt:78`, `:113` | two independent scans of the same block list | Wasted CPU; sheet and pinned table can drift. |

### 9.2 Potential problems (not proven; verify before acting)

| ID | Concern | Evidence | Why it may matter |
|---|---|---|---|
| P1 | Remote **quality rejection blanks the bubble** instead of retrying locally | `CachingTranslator.kt:129-140` (`rejectedIds` → empty) | Intentional ("never paint English"), but the user loses that bubble entirely. Falling back to ML Kit for rejected ids would keep the page complete. |
| P2 | Cache hit bypasses page-pinned names | `TranslationCache.kt:18-19`, `CachingTranslator.kt:63` | See F4.3. |
| P3 | Pessimistic auto-fit / measurer≠renderer | `StaticTextMeasurer.kt:31-35`, `AutoFitTextSizer.kt:37-51` | Extra CPU + more clipping than needed. |
| P4 | `lastReviewFrame` retained even when review mode is unused | `:283`, `:3016`, `:4143` | ~4.6 MB permanently held. |
| P5 | Mirror drains every frame at display rate with no consumer | `:660-680` | Battery/thermal under a persistent service. |
| P6 | `maxImages = 3` | `:678` | ~10 MB avoidable. |
| P7 | Two concurrent `translatePage` calls possible (tap on `captureExecutor`, live on `ioExecutor`) against one shared ML Kit client, each with ≤2-3 in flight | `:2826` vs `:1774`; `MlKitTranslator.kt:335` | Latency spikes / thermal throttling; ML Kit tolerates concurrency but the app's own "≤2 in flight" budget is not global. |
| P8 | No user-visible feedback when a page yields 0 bubbles after filtering | `recognizeAndFilterBlocks` → `recordPaintResult` | A fully filtered page looks like the app did nothing. |
| P9 | `PersistentTranslationCache.save()` full rewrite per page | `PersistentTranslationCache.kt:99-115` | Jank on low-end storage; safe otherwise. |
| P10 | Libre endpoint is user-supplied; no certificate pinning, no response-size cap | `translate/libre/LibreTranslateWire.kt` | Acceptable for a self-hosted tool; document it. |

**Explicitly checked and found correct** (so P1 does not re-litigate them): Gemini never blocks the local draft; bitmap recycling/ownership; live burned-in-frame avoidance; `AppVisibilityTracker`/own-window gating; circuit breaker semantics (5 min vs 30 s); settings migration and corrupt-prefs wipe; timeout bounds on OCR/model/translate; cache invalidation on glossary change.

---

## 10. Recommended Changes, Ranked

No code was modified. Priority = correctness first, then the stated product guarantees, then cost.

| # | Priority | Change | Files | Effort | Risk |
|---|---|---|---|---|---|
| 1 | **P0** | Fix C1: route the name sheet through the legacy body (or force `jsonReply = true`); add a wire test asserting the sheet is in the **first** request | `GeminiWire.kt:173-215`, `GeminiTranslator.kt:214`; `GeminiWireTest` | S | Very low |
| 2 | **P0** *(done in P0/C2)* | Fix C2: extend `requiresArabicGuard` to ML Kit (or delete the `?: b.english` echo and emit `""`), and add a painter-level guard that never draws non-Arabic into an RTL patch | `MlKitTranslator.kt:246`, `CachingTranslator.kt:51-52`, `FloatingButtonService.kt:3844` | S | Low |
| 3 | **P1** | Fix C4: move AI cleanup to its own executor / `remoteExecutor`; never queue network work on the live thread | `FloatingButtonService.kt:4053` | S | Low |
| 4 | **P1** | RTL correctness: `setTextDirection(TextDirectionHeuristics.RTL)` in one shared layout builder used by measurer + text + outline; add RLM wrapping for residual Latin/digits | `PatchSurface.kt`, `StaticTextMeasurer.kt`, `BubbleTextRules.kt` | M | Low |
| 5 | **P1** | Offline readiness: explicit "Prepare offline (EN→AR)" step with progress + Wi-Fi policy + persistent "offline ready" state surfaced in UI; block `Translate now` with an actionable message instead of a silent failure | `MlKitTranslator`, `SettingsScreen`, home UI | M | Medium (UX) |
| 6 | **P2** | Perf: binary-search `AutoFitTextSizer.fit`; reconcile measurer `maxLines`; hoist `StaticTextMeasurer` out of `drawArabicText` | `render/AutoFitTextSizer.kt`, `StaticTextMeasurer.kt`, `PatchSurface.kt` | S–M | Low (golden tests needed) |
| 7 | **P2** | Name persistence per series (foreground package + title key), consulted/merged around `NameMemory.plan`; make pinned table authoritative over cache hits | `NameMemory`, `data/`, `CachingTranslator` | M | Medium |
| 8 | **P2** | De-duplicate `NameMemory.plan` (C5); clear `pageNames` in `finally` (C3) | `GeminiTranslator.kt`, `MlKitTranslator.kt` | S | Very low |
| 9 | **P2** | Memory: `maxImages = 2`; retain `lastReviewFrame` only when review is enabled | `FloatingButtonService.kt:678`, `:3016` | S | Low |
| 10 | **P3** | Extract orchestration seams out of the 4,464-line service (capture session, painter/arbiter, relay) behind interfaces — **extraction only, no behaviour change**, done after 1–9 land | `FloatingButtonService` | L | Medium |
| 11 | **P3** | Debounce `PersistentTranslationCache.save()`; skip mirror draining when no consumer; coarse-then-fine `RowHashShift` | `PersistentTranslationCache`, `FloatingButtonService`, `LiveFrameMath` | S–M | Low |
| 12 | **P3** | Local fallback for remotely-rejected bubbles (P1 in §9.2) instead of blanking | `CachingTranslator` | S | Low |
| 13 | **P3** | Optional: Korean/Chinese OCR + KO→AR model behind a feature flag — **new dependency + new model, requires explicit approval** (outside P0/P1 scope) | `ocr/`, `translate/` | L | High |

**Explicitly not recommended:** removing any existing feature. Live mode, benchmark/auto-select, AI cleanup, review mode and the Libre engine all have evidence of deliberate design and tests or guards; the defects above are local, not architectural.

---

## 11. Testing Strategy

### 11.1 Current state

- 59 JVM unit test files, CHANGELOG claims 509 passing on v0.11.0 (**not verified here** — no build was run per the environment constraint).
- Test deps: JUnit 4 + `org.json` only (`app/build.gradle.kts:116-117`). No Robolectric, no Mockito, no Turbine, no Compose test, **no `androidTest` source set at all**.
- Well covered: `live/` (7 files incl. end-to-end simulation), `ocr/` (grouping, filters, QC), `render/` (planner, coverage, math, autofit, colors), `translate/` (cache, memo, batching, validators, glossary, name memory), `gemini/` + `libre/` wire & translator, `util/`.
- **Uncovered production classes with zero dedicated tests:** `MlKitTranslator`, `SceneNames`, `NameScript`, `KoreanRomanizer`, `JapaneseRomanizer`, `TranslatorFactory`, `TranslationTypes`, `PatchSurface`, `StaticTextMeasurer`, `BubbleRenderBox`, `BubblePatch`, `BubbleBoxRenderer`, `TextRecognizerClient`, `DetectedLine`, `GeminiApi` (HTTP impl), `GeminiImageEditor` (partial), `FloatingButtonService` (0 tests for 4,464 lines), `ErrorLog`, `LiveLog`, `MainThread`, `SafePrefs`/`SettingsPrefs`/`OverlayPrefs`.

Direct consequence: C1 (sheet routing), C2 (echo fallback) and C3 (state reset) are all *unit-testable without Android* and would have been caught by three small tests.

### 11.2 Tiers to add (in order)

**Tier 1 — pure JVM regression tests (no new deps, do first, ship with the fixes):**
1. `GeminiWire.buildRequestBody(blocks, level, sheet, jsonReply = false)` contains every sheet line — fails today (C1).
2. `GeminiTranslator` first `api.requestBodies[0]` contains the sheet (locks the fix at the call site, not just the wire).
3. `MlKitTranslator` with an injected failing/echoing engine: no `TranslatedBlock` whose `arabic` equals its `english`; `pageNames` empty after a failure (C2, C3).
4. `CachingTranslator` guard parity table: for every `engineId`, a cached English echo is rejected.
5. `NameMemory.plan` called once per page (assert via a counting fake).
6. `AutoFitTextSizer` binary search == linear descent within 1 px for a parameter grid (guards the perf change).

**Tier 2 — Android-framework behaviour (needs an approved test dependency; Robolectric or `androidTest`):**
7. RTL layout: `StaticTextMeasurer`/`PatchSurface` output for strings starting with a digit, a Latin run, and a quote — assert right alignment + no clipped baseline. A golden-bitmap comparison (patch PNG) is the honest test here; it is also the only way to lock R1.
8. Measurer vs renderer parity: same text/width → measurer height ≥ renderer height only by the documented margin, and `fits == false` never for a 4-line-fitting string.
9. `PatchOverlayWindow.upsert/replaceBitmap/shiftPatchesBy` bitmap recycling (assert `isRecycled`) and `PaintLedger`/`remoteWins` arbitration.

**Tier 3 — instrumented smoke (new `app/src/androidTest`, device or emulator):**
10. Consent → tap → first patch < N ms budget on a mid-tier device, asserting each `PipelineTracer` stage is present and ordered.
11. Live loop: 60 s of scripted frames → no OCR of a burned-in frame, no patch orphan, no bitmap double-recycle (`StrictMode` + `Bitmap` leak canary).
12. Accessibility service: read-only behaviour — assert no `performAction` other than the consented tap.

**Tier 4 — performance harness (report, not gate):** re-use `PipelineTracer` spans + `BenchmarkRunner` to publish per-release numbers: tap→first-patch, live tick cost, patch build ms/bubble, peak native heap, live alloc rate. This directly measures §7 and makes the C4/P3/P4 fixes verifiable.

### 11.3 Manual matrix (must stay documented, it covers what tests can't)

Arabic right-alignment with digit/Latin-initial bubbles · long dialogue clipping · 1/2/3-block bubbles · white vs black vs textured backgrounds · scroll shift during Live · screen-off/on, doze, battery-saver · overlay permission revoked mid-session · API 26 / 33 / 34 / 36 (MediaProjection one-display rule) · airplane mode on a fresh install vs after model download · Gemini key absent / invalid / rate-limited.

---

## Summary for review

- **The engines are in good shape.** Dedupe, batching auto-tuning, caching tiers, validators, live clean-capture and bitmap ownership are engineered, tested and correct.
- **Four defects deserve immediate attention:** the dropped Gemini name sheet (C1), the English-as-Arabic fallback (C2), AI cleanup blocking the live thread (C4), and the missing RTL text direction (R1). All four are small, local fixes.
- **The two structural risks** are the 4,464-line `FloatingButtonService` and the fact that "offline first" is not true on a fresh install. Neither needs a rewrite; both need a deliberate, incremental plan.
- **Testing is strong where the code is pure and absent where it is Android-bound** — exactly the two places the confirmed bugs live. Tier 1 tests can be written before any production change.

No file in the repository was modified. Awaiting engineering review before starting P1.
