# v0.12.25 — first-overlay breakdown and Paddle batch-size trial

1. Install the CI-built v0.12.25 APK over v0.12.24 without clearing app data.
2. Use the same Xiaomi device and manga page as the v0.12.24 Live report. Arm the Live session benchmark while Live is OFF, turn Live ON, wait for the first Arabic overlay, scroll through a few changes, then turn Live OFF.
3. Copy the report. Confirm **First overlay stages** reports main queue, callback prep, patch build, handoff, and overlay apply timings; confirm none is negative or missing after a visible first output.
4. Compare OCR recognizer timing and recognition batch count with the prior run. Check low-confidence drops and visible text coverage; batch size 8 is only an experiment and must be reverted if latency or recall worsens.
5. Confirm overlay expected/painted counts match for the observed output and that the local report contains no page text.

**Gate:** one same-device/page session. This build changes only Paddle recognition batching and opt-in timing instrumentation; CI results do not prove on-device speed or OCR quality.

# v0.12.8 — offline engine diagnostics

The three Diagnostics actions are deliberately different. Do not treat their timings or reports as interchangeable.

## ML Kit batching benchmark

1. Download the ML Kit EN→AR model once, then open Settings → Diagnostics → ML Kit batching benchmark.
2. **PASS:** the report identifies ML Kit explicitly, measures sequential/parallel/joined request strategies, and includes the separate ML Kit-only quality check.
3. **PASS:** selecting Bergamot in the engine settings does not redirect this action to Bergamot. It always calls raw ML Kit.
4. **PASS:** the benchmark may update only ML Kit's saved batching policy (parallel level and validated joined separator). It must not change the selected offline translation engine or Bergamot settings.
5. Confirm the report labels the saved policy as ML Kit batching state. “map OK” means output mapping or non-blank per-bubble responses, not semantic translation correctness.

## Bergamot-only benchmark

1. Confirm the Bergamot EN→AR model is downloaded and the device supports its ARM64 native runtime. ML Kit model installation is not required for this action.
2. Open Settings → Diagnostics → Benchmark Bergamot only. Run with Wi-Fi and mobile data disabled.
3. **PASS:** the report states Bergamot only, uses the fixed 20-case corpus, reports the first pass separately from the three warm passes, and includes accepted/rejected, blank, English-echo, exact-match, lexical F1 and per-case outputs.
4. **PASS:** no ML Kit request or ML Kit fallback occurs; the action does not change the selected engine, ML Kit batching policy, or saved preferences.
5. Repeat after returning to Diagnostics. The same 20 prepared inputs and ordering must be used. The first pass may include fresh native service/model initialization; warm median/worst are the warmed native measurements.

## Compare ML Kit vs Bergamot

1. Download both offline models and confirm Bergamot native runtime is available.
2. Run Compare ML Kit vs Bergamot on the same device.
3. **PASS:** both engines receive the same prepared 20-case corpus, without app translation cache.
4. **PASS:** ML Kit uses a snapshot of the device's saved page batching strategy (validated joined strategy when safe, otherwise saved parallel level). Bergamot uses one native multi-input request for this fixed corpus; a failed/malformed batch is isolated through per-case retries.
5. **PASS:** the report identifies actual dispatch strategies, alternates engine order across paired passes, and does not change selected engine or saved batching policy.
6. Treat first-pass startup time separately. Warm median/worst are page-batch wall times for the two dispatch paths, not isolated single-sentence model latency. Token F1 is lexical overlap only, not semantic accuracy; review the per-case Arabic outputs manually.


# v0.12.5 — Bergamot offline quality fallback

1. Select Bergamot, disable Wi-Fi/mobile data, and translate a page with short dialogue and long narration. **PASS:** no cloud request is made; accepted Bergamot bubbles remain visible.
2. Use a page containing text that Bergamot previously mistranslated or left partly English. **PASS:** bubbles rejected by the shared Arabic quality guard are retried by ML Kit; accepted Bergamot bubbles are not retransmitted.
3. Open Diagnostics → Pipeline trace. **PASS:** when recovery occurs, a `Bergamot -> ML Kit fallback` entry reports requested, filled and elapsed values.
4. Compare the same page with ML Kit selected directly. **PASS:** ML Kit remains independently selectable and remains the default; fallback does not change the saved engine preference.
5. Force or simulate a Bergamot native failure. **PASS:** the page is retried through offline ML Kit; if both engines fail, no English output is painted.
6. Repeat a page where all Bergamot outputs pass validation. **PASS:** no fallback trace appears and no additional ML Kit translation pass is run.

# v0.12.4 — Settings cleanup regression

1. Open Settings. **PASS:** the page contains Appearance, Translation engines/models, Saved translations, Tap on the lens, Diagnostics, Last error, Pipeline trace and About. There is no Automation, Ignore list, Character names, Names & terms or Live mode settings section.
2. Return to a manhwa page and tap the floating lens. **PASS:** the lens menu contains the Live ON/OFF control; toggling it updates the lens status and notification action. Long-press behavior remains unchanged.
3. Settings → Saved translations → Reset saved translations. Confirm the dialog. **PASS:** cached translations are cleared and the count becomes zero; translation glossary/name-processing data and diagnostic pairs are not erased by this action.
4. Settings → Diagnostics → translation pairs. Copy a report, then use **Reset log**. **PASS:** the pair count becomes zero and the next capture starts a fresh batch, so English/Arabic pairs from different engine runs do not mix.

---

# v0.12.4 — Bergamot offline translation experiment

Run this section before judging Bergamot as an ML Kit replacement. Keep the same source page set for both engines.

1. Install `ManhwaLens-v0.12.4-debug.apk` on an ARM64 Android 9+ phone. Confirm the app opens and ML Kit remains selected by default.
2. In Settings → Offline translation, download the ML Kit EN→AR model. In the Bergamot card, download its model (~23.8 MB compressed, ~35.6 MB installed). Confirm both report ready.
3. Select **Bergamot (experimental)**, enable **Offline mode**, then disable Wi-Fi and mobile data. Translate one page with Tap mode and one with Live mode.
   - **PASS:** Arabic appears, the app stays responsive, no cloud request is attempted, and the same page can be translated again offline.
   - **FAIL:** native crash, app freeze, empty page, missing bubbles, or network dependency after the model is installed.
4. Repeat the exact same pages with **ML Kit / on-device**. Use at least 20 dialogue bubbles across 3–5 pages, including short answers, long narration, names, punctuation and mixed Latin/Arabic.
5. Enable Settings → Diagnostics → Debug mode and collect the translation-pair report for each engine. Compare semantic accuracy, English echoes, omitted bubbles, consistency of names, total page time and time to first visible Arabic. Clear the translation-pair log between engine batches so the reports cannot mix.
6. Repeat each engine once after a cold app restart. Record device model, Android version, free RAM if available, engine, model-ready state, page count, median page latency and any crash.
7. **Decision rule:** keep ML Kit as default unless Bergamot completes all device checks without crashes and demonstrates a repeatable quality or speed advantage. Do not infer a winner from APK build success alone.

**Known compatibility:** Bergamot's native runtime is ARM64 only and requires Android 9+. ARMv7 devices must stay on ML Kit. The Bergamot model is downloaded separately and is not included in the APK.

---

# v0.12.3 — PaddleOCR-only OCR and offline-first translation

1. Install the v0.12.3 APK on an ARM64 or ARMv7 Android phone.
2. Open Settings: confirm there is no OCR engine selector and no LibreTranslate/Argos option.
3. Select ML Kit translation, download the English→Arabic model once, enable Offline mode, then disconnect Wi-Fi/mobile data.
4. Translate several manhwa pages using tap and Live modes. Confirm PaddleOCR detections appear in the diagnostic trace and ML Kit translations still render without network access.
5. Select Gemini only while online and verify it is unavailable when Offline mode is enabled.
6. Check cold-start PaddleOCR load time, warm latency, line accuracy, bubble grouping, and memory; compare against the v0.12.2 benchmark baseline.

---

# MANUAL_TESTS.md — Redmi 13C checklist

Device: Redmi 13C (Helio G85, 720×1600, MIUI/HyperOS). Browser: **Brave**.
Build under test: `ManhwaLens-v0.11.5-debug.apk` (versionCode 35; experimental).
Each numbered test lists **PASS / FAIL**. When in doubt, Settings → Debug mode ON and Settings → Last error → Copy.
Log filter for every test below: `adb logcat -s MLCapture ManhwaLens`.

---

# P2 — targeted translation regression checks (2026-11-14)

Run these after installing the current v0.11.0 debug artifact. Use the same multi-bubble
chapter for ML Kit and Gemini where possible. Enable Settings → Debug mode and capture
the diagnostics/log output for any failure.

### P2-06 — one failed ML Kit bubble must not erase the page
1. Select **ML Kit / on-device** and translate a page with at least 6 dialogue bubbles,
   including one long narration bubble.
2. Repeat on a page where OCR text is difficult or partly obscured.
3. **PASS:** successful bubbles remain translated in their original positions; a bubble
   that cannot be translated remains blank/original-art (never English painted as Arabic);
   one failed bubble does not make the entire page disappear.
4. For a long narration split into chunks, **PASS:** the full narration is shown only when
   every chunk succeeds; otherwise that bubble stays blank rather than showing a truncated
   beginning. Other bubbles remain visible.
5. **FAIL:** successful neighbours disappear, English is painted as Arabic, or a partial
   narration is presented as complete.

### Retired P2-08 — LibreTranslate partial quality rejection (removed in v0.12.3)
1. Only if a trusted HTTPS LibreTranslate test endpoint is available, select it and translate
   a page with multiple bubbles.
2. **PASS:** valid Arabic responses are kept; any rejected/English-echo bubble remains blank;
   the valid neighbours are not discarded. The page name spelling is preserved consistently.
3. **FAIL:** one rejected bubble erases otherwise valid translations, or page names are
   changed inconsistently.
4. If a deterministic test endpoint is available, configure it to return one valid Arabic
   result and one English echo in the same response; this is the direct regression case.
   Do not use an untrusted public endpoint.

### P2-16 — Gemini truncation recovery
1. Select Gemini with a valid personal API key and translate a dense page (many bubbles or
   long dialogue) that may approach the model output limit.
2. **PASS:** complete bubbles are retained; only missing bubble IDs are retried; when the
   response reports MAX_TOKENS, the retry uses smaller batches; no completed bubble is
   needlessly translated again.
3. **FAIL:** the entire page is discarded despite usable replies, completed bubbles are
   duplicated/replaced, or the same oversized batch is retried unchanged.
4. If the page does not trigger truncation, record **NOT TRIGGERED**; do not treat that as
   a pass for the MAX_TOKENS path.

### P2-14 — mixed and short output quality
Use dialogue that naturally includes a name such as `Eren` or `Han Ji-woo`, plus short
answers such as “Yes!” and “No!”.
- **PASS:** Arabic “نعم” / “لا” is accepted; a single preserved Latin name inside Arabic
  dialogue is accepted; a clearly untranslated English fragment or a very short Arabic
  answer to a long source is withheld rather than painted or cached.
- **FAIL:** valid short Arabic dialogue is blanked, a preserved name is rejected, or an
  obviously incomplete/English-mixed translation is shown as valid.
- Record the exact source/output pair for every unexpected result. These cases may need
  a deterministic engine fixture to reproduce; do not infer the internal validator result
  from appearance alone.

### Evidence to return
- Device model and Android/HyperOS version.
- App version shown in Settings and selected translation engine.
- PASS / FAIL / NOT TRIGGERED for each P2 item.
- Settings → Diagnostics → Live self-check → Copy report.
- Screenshots of any incorrect patch; redact API keys and private content.

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

# Historical v0.10.0 — self-hosted translation tests (retired in v0.12.3)

> Do not run the LibreTranslate setup/tests below: the engine, client, and tests were removed in v0.12.3. Current verification is the v0.12.3 section at the top of this file.

## Setup

The app ships an HTTPS client only. Run a LibreTranslate server with an installed English→Arabic Argos package, expose it through HTTPS, then enter the server base URL (or `/translate` URL) under Settings → Translation → LibreTranslate / Argos. A server API key is optional. Do not use an endpoint you do not trust.

## Retired M0.1 HTTPS and settings
1. Select LibreTranslate / Argos; enter the server HTTPS URL and optional key; Save.
2. Try `http://...`, a URL containing credentials, or a URL with query parameters.
**Expected:** these are rejected locally; no request is sent. The settings note says only OCR text is sent and the app does not run the server.

## Retired M0.2 Batched API contract (unit test)
**Steps:** Run `LibreTranslateWireTest` and `LibreTranslateTranslatorTest`.
**Expected:** one JSON request contains the page's `q` array, source `en`, target `ar`, format `text`, and optional key in the JSON body; returned translations map back to the original bubble IDs/order.
**Pass:** request/response and malformed-response tests pass without a live network.

## Retired M0.3 Progressive tap and Live fallback (device + trusted test server)
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

## Historical M0.5 Permissions and privacy
**Steps:** Inspect app permissions and both privacy-policy versions; verify that Offline mode blocks Gemini and that PaddleOCR/ML Kit Translation work locally.
**Expected:** `INTERNET` permission is present for optional network engines; request is HTTPS-only; API keys are never logged or put in the URL; PaddleOCR and ML Kit process OCR/translation locally; only optional Gemini features send data to Google.
**Pass:** data flow matches `PRIVACY.md`, `privacy-policy.html`, and the in-app policy.

## M0.6 Build and release checks
- `./gradlew :app:testDebugUnitTest`
- `./gradlew :app:assembleDebug`
- Confirm `versionCode = 29`, `versionName = "0.10.0"`.
- Manually verify tap and Live on a supported Android device. The sandbox cannot validate overlay timing or real-device OCR accuracy.


## v0.12.11 — release update and cache/telemetry verification

1. Install the v0.12.11 APK directly over v0.12.10; do not uninstall the app. **PASS:** Android treats it as an update and preserves app data/settings.
2. Open Settings → Diagnostics, enable **Debug mode**, disable Live mode temporarily, then run a tap capture on a page with visible English dialogue.
3. Copy **Settings → Last error** (or **Pipeline trace**). Use the P4-A checklist below to verify the OCR telemetry sections and compare raw OCR with the final overlay.
4. Restart the app and capture the same page again. **PASS:** no stale blank/label-only cache entry becomes visible; cache-hit trace details identify the actual cache tier; exact/fuzzy memo hits also show `matched=...` with the source that populated the memo entry; corrected visible Arabic remains consistent after restart.
5. Turn **Debug mode OFF** after collecting evidence. Share the report only if comfortable with the page text it contains.

## P4-A — collect an OCR/ML Kit evidence report (tap path)

1. Open **Settings → Diagnostics** and turn **Debug mode ON**.
2. Pause/turn off Live mode for this check, then open a manhwa page with visible English text and tap the floating lens once.
3. Return to **Settings → Last error** (or **Pipeline trace**) and press **Copy**.
4. Confirm the copied report contains `[OCR ENGINE STATS] present=true`, `[RAW OCR LINES]`, `[GROUPED BLOCKS]`, `[KEPT FOR TRANSLATION]`, `[ML KIT RAW CALLS]`, and `[OVERLAY TEXT]`. The engine section should identify PaddleOCR and include stage/cold-load timings, tensor shapes, effective thresholds/resize/thread settings and low-confidence counts. Raw OCR lines should include `confidence=` and `box=left,top,right,bottom`.
5. For every visible failure, trace the stage in order: raw OCR text/confidence/bounds → grouped block membership → kept-for-translation decision → prepared ML Kit input → raw ML Kit candidate → final overlay text. A bad source at the first stage is OCR recognition; a correct source assigned to the wrong group is OCR grouping; a correct prepared input with bad Arabic is translation/post-processing. A missing raw-call entry can mean a cache hit; check the pair log's `request`, `status` and `detail` before treating it as missing. All records sharing `request=...` belong to one `translatePage` call. Block numbers may restart at 1 in a different request/capture; do not interpret the same block number alone as a collision. `CACHE_HIT`/`FUZZY_HIT` details identify the cache tier; exact/fuzzy memo details also include `matched=...`, the original glossary-prepared source stored in the memo slot. Compare it with `EN:` to determine whether a near-match actually reused a different source. Compare that value with `EN:` to decide whether the result was exact reuse or a near-match from a different OCR string.
6. Repeat on 3–5 representative pages, including short dialogue, long narration, sound effects and vertically stacked lines. Keep original screenshots where a bounding-box or grouping error needs visual comparison.
7. Turn **Debug mode OFF** after collecting evidence. The report remains in app-private storage until a later report replaces it; do not share it if the page contains private content.

**Expected:** normal captures with Debug mode OFF do not include page dialogue in the generic pipeline report. These diagnostics do not score semantic correctness automatically; review Arabic meaning manually. Live-mode per-frame raw text is not included in this P4-A tap-path pass.


## P5-A — composite patch rendering (v0.11.5, device)

1. Install **ManhwaLens v0.11.5** and select **ML Kit / on-device**. Keep Debug mode ON for this test.
2. Translate a page containing a multi-line speech bubble, especially where OCR may split the bubble into adjacent blocks. Use both a short two-line bubble and a longer bubble with at least four visible lines.
3. Capture the page before and after translation. Repeat the same page twice to include both fresh translation and cache-hit paths.
4. **PASS:** Arabic from all translated member blocks remains visible together; a later sibling result does not erase earlier Arabic; one patch covers the full planned area; no English line is left visible inside that area when its member blocks were retained.
5. **FAIL:** only the last translated line remains, Arabic disappears after another block completes, or the patch covers only the first portion of a grouped area.
6. Repeat once with the remote engine enabled if configured. A remote upgrade must replace the corresponding composite area without removing other sibling translations.
7. Copy **Settings → Diagnostics → Last report** after the test. Confirm `[OVERLAY PATCHES]` contains `patch=`, `cover=`, and `members=` values. Keep the original and translated screenshots together with the report. Turn Debug mode OFF afterward.

**Scope:** this test verifies rendering and patch aggregation only. It does not establish semantic translation quality, glossary coverage, or OCR accuracy. Those are evaluated separately.

 
# v0.12.4 — Bergamot performance/lifecycle follow-up

1. With the Bergamot model installed, open Settings and wait for model-card prewarming. Translate the same 20-bubble page three times; record cold and warm timings.
2. Open Diagnostics → Pipeline trace. PASS: report includes Bergamot prepare, native batch and page total timings, plus input/unique/duplicate counts. An ordinary page should remain one native batch.
3. Test a long page with many bubbles and long narration. PASS: it is processed in sequential bounded batches without a native crash, missing IDs or reordered bubbles.
4. Test OCR text containing a word split by a hyphen across lines and text containing zero-width characters. PASS: wrapped words are healed, zero-width artifacts do not create visible spaces, and original OCR text remains available in translation-pair diagnostics.
5. While Bergamot is selected, trigger Android memory pressure or stop the overlay service, then start it again and translate. PASS: native allocations are released safely and the engine reloads on demand.
6. Repeat the same source page with ML Kit and Bergamot, clearing saved translation caches and translation-pair logs between runs. Compare semantic quality, rejected bubbles, median/P95 latency and memory use. Do not judge Bergamot by OK status alone.

Context limitation: the current Bergamot JNI bridge accepts independent text segments and exposes no document/context input. Do not concatenate neighboring bubbles to simulate context because that can corrupt bubble-to-translation mapping. Existing per-series name consistency and shared Arabic quality/post-processing remain active.


7. Run `BergamotTextPreparationTest`. PASS: OCR line joining, hyphenated line-break repair, zero-width cleanup and adaptive batching tests pass on the JVM.
8. Inspect the `translation quality` trace. PASS: accepted/rejected/empty/missing counts are present and the percentage is labelled rule-based, not a semantic translation score.


# v0.12.7 — isolated ML Kit vs Bergamot comparison

1. Install the ML Kit EN→AR model and the Bergamot EN→AR model before starting. Keep Wi-Fi/mobile data off after both models are ready.
2. Open Settings → Diagnostics → **Compare ML Kit vs Bergamot**. **PASS:** the comparison completes offline and shows all 20 stable case IDs for both engines.
3. Confirm both engines receive identical glossary-prepared, single-line source text. **PASS:** the report shows the same categories and preserves each source/reference/output pair.
4. Confirm each engine reports one first pass separately and three warm passes, with warm median/worst timings. The ML Kit model is pre-warmed; Bergamot's first pass may include native model loading.
5. Confirm accepted/rejected, blank, English echo/fragment, exact-match and lexical token-F1 metrics are reported separately. **PASS:** the report explicitly says token F1 is not semantic accuracy; manually review the Arabic meaning case by case.
6. Confirm a failed or malformed batch is retried per case and counted as a batch recovery; one bad case must not erase other outputs.
7. Run the comparison while the app's ML Kit batching self-test would otherwise be eligible. **PASS:** the benchmark-only warm-up does not start a new self-test, and the shared diagnostic lock covers both warm-up and all measured passes so an existing or pending self-test cannot compete with the comparison.
8. Record phone model, Android version, first/warm timings, screenshots of the report and any native errors. Do not promote Bergamot or change the default from this report alone; semantic review, OCR checks, memory observation and update-install validation remain required.

## v0.12.12 — translation-cache provenance capture

**Purpose:** determine whether an incorrect short-dialogue result came from an exact cache entry, a fuzzy memo entry from a different OCR source, or a fresh engine response. This build adds per-page request IDs and the original source that populated each exact/fuzzy memo hit.

1. Install **v0.12.12** directly over v0.12.11; do not uninstall. Keep the existing translation cache and app data intact.
2. Open Settings → Diagnostics. Turn **Debug mode ON**. Press **Clear** for the translation-pair log only; do **not** press the translation-cache reset action.
3. Keep **ML Kit / on-device** selected and open the same Solo Leveling chapter-3 page from the earlier device report. Reproduce the two short OCR sources `AH...` and `YEAH` in the same reading session (and any other previously failing short reply). Capture the page/request containing both when possible. Do not change translation style while testing.
4. Return to Settings → Diagnostics and press **Copy** beside the translation-pair count. Share the full report. Each record now displays `request=...` and `block #...`; records with the same request ID belong to the same page translation call.
5. Specifically compare the `AH...` and `YEAH` entries. They must not receive the same cached Arabic merely because the lines are short. For `FUZZY_HIT`, inspect `detail=...matched=...`; compare `matched` with `EN:`. A different matched source proves which memo source supplied the Arabic candidate. `CACHE_HIT` identifies exact in-memory or persistent cache reuse; a fresh `OK` entry identifies a new engine result. Legacy entries without request IDs are shown as `request=legacy`.
6. If the failure concerns OCR source text rather than cache reuse, also copy the Last capture / pipeline report so raw OCR text and bounding boxes can be compared with the translated pair.
7. Turn **Debug mode OFF** after collecting the reports. Do not clear the translation cache before the reproduction, because preserving it is necessary to investigate stale cache entries.

**Privacy:** Debug mode records page text in app-private storage and the copied report includes it. Review the report before sharing.


**Historical regression target:** the previous device report showed `AH...` and `YEAH` incorrectly served the same unrelated Arabic after fuzzy normalization collapsed both to a one-character shape. The current fuzzy policy must refuse ambiguous short shapes, and a fuzzy result must never be promoted under a different exact source key. The v0.12.12 report is intended to verify that behavior on the phone, not merely repeat the JVM tests.

## v0.12.13 — repeated-page OCR memory stability

**Purpose:** validate that PaddleOCR's OpenCV preprocessing and perspective-crop intermediates are released correctly across repeated recognition batches. This change must not alter OCR tensor geometry, BGR channel order, normalization, confidence filtering or reading order.

1. Install **v0.12.13** directly over v0.12.12; do not uninstall or clear app data.
2. Keep PaddleOCR selected for OCR and ML Kit for offline EN→AR translation. Turn **Debug mode ON** for captures.
3. Capture one dense page containing many small dialogue lines, several multi-line bubbles and at least one rotated/vertical text crop. Repeat the same capture 5 times.
4. Capture 5–10 additional pages with varied density and shapes. Keep the app running between captures; do not restart it during the sequence.
5. **PASS:** no crash, native OpenCV error, blank OCR response caused by a preprocessing failure, or progressive slowdown that persists across equivalent captures. For the repeated identical page, compare raw OCR line count and boxes; large output differences need screenshot inspection rather than an automatic pass/fail based on count alone.
6. Copy the Last capture / pipeline report after the final capture. Include Android version, device model, and whether the app remained responsive. Check Android system app-memory usage before and after the sequence when available.
7. Turn **Debug mode OFF** after collection. Do not clear translation caches unless investigating an unrelated translation issue.

**Scope:** this is a native-resource lifecycle check. CI verifies unit tests and APK packaging; only the phone can reveal device-specific OpenCV/ONNX memory pressure and long-session stability.


## Chapter 4 — ML Kit batching and translation-quality follow-up (2026-11-16)

**Device report received:** Xiaomi 23106RN0DA / Android 14. This is a real-device report supplied by the user.

### Batching evidence
- 20 bubbles; five passes per method, first discarded; four measured passes.
- Joined ` | `: median 573 ms, worst 576 ms, 20/20 mapped on every measured pass, one ML Kit request.
- Sequential: median 969 ms; parallel x2: 979 ms; x3: 1357 ms; x4: 1096 ms; x6: 1005 ms.
- Joined ` | ` is the fastest valid strategy in this sample (40.9% lower median than sequential). `@@@`, newline and double-newline strategies failed mapping and must not be selected for this sample.
- User report says the verdict was applied. The selected offline translation engine did not change.

### Translation quality evidence and limitations
- Fixed 23-case EN→AR reference set: raw/cleaned mean token F1 49.6%, visible mean token F1 74.8%; visible exact 9/23. The visible score includes seven exact source corrections. Token F1 is lexical overlap only, not semantic accuracy.
- The 59-entry chapter-4 pair trace shows many validly accepted but semantically weak ML Kit outputs (e.g. rank/dungeon phrasing, healer/raid lines, and awkward sentence structure). The Arabic guard is a script/echo/shape guard, not a semantic-quality evaluator.
- OCR source strings also contain likely recognition errors/wrap artifacts (e.g. `OLT` for `OUT`, `BLYING` for `BUYING`, `QLICKLY` for `QUICKLY`, `STATLES` for `STATUES`). Do not silently treat these as translation-engine defects or introduce broad OCR substitutions without image-level evidence.
- The high-latency page request `ed782881-p17` took 20,430 ms for three bubbles. This is a separate latency outlier from the 20-bubble batching benchmark and needs a focused follow-up with its stage trace before changing global timeouts/concurrency.

### Source-exact repair follow-up
- Added conservative exact-source repairs in `ShortDialogueOverrides` for observed chapter-4 strings (rank/dungeon terminology, raid/healer dialogue, hunter description, and final danger exchange).
- Added JVM regression coverage for observed normalized OCR variants. These repairs are intentionally exact-source only; they are not a general language model and do not prove semantic quality for unseen sentences.
- CI/build verification for these new changes is pending. Do not publish a new APK until the current branch build and all unit tests pass.

### Device recheck after the next CI-verified APK
1. Install the new APK as an update over v0.12.13; do not uninstall or clear app data.
2. Keep ML Kit selected and Debug mode enabled. Clear only the translation-pair history, not the translation cache.
3. Revisit the same chapter-4 page(s). Confirm the observed exact-source lines now show the repaired Arabic, and check that unrelated lines are unchanged.
4. Copy the complete translation-pair report and Last capture / pipeline trace. Include any page-level delay over 3 seconds.
5. Report any source OCR mismatch separately from a wrong Arabic translation.

**Current gate:** source-level repairs committed; automated CI and device confirmation are not yet verified.


## v0.12.14 — chapter 4 translation repair recheck (pending release)

Do not install until GitHub Release v0.12.14 exists and CI confirms package/version/signer continuity against v0.12.13.

1. Install v0.12.14 directly over v0.12.13; do not uninstall or clear app data.
2. Keep ML Kit selected and Debug mode enabled. Clear only translation-pair history, not the translation cache.
3. Revisit the same Solo Leveling chapter-4 pages. Verify repaired rank/dungeon, raid/healer, hunter-description and danger-dialogue lines; also confirm unrelated lines remain unchanged.
4. Copy the complete translation-pair report and Last capture / pipeline trace. Include any page-level delay over 3 seconds.
5. Distinguish OCR source errors from Arabic translation errors. Include the exact source text and visible output for each remaining defect.


# v0.12.15 — chapter 4 OCR-variant recheck

1. Install v0.12.15 over v0.12.14 without uninstalling or clearing app data. Keep ML Kit selected and Debug mode enabled.
2. Clear translation-pair history only; preserve translation cache. Revisit the same Solo Leveling chapter-4 pages.
3. Confirm “WHAT WAS RANK OF THE THE ONE WHO DIED JUST NOW?” renders as “ما رتبة الشخص الذي مات للتو؟” and the observed “WOULO'VE” sentence renders as “حتى لو كان ضعيفًا، لظل أقوى مني، فأنا من الرتبة E.”
4. Confirm unrelated dialogue remains unchanged. Collect the complete pair report and Last capture / pipeline trace; distinguish source OCR errors from Arabic errors and include any page over 3 seconds.
5. Keep the ML Kit batching preference at parallel x6 unless a real-page repeat validates mapping for all bubbles. The fixed benchmark alone is insufficient to remove the existing separator ban.

**Gate:** release and signer/update compatibility must pass CI before installation; device confirmation is still required.


# v0.12.16 — Solo Leveling chapter 5 translation recheck

1. Install v0.12.16 over v0.12.15 without uninstalling or clearing app data. Keep ML Kit selected and Debug mode enabled.
2. Clear translation-pair history only; preserve the translation cache. Revisit the same chapter-5 page.
3. Check the tunnel/gate narration, hunter/rank explanation, healer dialogue, guard statue descriptions, first commandment lines and corrected short replies.
4. Confirm corrected Arabic appears and neighboring lines remain unchanged. Collect the full pair report plus Last capture and pipeline trace. Include page latency above 3 seconds.
5. Keep current parallel x2 unless a real-page run verifies full bubble mapping. Fixed-corpus speed alone does not remove the prior real-page separator ban.

**Gate:** wait for CI, signature/update compatibility and a published APK before installing. Device verification remains required.


# v0.12.17 — Solo Leveling chapter 5 dialogue follow-up

1. Wait for the candidate to pass unit tests, APK build, signature verification and update-compatibility checks, then install v0.12.17 over v0.12.16 without uninstalling or clearing app data.
2. Keep ML Kit selected and Debug mode enabled. Clear translation-pair history only; keep the translation cache.
3. Revisit the same chapter-5 pages. Check the bowing/commandment lines, survival questions, “gut feeling” line and short reactions corrected in this build. Confirm neighboring text remains unchanged.
4. Return the app version from Settings, the full pair report, Last capture and pipeline trace. Identify any page over 3 seconds and include both captured English and visible Arabic for remaining errors.
5. Keep ML Kit parallel x4. Joined pipe remains banned unless a real-page run demonstrates complete mapping without mismatch.

**Gate:** app version in the prior report was not stated. Do not label these source-specific corrections phone-verified until v0.12.17 is installed and the same-page report is returned.


# v0.12.18 — capture latency check

1. Install v0.12.18 over v0.12.17 without uninstalling or clearing app data. Keep ML Kit and Debug mode enabled.
2. Use the same device and the same page used for the previous approximately 4-second capture. Clear pair history only; preserve the translation cache.
3. Capture the page once and note tap-to-first-Arabic and tap-to-complete-overlay time. This is a speed check, not a translation-quality retest.
4. Copy Pipeline trace and Last capture. Compare capture frame wait, OCR, translation and overlay durations with the previous trace; confirm no ManhwaLens UI was included in OCR.
5. Report any page still taking over 3 seconds.

**Gate:** CI build, APK signer and update compatibility must pass before installation. The shorter wait is not confirmed faster until measured on-device.


# v0.12.19 — OCR detector speed check

1. Install v0.12.19 over v0.12.18 without uninstalling or clearing app data. Keep ML Kit and Debug mode enabled.
2. Use the same page and device as the previous speed capture. Clear pair history only; preserve translation cache.
3. Measure tap-to-first-Arabic and tap-to-complete-overlay separately. This is one performance check; do not repeat the translation quality corpus.
4. Copy Pipeline trace and Last capture. Compare detector inference/input shape, OCR total, ML Kit translation and overlay timings against the previous capture.
5. Confirm small text is still detected. Report any page over 3 seconds and whether the app UI appears in OCR.

**Target:** reduce OCR work while preserving captured resolution. A full-page ≤1 s result is not claimed until measured, and may exceed one second for pages with many bubbles because the previous 20-bubble ML Kit batch itself measured about 1.03 s.


# v0.12.20 — benchmark and one-page speed check

1. Install v0.12.20 over v0.12.19 without uninstalling or clearing app data.
2. Run Settings → Diagnostics → ML Kit batching benchmark once. Save the complete table, including per-round runs and range; it now interleaves strategies. Do not run the 23-case quality benchmark.
3. Translate one ordinary page once with Debug mode on and copy Pipeline trace plus Last capture. Record tap-to-first-Arabic and tap-to-complete if shown.
4. Compare capture, OCR, ML Kit and overlay stages separately. The batching table measures only the translation-engine request strategies and cannot stand in for the full page time.
5. Include the app version shown in Settings. One run is sufficient; do not repeat unless it reports an error.


# v0.12.21 — Live OCR interruption recovery

1. Install v0.12.21 over v0.12.20. Keep Live mode enabled and ML Kit selected.
2. Start Live on a page after a fresh service start so PaddleOCR initialization is exercised. Let the first stable frame finish, then scroll normally through several small page changes.
3. Confirm Live returns to Waiting after translating and resumes on each stable frame. A shutdown/cancel interruption must not leave a false ERROR status or an unhandled crash report; genuine OCR failures should still appear as ERROR.
4. If the app is stopped while OCR is initializing, confirm it does not record that expected cancellation as a Live crash. Send Live diagnostics/Last error only if the error repeats.


# v0.12.22 — Live session benchmark

1. Install v0.12.22 over v0.12.21 without clearing app data.
2. In Settings → Diagnostics, arm **Live session benchmark** while Live is OFF.
3. Turn Live ON on a manga page, wait for the first Arabic overlay, scroll through a few changes, then turn Live OFF.
4. Return to Diagnostics and copy the Live report. It should show ON→OFF duration, first overlay latency, frame count, mean/max pass time, OCR time, engine time and visible outputs. It must not contain recognized or translated page text.
5. If the first translation appears late, compare first-overlay latency with OCR, engine and frame times to locate the delay. The existing ML Kit batching benchmark remains a separate engine-only diagnostic.

**Gate:** one Live session is enough. CI verifies build/tests; this on-device capture verifies the end-to-end timing boundaries and first-overlay measurement.


# v0.12.23 — Live OCR detector timing and speed check

1. Install v0.12.23 over v0.12.22 without clearing app data. Use the same device and manga page used for the previous Live session measurement.
2. Arm Live session benchmark while Live is OFF, switch Live ON, wait for the first Arabic overlay, scroll through a few page changes, then switch Live OFF.
3. Copy the Live report once. Compare detector and recognizer total/inference times with OCR total, and inspect the first-frame entry, first-frame work and delay outside it.
4. Confirm that small text remains detected. Do not rerun the ML Kit batching or translation-quality corpora.

**Gate:** detector input is capped at 1280 px on the long side, expected to reduce detector pixels by about 36% versus the previous 1600 px cap for a 720×1600 frame. This estimates detector workload only; it is not a claimed end-to-end speedup. Check small-text recall in the same Live session.


## v0.12.24 — unified Live benchmark
1. Install the CI-built v0.12.24 APK. On the Diagnostics page, confirm the ML Kit batching, Bergamot-only and ML Kit-vs-Bergamot benchmark controls are gone; the Live self-check remains.
2. With Live OFF, arm the single Live session benchmark. Turn Live ON, view a page and scroll through a few changes, then turn Live OFF.
3. Copy the report. Confirm it contains session/first-output/frame timings, OCR detector and recognizer timings plus counts/configuration, translation and cache counts, overlay counts, state transitions and device/app details.
4. Confirm no page text appears in the report. Use the report to identify the dominant measured stage; this is measurement only and does not claim a speedup.

**Expected:** one completed Live report with counts and timings only. Do not rerun the translation corpus benchmarks.
