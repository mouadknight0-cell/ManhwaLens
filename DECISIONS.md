# DECISIONS.md — one line per decision (2026-11-11, A→D pipeline task)

- Live mode auto-starts on the first lens tap (tap arms the capture session AND enables Live); an explicit user disable persists `liveOptOut=true` (Settings switch or long-press) and stops auto-arming for good; Settings toggle ON or long-press ON clears it.
- Live stays pausable by long-press (unchanged) and by the Settings switch; standby after 5 min without a frame change now auto-resumes on the NEXT detected change (per spec) instead of requiring a tap; the tappable pause notice text was updated accordingly.
- Stability window and sensitivity are per-tick settings read from SharedPreferences (200–1000 ms, default 400; low/medium/high → 16/10/6 mean-abs-diff), no tracker recreation needed.
- Overlay hide-before-capture sleep reduced 80 ms → 50 ms (~one frame at 60 Hz, exactly what the spec asks).
- Sensitivity mapping: high sensitivity = LOW threshold (6) so subtle changes (blinking cursor) count as change; low sensitivity = HIGH threshold (16).
- Floating-button live status = colored dot + tiny caption under the disc drawn by LensButtonView (green=waiting/same page, amber=scanning, violet=translating, grey=no session, red=error, hidden=paused) using the exact state strings from resources; the same strings are shown as a "Live: …" header line in the debug panel.
- Debug panel shows live status as a header line via a new `liveStatus` parameter on DebugPanelWindow.show(); CaptureMeta unchanged (spec asks for panel, not overlay footer).
- Auto-capture (accessibility) setting kept as an OPTIONAL extra trigger, description rewritten to say Live mode does not need it; optional "Faster scroll detection" card added with Open app info (Settings.ACTION_APPLICATION_DETAILS_SETTINGS) + Open accessibility settings buttons.
- Accessibility service left fully functional but never required for Live; it only pokes a capture when a session is armed (unchanged behavior).
- TranslationCache.normalize now lowercases (spec: trim, collapse whitespace, lowercase key); PageDedupe already lowercased — behavior unified.
- ML Kit strategy = joined single call with "\n@@@\n" separator (PageBatch), split-count validated, automatic fallback to parallel (≤3) per-block on mismatch — used by the LIVE path and as the failure fallback everywhere; the TAP path deliberately runs per-block progressive (createProgressive) so bubbles paint as they land, which is the only way "first bubble shown" and priority painting are real. Benchmark (Settings → Diagnostics) lets the user measure joined-vs-parallel on-device.
- Joined delimiter changed from "\n" (MlKitBatch) to "\n@@@\n" (PageBatch) so wrapped/multi-line model output cannot silently shift bubble mapping; old MlKitBatch removed and its tests migrated.
- Very long blocks (>400 chars) bypass joined/batch strategies: split by sentence (TextChunker), chunks translated via the per-block path, results reassembled per original block — first sentences can paint earlier and no single >400-char string is ever joined into a page batch.
- Block priority order = (short <80 chars first) → (bubble area, largest first) → (frame-center distance, most central first); applied to painting order and to the fallback per-block path; unit-tested via a pure `BlockPriority.order(ids, lengths, areas, centerDistances)` function.
- "First bubble shown at X ms" recorded as a PipelineTracer span (tap path + live path) at the moment the overlay first paints with ≥1 box.
- Benchmark = Settings → Diagnostics → "Benchmark translation": fixed 10-block sample page; strategies sequential / 3-way parallel / joined @@@ / two batches of 5, run against ML Kit (offline, no key) on a background thread; result table shown in a dialog and copied to the error log; timing uses an injected clock so the report logic is unit-testable with fake translators.
- Gemini non-blocking upgrade: launched IN PARALLEL with the ML Kit pass (not after it); uses a dedicated factory path `createGeminiOnly()` (never falls back to ML Kit behind our backs, never runs when circuit is open or key missing); on arrival each bubble is replaced in place; on failure ML Kit results stay untouched silently, circuit opens 5 min as usual. Bubbles translated by Gemini before/regardless of ML Kit timing paint through the same painter; `geminiWins` per id guarantees the Gemini text is never overwritten by the later ML Kit paint (and vice versa).
- Translator instances kept alive: ML Kit Translator.getClient is now a process-wide singleton (companion) shared by every MlKitTranslator; TranslatorFactory memoizes its CachingTranslator wrappers too; OCR client was already shared; nothing is created per tap anymore.
- User-editable ignore phrases live in SettingsPrefs.ignorePhrases (newline/comma separated, default: Subscribe, Get the app, Continue Reading, TOP, Episode, views, coins); BlockFilter matches lowercase substring; empty list disables the feature; wired into both tap and live paths.
- Live-mode cache line: tap + live paths both emit a "cache" span with hits/misses so per-page cache behavior is visible in the debug panel.
- Standby auto-resume requires a DETECTED change vs the last sampled frame (blinking-cursor-proof: tiny diffs keep standby); a 1/6 Hz thumbnail scan runs during standby (cheap, ~64x128 bytes).
- Overlay hide-sleep before OCR = 50 ms in BOTH the tap path (unchanged existing behavior of hiding nothing) and the live relay (was 80 ms).
- Release hygiene (standing rule, 2026-11-11): after EVERY new release build, delete all older release-APK copies — local `/home/user/ManhwaLens-v*-release.apk` and Dropbox `/ManhwaLens/ManhwaLens-v*-release.apk` — keeping only the newest (local copy named `ManhwaLens-vX.Y.Z-release.apk` + the `/ManhwaLens/` Dropbox upload). Dropbox deletes go to trash (recoverable); local deletes are permanent, so confirm by exact filename before `rm`.
- Startup crash-proofing (2026-11-12): code review of 0fd329c..HEAD found no single guaranteed-crash bug in the launch path; the plausible mechanisms are data-dependent (corrupt/legacy SharedPreferences after in-place upgrade, poisoned translation cache file) and environment-dependent (MIUI killing background work; JVM/Errors escaping executors). All were fixed; the crash-loop guard is the backstop for anything not yet found.
- manhwalens_settings now stores `settings_version` (v2). One-shot migration at Application start: known key with wrong type → key removed (reset to default); unknown keys → removed; a same-or-newer version → untouched (never downgrade-reset). Reads in SettingsPrefs/OverlayPrefs/TranslationPrefs go through safe getters (try/catch + defaults); an unavailable store degrades to defaults instead of crashing.
- Translation cache corruption recovery = delete + recreate (self-healing). There is NO Room/SQLite in the app — the only cache is the JSON disk LRU — so the "fallbackToDestructiveMigration" equivalent is file deletion; documented as such.
- Crash-loop guard: `filesDir/startup_crash_counter`, incremented in attachBaseContext, deleted after 5 s of healthy runtime (SuccessActivity-agnostic). 2 consecutive unhealthy starts → Safe Mode.
- Safe Mode = behavior gating, not data wipe: FloatingButtonService never starts (gate at top of onCreate/onStartCommand), MainActivity never auto-starts it and the toggle is inert, Live is therefore off, settings stay on disk untouched. Banner on the home screen shows the saved stack trace with Copy + "Exit Safe Mode" (clears the counter, recreates the activity). Crash streak also resets automatically after any 5 s of healthy running — Safe Mode is self-healing, exit is not destructive.
- Global uncaught handler: installed first thing in attachBaseContext (before any other init), idempotent (attachBaseContext + onCreate), writes the trace to `manhwalens-last-error.txt` (Settings → Last error) AND logs to Log.e under tag `ManhwaLens`.
- `LiveFrameMath.meanAbsoluteDifference` on mismatched thumbnails returns Float.MAX_VALUE ("changed") instead of require()-throwing; `RowHashShift.estimate` validates input sizes and returns null — the live loop can never die from a shape bug.
- Lint: `./gradlew lint` remains blocked by the offline `ui-geometry:1.9.1` cache gap (environment); `:app:lintVitalRelease` is the verified gate.
- Release hygiene (standing rule from user, 2026-11-11): after this batch ships as v0.9.1, v0.9.0 was deleted from Dropbox `/ManhwaLens/` and from the local archive — only `ManhwaLens-v0.9.1-release.apk` remains.
- Wrong-thread crash (2026-11-12, v0.9.2): live loop runs on the `manhwalens-capture` HandlerThread; `setLiveStatus` touched `LensButtonView.requestLayout()` → ViewRootImpl.CalledFromWrongThreadException. Root fix = ONE helper (`MainThread.run{}`) funneling every background→main hop; `MainThread.run` executes inline on main (no re-entrant posts).
- `LensButtonView.setLiveState` marshals itself to the main thread (belt & braces) + debug-only `WRONG THREAD` stack-trace log (`MainThread.assertDebug`) in debuggable builds. Same assert added to CaptureDebugWindow (show/updateBoxes/updateFrame/setOverlayVisible/toggleArabicOverlay/hide) — the overlay manager.
- CaptureDebugWindow surface is now thread-agnostic: setOverlayVisible/updateBoxes/updateFrame/hide all marshal to main internally; `show()` stays synchronous (callers are on main). Compose state holders (mutableStateOf) kept — writes are snapshot-thread-safe; View APIs and WindowManager only ever touch main now.
- Rapid status changes are COALESCED (`Coalescer<T>` — keeps only the newest value, one dispatch per burst); identical consecutive statuses are dropped as before. Live status delivery: tap→(coalesced)→main→LensButtonView.
- Live loop no longer starts at app launch. `ensureLiveLoopStarted()` runs only where a MediaProjection is armed (consent granted / first virtual display), restores persisted liveMode only then (unless `liveOptOut`), and is a no-op in Safe Mode. `LiveAutoStart` (pure, unit-tested) encodes the policy.
- Live loop lifecycle: explicit `liveTickRunnable` + `liveLoopActive`; `onDestroy()` stops the loop FIRST (removeCallbacks) before any view is removed; Boolean rescheduling happens only while active.
- Live tick failure semantics: catch(Throwable) in the tick → `ErrorLog` + span, `liveLoopActive=false` (loop stops), `SettingsPrefs.liveMode=false` persisted (Live stays off across restarts; re-enable manually), status=ERROR, app stays alive.
- Hide-before-capture is now latched: the hide is posted to main and the capture thread waits on a CountDownLatch with a 150 ms timeout (50 ms blind sleep before), then proceeds either way and logs a warning on timeout.
- StrictMode (penaltyLog, never crash) in debug builds only: ThreadPolicy.detectAll + VmPolicy.detectAll; debuggable flag read once via `MainThread.init()` in attachBaseContext. Penalty is LOG only — StrictMode never kills the app.
- Robolectric is NOT available in the offline cache, so the "background thread → view" test is implemented as: `CoalescerTest` (burst from a background thread coalesces and applies on the dispatcher = main) + `LiveAutoStartTest` + existing `StartupGuardTest` (crash-loop) + debug-only thread assertions that log `WRONG THREAD` in dev builds. Documented in BLOCKERS.
- Tap-path hide-before-capture intentionally NOT added (smallest safe change; tap frames normally contain no prior overlay since live hides it / first tap has nothing to hide). If repro shows Arabic in tap captures, add the same latch there as a follow-up.
- Crash-loop guard (v0.9.1) unchanged: 2 consecutive startup deaths (<5 s) → Safe Mode banner with trace + Copy + Exit; Live impossible in Safe Mode via three independent gates (Application, MainActivity auto-start, service onCreate/onStartCommand/ensureLiveLoopStarted).

# In-place bubble patch rework — 2026-11-12 (parts 0, A, B, C, D)

One line per decision. Commits: 82e486f (0) → 61c1419 (A) → 3ffeae2 (B) → 4204046 (C) → 2252a44 (D) → afcd3d7 (ignore-list wiring) → 76dcc59 (build safety).

- Part 0 — Live PAUSES while any ManhwaLens activity is visible (AppVisibilityTracker via Application.ActivityLifecycleCallbacks; liveTick gate + patches hidden), so the lens menu / settings can be used without self-capture loops.
- Part 0 — FLAG_SECURE near-black frames skip OCR entirely in BOTH tap and live paths; nothing drawn; trace records SKIPPED for OCR/FILTER/TRANSLATE/OVERLAY.
- Part 0 — User ignore-list semantics (v0.9.3): whole normalized text match anywhere on the frame, OR whole-word match only in short blocks (≤3 tokens, ≤24 chars) whose center sits in the top/bottom edge band; raw substring matching removed — "stop" never eats "stopwatch", "TOP" never eats "top of the tower".
- Part 0 — Progressive paint reports "patches painted = N of M" via new PaintLedger (every painted block id counted once, both ML Kit and Gemini paths).
- Part 0 — PipelineTracer.reset() at doCapture/liveTranslate entry; near-black detection refactored onto pure FrameDarkness grid sampler (JVM-testable).
- Part A — Display model changed from full-frame overlay to PATCH-ONLY: a single full-screen fully transparent PatchOverlayWindow draws only per-bubble Arabic patches; FLAG_NOT_TOUCHABLE|FLAG_NOT_FOCUSABLE; page scroll stays usable underneath.
- Part A — Patches fade in 120 ms / out 80 ms, time-based animation; the captured frame is NEVER drawn or retained by the patch overlay.
- Part A — The captured frame is kept ONLY for Review mode (lastReviewFrame) in the tap path; Live retains nothing (frame recycled right after patch rasterization).
- Part A — BubblePainter builds + upserts patches progressively per block as it lands (N-of-M trace); the old full-frame pushToOverlay path is deleted; tap fallback on failed/empty paint = ONE batch replaceAll.
- Part A — Live: hide-before-capture uses INVISIBLE (patches retained, not cleared); re-translated bubbles swap in place via replaceAll; zero-bubble pass clears patches (reason=page change); own-activity gate clears patches (reason=app visible); allow-list denial likewise.
- Part A — CaptureDebugWindow is demoted to REVIEW MODE only, opened from the new long-press lens menu (Live mode / Arabic patches / Review last capture / Clear patches / Settings); Live never shows it; Live's capture/status panels REMOVED entirely.
- Part A — Lens-menu Arabic switch toggles Arabic patches vs original art (patches stay built; toggle is instant).
- Part A — Battery standby keeps patches on screen (static, GPU-cheap) — no clear on standby.
- Part B — PatchMath (pure Kotlin, 17 JVM tests): pad (6–8 px), max 1.4× per-axis expansion bound (frame-clamped), sampleRing (per-channel median; RMS stddev ≤ 14 ⇒ uniform), floodRegion (bounded grow from the text-box perimeter, stops at dark outline strokes luma<80), buildTextMask (delta-vs-dominant ∪ dark strokes, 2 px dilated), inpaintMasked (iterative neighbour averaging, 256-wave cap).
- Part B — Uniform ring ⇒ solid rounded-rect fill in the median ring colour (2 px feather). Non-uniform ring (gradient/art) ⇒ keep the crop, erase glyphs via mask+inpaint, feather edges into the art (blurred rounded-rect destination-in).
- Part B — Arabic always Cairo-bold RTL ≤4 lines auto-fit; thin opposite-luminance outline on non-uniform/mid-luma backgrounds (needsOutline helper on ColorSampler).
- Part B — Expansion never exceeds 1.4× the text box per axis, and flood never crosses outline strokes — patches stay inside the bubble even for SFX-heavy pages.
- Part C — DiffMask (pure, 13 JVM tests): the frame-diff thumbnail EXCLUDES cells intersecting own Arabic patches (union with the PREVIOUS cycle's patch rects, so a frame captured right after patches disappeared still excludes the just-vacated cells); showing/hiding patches can never read as page change.
- Part C — LiveStabilityTracker.excludeMask + masked stableSameAsAttempted apply the same exclusion to the settle check.
- Part C — On a definite scroll (RowHashShift finds a confident vertical shift ≥1 thumbnail cell), patches RIDE THE PAGE: shiftPatchesBy(dyFramePx) moves every patch; patches fully off-frame are dropped (reason=scroll); NO re-OCR, no flash. Patches that cannot be matched to a shift are cleared with reason=page change instead.
- Part C — Removal reasons are pinned and logged everywhere: `overlay removed: reason=<scroll|page change|user clear|stop|error|app visible>`; scroll vs page change decided by RowHashShift (LiveRemovalReason, unit-tested).
- Part C — Frames captured with patches on screen are NEVER stashed/reused for OCR or the live stash (Arabic would burn into recognition) — recycled in both the Changing and Settling branches.
- Part C — Screen OFF hides patches entirely (never drawn above the lock screen); screen ON restores them. Own-activity visibility clears patches (reason=app visible).
- Part D — AI cleanup is DEFAULT OFF and tap-translate only: never in the Live path; opt-in switch in Settings (disabled without a Gemini key).
- Part D — Only bubble crops (+10 px margin), never the full page; ≤3 largest bubbles by pixel area (LargestBoxes, unit-tested).
- Part D — The image model is picked from the account's LIVE models list filtered to image-capable generateContent models — never a hardcoded id; "Auto" = first image-capable model (cached 30 min per key); user can pin one in Settings ("List image models" button).
- Part D — One 15 s attempt per bubble; any failure keeps the local Part B patch SILENTLY; key travels only in the x-goog-api-key header; list-models errors redact the key; bubble replacement happens IN PLACE (same rect), old bitmap recycled.
- Part D — Privacy policy updated (EN+AR) to disclose the bubble-crop upload when AI cleanup is enabled; default OFF.
- Ignore wiring — Live OCR band now passes `ignorePhraseList()` into BlockFilter.apply exactly like the tap path (afcd3d7); both paths use the SAME filter function and arguments.
- Build safety — gradle.properties `org.gradle.jvmargs=-Xmx1536m`; `android { lint { checkReleaseBuilds = false } }` so NO release lint ever runs on this box; lint is opt-in (`lintDebug --offline`, 600 s cap). lintVitalRelease is retired on this machine (a runaway starved the sandbox 2026-11-12).
- Part E — PatchOverlayWindow reads (`rects`/`hasPatches`/`patchCount`/`isArabicVisible`/`patchRect`) and `shiftPatchesBy` now run synchronously on the main thread via `runOnMainSync` (inline on main; otherwise post + 150 ms CountDownLatch, same pattern as the hide-before-capture latch). Previously `shiftPatchesBy` returned before the posted mutation ran, so the live loop always cleared patches as "page change" instead of letting them ride the scroll; direct background reads of `slots` were racy on the LinkedHashMap. No behavior change for main-thread callers; timeout degrades gracefully (empty/false) and logs. (2877126)

# v0.9.4 — live-loop starvation fix, consent-first tap, lens ON/OFF menu (2026-11-12)

One line per decision. Parts A → E, each committed separately (see `git log`).

## Root causes verified in v0.9.3 code (file:line, pre-change baseline)

- RC1 FRAME STARVATION — `FloatingButtonService.kt:910` (`val frame = acquireLiveBitmap() ?: return 333L`) only fed `liveTracker.onFrame()` when the mirror produced a frame; `acquireLiveBitmap():738-753` returned null on a still screen; `supervise():924` recycled the frame at `:955-957` (Changing) and `:963-965` (Settling) whenever `patchOverlay.hasPatches()` was true — which stays true through the 80 ms fade-out (`PatchOverlayWindow.clearImpl` re-inserts fading slots) and while patches ride a scroll. Nothing was stashed → the stability window could never elapse → `Stable` was unreachable after a scroll. `LiveScenarioTest` fed the tracker directly and never simulated "no new frames", so the suite stayed green.
- RC2 CONSENT ORDERING — `FloatingButtonService.kt:426` read `SettingsPrefs(...).liveMode` BEFORE `:440` checked `mediaProjection != null`; with liveMode persisted true and no projection the tap went to `handleLiveTap():1047` and returned without ever calling `launchConsent()`. `setLiveMode(true):2488` (menu/long-press) never requested consent either.
- RC3 HIDDEN MENU — `FloatingButtonService.kt:2620-2621`: the lens menu opened only on a ≥450 ms long press (`openLensMenu():2477`); a tap never showed any ON/OFF surface.
- RC4 BURNED-IN PATCHES — `FloatingButtonService.kt:1829` hid the overlay and `:1849` called `acquireLiveBitmap()` IMMEDIATELY (before any new frame existed), so the pass analysed a frame that still carried our own Arabic; `freshCapturePossible=false` then let OCR run on it anyway.

## Part A — live loop no longer depends on new frames

- ONE stash, owned by the new pure `LiveLoopDriver` (`live/LiveLoopDriver.kt`): `stashFrame` + `stashGray` + `stashPatchesBurnedIn`. Updated on EVERY acquired frame; only the SUPERSEDED frame is recycled. A frame is never dropped merely because patches exist (that rule from v0.9.3 Part C is reversed on purpose — it was the starvation cause).
- `stashPatchesBurnedIn` = "a patch was visible OR still fading AND the overlay was actually drawn when the frame was captured" → `PatchOverlayWindow.hasVisibleOrFading()` + `isOverlayDrawn()`. A frame captured while the overlay is INVISIBLE is CLEAN even though slots still exist.
- Every tick feeds the tracker: no new frame → reuse `stashGray` (diff 0) so `Changing → Settling → Stable` advances with wall-clock time. A tick is skipped only when there is no thumbnail at all (`FrameOrigin.NONE`).
- Clean-capture protocol on `Stable` + different page: clean stash → hand it over (origin `stash`); otherwise hide the overlay (INVISIBLE, main thread, 150 ms latch) and WAIT for a real new frame on a `ReentrantLock`/`Condition` signalled from `onFrameAvailable()` (`frameSignalSeq`), 400 ms cap → origin `fresh`; on timeout restore the overlay, abort the cycle, retry next tick. A burned-in stash is NEVER handed to OCR.
- `liveLastAttemptedThumb` moved into the driver and is nulled whenever patches are shifted with a scroll or cleared, so the next `Stable` pass is not skipped as "same page". The last TRANSLATED thumbnail still gates re-translation (deliberate: after "Clear translation" the same page must not repaint itself instantly).
- No frame ever within 3 s of a session → recreate the VirtualDisplay ONCE per session (release + `createVirtualDisplay` with the SAME projection, on `captureExecutor`), log `live: no frames …`, and show the caption "No frames yet" (grey ring).
- NEW: the live tick runs on its own `HandlerThread("manhwalens-live")`. The clean-capture wait blocks up to 400 ms, and the ImageReader listener is dispatched on `captureHandler` — blocking that handler would have deadlocked the wait (it would never receive the frame it is waiting for).
- Tap path (`doCapture`) reuses the stash only when CLEAN (`takeStashedFrameIfClean()`); if the stash is burned and patches are up it hides the overlay and waits ≤600 ms for a clean frame before falling back to the first-frame latch. The v0.9.3 unconditional `liveLastBitmap` reuse could OCR our own Arabic.
- Unchanged on purpose: 3 fps tick (333 ms), 400 ms stability window (Settings slider 200–1000), 80 ms patch fade-out / 120 ms fade-in, ignore-list filter (`BlockFilter` + `ignorePhraseList()`), ML Kit EN→AR default engine, region reuse via `RowHashShift`, battery standby after 5 min, foreground allow-list, own-activity pause.
- Safety valve: after 5 consecutive clean-capture timeouts the tick delay backs off from 333 ms to 1 s (battery), and keeps retrying.
- `LiveLoopDriver` is generic over the frame type (`<F : Any>`) and takes a `FrameSource` + `Sink`, so the whole tick decision table is JVM-testable with a fake mirror (no Robolectric — still unavailable offline).

## Part B — consent-first flow

- New pure `LiveAutoStart.tapAction(projectionReady, liveMode, tapMode) → CONSENT | MENU | TRANSLATE_NOW`. `projectionReady == false` ALWAYS returns CONSENT; `liveMode` deliberately does not change the outcome (that independence is the fix, and it is asserted by a test).
- `pendingLiveEnable` remembers a Live-ON request made without a session (tap with persisted liveMode, long-press toggle, menu switch, notification action). When consent lands (`handleConsentToken`), the service sets `liveMode = true`, `liveOptOut = false`, starts the loop and sets status WAITING.
- Persisted `liveMode=true` with no session → status `NO_SESSION`, grey ring, caption "Tap to start" (string changed from "Tap lens first"), applied in `addButton()` because the loop itself only starts once a session is armed.

## Part C — floating icon ON/OFF menu

- Single tap on the lens opens a compact menu ANCHORED NEXT TO the lens (was: long press only). Long press (≥450 ms) now toggles Live directly with haptic feedback + toast. Drag still moves the lens and closes the menu (never opens it).
- Menu rows: header with state ("Live ON"/"Live OFF") + ✕, big Live switch (green when on), Translate now (one-shot, works with Live OFF), Arabic/Original switch, Clear translation, Review last capture (kept from v0.9.3), Stop ManhwaLens (`stopEverything`), Settings. All rows ≥48 dp (Live row 56 dp), EN + AR strings.
- Menu decisions live in the pure `LensMenuModel` (open/close, 4 s inactivity auto-close reset by any touch inside, tap-outside, drag, per-row events) so they are unit-testable; `LensMenuWindow` is view-only.
- The menu window is WRAP_CONTENT at a clamped position (flipped to the other side of the lens when it would overflow; re-clamped once the real height is measured), touchable but NOT focusable, `FLAG_NOT_TOUCH_MODAL | FLAG_WATCH_OUTSIDE_TOUCH` → outside taps close the menu AND still reach the reader (the old full-screen dim backdrop blocked scrolling).
- Lens ring/dot palette: grey = Live OFF or no session, green = idle/waiting, amber = scanning, BLUE = translating (was violet), red = error. `LensButtonView.ringColorFor()` is the single mapping; the glow follows it.
- Settings → "Tap on the lens": Menu (default) | Translate now (old behaviour), persisted as `lens_tap_mode` (String) and added to `SettingsMigration` known STRING keys so a corrupt value resets to Menu.
- The menu rect (screen px → frame px) is added to the diff mask next to the patch rects, and the menu is dismissed when a capture starts, so our own popup can never trigger a cycle. The patch overlay stays `FLAG_NOT_TOUCHABLE`.
- Notification actions: "Live ON"/"Live OFF" (label follows the persisted state; `ACTION_TOGGLE_LIVE` → `setLiveMode`, consent-first) and "Stop". The notification is re-posted whenever Live changes.
- All view/window work goes through `MainThread.run` / `runOnMain` / `mainHandler.post` as before; `stopEverything()` now also stops the loop, dismisses the menu, detaches the patch overlay, hides the pill/panel/review window and releases the driver stash before releasing the projection and stopping the FGS.

## Part D — diagnostics

- `LiveLoopDriver.Diagnostics` (frames/s over a 1 s window, total frames, idle ticks, frame source `new|stash|none`, verdict, diff, ms since last change, patch counts visible/fading, stash `clean|burned|none` (+ thumb-only), clean-capture timeouts, same-page skips) is published every tick.
- The service adds projection/display state (+ whether the VirtualDisplay was recreated), `liveMode`/`liveOptOut`/`pendingLiveEnable`/standby/screenOff/fgAllowed, the last patch-removal reason (`PatchOverlayWindow.lastRemoval()` — reason, count, age), overlay drawn/Arabic flags, translated-thumb/reused-box counts, and the menu close reason + auto-close count.
- The debug panel renders those lines under a `— live loop —` header and refreshes at most 1/s while debug mode is on; the same facts go to `PipelineTracer` spans (`live clean frame`, `live starved`, `live same-page memory`, `live no frames`, `clean capture`, `live patch shift`, `live overlay hidden`, `live decision`).
- `live starved` is logged at most once per second when Changing/Settling persists with no new frames, together with the ms since the last real frame and the stash state.

## Part E — tests, docs, build

- New pure-JVM suites: `LiveLoopDriverTest` (22 tests, fake `FrameSource` + fake clock — including "scroll then NO new frames", patches visible/fading/shifted, burned stash never OCR'd, clean-capture timeout → abort + retry, backoff, blinking cursor, 3 s no-frame watchdog, starvation throttling, diagnostics), `LensMenuModelTest` (14 tests), 5 new `LiveAutoStartTest` cases (the full projection × liveMode × tapMode table), 1 new `LiveScenarioTest` case (tracker advances on a reused thumbnail), 2 new `SettingsMigrationTest` cases (`lens_tap_mode`). Total 283 tests, 0 failures.
- `versionName = "0.9.4"`, `versionCode = 18`.
- MANUAL_TESTS.md rewritten for the Redmi 13C with the 9 checks requested for this build (menu-first tap, ~2 s auto-translate, scroll-then-stop, 15 s still, Live OFF, long-press toggle, Translate now with Live OFF, Stop removes everything, debug panel frames/s + verdict + stash).
- Standing rule (carried forward): if release APKs are uploaded to Dropbox, keep only the newest `ManhwaLens-vX.Y.Z-release.apk` and delete older release copies.
- Build environment note: this sandbox had NO Android SDK and only JDK 11, so JDK 17 (Temurin) was installed to `/opt/jdk-17` and the SDK (platform-tools, platforms;android-36, build-tools;35.0.0) to `/opt/android-sdk`, both OUTSIDE `/home/user` to keep the workspace snapshot small; Gradle cache at `/opt/gradle-cache`; `local.properties` points at `/opt/android-sdk` (git-ignored).

# v0.9.5 — live-loop silent-stop audit, self-check, foreground policy (2026-11-12)

One line per decision. Parts 1 & 2 committed separately (see `git log`).

## Part 1 — per-item audit verdicts

- `FloatingButtonService.kt:2793` — `elapsedMs` on `PageTranslation.Failure`: **confirmed bug at file:line**. `PageTranslation.Failure(reason, message, engineId)` carries no `elapsedMs` (only `Success` does); any telemetry recorder in a Failure branch must time the call itself. This was the only v0.9.5 compile error. Fixed by bracketing `translatePage` with `SystemClock.elapsedRealtime()` and recording the delta in the Failure branch.
- `LiveSelfCheckScreen.kt` — `BuildConfig.VERSION_LABEL`: **checked, not a bug**. `BuildConfig.VERSION_LABEL` is not generated by the build; the service-absent report path builds its own plain text + `LiveLog.recentLines(60)` instead.
- Tap-translate Failure branch (`FloatingButtonService.kt:~2474–2531`): **confirmed bug at file:line**. The Failure branch never called `recordTranslateResult`, so self-check stage 10 ("last translation") missed tap failures entirely (only Success was recorded). Fixed by wiring `recordTranslateResult` into the gemini-failure branch, the offline-fallback success, and the tap-path exception handler (`LiveLog.swallowed`).
- `ensureLiveLoopStarted` early return (`FloatingButtonService.kt:~699`): **confirmed bug** (v0.9.4 root cause, re-audited in Part 1). Returned early when `liveLoopActive`, so `liveDriver.startSession()` never re-armed after a consent round-trip and the 3 s no-frame watchdog could never fire again. Fixed in v0.9.5 Part 1 (re-arm on consent).
- `AppVisibility` counter stuck above 0: **confirmed bug**. An activity destroyed without `onActivityStopped` (HyperOS process restore, translucent consent activity killed mid-transition) left the counter stuck, pausing Live forever with no visible reason. Fixed via `AppVisibility.reconcile()`, `AppVisibilityTracker.isAppVisibleSelfHealing()`, and `onActivityDestroyed` repair.
- `ForegroundAppPolicy` stale foreground event: **checked, not a bug** — behavior changed deliberately. An unknown or stale foreground event never blocks Live; only a FRESH event naming a package NOT in the allow-list does. (`ForegroundAppPolicy.decide`, `STALE_EVENT_MS=10s`, `QUERY_WINDOW_MS=5min`)

## Part 1 — new pure classes & rewritten utilities

- `live/LiveGates.kt` — pure gate chain for `liveTickInner()`: 9 gates (`liveMode|projection|screenOff|appVisible|standby|foregroundApp|captureInFlight|liveJobRunning|none`), `Outcome{RUN,PAUSE,WAIT,STANDBY}`, self-heals for stuck flags, `LiveGateLog` (lastGate ALWAYS updated, rate-limited, per-gate hit counts), `severity()` → OK/WAITING/BLOCKED. No Android types; JVM-testable.
- `live/ForegroundAppPolicy.kt` — pure allow-list policy: empty list → allow; no usage access → allow; no resumed event in 5 min → allow; stale event (>10 s) → allow; listed package → allow; else block with `foreground=<pkg> not in list(...)`.
- `live/LiveLogRing.kt` — 60-entry in-memory ring of Live-relevant log lines, key-scrubbed (`AIza…`, `api[-_ ]?key=…`, `bearer …`), pure JVM.
- `live/LiveSelfCheck.kt` — 12 stages in the user's exact order via `stages(input)`; `overall(stages)`; thresholds `NO_FRAME_FAIL_AFTER_MS=3000`, `STALE_RESULT_MS=60000`; stage 6 severity from `LiveGates.severity(gateNameOf(lastGate))`; `report(...)` → `OVERALL:` + `— stages —` + `— state —` + `— last N log lines —`.
- `util/LiveLog.kt` — logging funnel: every line to logcat AND into the 60-entry `LiveLogRing`; `swallowed(where, t)` ensures no `runCatching`/`catch` in the live path disappears silently.
- `util/AppVisibility.kt` + `util/AppVisibilityTracker.kt` — rewrite: counter + ground-truth activity tracking, `reconcile()`, `isStuck()`, `healCounter()`, self-healing reads, idempotent `register`.

## Part 1 — FloatingButtonService.kt changes

- `liveTickInner()` gate-chain replaced by `LiveGates.evaluate()`; outcome applied via `standbyProbeTick()` (STANDBY), `onLivePaused()` (PAUSE), or `liveDriver.tick()` (RUN).
- Watchdogs: `captureInFlight` (>30 s → heal), `liveJobRunning` (>20 s → heal); rejected-task guards.
- Android 14+ single-VirtualDisplay rule: one `createVirtualDisplay` per session, `requestFreshProjection()` on recreate, projection `onStop` cleanup.
- Consent replay: `pendingTapMode` + `pendingLiveEnableOnTap` replayed after consent lands (`afterConsent`).
- Self-check providers: `liveSelfCheckInput()` (L1612), `liveSelfCheckExtraLines()` (L1656), `liveSelfCheckReport()` (L1662), `liveStatusLine()` (L1730), `liveDebugStatusLine()` (L1794), `readOwnLogcat(60)`.
- Telemetry recorders: `recordOcrResult`, `recordTranslateResult(engine,ms,error)`, `recordPaintResult(painted,expected)`; tap-path Failure branch now records ms + error.
- `@Volatile var instance` (L76) for the self-check screen to read service state.
- Consts: `FOREGROUND_RECHECK_MS=2000`, `STANDBY_AFTER_MS=300000`.

## Part 2 — UI

- `ui/settings/LiveSelfCheckScreen.kt` — reads `FloatingButtonService.instance`, polls every 1 s, colors PASS `0xFF4ADE80` / FAIL `0xFFFF6E6E` / WAIT `0xFFFFC45E`; report dialog with `ClipboardManager` + `Toast(R.string.self_check_copied)`; graceful "service not running" fallback.
- `SettingsScreen.kt` — `showLiveSelfCheck` state (L64/72–73), `LiveSelfCheckScreen` full-screen swap, Diagnostics summary text + `OutlinedButton(R.string.self_check_button)` above the benchmark summary.
- `LensMenuWindow.kt` — "Live status" line (`statusLine` TextView after the title row, `liveStatusProvider`, `statusRefresher` 1 s beat, public `refreshLiveStatus()`, `toggle(..., liveStatus: (() -> String)? = null)`, `STATUS_REFRESH_MS=1_000L`); service passes `liveStatus = { liveStatusLine() }` at L3396.
- `res/values/strings.xml` + `res/values-ar/strings.xml` — 9 EN + 9 AR strings (`self_check_*` ×8 + `lens_menu_status_unknown`) inserted before `debug_panel_title`.
- 18 `guard(...)` sites in `liveTickInner` + `Sink.onSwallowed(where,error)`.

## Part 4 — tests

- New pure-JVM end-to-end simulation `LiveEndToEndSimulationTest.kt` (47 tests): LiveGates gate chain + `LiveGateLog` + `severity`, `ForegroundAppPolicy.decide` full table, `LiveAutoStart` (`tapAction`/`pendingLiveEnableOnTap`/`afterConsent`/`showsNoSession`), `LiveSelfCheck` (12-stage order, `overall`, `gateNameOf`, report format + key scrubbing), `LiveLogRing` (add/snapshot/capacity/scrub/clear), `AppVisibility` (transitions, stuck, reconcile), end-to-end gate→self-check scenario.
- Total 330 tests, 0 failures (`:app:testDebugUnitTest`).

- `versionName = "0.9.5"`, `versionCode = 19`.

## v0.9.6 (2026-11-12) — anti-self-translation, clean capture, opaque patches

Part 1 (OwnedWindows + OcrGarbageClassifier): CONFIRMED — own windows (lens caption "Watching/Scanning/مشاهدة", menu, pill, debug panel) were being OCR'd and translated, producing Latin garbage ("NHRAIP GAI PNG") from burned-in Arabic. Fix: hide all owned windows before every capture; await real new frame (400ms); post-OCR drop blocks overlapping our rects (current + grace-removed within 1s) or matching our UI strings (EN+AR); OCR garbage classifier for consonant-heavy all-caps noise. TESTED: OwnedWindowsTest (6), OcrGarbageClassifierTest (10).

Part 2 (one-shot parity): CONFIRMED — tap capture used a separate path (firstFrameLatch fallback) that could OCR frames with patches still visible. Fix: doCapture uses the same clean-capture routine as Live (hide → awaitNewFrame(400ms) → abort on timeout); retry once after longer hide (1200ms) if OCR output looks cut (CaptureQC.looksCut). TESTED: CaptureQCTest (6).

Part 3 (Live pauses on own screens): CONFIRMED — Live kept translating when user was inside ManhwaLens screens (Settings, consent). Fix: LiveGates.Input gains foregroundPackage; pauses with GATE_APP_VISIBLE/"own app visible" when foreground == com.manhwalens.app; translation skipped entirely for own app. TESTED: LiveGatesTest (6).

Part 4 (opaque full-coverage patches): CONFIRMED — English visible behind Arabic (patch didn't cover whole OCR box; text centered not right-aligned). Fix: PAD_PX 7→5 (4-6px spec); Arabic uses ALIGN_NORMAL (right-aligned); alpha-255 center + 2px feather already in place; fill = ring median. TESTED: PatchMathTest (8) — box coverage >= 100%.

Part 5 (don't translate UI chrome): CONFIRMED — "Episode 1", URL bar, "#2", "650" translated. Fix: ignoreBottomPct 8→13 (nav bar + 160px @720x1600); BlockFilter gains minHeightPx+minWordCount to drop non-bubble tiny boxes. TESTED: BlockFilterTest +8 with exact trace strings.

Part 6 (translation quality): CONFIRMED — ML Kit default even when Gemini key exists. Fix: TranslationPrefs.engine defaults to GEMINI when key exists (unless explicitly set); SYSTEM_INSTRUCTION updated to Modern Standard Arabic, short lines, keep names. All bubbles sent in ONE request (already batched). TESTED: GeminiWireTest +2.

Part 7 (diagnostics): CONFIRMED — debug panel could appear in captures; no drop-reason visibility. Fix: debug-panel rect added to diff mask (computeThumbMask); ocrDropsByReason + captureHideWaitMs exposed in liveDebugLines().

Part 8: versionCode 20 / versionName "0.9.6"; this DECISIONS.md section; MANUAL_TESTS.md updated with 4 prescribed steps.

Build: :app:compileDebugKotlin + :app:testDebugUnitTest BUILD SUCCESSFUL; assembleDebug BUILD SUCCESSFUL; APK at apk/ManhwaLens-v0.9.6-debug.apk.

Bug fixes (committed a9a72eb):

- Patches disappear after ~1 sec — CONFIRMED root cause: `patchOverlay?.clearPatches("stop")` was called in `setLiveMode(false)`, fading out all patches when Live was disabled. Fix: removed the `clearPatches("stop")` call from `setLiveMode(false)` in `FloatingButtonService.kt`. Patches now persist until the user explicitly clears them. — Applied, compiled, tested, committed.
- Debug panel has no visible close button — CONFIRMED: only "(tap to close)" text, no X button. Fix: replaced full-panel `clickable { onDismiss() }` with a `Row` containing title text + visible `✕` close button (`Text("✕")` in clickable `Box`). — Applied, compiled, tested, committed.
- Debug panel flashes briefly with every translation — CONFIRMED: `hideOverlayAndAwaitCleanFrame()` hid the debug panel during every capture cycle, then `onDiagnostics()` re-showed it every second → flash effect. Fix: removed the debug panel overlay entirely; it no longer appears as a floating window. — Applied, compiled, tested, committed.
- Debug panel gets Arabic patches overlaid on it — CONFIRMED: PatchOverlayWindow draws on top of DebugPanelWindow. Fix: removed the debug panel overlay entirely (no window = no overlay conflict). — Applied, compiled, tested, committed.
- Settings UI translated ("#2 Brave", "#3 Back", "#5 Episode 1" shown as patches over Settings screen) — CONFIRMED: both reader and Settings are the same app, so app-visible gating doesn't fire between them; stale patches remain visible over Settings. Fix: `patchOverlay?.clearPatches("settings")` in `openSettingsScreen()` before launching SettingsActivity. — Applied, compiled, tested, committed.
- Translation should be continuous sentences per bubble — CONFIRMED: Gemini system instruction rule (6) said "one bubble, one line" which fragmented text. Fix: replaced rule (6) with "Each bubble's translation must be ONE continuous sentence — even if the Arabic wraps across multiple visual lines, do not split one sentence into fragments". Updated `GeminiWireTest.systemInstructionRequestsModernStandardArabic` accordingly. — Applied, compiled, tested, committed.
- Debug panel not wanted on screen — CONFIRMED: user doesn't want the debug panel floating overlay. Fix: removed `DebugPanelWindow` from `FloatingButtonService.kt` entirely (all `debugPanel?.show()` calls, `maybeShowLiveDebugPanel()`, `debugPanel` field, debug-panel rect from `registerOwnedWindows()` and `computeThumbMask()`). Added "Last pipeline trace" section in SettingsScreen that reads `ErrorLog.read(context)` (where `PipelineTracer.persistToErrorLog` writes the trace) with a Copy button. Added string resources (EN + AR) for the section. — Applied, compiled, tested, committed.
- Floating lens icon needs Run/Stop buttons — CONFIRMED: user wants explicit "تشغيل" (Run/Start Live) and "إيقاف" (Stop/Disable Live) buttons when tapping the floating lens. Fix: replaced the Live mode `Switch` in `LensMenuWindow` with two prominent `TextView` buttons: "تشغيل" (green, shown when Live OFF) and "إيقاف" (red, shown when Live ON). Both call `onToggleLive()` → `setLiveMode()`. Added string resources (EN + AR). — Applied, compiled, tested, committed.

# Environment: JDK 17 (2026-11-12)

- No JDK 17 existed anywhere on the box (searched /usr/lib/jvm, /opt, /usr/local, ~/.sdkman, ~/.gradle, /opt/gradle-cache, find / -maxdepth 4). Only JDK 11 at /usr/lib/jvm/jdk-11.
- Network confirmed available: github.com (HTTP 200), api.adoptium.net (HTTP 200), download.oracle.com (HTTP 301). No proxy variables set.
- /opt is root-owned and not writable; fell back to $HOME/jdk-17 per step 3 fallback.
- Downloaded Temurin 17 x86_64 tarball from https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse (184 MB, valid gzip).
- Extracted with tar --strip-components=1 into /home/user/jdk-17. Verified: java -version → 17.0.21, javac -version → 17.0.21.
- Created /home/user/ManhwaLens/env-build.sh (added to .git/info/exclude, never committed) with JAVA_HOME=/home/user/jdk-17, GRADLE_USER_HOME=/home/user/gradle-cache, ANDROID_HOME=/opt/android-sdk.
- /opt/gradle-cache not writable (root-owned /opt); using /home/user/gradle-cache instead.
- Verified: ./gradlew --no-daemon --version → Launcher JVM: 17.0.21 (Eclipse Adoptium 17.0.21+9), Daemon JVM: /home/user/jdk-17.

- Build results (JDK 17, Temurin 17.0.21):
  - `:app:testDebugUnitTest` → BUILD SUCCESSFUL (47s), all tests pass
  - `:app:assembleDebug` → BUILD SUCCESSFUL (2m 28s) with `-Xmx800m -XX:MaxMetaspaceSize=256m` (`-Xmx1200m` OOM-killed at `:app:mergeExtDexDebug` on 1.9GB box)
  - APK: `/home/user/ManhwaLens/apk/ManhwaLens-v0.9.6-debug.apk` (62,686,066 bytes)
  - `local.properties` updated: `sdk.dir=/home/user/android-sdk`
  - Android SDK installed at `/home/user/android-sdk` (platforms;android-36, build-tools;35.0.0, platform-tools)

# PART 3 — Speculative OCR + adaptive stability window — 2026-11-12

- Speculative OCR: when the tracker enters `Settling`, the driver kicks off background OCR on the clean stashed frame via `Sink.onSpeculativeOcr(frame, gray, hash)`. If the next tick is `Stable` on the same frame hash, `Sink.onSpeculativeStable(hash)` is called and OCR is skipped (the result was already computed during the Settling window). If the verdict returns to `Changing`, `Sink.cancelSpeculativeOcr()` discards the speculative job. Speculative frames are never painted from a burned-in patch frame (gate: `!stashPatchesBurnedIn`).
- Adaptive stability window: slow scroll (diff ≤ 5) → 250 ms; fast scroll (diff ≥ 15) → 400 ms; default window (400 ms) respected as-is when the user sets a non-default `stableWindowMs`. Applied only in `LiveStabilityTracker.onFrame()`; explicit user overrides bypass the adaptation.
- `LiveStabilityTracker` gains `SLOW_DIFF_MAX = 5f`, `FAST_DIFF_MIN = 15f`, `ScrollSpeed` enum; `thumbnailHash()` FNV-1a hash for speculative frame matching.
- `LiveLoopDriver.Sink` gains `onSpeculativeOcr(frame, gray, hash)`, `cancelSpeculativeOcr()`, `onSpeculativeStable(hash)`.
- `FloatingButtonService.LiveDriverSink` implements speculative OCR on the ioExecutor (OCR + filter + group into TranslationBlocks, cached by hash) and `onSpeculativeStable` (logs hit/miss, clears cache).
- Tests: `LiveScenarioTest.noNewFrames_reusingTheStashedThumbnailStillReachesStable` updated — with adaptive window, reusing the same stashed thumbnail (diff 0 → slow → 250 ms) reaches Stable in one tick (333 ms > 250 ms), so Settling is not observed; test now expects Stable directly. `LiveLoopDriverTest.scrollThenScreenGoesStill_reachesStableAndTranslatesWithinWindowPlusOneTick` idle-tick assertion relaxed from ≥2 to ≥1 (250 ms window < 333 ms tick).
- Blinking-cursor test (`staticPageWithBlinkingCursor_translatesOnceAndNeverRetriggers`) remains green — cursor diff (~0.36) stays well below the 10f change threshold.

# PART 4 — Two-phase translation — 2026-11-12

- Phase 1 (ML Kit immediate paint): `TranslatorFactory.createProgressive()` runs ML Kit EN→AR on the tap path, painting each bubble via `BubblePainter.onBlockReady()` as it lands. Priority order: short (<80 chars) → largest bubble area → most central.
- Phase 2 (Gemini replacement): `relayGeminiTranslation()` runs IN PARALLEL with Phase 1 via `ioExecutor`. On arrival, each bubble is replaced in place via `BubblePainter.onGemini()` with a 60 ms cross-fade (≤120 ms requirement). Never clears patches between phases. If Gemini fails/garbage, Phase 1 ML Kit results stay untouched silently.
- Gemini: Flash model (`gemini-3.8-flash`), thinking disabled (`thinking_budget: 0`), compact JSON array output `[{"id":N,"ar":"..."}]` same order as input, one request for all bubbles + last 2 translated lines from previous page as context (CTX: prefix, not translated). Circuit breaker (5 min), adaptive timeout, and cache (memory + disk LRU) all retained.
- `Translator.translatePage(blocks, previousContext)` interface — `previousContext` defaults to empty. `CachingTranslator` passes it through to the delegate. `GeminiTranslator` includes it in the prompt as CTX lines.
- `GeminiWire.buildRequestBody()` adds `"thinkingConfig":{"thinking_budget":0}` to `generationConfig`. `userPrompt()` emits compact JSON array. `parseJsonArray()` replaces `parseNumberedLines()`; malformed output → null → caller keeps Phase 1 silently.
- `FloatingButtonService.lastTranslatedLines` tracks the last 2 Arabic lines from the previous page for Gemini context; updated after each successful Gemini relay.
- Tests: `GeminiWireTest` rewritten for JSON array format (thinking_budget, compact output, context lines, malformed→null). `GeminiTranslatorTest.okBody()` generates JSON array responses. `blankBlocksAreNeverSentToTheApi` checks for `"id":N` instead of `[N]`.

# Final Build Results (2026-11-13)

- `:app:testDebugUnitTest` → BUILD SUCCESSFUL — **358 tests, all green** (was 355 before Parts 3-4 added 3 new test classes/methods)
- `:app:assembleDebug` → BUILD SUCCESSFUL — APK: `/home/user/ManhwaLens/apk/ManhwaLens-v0.9.6-debug.apk` (62,686,938 bytes)
- versionCode 20 / versionName "0.9.6" · Git tree clean at HEAD `d442c7b`
# ManhwaLens — Translation Quality Decisions

Records architectural decisions made during the Arabic translation quality
improvement work (Parts 1–6). Each entry: decision, rationale, consequences.

---

## Part 1: OCR Text Cleaner (OcrTextCleaner.kt)

### D1: Pure Kotlin, no Android types
**Decision:** `OcrTextCleaner` is pure Kotlin/JVM — no Android types, no Context,
no Resources. All geometry uses `Rect2D` (custom value class).

**Rationale:** Unit-testable on the JVM without Robolectric or Android emulator.
The cleaner must run in unit tests during CI.

**Consequence:** Callers (bubble grouper, pipeline) must adapt Android `Rect`
to `Rect2D` at the boundary.

---

### D2: Hyphen removal before all other cleaning
**Decision:** Hyphen line-breaks (`YOUNG-\nER` → `YOUNGER`) are removed first,
before whitespace collapse, OCR fixes, caps normalization.

**Rationale:** Hyphen-joined fragments must be whole words before confusion
fixes (e.g. `he-11o` should become `hello`, not `he-llo`).

**Consequence:** A `hyphenRemoved` flag is tracked and passed to cut detection
so intentionally joined words aren't falsely flagged as cut.

---

### D3: ALL-CAPS only sentence-cased when ending with sentence punctuation
**Decision:** `normalizeCaps()` only converts ALL-CAPS to sentence case when the
text ends with `.`, `!`, `?`, or `…`. Standalone ALL-CAPS words (e.g. `YOUNGER`,
`NASA`) are preserved.

**Rationale:** Comic text uses ALL-CAPS for emphasis, not just shouting.
Sentence-ending punctuation indicates a complete sentence.

**Consequence:** `YES, WE WERE MUCH YOUNGER THEN.` → `Yes, we were much younger then.`
But `YOUNGER` stays `YOUNGER`.

---

### D4: SFX classification checks raw OCR text
**Decision:** `classifyNonDialogue()` checks SFX patterns against both the
cleaned text AND the raw OCR text (uppercased).

**Rationale:** `normalizeCaps()` converts `KRAK!` → `Krak!`, which no longer
matches all-caps SFX patterns. Checking raw text preserves SFX detection.

**Consequence:** SFX patterns use raw text; cleaned text is used for everything
else.

---

## Part 2: Gemini Quality Engine

### D5: Three quality tiers (FAST/BALANCED/BEST)
**Decision:** `TranslationQuality` enum maps to model + temperature + tokens:
- FAST: gemini-3.5-flash-lite, temp 0.2, 4096 tokens
- BALANCED: gemini-3.8-flash, temp 0.3, 8192 tokens (default)
- BEST: gemini-3.8-flash, temp 0.2, 16384 tokens

**Rationale:** Users need to trade latency/cost for quality. Simple dialogue
doesn't need BEST; complex drama does.

**Consequence:** Settings card has a quality dropdown. The model is selected by
quality tier (not a separate model dropdown). The old `geminiModel` pref is kept
for backward compatibility but quality takes precedence.

---

### D6: Page context in prompt (last 6 lines + glossary + title/genre)
**Decision:** `PageContext` (title, genre, previousLines, glossary) is included
in the user prompt for BALANCED/BEST tiers, skipped for FAST.

**Rationale:** Cross-bubble context (pronouns, names) improves translation
consistency. Last 6 lines balances context vs. prompt size.

**Consequence:** `GeminiWire.userPrompt()` accepts `PageContext`. `PageContext`
is set per-page by the caller before `translatePage()`.

---

### D7: System instruction as quality-aware constant
**Decision:** `systemInstruction(quality)` builds the system prompt dynamically
based on quality tier. FAST is lean; BALANCED/BEST include glossary/context
guidance.

**Rationale:** Different quality tiers need different instructions. BEST gets
explicit glossary enforcement.

**Consequence:** The system instruction is no longer a single static string;
it's generated per-request based on quality.

---

### D8: Vision images as optional inlineData parts
**Decision:** `buildRequestBody()` accepts optional `visionImages` (list of
mimeType+base64 pairs) added as inlineData parts after the text prompt.

**Rationale:** Optional vision context (page crop) helps with layout-aware
translation without requiring it for every call.

**Consequence:** Vision support is additive; existing callers without images
are unaffected.

---

## Part 3: Glossary + Settings

### D9: Glossary stored as "English=Arabic" lines in SharedPreferences
**Decision:** `TranslationPrefs.glossary` stores newline-separated
`EnglishName=ArabicName` pairs, max 2000 chars.

**Rationale:** Simple, human-editable format. Flows into `PageContext.glossary`
for Gemini prompts. Per-series glossary support.

**Consequence:** Glossary settings screen is a text field with term count
display and clear button. Auto-detect names (Part 3) is future work.

---

### D10: Quality tier selector in Gemini settings card
**Decision:** `TranslationEngineCard` has a dropdown for FAST/BALANCED/BEST,
bound to `prefs.translationQuality`.

**Rationale:** Users choose quality without leaving the translation settings.

**Consequence:** The dropdown shows the current quality; selecting one saves to
prefs and affects the next translation.

---

## Part 4: Validation, Retry, Fallback, RTL

### D11: Pure validator with 5 checks
**Decision:** `ArabicValidator` checks: empty output, Arabic script, length
ratio (0.35–2.0x), garbage (repeated chars/control chars), English leftover
(2+ consecutive Latin letters).

**Rationale:** Catches the most common failure modes: no translation, wrong
script, truncated output, garbage, untranslated words.

**Consequence:** `Failure` enum categorizes failures for retry/fallback
decisions. `validatePage()` checks all blocks in a page.

---

### D12: Retry once with BEST, then fall back to ML Kit
**Decision:** `TranslationPipeline` validates primary output; if invalid and
primary was Gemini, retries once with the same translator (quality from prefs).
If still invalid or transport failure, falls back to ML Kit.

**Rationale:** One retry balances latency vs. quality. ML Kit is the safety net.

**Consequence:** BEST-quality retries require the user to set quality=BEST in
Settings. The pipeline doesn't dynamically upgrade quality on retry.

---

### D13: Arabic RTL post-processing
**Decision:** `ArabicPostProcessor` removes invisible zero-width characters,
trims whitespace, and prepends RTL mark (U+200F) for Arabic-starting text.

**Rationale:** Android TextView handles Arabic shaping; the post-processor
cleans up edge cases that affect RTL display.

**Consequence:** Applied to all successful translation outputs in the pipeline.

---

## Part 6: Build

### D14: No minification on this box
**Decision:** Release builds on this sandbox box do NOT run R8 minification
(OOM risk on 2 vCPU / 1.9 GB RAM). `lintVitalRelease` is disabled.

**Rationale:** The build box is memory-constrained. Minification and lint
starve the sandbox.

**Consequence:** `./gradlew :app:assembleRelease` works without minify. On
>=4 GB machines, use `-Prelease.minify=true`.

---

### D15: JDK 17 re-provisioned
**Decision:** `/home/user/jdk-17` was invalid (missing `lib/server/libjvm.so`).
Re-downloaded Temurin 17.0.21 from Adoptium API.

**Rationale:** The original JDK extraction was incomplete.

**Consequence:** `JAVA_HOME=/home/user/jdk-17` is now valid. Build uses
`--no-daemon --max-workers=1` with `-Xmx800m -XX:MaxMetaspaceSize=256m`.

---

# v0.9.8 (2026-11-13) — icon menu on the lens + "translation does nothing" fixes

Built from the Dropbox 0.9.7 tree. NOTE: several features described earlier in this file
(OcrTextCleaner, quality tiers, glossary, validator/golden set, speculative OCR, JSON-array
Gemini wire format) are NOT in the code that was archived — treat them as not implemented.

## Lens menu → icon strip
- `LensMenuWindow.show()` now builds a compact pill of round Canvas-drawn icons attached to the
  lens: Live (play/stop) · Capture · Clear (only while patches are up) · Exit. Tiny captions,
  localized (`lens_icon_*`). Arabic/Original switch, Review and Settings rows were removed from
  the floating menu (Settings stays reachable from the app's main screen). `LensMenuModel` and
  its tests are unchanged; the Live icon dismisses the menu right after toggling.

## Translation not appearing — root causes found by code reading (not device-verified)
1. Tap capture aborted on every static page: it demanded a NEW mirror frame within 400 ms
   (150 ms with patches up), but a still page produces no frames. New `acquireTapFrame()`:
   hide our windows → wait for the post-hide frame only if something was hidden (or the menu
   closed <500 ms ago) → otherwise use the newest buffered frame. First-frame wait back to 1.5 s.
2. OCR blocks were vetoed by STALE rects: `OwnedWindows` kept every rect registered at the
   last hide forever (old patches, the menu, the pill), and `patchOverlay.rects()` vetoed any
   text under an existing patch even though frames are captured with patches hidden. A second
   capture therefore dropped most/all bubbles and then cleared the old patches. Now only the
   lens-button rect (plus the just-hidden rects when the frame is not provably clean) is used.
3. The "Capturing…" pill was shown BEFORE the frame was grabbed (it ended up in the frame).
4. Gemini: 3 s connect / 6 s read timeouts with the model's default "medium" thinking → frequent
   TIMEOUT → circuit breaker disabled Gemini for 5 min on ANY failure, and changing engine/key/
   model in Settings never cleared it. Now: 10 s / 30 s, `thinkingConfig.thinkingLevel = "low"`
   (one retry without it on HTTP 400), 30 s pause for transient failures (5 min only for bad
   key / quota / unknown model), Settings changes clear the pause, a reply missing fewer than
   half of the blocks is accepted as partial.
5. Live: a failed translation was silent AND marked the page as done. Now falls back to ML Kit
   when Gemini fails, shows a toast with the reason (≤ 1 per 15 s) and retries after 6 s.
6. OCR input max dimension back to 1600 (1200 shrank 720-px frames by 25 % — small bubble text).
7. The "looks cut" OCR re-capture is disabled (1.2 s stall on static pages, never succeeded).

Build: `:app:testDebugUnitTest` 357 tests green; `:app:assembleDebug` OK; versionCode 22 / 0.9.8.

# v0.9.9 (2026-11-13) — ML Kit batching: stop the double cost, prove the separator, keep it hot

Device evidence (Redmi 13C, ML Kit EN→AR, 10 bubbles, one run each): sequential 608 ms OK ·
parallel x3 483 ms OK · joined "@@@" 322 ms FAIL (split mismatch) · batches of 5 477 ms FAIL.
The 0.9.8 default for non-progressive pages (Live, offline fallback) was joined "@@@" → on
mismatch per-block SEQUENTIAL, i.e. ~322 + ~608 ≈ 930 ms per page on that device.

Code reading first — what was already true and was therefore NOT changed:
- The TAP path already translated per block, 3 in flight, painting each bubble as it landed,
  and already launched Gemini before the ML Kit pass. Only its thread was wrong (D9).
- The ML Kit client was already a process-wide singleton.
- There is no screen called "Timeline" in this source. "Timeline" is implemented as the
  pipeline trace spans (Settings → Last pipeline trace / Copy report). (D10)

D1. Release default = parallel per block, 3 in flight, for EVERY ML Kit page. The joined call
    is reachable only through `BatchPolicy.current()`, which returns a separator only after a
    self-test or the benchmark proved it on this device. Fallback when a parallel call fails:
    sequential per block (was: a joined "@@@" call — the very thing that fails).
D2. Failure memory = `BatchPolicy.noteJoinedFailure`: a session flag (one policy instance per
    process) + `batch_joined_blocked_until = now + 7 d` in `manhwalens_translation` prefs, and
    the stored choice is erased so it must be re-proven after the ban. A joined call that
    throws (engine error) is NOT a mismatch and is not remembered.
D3. Splitter = pure `BatchSplitter` + `SplitStrategy` (@@@, \n, \n\n, " | ", ###, [1], <1>, •).
    `PageBatch` (count-only check) is no longer used by the app; left in place with its tests.
D4. Validation deviations from the literal spec, both to avoid false FAILs:
    (a) "has Arabic letters" is required only when the source has letters ("…?!", "100%").
    (b) the 4x upper bound uses max(sourceLength, 4) as its base ("No" → "لا أريد" is valid).
    Sources are flattened to one line before joining (inner newlines would collide with the
    line-based separators).
D5. "Mismatched segments" = |got − expected| when the count differs, otherwise the number of
    segments failing a rule. Numbered markers must read 1..n in order; Arabic-Indic digits ok.
D6. Self-test (`BatchAutoSelect`): 4 short strings × 8 separators × 2 runs (both must map).
    Two additions beyond the spec, both in the direction of "joined only after PROVEN":
    (a) the winner must also map ONE 10-bubble call — the size at which "@@@" failed on the
        device; otherwise the next-fastest is tried;
    (b) if parallel x3 on the same strings is at least as fast, parallel stays.
    It runs on a background thread after pre-warm (or after the first page); pages use
    parallel until a choice exists, so no page waits for it. Stored per device
    (`Build.FINGERPRINT`) and re-proven after 7 days (ML Kit models update silently).
D7. Benchmark: 20 bubbles, 5 passes, first discarded, median + worst, sequential + parallel
    2/3/4/6 + every separator, table with map OK/FAIL and the worst mismatch count. A joined
    method is OK only if EVERY measured pass maps. Its verdict is written to the policy (it is
    stronger evidence than the self-test) unless a 7-day ban is active; if the separator
    currently in use fails in the benchmark, that counts as a runtime failure. It now uses the
    shared pre-warmed client instead of creating a second engine. ~1 minute on a slow phone.
    The parallel LEVEL stays 3 at runtime — the benchmark reports 2/3/4/6 so the developer
    can decide from real numbers; no evidence yet that another level is better.
D8. "Copy report": the benchmark dialog keeps its own copy button, and the last benchmark
    table (file `benchmark-last.txt`), the batching choice, the self-test result and the warm
    state are appended to the self-check "Copy report" (also when the service is not running).
    The existing `BenchmarkRunnerTest` asserted the old 4-method/echo-engine contract and was
    rewritten for the new one; all other existing tests are untouched and green.
D9. Pre-warm: `MlKitTranslator.warmUp()` now also runs a dummy "hello" translation (model in
    memory, not just on disk); `keepWarm("screen on")` repeats it on ACTION_SCREEN_ON.
    `MlKitWarmth` keeps cold vs warm timings; every ML Kit page writes a "mlkit warm state"
    span. Gemini requests moved from the single-thread `ioExecutor` (shared with pre-warm —
    a 30 s model download delayed Gemini by 30 s) to their own 2-thread executor.
D10. Live + Gemini key: Gemini and ML Kit now start at the same moment. ML Kit's draft is
    painted as soon as it is ready; Gemini's text is re-rendered and swapped in place
    (`replaceBitmap`, no re-fade). Live blocks on Gemini only if the draft itself failed.
    If Gemini is already back when the draft finishes (cache), it is painted once.
    `PatchOverlayWindow.contentEpoch()` guards the swap: if the patch set was replaced or
    cleared meanwhile, nothing is painted and the text is kept for the next pass (translation
    cache + reused boxes updated by English text). Scroll shifts do not invalidate the swap.
    Trace spans: "live phase 1 (ML Kit)", "live phase 2 (Gemini)", "live phases",
    and for taps "phase 1 / phase 2" — both durations side by side.
    Cost: the frame bitmap lives until Gemini answers (one frame, ≤ ~5 MB, recycled after).

NOT verified on a device: everything above is covered by unit tests where it is pure logic
(399 tests green) and by a clean build, but the Live two-phase swap, the screen-on warm-up and
the real separator behaviour of ML Kit can only be confirmed with the MANUAL_TESTS v0.9.9 run.

# v0.9.10 (2026-11-13) — Live had a projection but no mirror (device self-check, v0.9.9)

Device report: stage 2 FAIL "projection active but NO virtual display/ImageReader",
`display=none recreated=yes`, frames total=0, log: consent granted with
`enableLive=false openMenu=true`, then "live enabled (menu switch)".

Cause (code): the VirtualDisplay + ImageReader were created ONLY inside `doCapture()` (a tap
capture). Consent → menu → Live ON never ran a capture, so the loop ticked with nothing to
watch. The no-frame watchdog then hit its `surface == null → "cannot create a display"` branch
after setting `virtualDisplayRecreated = true`, i.e. it gave up permanently.

Fix: `armCaptureSessionAsync()` creates the session on `captureExecutor` (same single thread as
the tap capture, so no double creation) and is called from `ensureLiveLoopStarted()` — every
path that starts/re-arms the loop with a projection. The watchdog's dead-end branch now
creates the session too. Android 14+ single-display rule is respected (no second create on a
used token; that case still goes to fresh consent).

Also seen in that report, and expected: stage 6 "paused (ManhwaLens UI visible)" — Live pauses
while the app's own Settings screen is in front. The self-test picked joined "•"
(151 ms vs parallel 259 ms on 4 strings, passed the 10-bubble check); the benchmark has not
been run yet. Not device-verified.

# v0.9.11 (2026-11-13) — device trace + benchmark (Redmi 13C, v0.9.10)

Evidence: tap trace `CAPTURE 720x1600 black=true (FLAG_SECURE?)` → whole pipeline skipped.
Benchmark (20 bubbles, median): sequential 1022 · x2 950 · x3 1254 · x4 1624 · x6 1426 ·
joined • 714 OK · joined | ### [1] <1> FAIL by one segment "#3 no Arabic letters" ("KRAK") ·
@@@ \n \n\n FAIL 19 (separators eaten). Runtime: "•" banned for 7 days by
"#2 no Arabic letters" on a real page.

E1. Black-frame test was a 3×3 sample with "8 of 9 darker than luma 32" — any dark-themed page
    or dark panel qualifies, and the capture is then dropped. Now: 40×40 samples over the
    middle 84 % of the frame, protected only if ≤ 0.3 % are brighter than 6/255. Reasoning: a
    false "not black" costs one empty OCR pass; a false "black" costs the whole translation.
    The trace now prints the sample count. Whether the user's frame was a dark page or a truly
    protected app is NOT known — the next trace will show it.
E2. If a frame is still pure black, the mirror is restarted once (setSurface(null) → surface,
    the watchdog's legal restart) and a fresh frame awaited ≤ 700 ms — covers a stale black
    first frame on a still page, which became possible when v0.9.10 armed the session early.
E3. "No Arabic letters" is no longer a mismatch when the segment is the source itself
    (untranslated SFX/name): per-block calls return the same text, the mapping is intact.
    This was a false FAIL of my own rule (D4) on four separators and caused the runtime ban.
E4. `BatchPolicy` rules version 2: bans/choices made under the old rules are erased on first
    start, so the device re-runs the self-test instead of sitting out a 7-day false ban.
E5. Parallel level default 3 → 2 (measured best; ML Kit does not scale with callers — x3 is
    slower than sequential here). The benchmark now stores the best per-block level and the
    runtime uses it.
Not device-verified.

# v0.9.12 (2026-11-13) — screenshots from v0.9.11 + "the Exit button does not work"

Evidence: v0.9.11 translates in Brave (black-frame fix confirmed, self-check stage 2 PASS).
Screenshots show (a) Exit does nothing, (b) tiny patches "توقف / التقاط / واضح" and
"Capturehe Li Exit Scanning" = our own menu labels and caption translated, (c) patches from an
earlier page still on screen after scrolling and over the launcher, with Live OFF.

F1. Menu icons Capture / Clear / Exit were dead: the model emits [Hidden, action]; Hidden ran
    teardown(), which nulled `callbacks`, so the action was dropped (bug since the v0.9.8 strip).
    `runEvents` now holds the callbacks for the batch.
F2. Exit also sets `prefs.enabled = false` and removes the lens at once — otherwise opening the
    app re-started the service. Re-enable from the app's main switch.
F3. Live OCR had no own-UI filter (it reused the last TAP capture's rects). Now: live blocks
    over the lens, its caption or the menu (open, or closed < 3 s ago) are dropped, and the
    driver is not ticked while the menu is on screen / < 500 ms after it closed.
F4. Live OFF + patches: a 1 Hz watch on the paused tick compares the newest mirror frame with
    the first frame seen after painting (patch cells masked); change > Live threshold clears the
    patches ("page moved (Live off)"). Trade-off: with Live off, patches vanish on scroll rather
    than follow it — following needs the Live tracker. Skipped while a tap capture is in flight.
F5. Self-check stage 6 shows WAIT, not FAIL, when the gate is simply "Live is OFF".
Not device-verified.

# v0.9.13 (2026-11-13) — pipeline trace from v0.9.12

Evidence: capture OK (`black=false [1581/1600]`), 2 bubbles translated, but
`strategy=parallel x6 (joined disabled after a mismatch —  | : mismatch(1): count 3 != 4; retry in ≤7 d)`.

G1. One bad separator no longer switches joined mode off for a week. `noteJoinedFailure` bans
    THAT separator for 7 days; the self-test re-runs at once over the remaining ones. Second
    mismatch in a session → joined off until restart (so ≤ 2 pages per session pay a failed
    attempt; this relaxes the v0.9.9 "exactly one" rule on purpose).
G2. Self-test ranking: "•" goes first when within 1.5× of the fastest. " | " won a 4-string
    timing and then dropped a separator on a 4-bubble page; "•" mapped 20/20 ×5 in the benchmark.
G3. A page whose own text contains the separator character is sent per block (no ban).
G4. Parallel level: x6 had been stored from one noisy benchmark run. The level now leaves
    the default 2 only for a ≥ 15 % win over x2; rules version 3 resets level, bans and choice.
G5. ML Kit is re-warmed when the lens menu opens if idle > 20 s.
Note: tap captures use the progressive per-bubble path and never the joined call; joined is
used by callers without a per-bubble listener. Not device-verified.

# v0.9.14 (2026-11-13) — quality: names dictionary, one sentence per bubble, Arabic punctuation

Evidence: the 16:59 benchmark (joined "•" 524 ms, 20/20, verdict applied — the v0.9.13 changes
work) plus the reader's report that the translation quality is bad and that names are
inconsistent. Root causes found in the code:
 - ML Kit received raw OCR lines: a bubble split by the letterer into two lines was translated as
   two sentences ("…فعل. و هو…"), which also breaks pronoun flow.
 - No glossary existed at all, so 魏无羡 came back as وي لين / ويلين / وي وو شيان from one bubble
   to the next.
 - Engine output kept Latin ?, , and ; instead of ؟ ، ؛.

H1. New `translate/Glossary.kt`: ~250 Chinese-style names/terms (MDZS, TGCF, SVSSS, common
    surnames, cultivation terms) mapped to fixed Arabic spellings, plus the user's own entries
    (`English = عربي`, one per line, in Settings → Names & terms). Patterns are compiled once
    into a cached table ([Config] + `compiledCache`) — 250 names × N bubbles must not recompile.
H2. `Glossary.prepare` runs INSIDE the engines (ML Kit: every `client.translate`, including the
    joined branch, chunk path and warm-up path; Gemini: the prompt), so the engine is shown Arabic
    names as opaque tokens and every bubble spells them the same.
    Cache keys now hash the prepared text (`TranslationCache.key`) — otherwise a cached translation
    from before the glossary would win. Adding a name invalidates only affected entries.
H3. `translate/BubbleTextRules.kt`: `normalize` heals OCR hyphenation, `oneLine` joins the lines of
    one bubble into ONE sentence before translation (toggle in Settings, default on),
    `polishArabic` maps ?, , ; → ؟ ، ؛ for every engine (applied in `CachingTranslator`).
H4. Translociteration fallback: an unknown capitalized pinyin-looking name (x / q / zh / iao /
    iong / uang / uai) is transliterated deterministically; ordinary English words are protected
    by `ENGLISH_WORDS` + `ambiguousShort` (a 3-letter table entry such as "He"/"Ma" is left alone).
H5. Gemini prompt: glossary hint list appended, plus rules (8) keep glossary spellings and
    (9) one spelling per name across the page.
H6. Benchmark sample now includes "Wei Wuxian … Lan Wangji" so the dictionary is timed and its
    mapping is verified on device (sample stays at 20 bubbles).
Not device-verified.

# v0.10.0 (2026-11-13) — self-hosted open-source translation + remote output guard

A copy of the app's v0.9.14 source was recovered from the connected Dropbox archive. The GitHub branch started as an empty baseline, so source files were restored without the archive's nested `.git` history, generated APK, or signing files.

## Decisions

J1. Add a user-configured LibreTranslate-compatible engine, not an embedded translation server. The app sends one page-array request (`q`, source `en`, target `ar`) to a server the user operates. This enables Argos Translate models without shipping a large model in the APK.

J2. Require HTTPS; reject URLs with credentials, query strings, fragments, or invalid ports; do not follow redirects. The optional server key is sent in the JSON body because that is supported by the LibreTranslate API; it is not placed in the URL, logs, or cache key.

J3. Keep the existing progressive UX: ML Kit is still the first/local draft. The selected remote engine runs in parallel and can replace a bubble only when a complete response passes the Arabic output guard. Remote failure leaves the local draft untouched; only Gemini errors affect the Gemini circuit breaker.

J4. Use one page request and apply the existing glossary before sending. Endpoint hashes are added to the LibreTranslate cache namespace so a changed server cannot hit old entries. The API key is excluded from the namespace.

J5. The Arabic validator is deliberately conservative and mechanical. It rejects empty output, malformed control/replacement characters, and ordinary English-only echoes. It allows an unchanged single unfamiliar proper name. It is not a semantic judge; human comparison on device remains necessary.

J6. Add `INTERNET` permission for optional HTTP clients. The source archive's manifest lacked it even though the Gemini engine used `HttpURLConnection`.

J7. Review GitHub OCR/translation projects before adding native dependencies. PaddleOCR has an Android/ONNX path and is the next OCR candidate, but adding its runtime and model assets without target-device CER/latency/memory data would increase APK and memory use without proven benefit. `manga-ocr` targets Japanese OCR; `manga-image-translator` is GPL-3.0 and a separate full-image server. Neither is bundled.

## Version and verification

- `versionCode = 29`, `versionName = 0.10.0`.
- Added wire/translator/Arabic-validator unit tests. Local Gradle remained unavailable because Java/JDK and Android SDK are absent; GitHub Actions run `43478059287` later passed `:app:testDebugUnitTest` and `:app:assembleDebug`, producing `ManhwaLens-v0.10.0-debug.apk` as the `ManhwaLens-debug-apk` artifact. Device/server validation and release signing remain outstanding.
- Manual device/server checks are listed in `MANUAL_TESTS.md` M0.1–M0.6.
- Full GitHub project review, licenses, and future OCR test plan: `OPEN_SOURCE_RESEARCH.md`.

## v0.11.0 — quality, names, noise, and opaque patches

K1. **One opaque patch per visual area.** `PatchCoverage` unions and dilates (3 px) every OCR
box that belongs to the same bubble, and `PatchPlanner` names exactly one owner per area.
Reason: the screenshots showed English surviving outside the patch and two Arabic texts
smeared into each other. Root causes: the patch was sized from a single OCR box (+5 px pad),
and a bubble split into two blocks produced two patches whose feathered edges overlapped.
The owner decision is computed from the whole page before painting, so it never depends on
which bubble's Arabic arrived first.

K2. **Patches are fully opaque; no feather to transparency.** A soft edge blended the patch
ring with the page underneath, i.e. with the original English. Bubble interiors on the sample
pages are near-uniform (`#FAFAFA–#FCFCFC`), so an opaque fill is visually indistinguishable
from the art and reads correctly. Corner rounding no longer clears alpha either.

K3. **A rejected bubble is empty, never English.** `CachingTranslator` records the guard's
per-block verdicts, blanks the rejected ids, keeps the readable siblings, and reports
`PARSE_ERROR` only when nothing on the page is usable (so the caller keeps the ML Kit draft).
Reason: the old code returned the English text for a rejected id, which the overlay painted —
that is exactly the "English behind the Arabic" defect.

K4. **Partial translations are a rejection.** `ArabicValidator` fails a reply that keeps a
long English sentence next to Arabic, while allowing a preserved character name (1–3 Latin
words) and a single stray word. Reason: the reported bubble read
"أنت ستستمر في الوصول من الآن. You're going to keep coming from now on."

K5. **Names: one spelling per person per page.** `NameMemory` pins a spelling (user glossary >
built-in > transliteration), Korean names are romanized syllable-by-syllable
(`Ji-woo` → `جي وو`), and the page's name sheet is sent with the request. Rewriting is limited
to Latin remnants: blind Arabic→Arabic rewriting corrupts ordinary prose.

K6. **Noise is dropped before the engine, not translated.** `MeaninglessText` (used by
`PagePlan` and `BlockFilter`) removes punctuation-only bubbles, SFX, watermarks/credits, own
UI chrome, chapter headers, and already-Arabic text. `"chapter"`/`"ch."` left the substring
watermark list because it ate real dialogue ("You read that chapter already."); a chapter
header now requires a number.

K7. **`PagePlan` keeps real block ids.** Engine blocks use each item's first original id as
their id, so progressive painting and the guard's per-block ids stay valid; deduped siblings
receive the same Arabic through `PagePlan.arabicById`. An earlier synthetic-dense indexing
would have broken the painter's id → bounds lookup.

K8. **Verification.** 509 JVM unit tests pass in the local harness. Gradle and the Android SDK
are unavailable in the authoring sandbox, so the APK and `testDebugUnitTest` run in GitHub
Actions; device validation (patch opacity, name consistency on a real chapter) is outstanding.

# P1 — series character memory — 2026-11-13

**Report:** [P1_SERIES_NAME_MEMORY.md](P1_SERIES_NAME_MEMORY.md) · **Design:** [P1_CHARACTER_MEMORY.md](P1_CHARACTER_MEMORY.md) · **Baseline:** v0.11.0 `b8e37de` → `8d5cc9e` (PR #2) · **CI:** run 43518604612 green.

One line per decision.

- P1.1. Character memory is scoped by an **explicitly selected series** (`SeriesCatalog` id), never by the reader package name: one package can host two stories, and one story can ship in two packages.
- P1.2. With **no series selected** the session is unassigned: names still resolve for the page (no regression vs v0.11.0) but nothing is read from or written to series memory, so a mapping can never be attributed to a story the reader did not choose.
- P1.3. Storage is a **JSON file** (`filesDir/character_memory.json`, `version: 1`) written via `.tmp` + rename; Room was rejected because it would add a Gradle dependency (standing rule) to query tens of rows that fit in memory, and the app already persists JSON atomically for the translation cache.
- P1.4. A corrupt file is set aside as `.corrupt` and a newer schema as `.newer` (both kept for support); the store starts empty instead of crashing, matching the self-healing policy of the translation cache.
- P1.5. A **locked** record is final: `upsert` returns it untouched when the incoming write is unlocked, so no automatic detection can displace a reader's decision.
- P1.6. Lookup key = letters only, lower case, **no fuzzy matching** (`Han Ji-woo` / `Han Jiwoo` / `han ji woo` are one person); fuzzy matching would eventually merge two different people.
- P1.7. Priority ladder lives in one function (`CharacterResolver.decide`): locked → user (series, then glossary) → built-in → trusted series memory → page model (0.8) → transliteration → **keep Latin**.
- P1.8. `TRUST_MIN = 0.6`: a stored machine transliteration is never treated as confirmed, and is only persisted when the script is confident **and** the name was seen at least twice.
- P1.9. A bare surname (`Wang`, `Kim`, `Lee`, `Lin`, `Han`) is **not evidence of a script**; `NameScript.classify` returns `UNKNOWN` with confidence 0.25, and the guard runs *before* the pinyin letter-cluster rules because `wang`/`lin` contain `ng` (a single spelling must never decide a script).
- P1.10. Ambiguous English given names (`Hope`, `May`, `Rose`, …) are never auto-confirmed but can still be locked, and a locked one is applied even when the page scanner deliberately drops the token.
- P1.11. One `NameSession` per translator instance feeds all three consumers from one memoised table: ML Kit gets `.table` (longest-first inline substitution), Gemini gets `.sheet` (the P0-A path), so the engines cannot disagree by construction.
- P1.12. `CachingTranslator.pinnedOverride` repairs a cache hit (memory/disk/fuzzy) **before** `ArabicValidator.issue` judges it, and writes the repair back; fresh engine output is repaired before caching — so a correction beats an already-cached chapter.
- P1.13. `CharacterRecord.previousArabic` (≤ 4) is what makes an old Arabic spelling findable; repairs are whole-word only and an Arabic fragment under 3 letters is never rewritten.
- P1.14. UI = Settings → Character names: create/select a series, then edit (save = save + lock), alias, lock/unlock, delete; all I/O on `Dispatchers.IO` against the same store instance the pipeline uses. No network anywhere on the naming path.
- P1.15. Verification: 73 new JVM tests (590 total). CI run 1 failed to compile (nullable *property* cannot be smart-cast inside a lambda — snapshotted into local vals), run 2 failed 4 tests, one of which exposed a real bug (a repaired plain **memory** cache hit was never written back); run 3 green. Device validation of §12 of the report is still outstanding because the authoring sandbox has no Android SDK/JDK.

# P1 review corrections — 2026-11-13

**Report:** [P1_REVIEW_CORRECTIONS.md](P1_REVIEW_CORRECTIONS.md) · **Base:** `1c0c270` → **Fix:** `e99dd93` · **CI:** run 43523522645 green.

One line per correction.

- R1. `JsonCharacterStore` no longer writes the live file in place: its `renameTo` fallback (`file.writeText`) could truncate valid data if the process died mid-write, so the whole save path now follows the AtomicFile contract — complete copy in `<file>.tmp`, `fd.sync()`, one atomic move, and delete-on-failure with the live file never opened for writing.
- R2. `android.util.AtomicFile` was rejected deliberately: it is an `android.jar` stub under `unitTests.isReturnDefaultValues` and Robolectric is unavailable offline, so the persistence path would be untestable; the same contract is implemented with `FileOutputStream` + `fsync` + `Files.move(ATOMIC_MOVE)` and covered by JVM tests on a real filesystem.
- R3. A leftover `<file>.tmp` is adopted at load only when it parses as JSON **and** the live file is missing/empty/unparsable; a truncated copy is discarded, so an interrupted write can never replace or shadow valid records.
- R4. A failed save is non-fatal and self-healing: the in-memory index stays authoritative, nothing is truncated, and the next successful save persists the complete state.
- R5. `NameSession.tableFor` now memoises on the actual immutable text list instead of `texts.hashCode()`; two pages with colliding hashes could otherwise share one name table (`listOf("Aa")` and `listOf("BB")` both hash to 2143). One entry per page is kept, and the stored list is a defensive copy.
- R6. Series selection has no inference: no package/app-name lookup, no default series, `create()` never selects, and an unassigned session reads and writes nothing. The sticky manual selection is intentional and documented.
- R7. The active series is now visible where memory is managed: `Series: <title>` (or a red "names are not saved" state) on the Settings card, refreshed on return from the editor, plus one `character memory` trace line per page with the series and record count.
- R8. Verification: 9 new regression tests (599 test methods in the module), `:app:testDebugUnitTest` + `:app:assembleDebug` green on CI run 43523522645 at `e99dd93`; no local run was possible (no JDK/SDK in the sandbox) and none is claimed.

# Standing rule — a report travels with every change — 2026-11-13

- S1. Every change (code, tests, data model, behaviour, docs that fix a claim) ships in the **same push** as a Markdown report at the repository root, named after its phase (`P0_…`, `P0A_…`, `P1_…`), and indexed in [REPORTS.md](REPORTS.md).
- S2. The report must contain: scope and what is out of scope, the exact **base commit SHA**, the verified root cause with file:line evidence, the fix and why that shape (including rejected alternatives), files changed with a reason each, tests added with the property each pins, the exact command executed + the CI run that executed it, the `base...final` compare link with `--stat`, and the remaining risks (including "not verified on device" when true).
- S3. Evidence discipline: no test is reported as passing unless a run actually executed it; a command that cannot run in the authoring sandbox (no JDK/Android SDK) is reported as such, never simulated; a finding that contradicts an existing document is stated in the report **before** the code changes.
- S4. Chat summaries, commit trailers and PR descriptions are conveniences, never the deliverable: the report in the repository is the record. If a later change alters an earlier report's claim, the earlier report is corrected in the same push that changes the behaviour.

# P1 persistence safety — recovery may never write the live file — 2026-11-13

**Report:** [P1_PERSISTENCE_SAFETY_FIX.md](P1_PERSISTENCE_SAFETY_FIX.md) · **Base:** `b960444` (store code `e99dd93`).

- T1. The `staging.copyTo(file, overwrite = true)` fallback in `recoverInterruptedWrite()` is removed: it truncated and rewrote the live character-memory file in place, so a kill during recovery could leave partial JSON — the exact failure the atomic save exists to prevent, on the path that runs at start-up.
- T2. Recovery is now read-only with respect to the live file. It loads the complete `<file>.tmp` **in place** and only then attempts one atomic promotion; if the move is refused, nothing is written, the copy stays on disk for the next attempt, and the live file (if any) keeps its exact bytes.
- T3. Reading the copy in place is what makes a refused promotion lossless: the in-memory index is complete, so the next successful save serialises the whole state and promotes it. Without it, a later mutation would have overwritten the only surviving copy with an empty document.
- T4. `JsonCharacterStore` gained two defaulted constructor seams — `atomicMove` (commit) and `onWarning` (diagnostics) — so an atomic-move refusal is testable deterministically instead of depending on filesystem permissions; production behaviour is unchanged for every existing call site.
- T5. The deferred-recovery warning goes to `PipelineTracer.span("character memory", …)` (the debug-panel trace that already carries the per-page series line), not to the last-error file, which stays reserved for crashes. The store remains Android-free and JVM-testable.
- T6. Five regression tests pin the new behaviour: no write into the live path on refusal, byte-for-byte preservation of a valid live file, the copy surviving for a later attempt (and a later load restoring every record), no empty-overwrite after a refused promotion, and a truncated copy never being promoted.
- T7. Verification: 604 test methods in the module; `:app:testDebugUnitTest` + `:app:assembleDebug` run by GitHub Actions on the final commit (no local run is possible in the sandbox and none is claimed).

# Repository report — 2026-11-13

- S5. [REPOSITORY_REPORT.md](REPOSITORY_REPORT.md) is the repository-level snapshot (branch and PR state, the per-change work packages with their commits and CI runs, what is verifiable in this environment and what is not, the code map, the open blockers, and the ranked next steps). It is updated in the same push whenever the snapshot changes — a new phase, a changed head/PR state, a new verification run, or a blocker appearing or clearing — while per-change detail stays in the reports indexed by [REPORTS.md](REPORTS.md).

# P1.1 strict staging validation — 2026-11-13

**Report:** [P1_1_STRICT_STAGING_VALIDATION.md](P1_1_STRICT_STAGING_VALIDATION.md) · **Base:** `8140a4b`.

- U1. A staging copy is promoted into the live slot, so it is validated by a **strict** path (`validateStaging`) that is separate from the tolerant live-file reader: root object, `version` present/integral/supported, `series` present and an object, every series value an array, every element a record the parser accepts, non-blank id/series-id/name, `seriesId` equal to its containing key, and per-series uniqueness of both record id and normalized name.
- U2. Validation is all-or-nothing: one deviation rejects the whole document. The old gate (`isReadableJson`, syntax only) plus the tolerant loader silently skipped bad records, and the next save persisted the reduced set — silent, permanent data loss.
- U3. A parsable-but-invalid staging copy is renamed to `<file>.tmp.rejected` (bytes kept for diagnosis, one copy at a time), nothing is loaded, nothing is promoted, the in-memory store stays empty, and the exact reason goes through `onWarning`. A copy that is not JSON at all keeps the previous "demonstrably truncated" cleanup.
- U4. The live file stays tolerant and is still never opened for writing: recovery only renames the staging file, and the only writer of the live path remains `commitAtomically` (atomic move, else same-directory rename).
- U5. No schema change: `version` stays `1` and the writer is untouched, so self-written documents always satisfy the strict rules (the writer's own invariants — ids and names unique per series — are exactly what is enforced).
- U6. Verification: 11 new regression tests (615 test methods in the module) covering missing `series`, missing/invalid/unsupported `version`, wrong `series` type, malformed and misfiled records, duplicate id and duplicate normalized name, invalid staging beside a valid live file (bytes unchanged), invalid staging with no live file (no live path created), and a post-rejection save persisting the complete state; the valid-recovery and refused-promotion suites still pass.

# P1.1-A canonical normalized-name integrity — 2026-11-13

**Report:** [P1_1_STRICT_STAGING_VALIDATION.md](P1_1_STRICT_STAGING_VALIDATION.md) §8 · **Base:** `feb20ef`.

- V1. The stored `normalizedName` is untrusted input: `CharacterRecord.fromJson` accepts any non-blank string, so P1.1's duplicate check (which compared stored values) could be sidestepped by two rows for one character carrying different forged keys — both would pass and be promoted.
- V2. `validateStaging()` now recomputes `NameForm.normalize(originalName)` for every record, rejects the whole document when the stored value differs, and judges duplicate identity on the recomputed canonical key rather than on the JSON value. A missing/blank stored value needs no leniency: the record parser already canonicalises it, so the comparison stays exact.
- V3. No rule was relaxed and nothing else changed: schema `version: 1`, the writer, atomic promotion, the recovery flow, the tolerant live-file reader, `NameSession`, series selection, resolution priority, cache rewriting, OCR/translation and the settings UI are untouched.
- V4. Four regression tests pin it: a single forged key, two spellings with different forged keys (the bypass), two spellings with canonical keys (canonical duplicate detection), and a rejected copy leaving a present-but-unloadable live file byte-for-byte unchanged. 615 → 619 test methods in the module.
- V5. If `NameForm.normalize` ever changes its rule, documents from older builds would be rejected by the strict check — that is a future schema-migration concern (bump `SCHEMA_VERSION` then), deliberately not solved speculatively now.

# P0/C2 ML Kit English echo — 2026-11-13

**Report:** [P0B_MLKIT_ENGLISH_ECHO.md](P0B_MLKIT_ENGLISH_ECHO.md) · **Base:** `6eb8974`.

- W1. A block ML Kit gives no answer for is returned **empty**, never as the English source (`MlKitPageAssembly.ordered`). Page order and ids are preserved and the source stays on the block for the painter/fallback, but it is never the output; a page with nothing translated is no longer a silent "success" carrying English text.
- W2. `CachingTranslator.requiresArabicGuard` includes `ml_kit_en_ar`, so ML Kit output — fresh and cached — is judged by the same `ArabicValidator` as the remote engines: a rejected bubble is returned empty and never written to the memory, disk or fuzzy tier; a stale cached entry that fails the guard is evicted from all three tiers and treated as a miss.
- W3. The progressive path is **filtered, not disabled**: `MlKitPageAssembly.progressivePayload` withholds blank/rejected blocks from the callback (with a `PipelineTracer` span) while valid Arabic and legitimately preserved standalone names are delivered immediately and unchanged.
- W4. `BubblePainter` gained a last-line-of-defence check against the same validator, so no path (progressive callback, remote upgrade, final reconciliation, cache) can draw non-Arabic into an RTL patch; the refusal is logged with its reason and `remoteWins` arbitration is untouched.
- W5. Rejected blocks stay empty, so the existing failure contract, fallback and area merging apply — no retry loop and no added latency. The rules live in pure Kotlin (`MlKitPageAssembly`) because `MlKitTranslator` needs Android; Robolectric and new dependencies stay forbidden.
- W6. Verification: 10 new regression tests (619 → 629 test methods in the module); `:app:testDebugUnitTest` + `:app:assembleDebug` ran in GitHub Actions run 43563394305 at head `08004c4` — success (steps *Unit tests* and *Build debug APK*). No device validation is claimed.
