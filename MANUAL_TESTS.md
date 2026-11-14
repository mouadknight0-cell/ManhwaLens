# MANUAL_TESTS.md — Redmi 13C checklist

Device: Redmi 13C (Helio G85, 720×1600, MIUI/HyperOS). Browser: **Brave**.
Build under test: `app/build/outputs/apk/debug/app-debug.apk` (v0.11.0, versionCode 30).
Each numbered test lists **PASS / FAIL**. When in doubt, Settings → Debug mode ON and Settings → Last error → Copy.
Log filter for every test below: `adb logcat -s MLCapture ManhwaLens`.

---

# v0.11.0 — the four screenshot defects

Run these on the same pages that produced the four screenshots. Logs: `adb logcat -s MLCapture ManhwaLens`.

1) **No English around the Arabic (screenshot 1)**
   - Translate a page with a long bubble, then zoom in on its edges.
   - **PASS:** the patch is uniformly opaque; no Latin glyph pixel, tail, or shadow is visible
     inside or around the Arabic; the log shows `patch fold: … patch area(s)` only when bubbles merged.
   - **FAIL:** any English visible through or beside the patch.

2) **No smeared Arabic at overlaps (screenshot 2)**
   - Find a bubble OCR splits into two visual lines and translate it.
   - **PASS:** one Arabic text per bubble, readable, no double-exposed letters.
   - **FAIL:** two Arabic texts overlapping, or Arabic half-repainted by a neighbouring patch.

3) **Korean/Chinese names (screenshot 3)**
   - Translate a Korean chapter and read the names across 5 consecutive pages.
   - **PASS:** each character keeps ONE Arabic spelling for the whole page; `Ji-woo` never
     appears as two different words, and no Latin remnants are shown to the reader.
   - **FAIL:** inconsistent or mangled names.

4) **Meaningless text (screenshot 4)**
   - Translate a page that has a watermark, "……", a chapter header, and a bilingual credit.
   - **PASS:** none of them get a patch, none are translated, and the pipeline trace lists them
     as `dropped` with a reason.
   - **FAIL:** any of them translated or covered.

5) **One bad bubble must not sink the page**
   - Force a remote failure (wrong Gemini key) on a page with several bubbles.
   - **PASS:** every other bubble still gets Arabic from the on-device draft.
   - **FAIL:** whole page left untranslated.

Confirm `versionCode = 30`, `versionName = "0.11.0"`, and report the Diagnostics → Live self-check output.

---

# v0.9.6 — THE 4 CHECKS FOR THIS BUILD (run these first)

1) **Live ON in Brave on webtoon page**
   - Install over the old version, grant capture consent, open a webtoon page in Brave, turn Live ON.
   - **PASS:** translation appears within ~2 s and NO patches over the lens button/caption or Brave toolbar.
   - **FAIL:** any patch over our own chrome, or no translation after 5 s.

2) **Open Settings while Live ON**
   - With Live ON and patches visible, open ManhwaLens Settings.
   - **PASS:** all patches vanish immediately; nothing is drawn over our own screens.
   - **FAIL:** any patch remains visible over Settings or any ManhwaLens screen.

3) **Zoom into one bubble**
   - Zoom into a translated bubble on the webtoon page.
   - **PASS:** Arabic covers the whole English text, no ghost letters, no Latin garbage words.
   - **FAIL:** English visible behind the Arabic, or garbage like "NHRAIP GAI PNG".

4) **Press "Translate now" with Live OFF**
   - Turn Live OFF, open a webtoon page, press "Translate now" (lens tap).
   - **PASS:** same clean result as Live, no cut sentences.
   - **FAIL:** incomplete translation or patches over our own windows.

Then: Settings → Diagnostics → Live self-check → Copy report → paste to developer.

---

# v0.9.4 — THE 9 CHECKS FOR THIS BUILD (run these first)

Prepare once: install v0.9.4 over the old build (do NOT clear data), then enable
**Settings → Debug mode** so check 9 has something to show. If the lens is not on screen,
open ManhwaLens and toggle the floating button ON.

### 1. Tap the lens → the menu opens → Live ON → consent

- Tap (single, short) the floating lens.
- **PASS:** a compact menu appears NEXT TO the lens (not centred at the top), fully on screen,
  header reads **Live OFF** with a grey ring on the lens. Rows: Live mode switch, Translate now,
  Arabic (off = original), Clear translation, Review last capture, Stop ManhwaLens, Settings….
  Tap **Live mode** → the system capture-consent sheet appears → allow it → the switch turns
  green, the header reads **Live ON**, the lens ring turns green, a toast says Live mode is ON,
  and the notification now shows a **Live OFF** action.
- Also PASS: the menu closes by itself after ~4 s if you touch nothing, and closes immediately
  when you tap outside it (the tap still reaches the page underneath).
- **FAIL:** no menu on a short tap, menu off-screen/clipped, no consent sheet when Live is
  switched on, or the menu steals scrolling on the page.

### 2. Open a webtoon page in Brave and wait — patches appear by themselves (~2 s)

- With Live ON, open a chapter with English bubbles in Brave and DO NOT touch the screen.
- **PASS:** Arabic patches fade in over the bubbles within ~2 s of the page settling, with no tap.
  Lens ring: green (waiting) → amber (scanning while the page still paints) → blue (translating)
  → green. The page remains scrollable (patches never block touch).
- **FAIL:** nothing appears until you tap, or it takes >5 s on a settled page.

### 3. Scroll and stop — old patches clear or move, new page translated (~2 s)

- Scroll about one screen, then stop and keep still.
- **PASS:** during the scroll the existing patches either slide with the page or fade out
  (80 ms); ~0.4–2 s after you stop, the newly visible bubbles are translated. No full-screen
  flash, no white/black redraw, no half-burned Arabic left at stale positions.
- This is THE regression this build fixes: before v0.9.4 nothing was translated after a scroll.
- **FAIL:** the page stays in English after you stop scrolling (check the log for
  `live starved` repeating forever with `source=none` — that means no frames at all).

### 4. Leave the page still for 15 s — no flicker, no disappearing

- After patches are up, do not touch anything for 15 s.
- **PASS:** patches stay rock-solid for the whole 15 s — no repeated fade-out/fade-in, no
  blinking, no vanishing, no re-translation churn. Ring stays green; caption may read
  "Same page". Logcat shows at most a few `live decision: stable; same page — skipped`
  lines and NO repeated `OCR`/`translate` work.
- **FAIL:** any flicker, patches disappearing, or continuous OCR in the log.

### 5. Live OFF from the menu — patches clear, nothing translates

- Tap the lens → switch **Live mode** OFF.
- **PASS:** the patches fade out immediately, the lens ring turns GREY, header reads **Live OFF**,
  a toast says Live mode is OFF, the notification action now reads **Live ON**. Scroll to a new
  page and wait 10 s: NOTHING is translated.
- **FAIL:** patches stay, or a new page still gets translated while Live is OFF.

### 6. Long-press toggles Live

- Long-press (≥ ~0.5 s) the lens.
- **PASS:** you feel a haptic tick, a toast says Live mode ON/OFF, the ring colour flips
  (grey ↔ green) and the menu does NOT open. Repeat 3× — the state alternates every time and
  matches the notification action label and Settings → Live mode.
- If Live is switched ON by long-press while no capture session exists, the consent sheet must
  appear first, and Live must switch on by itself right after you allow it.
- **FAIL:** long-press opens the menu, no haptic/toast, or the state desynchronises.

### 7. "Translate now" works with Live OFF

- With Live OFF, tap the lens → **Translate now**.
- **PASS:** a one-shot capture runs (pill shows "Capturing…"), and Arabic patches appear for the
  current page even though Live is OFF. Repeat on a second page: works again.
- If no capture session exists, the consent sheet comes FIRST and the capture runs after you allow it.
- **FAIL:** nothing happens, or it silently turns Live ON.

### 8. "Stop ManhwaLens" removes everything

- Tap the lens → **Stop ManhwaLens** (red row). (Same as the notification's **Stop** action.)
- **PASS:** the lens button, all patches, the status pill and the debug panel all disappear
  immediately; the ongoing notification is gone; the page scrolls normally; logcat shows
  `stopEverything:` followed by `overlay removed: reason=stop`, `capture session released`,
  `projection released`. Re-opening ManhwaLens and toggling the button ON brings the lens back.
- **FAIL:** any overlay left on screen, the notification still there, or the app needs a force-stop.

### 9. Debug panel shows frames/s, verdict and stash state

- With Settings → Debug mode ON, tap the lens to translate a page, then read the panel
  (it also refreshes ~1×/s while Live runs).
- **PASS:** under **— live loop —** you can read:
  `projection=active display=alive recreated=no`,
  `liveMode=true liveOptOut=false pendingLiveEnable=false standby=false …`,
  `frames/s=… total=… idleTicks=… source=new|stash|none`,
  `verdict=changing|settling|stable diff=… sinceChange=…ms`,
  `patches(visible=N fading=M) stash=clean|burned|none`,
  `cleanCaptureTimeouts=0 samePageSkips=…`,
  `overlay: <reason> · N patch(es) · Xms ago drawn=… arabic=…`,
  `menu: open=… lastClose=… autoCloses=…`.
  While the page is still, `idleTicks` must CLIMB and `source=stash` — that is the proof the
  loop no longer depends on new frames. `cleanCaptureTimeouts` should stay 0 on this device.
- **FAIL:** panel empty/missing, `source=none` forever on a still page, `stash=none` right after
  a scroll, or `cleanCaptureTimeouts` climbing (means the mirror is not redrawing after we hide).

### Extra: "Tap on the lens" setting (old behaviour)

- Settings → **Tap on the lens** → choose **Translate now**; tap the lens.
- **PASS:** no menu — a one-shot capture runs immediately (v0.9.3 behaviour). Switch back to
  **Menu** and the tap opens the menu again. The choice survives an app restart.

---

# v0.9.3 checklist (in-place bubble patch build) — still valid

## Part 1 — Tap-translate (patches only, no full-frame flash)

1. **Brave + webtoon page → tap lens**
   - Open a webtoon page with English bubbles in Brave (e.g. m.webtoons.com), scroll to mid-chapter.
   - Tap the floating lens button; accept capture consent if asked.
   - **PASS:** the original art stays fully visible; Arabic replaces exactly the English bubbles (rounded patches where bubbles are). NO full-screen white/black/colored flash, no overlay panel covering the page, page still scrolls underneath (overlay is untouchable).
   - **FAIL:** any full-screen redraw, tinted rectangle over the page, or page-blocked taps → report with `adb logcat -s MLCapture ManhwaLens`.

2. **Colored-background bubble looks clean**
   - Find (or zoomable-in) a bubble whose fill is NOT white (beige/grey/gradient/art).
   - **PASS:** the patch covers the bubble; old English glyphs are GONE (not ghosted under the Arabic); no starry noise edges; Arabic stays inside the bubble outline.
   - **FAIL:** English still visible under Arabic, mottled/rustled patch edges, or text overflow outside the bubble.

3. **Original/Arabic toggle in the lens menu**
   - Long-press the lens button → menu → toggle "Arabic patches" OFF → patches vanish instantly (original art back); toggle ON → patches return.
   - **PASS:** instant toggle, no re-capture, no re-OCR (watch for absence of "OCR done:" in logcat on the toggle).

## Part 2 — Live mode (in-place patches stay stable)

4. **Live ON, stable page, 10 s — zero flicker**
   - With Live mode ON (long-press lens), stop scrolling and keep the page untouched for 10 s.
   - **PASS:** patches stay rock-solid; no repeated fade-in/fade-out; dot/caption returns to WAITING and stays; no "OCR done:" spam in logcat during the 10 s.

5. **Scroll, then stop — old patches clear, new ones appear**
   - Scroll ~1 screen, stop.
   - **PASS (fast path):** patches slide with the page (no flash) or fade (80 ms) then the new bubbles fade in (~120 ms, progressive if several) — either way the transition reads as "the page re-typeset", never as a full-screen redraw. Status dot: WAITING→SCANNING→TRANSLATING→WAITING.
   - **FAIL:** full-screen flash, Arabic burned half-off a bubble after scroll, or patches stuck at stale positions.

6. **Live pauses while ManhwaLens is open**
   - With Live ON, open ManhwaLens itself (home screen / Settings / lens menu) for ~5 s, then return to the webtoon.
   - **PASS:** Live did NOT translate ManhwaLens' own screens (no OCR over the settings UI); back in Brave, translation resumes on the next scroll stop.

7. **Dialogue containing "stop" IS translated**
   - Navigate to a panel whose bubble says e.g. "Stop!" / "Don't stop now".
   - **PASS:** the bubble gets Arabic. (Regression check for the ignore-list substring bug — "top of the tower" must also pass.)

8. **Reader chrome "TOP"/"views"/"Subscribe" is NOT translated**
   - On a page with site chrome ("TOP", "views", "Subscribe", "Continue Reading") visible in the top/bottom bands.
   - **PASS:** those lines never receive patches. (Ignore list lives in Settings → Automation; defaults are Subscribe / Get the app / Continue Reading / TOP / Episode / views / coins.)

## Part 3 — Debug mode & telemetry

9. **Debug mode shows "patches painted = N of M"**
   - Enable Settings → Debug mode, tap the lens, open the debug panel after capture.
   - **PASS:** FILTER stage line shows e.g. `patches painted = 5 of 6`; a MISSING patch means N < M — cross-check against the removed list ("ignore-list"/"edge-top"/…) before calling it a bug.

10. **Removal reasons in the trace / log**
   - Scroll away from a translated page; search logcat for `overlay removed: reason=`.
   - **PASS:** you see `reason=scroll` (patch rode off / page changed) or `reason=page change`; after Clear patches in the menu → `user clear`; after app kill → `stop`; when ManhwaLens itself is on screen → `app visible`.

## Part 4 — (Optional) AI cleanup + regression

11. **AI cleanup (experimental, OFF by default)**
   - Settings → Translation → save a Gemini key → enable "AI cleanup (experimental)" → tap the lens again.
   - **PASS:** logcat shows `ai cleanup used (cloud) · replaced K/3 …`; the ≤3 largest bubbles look re-rendered with Arabic inside the art; smaller bubbles keep local patches; turning the switch OFF restores local patches on the next tap.
   - **FAIL-safe check:** with Wi-Fi OFF, tap again → local patches render normally and log says cleanup was skipped/failed (never crashes, never blocks).

12. **Crash-loop guard still armed (carried from v0.9.2)**
   - Kill the app within 5 s of launch twice → Safe Mode banner on third launch. Unchanged by this rework.

## Carried forward from v0.9.2 (still valid)
- Reinstall **OVER** v0.9.2 without clearing data → app opens, floating button + settings intact; no `CalledFromWrongThreadException` in logcat.
- Switch apps and back → state intact; toggle lens ON/OFF ×5 → no duplicates/crashes.
- Wrong thread log: debug builds print `WRONG THREAD …` on any background→main view touch — none should appear.

## What the sandbox cannot verify

OCR quality, HyperOS GPU/overlay paths, MediaProjection frame-delivery timing and battery.
Sandbox verification for v0.9.4: **283/283 unit tests green** (`:app:testDebugUnitTest`), including
the pure `LiveLoopDriver` suite that simulates "the screen went still" (no new frames at all) —
the exact case v0.9.3 never covered. Everything above the line "THE 9 CHECKS FOR THIS BUILD"
still needs this device: only a real MediaProjection session can prove frames/s, the clean-capture
redraw after hiding the overlay, and the ~2 s settle-to-patch latency.
# ManhwaLens — Manual Translation Quality Tests

Tests to run on a physical device or emulator. Each test: steps, expected
result, pass/fail criteria.

---

## Part 1: OCR Text Cleaner

### T1.1 Hyphen line-break removal
**Steps:** Capture a page with a hyphenated word split across bubbles
(e.g. "YOUNG-" on line 1, "ER" on line 2).
**Expected:** Translated output shows "YOUNGER" (joined), not two fragments.
**Pass:** Single bubble with joined word.

### T1.2 OCR confusion fixes
**Steps:** Capture text with OCR noise: "he11o", "c0ld", "barrn", "he||o".
**Expected:** "hello", "cold", "barm", "hello".
**Pass:** All four cleaned correctly.

### T1.3 ALL-CAPS to sentence case
**Steps:** Capture an ALL-CAPS sentence ending with "." (e.g. "YES, WE WERE MUCH YOUNGER THEN.").
**Expected:** Sentence case: "Yes, we were much younger then."
**Pass:** First letter uppercase, rest lowercase.

### T1.4 Short acronym preserved
**Steps:** Capture "NASA" as a standalone bubble.
**Expected:** "NASA" unchanged.
**Pass:** All-caps acronym preserved.

### T1.5 Cut sentence detection
**Steps:** Capture text touching the right edge of the frame, ending mid-word.
**Expected:** `isCut = true`, confidence = "medium".
**Pass:** Cut detected and flagged.

### T1.6 Non-dialogue / SFX skipping
**Steps:** Capture "KRAK!" (SFX) with translateSfx = false (default).
**Expected:** `isNonDialogue = true`, skipped before translation.
**Pass:** SFX not translated by default.

---

## Part 2: Gemini Quality Engine

### T2.1 Quality tier selection
**Steps:** Settings → Translation → Quality → pick FAST / BALANCED / BEST.
**Expected:** Dropdown shows selection; saved to prefs.
**Pass:** Selection persists across app restarts.

### T2.2 FAST vs BEST output difference
**Steps:** Translate the same page with FAST then BEST.
**Expected:** BEST produces longer, more context-aware output; FAST is shorter.
**Pass:** Different outputs (BEST typically more detailed).

### T2.3 Page context usage
**Steps:** Set page title/genre, enable glossary with "Naruto=ناروتو".
Translate a page containing "Naruto".
**Expected:** Arabic output uses "ناروتو" for Naruto.
**Pass:** Glossary term appears in translation.

### T2.4 One request per page
**Steps:** Translate a page with 20+ bubbles.
**Expected:** Single Gemini request containing all blocks (batched).
**Pass:** One HTTP request, all blocks in one body.

---

## Part 3: Glossary Settings

### T3.1 Glossary entry
**Steps:** Settings → Series glossary → add "Sakura=ساكورا" (one per line).
**Expected:** Text field accepts input; term count updates.
**Pass:** Glossary saved and shown in UI.

### T3.2 Glossary in translation
**Steps:** Translate a page with "Sakura" after adding to glossary.
**Expected:** Arabic output uses "ساكورا".
**Pass:** Glossary term used consistently.

### T3.3 Glossary clear
**Steps:** Click "Clear glossary" in settings.
**Expected:** Text field emptied; term count = 0.
**Pass:** Glossary cleared.

---

## Part 4: Validation, Retry, Fallback

### T4.1 Validation catches bad translation
**Steps:** Force Gemini to return non-Arabic output (or test with mock).
**Expected:** Validator flags `NO_ARABIC_SCRIPT` or `ENGLISH_LEFTOVER`.
**Pass:** Invalid output detected.

### T4.2 Retry with BEST
**Steps:** Translate with FAST, force validation failure, observe retry.
**Expected:** Second attempt uses BEST quality (if configured).
**Pass:** Retry attempted before fallback.

### T4.3 ML Kit fallback
**Steps:** Disable Gemini (no key or circuit open), translate a page.
**Expected:** ML Kit translation returned, no crash.
**Pass:** Offline fallback works.

### T4.4 RTL post-processing
**Steps:** Translate a bubble, observe Arabic output in overlay.
**Expected:** Arabic text displays right-to-left with connected letters.
**Pass:** Proper RTL rendering in overlay.

---

## Part 5: Quality Harness

### T5.1 Golden set validation
**Steps:** Run `GoldenSetTest` (30 reference translations).
**Expected:** All 30 pass `ArabicValidator`.
**Pass:** Zero validation failures on golden references.

### T5.2 Engine comparison
**Steps:** Settings → Diagnostics → Compare engines (future UI).
**Expected:** Side-by-side ML Kit vs Gemini output for same page.
**Pass:** Quality difference visible (Gemini typically better).

---

## Part 6: Build

### T6.1 APK builds
**Steps:** `./gradlew :app:assembleDebug --no-daemon --max-workers=1`
**Expected:** APK produced at `app/build/outputs/apk/debug/`.
**Pass:** Build succeeds, APK installs on device.

### T6.2 Version bumped
**Steps:** Check `app/build.gradle.kts`.
**Expected:** versionCode = 21, versionName = "0.9.7".
**Pass:** Version matches expected.

### T6.3 All unit tests pass
**Steps:** `./gradlew :app:testDebugUnitTest`
**Expected:** All tests green (100+ tests across all packages).
**Pass:** Zero failures.

---

## Environment

Build command (this sandbox box):
```bash
source /home/user/ManhwaLens/env-build.sh
./gradlew --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs="-Xmx800m -XX:MaxMetaspaceSize=256m" \
  :app:testDebugUnitTest
```

Never run `lint`/`lintVitalRelease` on this box (memory constrained).

# v0.9.9 — ML Kit batching / pre-warm / two-phase Live

1. Settings -> Diagnostics -> benchmark: run it twice and paste the Copy report to the developer.
2. Translate a webtoon page: note the time to first Arabic patch.
3. Scroll to 3 more pages: no page pays a failed joined attempt (trace shows the chosen strategy).

# v0.10.0 — self-hosted open-source translation + output guard

## Setup

The app ships an HTTPS client only. Run a LibreTranslate server with an installed English→Arabic Argos package, expose it through HTTPS, then enter the server base URL (or `/translate` URL) under Settings → Translation → LibreTranslate / Argos. A server API key is optional. Do not use an endpoint you do not trust.

## M0.1 HTTPS and settings
1. Select LibreTranslate / Argos; enter the server HTTPS URL and optional key; Save.
2. Try `http://...`, a URL containing credentials, or a URL with query parameters.
**Expected:** these are rejected locally; no request is sent. The settings note says only OCR text is sent and the app does not run the server.

## M0.2 Batched API contract (unit test)
**Steps:** Run `LibreTranslateWireTest` and `LibreTranslateTranslatorTest`.
**Expected:** one JSON request contains the page's `q` array, source `en`, target `ar`, format `text`, and optional key in the JSON body; returned translations map back to the original bubble IDs/order.
**Pass:** request/response and malformed-response tests pass without a live network.

## M0.3 Progressive tap and Live fallback (device + trusted test server)
1. Select LibreTranslate and configure the test server. Translate a page by tapping the lens, then repeat with Live.
2. Compare the first-bubble time with ML Kit-only mode. Stop the server or disconnect the phone and repeat.
**Expected:** ML Kit patches appear first. A valid Arabic batch may replace them in place; a timeout, malformed response, or English echo leaves the ML Kit draft visible. No screenshot is sent to LibreTranslate.
**Pass:** no blank/English remote reply replaces a readable local patch; Live continues translating offline when the server is unavailable.

## M0.4 Gemini output quality guard
**Steps:** Run `GeminiTranslatorTest.englishEchoIsRejectedSoTheOfflineDraftCanRemainVisible`, `ArabicValidatorTest`, and the stale-cache cases in `CachingTranslatorTest`.
**Expected:** ordinary English echoes, empty replies, and malformed control/replacement characters fail before caching; pre-guard remote cache entries are retried and purged; an unfamiliar single-word character name may remain Latin.
**Pass:** invalid output is rejected and existing ML Kit fallback behavior is retained.

## M0.4b ML Kit English echo withheld (P0/C2, device)
**Why:** P0/C2 stops ordinary untranslated English from being painted as Arabic on the on-device path.
The unit tests prove the rules (`MlKitEnglishEchoTest`, `ArabicValidatorTest`, `CachingTranslatorTest`);
the *visual* result of a withheld bubble has never been seen on a device, so this is the check that
closes it.

1. Translate a page on the on-device engine (no Gemini/Libre), on a device whose EN→AR model may answer
   a short bubble with the source text unchanged.
2. Repeat the same page twice: once cold, once after the first pass has filled the caches.
3. Contrast: pick a page where a character name legitimately stays Latin (`Hanwoo`, `Wei Wuxian`).

**Expected:** a bubble whose Arabic is an English echo keeps the **original art** (no English painted in
Cairo Bold, no half-translated sentence); its neighbours still paint instantly as they finish; a later
remote upgrade can still replace a valid local patch. A preserved Latin name inside Arabic dialogue
still paints, and a lone name bubble is not blanked.
**Pass:** no bubble on the page shows untranslated English as Arabic, the second pass does not resurrect
one from cache, and progressive painting is still visibly progressive (first bubble before the page ends).
**Evidence to record:** device model, engine, page id, and before/after screenshots.

## M0.5 Permissions and privacy
**Steps:** Inspect app permissions and both privacy-policy versions; run one LibreTranslate test with a trusted HTTPS server.
**Expected:** `INTERNET` permission is present for optional network engines; request is HTTPS-only; API keys are never logged or put in the URL; only recognized text is sent to LibreTranslate.
**Pass:** data flow matches `PRIVACY.md`, `privacy-policy.html`, and the in-app policy.

## M0.6 Build and release checks
- `./gradlew :app:testDebugUnitTest`
- `./gradlew :app:assembleDebug`
- Confirm `versionCode = 29`, `versionName = "0.10.0"`.
- Manually verify tap and Live on a supported Android device. The sandbox cannot validate overlay timing or a real HTTPS LibreTranslate deployment.
