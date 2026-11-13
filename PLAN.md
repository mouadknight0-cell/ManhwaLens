# ManhwaLens — Engineering Plan

**Package:** `com.manhwalens.app` · **minSdk:** 26 · **targetSdk/compileSdk:** 36 · **Language:** Kotlin · **UI:** Jetpack Compose + Material 3
**Tagline:** "Floating screen translator for manhwa, manga & webtoons — EN text in, Arabic text out, right on top of the original."

Status: **M5 speed/settings delivered — build green + 68/68 unit tests green + lintVitalRelease green + signed release v0.7.0 (2026-11-11).** R8 minify pass is BLOCKED on this 2 vCPU / 1.9 GB box (OOM-kills the Gradle daemon even with -Xmx1400m + -dontoptimize); release ships signed & unminified via a `-Prelease.minify=true` escape hatch, with proguard-rules.pro prepared. On-device timing + soak checks pending.

---

## 1. Product summary

ManhwaLens is an Android utility (not a reader) that works **over any app**:

1. User taps a **floating button** (`TYPE_APPLICATION_OVERLAY` icon, draggable).
2. App captures the current screen (MediaProjection) as a bitmap.
3. On-device **OCR** (ML Kit Text Recognition, Latin script) extracts English text **with bounding boxes**.
4. On-device **translation** (ML Kit Translation, EN → AR) converts each text line/block to Arabic.
5. Arabic text is **rendered as an overlay** precisely where the English text was, matching line width & position as closely as possible, respecting Arabic shaping and RTL.

Primary use case: reading licensed manhwa/manga apps or browsers that have no built-in Arabic layer.

**Non-goals (v1):** CJK source languages (Chinese/Japanese/Korean), manga vertical typesetting, video/live-frame translation, fully automatic background translation, iOS/desktop versions, account systems.

---

## 2. Key decisions (confirmed)

| Decision | Choice | Notes |
|---|---|---|
| Package / applicationId | `com.manhwalens.app` | |
| Source → target language | English → Arabic | |
| Translation backend | **On-device ML Kit Translation** (`en→ar`, downloadable ~30 MB model) | Free, private, works offline after first download. |
| OCR | **ML Kit Text Recognition v2 (Latin)** | Bundled or Play-delivery variant (see M2). |
| UI language | **English (LTR)** for v1 | Overlay Arabic text is rendered RTL internally regardless of UI language. |
| Capture API | MediaProjection + `VirtualDisplay` + `ImageReader` | Requires user consent per session (Android 14+). |
| Overlay | WindowManager, `TYPE_APPLICATION_OVERLAY` | Needs `SYSTEM_ALERT_WINDOW`. |
| Architecture style | Single `:app` module, Clean-ish layering (ui / domain / data / capture / ocr / translate / overlay) | Split into `:core:*` modules only if it grows. |
| Build | Gradle 9.0.0 + AGP 8.13.2 + Kotlin 2.4.21, Compose BOM 2026.09.00 | Verified compatible pair (Gradle 9.0.0 ↔ AGP 8.13.2). |

---

## 3. Module & package layout (target, inside `:app`)

```
com.manhwalens.app
├── ManhwaLensApp.kt                 // DONE (M1): Application + notification channel
├── MainActivity.kt                  // M1: overlay permission + floating-button toggle screen
├── ui/
│   ├── home/                        // (folded into MainActivity for now)
│   ├── settings/
│   └── theme/
├── ui/components/LensGlyph.kt       // DONE (M1): shared brand glyph
├── data/OverlayPrefs.kt             // DONE (M1): SharedPreferences (DataStore in M4)
├── overlay/
│   ├── FloatingButtonService.kt     // DONE (M1+M2a): button + capture session (FGS mediaProjection)
│   ├── LensButtonView.kt            // DONE (M1): brand lens drawn on Canvas
│   ├── TranslationOverlayWindow.kt  // M3
│   └── overlay/ArabicTextRenderer.kt // M3 (shaping + RTL + auto-fit to bbox)
├── capture/                         // M2a DONE: CaptureConsentActivity (consent trampoline),
│                                    // CaptureDebugWindow (bitmap + meta debug overlay), CaptureMeta
├── ocr/                             // M2b: ML Kit Latin OCR + LineGrouper
├── translate/                       // M3: ML Kit en→ar, model download, batching + cache
├── domain/                          // M2b+: TranslateScreenUseCase + models
└── di/                              // manual DI for now; Hilt only if graph grows
```

**Data flow (one translation cycle):**

```
Tap floating button
  → ensure capture consent (MediaProjection) → start/bind foreground service
  → capture 1 frame (ImageReader, max 1 in-flight)
  → downscale if > N px; EXIF/display-rotation aware
  → ML Kit OCR (English/Latin) → List<TextBlock> with BoundingBox
  → LineGrouper: merge words → lines → paragraphs (reading order: top→bottom, LTR; RTL-aware re-ordering for Arabic output)
  → TranslationBatcher: dedupe, batch, ML Kit en→ar (on-device)
  → OverlayPlacement: map OCR bbox → overlay window coordinates (display density, rotation, status bar offsets)
  → TranslationOverlayWindow: full-screen transparent TYPE_APPLICATION_OVERLAY, draws Arabic via Compose/StaticLayout (NOT raw Canvas text — see §7.4)
  → Tap-through: overlay is touch-non-modal; floating button remains the only interactive element; long-press/tap overlay to dismiss
```

---

## 4. Permissions (target manifest, M1+)

| Permission | Why | When requested | Notes |
|---|---|---|---|
| `INTERNET` | Download ML Kit models (EN→AR) + Play Services delivery | declared, no runtime ask | Only for model fetch; zero data leaves the device. |
| `SYSTEM_ALERT_WINDOW` | Draw floating button + translation overlay over other apps | Special access: `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` | Cannot be runtime-granted; onboarding wizard required. |
| `FOREGROUND_SERVICE` | Keep capture service alive during grab | declared | |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Required on API 34+ for mediaProjection FGS | declared | FGS type must be `mediaProjection`. |
| `MEDIA_PROJECTION` | Screen capture consent token | via `mediaProjectionManager.createScreenCaptureIntent()` | Consent is per-session on Android 14+; must re-prompt. |
| `POST_NOTIFICATIONS` | FGS persistent notification (API 33+) | Runtime request | Denial → capture still works but service may be killed; guide user. |
| `RECEIVE_BOOT_COMPLETED` *(optional, M5)* | Restore floating button after reboot if user enabled autostart | Runtime-ish (normal permission, needs user toggle) | Skip for v1 unless requested. |
| ~~`BIND_ACCESSIBILITY_SERVICE`~~ | **Rejected** | — | Not needed; avoids Play policy scrutiny. |

**Onboarding order:** (1) explain value → (2) overlay permission → (3) notifications → (4) capture consent trial → (5) download EN/AR models on Wi-Fi (with progress + offline note).

**Play policy:** apps using `MediaProjection` must declare the FGS type, show the system consent UI per session, and document in the store listing that screen content is processed on-device only. Privacy policy required; Data Safety form: no data collected/shared; all processing on-device.

---

## 5. Milestones

- **M0 — Project scaffold (DONE):** Gradle project, Compose placeholder, theme, launcher icon, PLAN.md. ✅ build green 2026-11-11
- **M1 — Overlay foundation (DONE 2026-11-11; on-device verification pending):** floating lens button service (`TYPE_APPLICATION_OVERLAY`, drag, tap, snap-to-edge, position persisted, `START_STICKY`), overlay-permission onboarding card with deep-link to `ACTION_MANAGE_OVERLAY_PERMISSION`, toggle + auto-restore from MainActivity, `POST_NOTIFICATIONS` runtime request hook, notification channel `manhwalens_status` in `ManhwaLensApp`. **Notes:** (a) since M2a the SAME service also owns the capture session and is promoted to FGS `mediaProjection` while a projection is alive; otherwise it is a plain service; (b) state stored in SharedPreferences — migrate to DataStore in M4. **Exit for you to confirm:** button floats over a manhwa reader, drags, snaps to edge, toggle on/off works.
- **M2a — Capture + debug view (DONE 2026-11-11, build green; on-device verification pending):** tap lens → single frame `Bitmap` (RGBA_8888, rowStride-correct copy) → full-screen debug overlay (tap to dismiss) showing resolution, capture ms, first-frame wait ms, save path (`cacheDir/capture_*.jpg`), 9-point near-black heuristic for FLAG_SECURE apps. Android 14+: `CaptureConsentActivity` trampoline → FGS `mediaProjection` promoted BEFORE `getMediaProjection()`; token is per-session; after Stop / system revoke (`MediaProjection.Callback.onStop()`) / process death, next tap re-requests consent; deny → toast. One service holds button + capture (no IPC; taps arrive as View callbacks). Logging tag `MLCapture` (consent, projection acquire/release, display-create ms, frame wait ms, bitmap bytes, total ms, failures with stack traces). Notification carries a **Stop ManhwaLens** action. **Exit for you to confirm:** capture works in a manhwa app, browser, home screen; deny → re-request works; rotation re-captures correctly; 10 captures in a row — no crash, no visible memory growth.
- **M2b — OCR + bubble grouping (DONE 2026-11-11, build green; 10/10 JVM unit tests green; on-device verification pending):** ML Kit Text Recognition v2 **Latin, bundled model** (`com.google.mlkit:text-recognition:16.0.1`) — offline, zero Play-Services dependency, at the cost of ~40 MB in APK (all ABIs). `TextRecognizerClient` = blocking wrapper on the capture executor (`Tasks.await`), logs `OCR done: N lines in M ms` (tag `MLCapture`). `BubbleGrouper` (pure Kotlin, O(n²) union-find over reading-order lines) merges lines into bubble blocks with a 4-guard predicate: vertical gap ratio ≤ 0.9× taller line, horizontal overlap ≥ 0.2× narrower line, font-size guard min(h)/max(h) ≥ 0.40 (SFX vs dialogue), center guard |Δcx| ≤ 0.75× wider line. `BubbleBoxRenderer` annotates the frame: amber thick = merged bubble (+ number chip), violet thin = raw line, red thin = confidence < 0.3. Debug window now shows `OCR N ms · lines · bubbles` (+ error line on failure). Raw frame still saved un-annotated to `cacheDir/capture_*.jpg`. Unit tests: `BubbleGrouperTest` (10 cases incl. single bubble, side-by-side split, SFX burst isolation, asymmetric widths, thresholds). **Known limitations (expected failures, to confirm on device):** SFX line sandwiched BETWEEN dialogue lines splits the bubble (no bridging pass yet); narrator caption directly above a bubble may merge with it; very tall bubbles with >0.9× gap split; very small-font lines (<0.4 height ratio) split off mid-bubble. **Size follow-ups:** strip x86 ABIs for release, or switch to Play-delivery OCR (decide at M5).
- **M3 — Translation engines + batch + cache + settings (DONE 2026-11-11, build green; 45/45 JVM unit tests green; on-device verification pending):** blocking `Translator` contract (never throws; background-executor call sites; no coroutines) with two engines: (a) ML Kit EN→AR on-device (`com.google.mlkit:translate:17.0.3`; EN→AR model ~30 MB downloaded on first use, then fully offline), (b) Google `gemini-2.5-flash` via REST (`x-goog-api-key` header ONLY — never URL/body/logs; key in app-private SharedPreferences, never in source). **One batched request per page** for both engines: ML Kit = joined `\n`-delimited blocks (page context) with per-block fallback on delimiter loss; Gemini = single `generateContent` with numbered `[id]` protocol (temperature 0.3, maxOutputTokens 8192). Response parsers map missing/malformed blocks to `PARSE_ERROR`. Error taxonomy: `NO_INTERNET` / `INVALID_API_KEY` (401/403) / `TIMEOUT` / `RATE_LIMITED` (429) / `MODEL_DOWNLOAD_FAILED` / `PARSE_ERROR` / `EMPTY_INPUT` / `UNKNOWN`. Cache-aside `CachingTranslator` over a process-scoped in-memory LRU (1000 entries, engine-namespaced, whitespace-normalized keys; failure → nothing cached; Room persistence deferred to M4). Settings card: engine toggle + Gemini key save/clear (device-private) + **Test translation** sample-page button + clear-cache action. Debug window now lists each bubble: `#id` + Arabic (RTL `TEXT_DIRECTION_RTL`) + English source, plus engine/ms/cache line in the header. Raw OCR/JPG pipeline unchanged. **Size:** `useLegacyPackaging=true` (native libs compressed inside the APK; everything else unchanged) → debug APK **~62 MB** with all 4 ABIs kept. A Play App Bundle + further ABI/Play-delivery decision remains for M5. **Not in M3 (deliberately, per milestone scope):** in-place Arabic rendering over bubbles (M4), model-download progress UI/Wi-Fi metered gate (M4). **Exit for you to confirm (manual checklist):** same page in both engines, airplane mode, wrong key, first-run model download, cache hit counter on repeat capture.
- **M4 — In-place Arabic bubble overlay (DONE 2026-11-11 for the overlay-rendering scope; build green + 63/63 JVM unit tests green; on-device verification pending):** capture now samples each bubble interior (5-bit channel histogram → dominant bucket → exact-bucket average; `render/ColorSampler.kt`) and draws the Arabic translation patch over the OCR box in the full-screen window: bundled **Cairo Regular/Bold** (OFL, `res/font/`, license at `licenses/Cairo-OFL.txt`), auto-fit font via `AutoFitTextSizer` + `StaticTextMeasurer` (StaticLayout + Cairo, exact same stack as the renderer) with a unit-testable `FakeMonospaceMeasurer`, RTL shaping via Compose + `LocalLayoutDirection.Rtl`, text color auto-picked by background luminance (`bestTextColorOn`), padding 4 dp, corner radius 4 dp, clip-to-bounds. The window (Compose inside `TYPE_APPLICATION_OVERLAY`) shows frame (ContentScale.Fit) + pills positioned through `computeContentRect`/`mapBox` (JVM-tested pure math), plus a footer with capture/OCR/translate stats and an **Original ↔ Arabic** toggle; tap the frame to dismiss. OCR annotation boxes are no longer drawn on the display path. Debug recompose path keeps raw frame pristine for sampling (sampling happens on the capture bitmap before any drawing). **Remaining/not delivered (still open from broader M4, NOT approved yet):** Room translation cache, model-download progress UI + Wi-Fi gate, failure-UX polish, bubble-merge heuristics tuning, analytics.
- **M5 — Speed + settings + privacy + signed release (DONE 2026-11-11 build-side; on-device verification pending):**
  - **Speed:** OCR input downscaled to ≤1600 px long edge for ML Kit (boxes scaled back to bitmap px, `BitmapScaler` + `TextRecognizerClient`); background/foreground split unchanged (single capture executor + separate `ioExecutor` for the best-effort debug jpg — no longer on the critical path); bubble background sampling now reads only each box's pixel region (≈100 KB) instead of a full-frame IntArray (~10 MB). Frame → overlay waterfall logged (`debug window shown — tap→overlay X ms (capture C ms, OCR O ms, translate T ms)`) and shown in the overlay footer (`tap→overlay`). No on-device before/after numbers yet (no device on this host) — see checklist.
  - **Loading & error states:** lightweight top-of-screen status pill (`StatusPillWindow`) — indeterminate spinner + "Capturing…" during the pipeline; red-ish auto-dismissing pill on capture/translate failure; full-screen debug view still carries the detailed error line.
  - **Arabic/English UI:** complete `values-ar/strings.xml` Arabic mirror of every UI string (incl. settings, pills, notifications, privacy policy, milestone labels); layouts auto-RTL via `android:supportsRtl="true"`.
  - **Settings screen** (`SettingsActivity`, launcher card on home): Arabic font size (Small 0.85 / Medium 1.0 / Large 1.2 multiplier applied to the auto-fit font on next overlay), translation engine + Gemini key test/clear (moved here), **auto-capture on scroll** (opt-in; accessibility service `ManhwaAutoCaptureService`, read-only `typeViewScrolled`, no screen content, 4 s cooldown, requires FAST an armed capture session — tap the lens once per session), About + Privacy policy (in-app, EN + العربية).
  - **Privacy:** `privacy-policy.html` (publishable, EN+AR summary) and `PRIVACY.md`; in-app policy screen; no new data collection — accessibility service strictly opt-in, read-only.
  - **Release:** versionCode 7 / **v0.7.0**; signed with fresh RSA-2048 upload keystore `keystore/manhwalens-release.jks` (material only in gitignored `local.properties`); apksigner-verified `CN=ManhwaLens`; **55.0 MB**. **R8/ProGuard could not run on this box** (reproducible daemon OOM at minifyReleaseWithR8 despite -Xmx1400m, non-optimize proguard-android.txt, single worker, in-process compiler, -dontoptimize). Mitigations: `proguard-rules.pro` prepared (ML Kit/gms/gms.tasks keeps + service keeps); `:app:lintVitalRelease` GREEN; signed release builds via property gate — on a ≥4 GB machine/CI: `./gradlew :app:assembleRelease -Prelease.minify=true`. Play-console forms, store listing, AAB remain open.
  - **Checklist (device):** timing before/after via the new `tap→overlay` footer line (v0.6.0 lacked the instrumentation — take the "before" from logcat sums `capture`+`OCR`+`translate`), 30-min soak, low-end device, battery/memory profile, cold start.
- **M6 — Post-launch:** manga vertical text, JP/ZH sources via `zh`/`ja` OCR+translation, bubble auto-detection, horizontal-scroll webtoon strip mode, accessibility hooks.

---

## 6. Risk register

| Risk | Impact | Mitigation |
|---|---|---|
| `FLAG_SECURE` windows (Netflix, some manga apps) block capture | Blank/black frames | Detect all-black/empty frames; show "this app blocks capture" toast instead of garbage OCR. |
| Stylized/lettering fonts defeat Latin OCR | Garbage translations | Confidence filtering (drop lines < 0.5), manual re-capture, future custom TFLite model; document limitation. |
| Arabic rendering via raw `Canvas.drawText` shows broken joining | Unreadable output (critical) | **Never use raw canvas text for Arabic.** Use `StaticLayout`/`ComposedText` path (handles shaping + bidi) — see §7.4. |
| ML Kit model download fails / no Wi-Fi | Hard blocker at first use | Pre-download UI in onboarding with retry + metered-data warning; graceful "model required" state. |
| Overlay window blocks touches to underlying app | App unusable | Overlay is non-interactive & tap-through except floating button; translations clear on outside tap (configurable). |
| Screen rotation / foldables | Misaligned overlay | Recompute mapping from `DisplayMetrics` + rotation listener; dismiss overlay on configuration change. |
| Android 14+ per-session MediaProjection consent | UX friction | Consent only at capture time; cache intent grant when allowed; explain in one-line toast. |
| High-DPI frames → OOM (2 GB heap on low-end) | Crash | Downscale to max 1600 px, reuse `Bitmap.Config.RGB_565` buffers, single in-flight frame, in-place prefs. |
| Play policy on screen capture | Rejection | On-device-only processing statement, prominent consent, privacy policy; video demo for reviewers. |
| License/IP of captured content | Legal | App never stores or transmits screenshots; memory-only, overwritten per frame; cache holds text only (opt-out). |

---

## 7. Technical notes

### 7.1 Capture
`MediaProjectionManager.createScreenCaptureIntent()` → user grants → `getMediaProjection()` → `createVirtualDisplay(name, width, height, density, VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, …)` with `Surface` from `ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 1)`. Each `acquireLatestImage()` → `HardwareBuffer` → copy to software `Bitmap` (crop status bar if desired). **Release projection after frame grab** (Android 14 requirement + battery). Wrap everything in `ScreenCaptureService : Service` with `foregroundServiceType = mediaProjection` and a persistent notification ("ManhwaLens is ready").

### 7.2 OCR
ML Kit `TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)` (Latin). Output: `Text` → blocks (paragraphs) → lines → elements → `boundingBox: Rect` in image pixels. Filter by `confidence` where available. Rotate/normalize bitmap to match display orientation **before** OCR so boxes map 1:1 to screen coordinates.

### 7.3 Overlay mapping
OCR bbox (bitmap px) → screen px: `screen = bbox * (displayWidthPx / bitmapWidthPx)`. Draw into a full-screen `WindowManager` overlay with `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_LAYOUT_NO_LIMITS`? (no — respect safe areas) `TYPE_APPLICATION_OVERLAY`, `PixelFormat.TRANSLUCENT`. Each translated line is a small pill (semi-opaque dark background + Arabic text centered) sized to the original bbox, width auto-expanded if Arabic needs more room, clamped to screen edges.

### 7.4 Arabic rendering (critical)
Arabic is cursive + RTL. **`Canvas.drawText` does neither shaping nor bidi.** Correct options, in order of preference:
1. Compose overlay window (`setContent` in a `ComposeView` added to WindowManager) — Compose `BasicText`/`Text` handles shaping, bidi and RTL natively. ✅ simplest, chosen.
2. `StaticLayout` with `Layout.Alignment.ALIGN_NORMAL` + `TextDirectionHeuristics.FIRSTSTRONG_RTL` inside a custom View, if Compose overlay proves heavy.

Line-break Arabic manually by reusing OCR line grouping so translated text never re-flows into unrelated lines.

### 7.5 Translation
ML Kit Translation: `TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(TranslateLanguage.ARABIC)`. `downloadModelIfNeeded()` → `Task<Void>` → then `translate(String)` per line; batch by building paragraph strings to reduce model invocations. Cache `HashMap<String /*hash*/, String /*ar*/>` + optional Room persistence in M4. Respect ML Kit's per-language model sizes. **M3 shipped** as: blocking contract, one joined page request (`\n`-delimited) + per-block fallback, EN→AR model auto-downloaded on first use (~30 MB, needs internet once). Wi-Fi `DownloadConditions` gate + progress UI deliberately deferred (M4).

### 7.6 Threading & lifecycle
Capture on `Dispatchers.IO`/own handler; OCR/translate are async Tasks — bridge to coroutines with `suspendCancellableCoroutine`. Overlay work on main. All bitmaps recycled in `finally`. Services are `START_NOT_STICKY`; button service may be `startForegroundService` only while capturing (M1 decision: button service runs as normal service, FGS only for capture).

### 7.7 Security & privacy
No screenshot leaves the process. No storage of bitmaps (temp file in `cacheDir` deleted in `finally`, or pure in-memory). Translation cache stores text pairs only; user can wipe in settings. No analytics by default. `android:allowBackup="false"` for the data store section.

---

## 8. Manual verification checklist (for release builds)

**Install & launch**
- [ ] Fresh install on Android 14 (and minSdk smoke on API 26/28 emulator if available)
- [ ] First launch shows placeholder; no crash; back exits cleanly
- [ ] App name & icon correct in launcher, recents and settings

**Overlay (M1+)**
- [ ] Overlay permission prompt flows to Settings and back; denial handled gracefully
- [ ] Floating button draggable, snaps to edge, survives rotation & reboot (if enabled)
- [ ] Button does not steal touches from underlying app

**Capture + OCR (M2+)**
- [ ] Capture consent dialog appears on API 34/36 every session
- [ ] OCR returns expected English lines on 3+ real manhwa pages (light/dark UI)
- [ ] FLAG_SECURE screen → friendly message, no garbage overlay

**Translate + overlay (M3+)**
- [ ] First run downloads AR model with progress; works in airplane mode afterwards
- [ ] Arabic renders fully shaped (no disconnected letters), right-to-left, inside pills aligned to original lines
- [ ] Tap-outside dismisses; long-press dismiss works; underlying app still interactive
- [ ] Latency ≤ 2 s on mid-range device (e.g., Pixel 6a / SD 778G)

**Release (M5)**
- [ ] `./gradlew assembleRelease` signed; R8 clean (no ML Kit/annotation-stripping crashes)
- [ ] Privacy policy live; Data Safety form consistent with behavior
- [ ] 20-page soak run: no ANR, no leaked Service/Bitmap (LeakCanary debug build)

---

## 9. Open questions (non-blocking)

1. **DI:** Hilt vs manual — decide at M1 (Leaning: manual DI to keep APK/build small; promote to Hilt if graph grows).
2. ~~**OCR variant:** bundled Latin OCR (+~40 MB incl. all ABIs) vs Play Services delivery (smaller APK, runtime dependency).~~ **Decided (M2b): bundled Latin** — offline, no Play Services dependency; revisit ABI filtering / Play delivery at M5.
3. **SFX/sound-effect bursts** in manhwa (stylized): translate, transliterate, or skip? Default: skip non-speech SFX, revisit M4.
4. **Bundled ten-inch tablets:** overlay pill size scaling on `sw600dp+` — M4.
5. **Auto-translate mode** (continuous re-capture on scroll): out of v1 scope; note in M6.

---

## 10. Build & environment (this workstation)

**Important — this sandbox resets between sessions:** `/opt`, `/usr/lib/jvm`, the Gradle distribution and all apt-installed packages vanish on reset; only `/home/user` persists (and `app/build/` output is excluded from snapshots anyway). Each fresh session must reinstall the toolchain before building: `apt-get install openjdk-21-jdk-headless` → cmdline-tools → `sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"` → `./gradlew assembleDebug` (wrapper re-downloads Gradle 9.0.0 and fills `GRADLE_USER_HOME=/opt/gradle-home` from scratch, ~600 MB of deps). On a normal dev machine none of this applies — just open the project in Android Studio.

- OS: Debian-based sandbox, 2 vCPU, ~2 GB RAM (tight — prefer a single build at a time; watch for GC thrash / OOM of the Gradle daemon; if the build stalls at `mergeDebugGlobalSynthetics`/`stripDebugDebugSymbols`, kill the daemon and re-run — cached task outputs make the retry finish in seconds).
- JDK: OpenJDK 21 (`apt: openjdk-21-jdk-headless`).
- Android SDK (fresh installs): cmdline-tools 12.0 → platform 36, build-tools 36.0.0 (+ AGP auto-installs 35.0.0), platform-tools.
- Gradle: wrapper pins 9.0.0; `GRADLE_USER_HOME=/opt/gradle-home` used here to keep caches out of the project tree — **locally use the default `~/.gradle`**.
- Dependency pin reality-check (2026-11): latest Compose (ui 1.12.x) demands **AGP ≥ 9.1 and compileSdk ≥ 37**. To stay on the verified **Gradle 9.0.0 ↔ AGP 8.13.2** pair, the project pins **Compose BOM 2025.09.00 (Compose 1.9.1)**, core-ktx 1.18.0, lifecycle 2.9.4, activity-compose 1.13.0. Upgrade to AGP 9.x + Compose 1.12.x + compileSdk 37 as a deliberate M1.5 task, not accidentally via BOM bump.
- `gradle.properties` keeps heap at 1 GB, one worker, in-process Kotlin compilation, no daemon — do not raise these on this box.
- This machine has no emulator/KVM → **launch must be verified manually on a device**; `assembleDebug` proves compile-time correctness only.

## 11f. Definition of Done for M5 (2026-11-11)

- [x] OCR input downscaled to ≤1600 px; boxes re-mapped to original px (unit-tested `BitmapScalerTest`)
- [x] Async jpg save; per-box pixel-region background sampling (no full-frame IntArray)
- [x] Loading pill (capturing) + error pills (capture / translation failure)
- [x] tap→overlay timing: logged (`MLCapture`) + shown in overlay footer + meta field `tapToOverlayMs`
- [x] Complete Arabic UI mirror (`values-ar/strings.xml`); Arabic/English follows system locale
- [x] Settings screen: font size, engine + key, auto-capture on scroll (opt-in, accessibility, read-only), privacy policy screen
- [x] `./gradlew :app:testDebugUnitTest` → **68/68 green**; `:app:assembleDebug` green
- [x] `:app:lintVitalRelease` GREEN (full `lintRelease` OOM-blocked on 2 GB box — reported, not hidden)
- [x] `:app:assembleRelease` → signed `app-release.apk` **v0.7.0 / code 7**, apksigner-verified (CN=ManhwaLens), 55.0 MB
- [ ] **R8 minify pass** — blocked by build-box RAM (documented; run with `-Prelease.minify=true` on ≥4 GB machine)
- [ ] On-device (this checklist is yours): tap→overlay timing before/after (footer line), 30-minute soak, low-end device, battery/memory behavior, settings screen round-trip, auto-capture on scroll enable/fires/cooldown, Arabic system-locale pass (كل النصوص + RTL), privacy policy screen, FLAG_SECURE page
- [ ] R8-minified build verified on device once produced (no crashes from stripped classes)

## 11e. Definition of Done for M4 overlay (2026-11-11)

- [x] Arabic text drawn ON TOP of each OCR box, covering the original English (Compose window, Cairo Bold)
- [x] Box background sampled from bubble interior (dominant 5-bit bucket average); contrasting text color chosen by luminance — `ColorSampler`, pure Kotlin
- [x] Font auto-sized to box (descending from max bound, StaticLayout-measured, min 6 px, clip-to-bounds + ellipsis-free RTL clip at floor) — `AutoFitTextSizer`
- [x] RTL layout & shaping (`LocalLayoutDirection.Rtl`, Compose Text with Cairo family; lineHeight 1.15)
- [x] Bundled Arabic font: Cairo 400/700 static instances from the OFL variable TTF (glyph coverage verified: U+0627/U+064A); license at `licenses/Cairo-OFL.txt`
- [x] Tap-to-dismiss (frame tap) + Original ↔ Arabic toggle chips in the footer
- [x] Mapping math bitmap px → overlay px (Fit/axis math + box mapping) — `computeContentRect`/`mapBox`, JVM-tested
- [x] Unit tests added: `ColorSamplerTest` (8), `AutoFitTextSizerTest` (5), `ContentRectTest` (5) → **63/63 green** total
- [x] `./gradlew assembleDebug` green (APK **v0.6.0 / code 6**, aapt-verified, ~62 MB; Cairo TTFs packaged at `res/font/`)
- [ ] Verified on a real device (manual checklist): 5+ pages — dialogue-heavy, SFX-heavy, colored bubbles (wrong-bg risk), dark bubbles (low-contrast risk), small text (overflow risk), narrator captions, centered short lines; RTL punctuation (؟ ، ؛) direction; overlay alignment vs bubble edges (rotation, cutout); toggle Original↔Arabic; tap-to-dismiss; cached vs fresh translation render
- [ ] Failure screenshots from on-device run saved to `m4-manual-screenshots/` with one-line notes (page type, engine, expected vs seen) — I cannot produce these on a PC; they must come from your device

## 11d. Definition of Done for M3 translation (done 2026-11-11)

- [x] `Translator` interface (blocking, never-throw contract, engine id for cache namespacing)
- [x] ML Kit EN→AR implementation with one-time model download + joined-page batching + per-block fallback (`translate/mlkit/MlKitTranslator.kt`)
- [x] Gemini 2.5 Flash implementation: ONE batched `generateContent` per page, numbered `[id]` protocol, key in `x-goog-api-key` header only (`translate/gemini/*`); key stored ONLY in app-private SharedPreferences (`data/TranslationPrefs.kt`), never in source/logs (grep-verified)
- [x] Cache: engine-namespaced, normalized-key LRU(1000), cache-aside decorator; engine failure leaves cache untouched (`translate/TranslationCache.kt`, `CachingTranslator.kt`)
- [x] Settings UI: engine toggle (ML Kit / Gemini), Gemini key field with save/clear + saved indicator, sample-page **Test translation** button, cache size + clear action (`ui/settings/TranslationEngineCard.kt`)
- [x] Translation wired into capture pipeline; results shown in the debug window (Arabic RTL + English source + engine/timing/cache stats) (`overlay/FloatingButtonService.kt`, `capture/CaptureDebugWindow.kt`)
- [x] Unit tests: `TranslationCacheTest` (6), `MlKitBatchTest` (4), `CachingTranslatorTest` (7), `GeminiWireTest` (7), `GeminiTranslatorTest` (11) + pre-existing `BubbleGrouperTest` (10) → **45/45 green**
- [x] `./gradlew assembleDebug` green (APK **v0.5.0 / code 5**, aapt-verified, **~62 MB** after `useLegacyPackaging` compresses native libs; all 4 ABIs kept; ABI filtering / App Bundle decision at M5)
- [ ] Verified on a real device (manual checklist): same page translated by BOTH engines compared; airplane-mode behavior (ML Kit should still work post-download; Gemini must fail with `NO_INTERNET`); wrong key → `INVALID_API_KEY` exactly once; first-run EN→AR model download; second capture of an identical page shows `cache N/N` with no engine call; FLAG_SECURE page → no crash
- [ ] Translation quality judged acceptable for both engines on ≥3 real manhwa pages (your call, not mine)

## 11c. Definition of Done for M2b OCR (done 2026-11-11)

- [x] ML Kit Latin text recognition on the captured frame; lines with bounding boxes returned (`TextRecognizerClient`, tag `MLCapture`)
- [x] Bubble grouping heuristic: vertical distance + horizontal overlap (+ font-size & center guards) — `BubbleGrouper`, pure Kotlin
- [x] Boxes drawn on the debug overlay (amber = merged bubble with id chip, violet = raw line, red = low confidence)
- [x] OCR time logged per capture (`OCR done: N lines in M ms`) and shown in debug view footer
- [x] Unit tests for merging: single bubble, two close bubbles, bubble with SFX, asymmetric widths, thresholds — 10/10 green (`./gradlew testDebugUnitTest`)
- [x] `./gradlew assembleDebug` green (APK v0.4.0 / code 4, 55 MB due to bundled model)
- [ ] Verified on a real device: 5 page types (dialogue-heavy, SFX-heavy, small text, narrator+speech mix, centered short lines) — see manual checklist; known failure classes listed in M2b entry

## 11b-2. Definition of Done for M2a capture (done 2026-11-11)

- [x] Tap floating lens → single frame captured as ARGB_8888 Bitmap (rowStride-safe row copy)
- [x] Android 14+: FGS `mediaProjection` IS started BEFORE `getMediaProjection()`; manifest declares `FOREGROUND_SERVICE_MEDIA_PROJECTION` + type on the service
- [x] Per-session consent: fresh prompt on first tap, after Stop, after system revoke (`onStop`), after process death; deny → toast, next tap re-asks
- [x] Debug overlay shows the bitmap + metadata (resolution, capture ms, frame wait ms, save path `cacheDir/capture_*.jpg`); tap anywhere dismisses
- [x] 9-point near-black heuristic detects FLAG_SECURE-ish silent failures (logged + shown in debug UI)
- [x] Logging under tag `MLCapture` (consent, projection lifecycle, display-create ms, frame-wait ms, bitmap bytes, total ms, failures with stack traces)
- [x] `./gradlew assembleDebug` green (APK v0.3.0 / code 3, verified via `aapt`: permissions + `foregroundServiceType=mediaProjection`)
- [ ] Verified on a real device: manhwa app / browser / home screen captures, deny→re-request, rotation, 10 consecutive captures without crash/memory growth

## 11b. Definition of Done for M1 (done 2026-11-11)

- [x] Draggable floating lens button over other apps (`TYPE_APPLICATION_OVERLAY`), tap = M2 toast placeholder
- [x] Overlay permission onboarding: status chip, deep-link to system settings, state re-checked on resume
- [x] Toggle show/hide from MainActivity; position + enabled persist across restarts
- [x] `./gradlew assembleDebug` green (APK v0.2.0 / code 2, verified via `aapt`: permissions + service present)
- [ ] Button verified visually over a manhwa reader on a real device (needs Android hardware — none in this sandbox)

## 11. Definition of Done for M0 (this task)

- [x] Kotlin + Compose + minSdk 26 project, applicationId `com.manhwalens.app`
- [x] Placeholder UI (no feature code), English LTR
- [x] PLAN.md (this file)
- [x] `./gradlew assembleDebug` green (2026-11-11; APK verified: package/minSdk 26/targetSdk 36/launchable `MainActivity`/debug-signed, 10.8 MB)
- [ ] Empty app verified to launch on a device/emulator (manual — no KVM here; see §10)

---

## 12. Hotfix v0.7.1 (code 8) — Floating-button crash on toggle (2026-11-11)

**Root cause (3 lines):**
1. `FloatingButtonService.onCreate()` passed `windowManager` to `StatusPillWindow` BEFORE assigning it → `UninitializedPropertyAccessException` → instant "ManhwaLens keeps stopping" on every toggle ON.
2. The toggle path never called `startForeground()` — promotion to mediaProjection happened only after capture consent → Android 12+ 5-second FGS rule violation on API 33/34.
3. Fix: correct init order; synchronous FGS promotion with `specialUse` type (manifest `specialUse|mediaProjection` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`); `START_STICKY`; try/catch + `Log.e("ManhwaLens")` + Toast instead of crashes; capture frames downscaled to max width 1080 px and recycled after display; same signature key as v0.7.0 (upgradeable).

Build: `:app:testDebugUnitTest` 68/68 ✓ · `:app:assembleDebug` ✓ · `:app:assembleRelease` ✓ (signed, code 8, v0.7.1) · Verified on-device checklist PENDING (device required).

---

## 13. Hotfix v0.7.1 (cont.) — visible errors + FGS permission fix (2026-11-11)

**Root cause of the "could not stay on top" + crash-on-interact:** `startForeground(NOTIF_ID, n, SPECIAL_USE)` on API 34 throws `SecurityException` — manifest declared the specialUse type but was MISSING `FOREGROUND_SERVICE_SPECIAL_USE`. The catch swallowed it, the button was added without an active FGS, and the platform killed the process moments later.

**Changes:** global `Thread.setDefaultUncaughtExceptionHandler` (writes `filesDir/manhwalens-last-error.txt`) installed in `ManhwaLensApp`; Settings → "Last error" section with Copy button (EN/AR); every caught exception logged `Log.e("ManhwaLens", …, e)` with class name + full stack; failure Toasts now carry the real exception message; service no longer adds the overlay button before a successful FGS promotion — on failure it resets `prefs.enabled`, flips the toggle via a one-shot `OverlayPrefs.serviceFailure` signal, stops itself, and shows the real error; capture pipeline instrumented per stage (screen/imageReader/virtualDisplay/firstFrame/bitmap/ocr/translation/overlay) with `stage=` logs + stage-specific real-reason Toasts; OCR/translate failures now log via `ManhwaLens` and write the last-error file; consent-token rejection and projection setup failures are visible (Toast + log) instead of silent.

Tests 68/68 ✓ · assembleDebug ✓ · device verification pending.

---

## 14. Part A — Gemini model fix + error mapping (2026-11-11, UNRELEASED)

Hardcoded `gemini-2.5-flash` (HTTP 404 "no longer available to new users" shown as "UNKNOWN") is gone. Model is now a setting (`TranslationPrefs.geminiModel`, default `gemini-3.8-flash`, option `gemini-3.5-flash-lite`) with a dropdown in Settings; "Check available models" button lists generateContent-capable models via `GET /v1beta/models` (key in `x-goog-api-key` header only). On HTTP 404 the other model is tried ONCE, persisted on success (Toast "Gemini: now using …"). Failure mapping now: 400/401/403→INVALID_API_KEY, 404→MODEL_NOT_FOUND, 429→RATE_LIMITED, transport→NO_INTERNET/TIMEOUT, junk body→PARSE_ERROR — all with the HTTP code in the message; "UNKNOWN" only for unclassified 5xx. Gemini failure during a capture now falls back to on-device ML Kit for that capture with the notice "Translated offline (Gemini unavailable)". Tests: 72/72 ✓ (4 new: 404 retry, dual-404→MODEL_NOT_FOUND, 400→INVALID_API_KEY, models-list parse).

## 15. Part B — Pipeline trace + Debug mode (2026-11-11, NEEDS DEVICE VERIFICATION)

Traceable pipeline for the floating-button tap. New files:

- `util/PipelineTracer.kt` — one recorder for the 7 stages (TAP, CONSENT,
  CAPTURE, OCR, FILTER, TRANSLATE, OVERLAY). Per stage: status
  (OK/FAILED/SKIPPED), duration ms, ≤600-char detail. Details are scrubbed of
  API keys (`AIza…` and key=… / api_key=… patterns → `[redacted]`). Never
  constructed with the key. `persistToErrorLog()` writes the full report into
  the existing Settings → "Last error" file (Copy button reuses as-is).
- `util/ErrorLog.writeRaw()` — raw-text sibling of `write()` for the trace.
- `overlay/DebugPanelWindow.kt` — small NON-BLOCKING overlay panel (tap
  anywhere = dismiss), 7 stages with colored status; shown only when Settings
  → Diagnostics → **Debug mode** is on; any stage the pipeline never reached is
  back-filled as SKIPPED so all seven always appear.
- `ocr/BlockFilter.kt` — NEW stage-5 filter (executed, not just traced):
  drops empty blocks, watermarks (`www.`, URLs, tapas/mangadex/webtoon/
  read-at/chapter/ch., `.com/.net/.org/.io`), the ignore-list ("sfx"), and
  pure SFX bursts (every token a known SFX word; or a single exact SFX word).
  Conservative on purpose: multi-token real sentences always pass; one-word
  dialogue ("WHAT?!", "YES!") passes. The acceptance bubble
  **"ALRIGHT, I'LL HAVE A GLASS."** passes (unit-tested).
- ML Kit EN→AR: `MlKitTranslator.isModelReady()` process-wide flag — once the
  model downloaded/verified this process no re-download path is taken; the
  capture flow shows a visible "Downloading translation model (one time,
  ~30 MB)…" pill before translating when the model isn't ready yet, then the
  normal download+retry happens inside the engine.
- Settings → **Diagnostics**: Debug mode switch + **View last capture** button
  (full-screen dialog, only when the file exists). Debug mode saves the last
  frame with OCR boxes + block ids drawn on it to
  `filesDir/manhwalens-last-capture.jpg`.
- Trace recorded on both success and failure paths; on failure, OVERLAY is
  marked SKIPPED ("pipeline ended earlier") and the panel still shows all 7.
- OCR boxes → screen: mapped in the overlay by `computeContentRect` (Fit
  mode) — unit-tested round-trip after downscale (720x1600 OCR space → capture
  space) and bitmap→view offset/scale (`CoordinateMappingTest`).
- Unit tests: `BlockFilterTest` (SFX/watermark/dialogue), `CoordinateMappingTest`
  (scaler inverse + content rect), `PipelineTracerTest` (order, key-scrub, line
  format). **Tests 91/91 ✓ · assembleDebug ✓** (release build NOT done — wait
  for Part B approval).

Device steps for the user: Settings → Diagnostics → enable **Debug mode** →
open a webtoon page → tap the lens → screenshot the panel → paste Settings →
Last error here if anything fails.

## 16. Part C — static fixes before device trace (2026-11-11)

Read the full tap → consent → capture → OCR → translate → overlay path.
Fixed the two silent "everything looks fine but nothing shows" defects; all
feedback paths are now visible. Remaining PART C work NEEDS the on-device
trace (Debug mode) from the user's Redmi before the true stage failure is
known.

Fixes:

1. `CaptureDebugWindow.show()` was swallowing `SecurityException` /
   `BadTokenException` / IllegalArgumentException with NO log and NO UI —
   the tap produced a completely silent dead lens. Now logs the reason,
   returns Boolean, and the service:
   - records OVERLAY = FAILED with "overlay window NOT attached (addView
     denied)" in the trace,
   - shows Toast `overlay · addView denied`,
   and OVERLAY is recorded AFTER the addView attempt (was recorded before,
   always claiming OK).
2. `onTap()` while a capture is in flight showed ZERO feedback — now shows
   "A capture is already running…" / "الالتقاط جارٍ بالفعل…" (new string
   `toast_capture_in_progress`, EN+AR); trace keeps TAP = SKIPPED.
3. Reviewed and kept as-is (correct): imageToBitmap rowStride handling;
   first-frame await ≤1.5 s then visible CAPTURE FAILED + Toast; auto-capture
   scroll path (rate-limited, guarded by the single-flight capture); two-step
   consent-then-capture UX (consent grant shows capture-ready toast first).

**Build/tests: 91/91 ✓ · assembleDebug ✓.**
**Next (user on device, needs v0.7.4):** build & install v0.7.4 → Settings →
Diagnostics → Debug mode → webtoon page → tap lens → send the 7-stage debug
panel screenshot + Settings → Last error (Copy). Then PART C continues with
whatever stage the trace reveals.

## NOTE — sandbox restore lost intermediate git objects (2026-11-11)

Between delivery of v0.7.4 and this session the sandbox was rebuilt from a
snapshot with `git` state at `06dfdd4` (Part A). Commits `ba2e4dd` (Part B),
`00b38cc` (Part C static fixes) and `b781e24` (v0.7.4 bump) are NOT in the
local object store anymore. All file contents survived in the working tree and
are re-committed here in one squashed commit on top of Part A (`06dfdd4`):
Part B (trace + debug mode), Part C static fixes, Part C crash fix
(ViewTreeLifecycleOwner), and the bump to v0.7.5 (code 12).

## 17. Part C root cause — ViewTreeLifecycleOwner crash (2026-11-11, DEVICE-REPORTED)

Device crash (Redmi 13C, main thread, uncaught):
`IllegalStateException: ViewTreeLifecycleOwner not found from ComposeView{...}`
thrown at `AbstractComposeView.onAttachedToWindow → createLifecycleAwareWindowRecomposer`
when the overlay ComposeView is attached via WindowManager from the service.
Escapes addView()'s try/catch because attach happens during traversal, not inline.
**This is the "tap shows nothing / keeps stopping" root cause** — the app died,
so no translation could ever be shown.

Changes (smallest safe fix):

- NEW `overlay/OverlayLifecycleOwner.kt` — LifecycleOwner + ViewModelStoreOwner +
  SavedStateRegistryOwner. `LifecycleRegistry`, `ViewModelStore`,
  `SavedStateRegistryController.create(this)`. `onCreate()`: `performRestore(null)`
  then CREATED; `onResume()`: RESUMED; `onDestroy()`: DESTROYED + `viewModelStore.clear()`.
  Helper `ComposeView.attachOverlayOwners(owner)` sets the three ViewTree owners.
- Applied to ALL 3 overlay ComposeViews (STEP 1–3), exact order onChanged:
  fresh owner per show → onCreate → attachOverlayOwners →
  setViewCompositionStrategy(DisposeOnDetachedFromWindow) → setContent → onResume →
  windowManager.addView. On removal: removeView (ignore already-removed) →
  owner.onDestroy(). Never reuse a destroyed owner.
  - `CaptureDebugWindow` (full-screen Arabic overlay)
  - `DebugPanelWindow` (debug pipeline trace)
  - `StatusPillWindow` (loading/error pill; composition still reused while attached —
    same behavior as before, now with owners)
- Logs (tag `ManhwaLens`) on overlay create/attach/remove for all three.
- Global uncaught exception handler + Last error screen unchanged.
- STEP 4 verified: no unbounded verticalScroll in WRAP_CONTENT overlays
  (DebugPanel already `heightIn(max=420.dp)`; Settings dialog `heightIn(max=720.dp)`;
  no LazyColumn in overlays).
- Deps added (smallest): lifecycle-viewmodel-ktx (setViewTreeViewModelStoreOwner)
  + savedstate-ktx 1.4.0 (setViewTreeSavedStateRegistryOwner). lifecycle 2.9.4.
- OverlayStage verdict ordering fixed previously (record AFTER addView).

**Build/tests: tests 91/91 ✓ · assembleDebug ✓ · assembleRelease ✓ → v0.7.5 (code 12,
0.7.5 in manifest).**

**Environment repair (this sandbox lost /opt + JDK21 on restart):** reinstalled
`openjdk-21-jdk-headless` via sudo apt; recreated `/opt/android-sdk` (cmdline-tools
13114758, platform android-36, build-tools 35.0.0 + 36.0.0, platform-tools);
`local.properties` unchanged (sdk.dir=/opt/android-sdk). Gradle config-cache for the
project was wiped (`--no-configuration-cache` used for builds). NOTE: these installs
do NOT persist across sandbox rebuilds — if builds fail with "Gradle requires JVM 17"
or missing SDK, reinstall JDK/SDK the same way (see PLAN §17).

**Workspace cleanup (requested):** deleted stale `ManhwaLens-v0.7.4-release.apk`,
project build/.gradle/.kotlin dirs (~62 MB), `.sudo_as_admin_successful` marker.
Kept: `uploads/` (user screenshots), `ManhwaLens/`, `preview/`, user `.gradle` cache.
**App preview:** `python3 -m http.server 36605` serving `/home/user/preview`
(index.html = privacy-policy.html) — LIVE PREVIEW on 0.0.0.0:36605.

**Next (user on device):** install v0.7.5 → open m.webtoons.com in Brave → page with
the English bubble "ALRIGHT, I'LL HAVE A GLASS." → tap floating button → Arabic
overlay must appear WITHOUT a crash → repeat 10×, then toggle lens OFF/ON and repeat.
If any stage fails, read Debug mode panel / Settings → Last error (Copy).

## 18. Workspace hygiene + Dropbox (2026-11-11)

Standing instruction from the user: keep only the CURRENT release; delete any
old version; if workspace space gets tight, use the connected Dropbox.

- Deleted: duplicate `app/build/outputs/apk/release/app-release.apk` (333 MB
  build dir) — identical to the workspace-root deliverable.
- Dropbox (connected, 2 GB quota, was empty): created `/ManhwaLens` and
  uploaded `ManhwaLens-v0.7.5-release.apk` (55 003 959 bytes, rev …341ba08a1)
  as the cloud copy of the current release. Future old releases may be
  offloaded there before deletion, or kept locally if space allows.
- Workspace now: `ManhwaLens/` source 5.4 MB + `ManhwaLens-v0.7.5-release.apk`
  53 MB + uploads/ (device screenshots) + preview/ (privacy-policy on :36605).
  `~/.gradle` (1.6 GB) is the dependency cache — kept for builds.
## 19. Pipeline work A–D (2026-11-11) — tap→overlay latency + Live mode

User-mandated senior-task, done in order with approval gates. All unit tests green
(126/126), `assembleDebug` OK after each part. Committed per part:

- **A (4300228)** — named pipeline spans (tap, consent wait, queue wait, model
  check/download, translate, gemini request/404 retry/circuit, fallback, overlay
  attach). Gemini connect 3 s / read 6 s (was 8/25), still ONE retry. New GeminiCircuit
  (5 min pause after any failure) wired into the factory + a one-line user toast;
  "Retry Gemini" button in Settings; ML Kit `Tasks.await` bounded; OCR recognizer
  shared + 20 s cap; pre-warm (OCR + EN→AR model) at service start.
- **B (31188d1)** — persistent capture session (VirtualDisplay/ImageReader live for
  service lifetime; drain-to-newest-slot; prompt image close). Status/nav bars cropped
  before OCR (boxes re-offset). 720 px capture cap. BlockFilter: already-Arabic /
  digits-only / symbols-only drop. Per-page dedupe (PageDedupe, expanded back to every
  bubble id). Persistent disk cache (`PersistentTranslationCache`, atomic tmp+rename,
  LRU 2000) keyed by engine|style|normalized — wired into CachingTranslator with
  memory-persistent two-tier lookup + promote-on-hit; Clear Cache wipes both.
  ML Kit parallel per-bubble translation ≤3 concurrent (joined fallback). Progressive
  display: ML Kit first, Gemini relay swaps each bubble in place via
  `CaptureDebugWindow.updateBoxes` (Compose state swap, fresh-owners lifecycle intact).
- **C (b765f8b)** — Live mode (default OFF; long-press lens button toggles; tap when ON
  = Original/Arabic toggle, tap in standby resumes). ~3 fps supervision on
  captureExecutor: 64×128 luma thumbnail → mean-abs-diff (LiveStabilityTracker, 400 ms
  window). Overlay hides immediately when frame changes; stable+different → OCR
  (feedback-loop protection: overlay hidden → ~80 ms → fresh capture); stable+same →
  no-op. Region reuse: RowHashShift (direct row-SAD, unit-tested) estimates vertical
  shift; still-visible boxes REUSED shifted, only the newly revealed band is OCR'd
  (<48 px bands skip OCR). Battery: screen-off pause (BroadcastReceiver), 5 min
  no-change standby (toast; tap/screen-on resumes), foreground allow-list via
  UsageStatsManager (optional, in Settings, usage-access permission declared), one live
  OCR job at a time, tap preempts. Overlay in live mode is FLAG_NOT_TOUCHABLE +
  FLAG_NOT_FOCUSABLE (scroll underneath); no footer; lens button dot shows
  idle/scanning/translating. Tap path reuses the live-stashed frame on static screens.
- **D (47a0d2f)** — frame-edge ignore: BlockFilter.apply now drops blocks fully inside
  top/bottom % bands (default 8%, 0–30% sliders in Settings, both tap and live paths).
  Arabic auto-fit + RTL + Cairo in boxes was already in place — verified.

**Device verification (Redmi 13C) — STILL PENDING (user):**
1. Install `app/build/outputs/apk/debug/app-debug.apk` (note: debug-signed, ok for testing).
2. Tap-overlay ms with/without Gemini + full span list from the debug panel.
3. Live ON on a webtoon page, scroll 20 screens → avg scroll-stop→overlay, CPU/battery,
   flicker/wrong positions, scroll-under-overlay confirmation.
Note: debug builds are NOT release candidates; no release without explicit approval.
## 20. v0.8.0 release (2026-11-11)

User standing rule from now on: **after every batch of edits/optimizations, a new
release is built and kept.** Done:

- versionCode 12→13, versionName 0.7.5→0.8.0 (commit bump).
- `assembleRelease` (minify off per §17 build-box constraint): signed with the
  user's release keystore; cert SHA-256 `958e8ef7…` — IDENTICAL to v0.7.5, so 0.8.0
  installs as a direct update. `apksigner verify` OK; badging confirms
  versionCode=13 / versionName=0.8.0 / minSdk 26 / targetSdk 36.
- Deliverable: `~/ManhwaLens-v0.8.0-release.apk` (55 051 463 B).
- Dropbox `/ManhwaLens/ManhwaLens-v0.8.0-release.apk` uploaded (id …Bw, rev …a08a1).
  NOTE: v0.7.5 is intentionally kept both locally and in Dropbox as the
  last-known-good fallback; say the word to delete it.
- Build-box quirk: first `assembleRelease` invocations died with "Gradle daemon
  disappeared" (§17 box) — recovered by `pkill -9 -x java` + rerun (and the
  final run went green). Lesson reinforced: release builds need the box free.
- Standing rule recorded: every future change batch ends with a fresh release.

Tooling note: apksigner/AAPT2 under `/opt/android-sdk/build-tools/36.0.0/`.

## 21. v0.9.0 — Live mode without accessibility + progressive translation (2026-11-11)

Commits: 60b2b5b (A/B) → 8d971fe + 86d6aea (C) → 5add842 (D) → v0.9.0.

- PART A: lens tap now ARMS the capture session AND live mode (opt-out respected);
  frame-difference loop at ~3 fps (LiveStabilityTracker + 64×128 luma, mean-abs-diff);
  changing → overlay hides instantly; stable ≥200–1000 ms (slider, default 400) → translate;
  stable+same → skip; sensitivity low/med/high (16/10/6); battery standby auto-resumes on next
  detected change; screen-off pause; overlay FLAG_NOT_TOUCHABLE+FLAG_NOT_FOCUSABLE; latest
  frame only, one OCR job at a time; accessibility service demoted to optional extra trigger.
- PART B: exact live states on the floating button (dot+caption) and in the debug panel
  ("Live: paused|waiting|scanning|translating|same-page-skipped|no-session|error"); live
  trace lines (frame diff, stable ms, decision, overlay hidden/shown, cache hits/misses);
  auto-capture copy rewritten (accessibility NOT required); optional "Faster scroll detection"
  card with Open app info (restricted-setting path) + accessibility settings buttons.
- PART C: PageBatch joined @@@ default for live path (split-count validated, auto fallback to
  ≤3-way per-block); TAP path paints progressively (BubblePainter, lazy bg sampling, Gemini
  swapped in place per bubble); BlockPriority (short→large→central); TextChunker for >400-char
  blocks; lowercased cache keys; ML Kit client singleton (never recreated per tap); cache
  hits/misses span; user-editable ignore list (Subscribe/Get the app/…); benchmark in
  Settings → Diagnostics (sequential vs 3-way parallel vs joined @@@ vs 2×5) with report dialog.
- PART D: assembleDebug + 149/149 tests green; lintVitalRelease green; lint/lintDebug fail on
  this offline box (ui-geometry:1.9.1 not in cache — see BLOCKERS.md); MANUAL_TESTS.md written.
- Release: ManhwaLens-v0.9.0-release.apk (versionCode 14), signed w/ same keystore as 0.8.0
  (update-installable), uploaded to Dropbox /ManhwaLens/ (rev …ba08a1). Built with --no-daemon
  after two daemon-OOM deaths on this 1.9 GB box.

## 22. Startup crash-proofing + Safe Mode (2026-11-12) — v0.9.1

**Problem:** app closes immediately on open (Redmi 13C, MIUI/HyperOS) after an in-place upgrade install; no stack trace available.

**Part A review (0fd329c..HEAD):** no hard crash found in the launch composition/service path itself (overlay ComposeViews already wired with fresh OverlayLifecycleOwner; FGS promotion ordered correctly; live tick try/catch'd). Documented candidates instead: (1) unguarded typed prefs reads → ClassCastException on corrupt/legacy values at composition time; (2) poisoned `translation_cache.json` persisted across the upgrade (parse failure kept the bad file); (3) uncaught Throwables (Errors) on background executors bypassing the Exception-only catches; (4) `require()` in the live frame-diff; (5) `!!` on nullable flows; (6) no crash-loop protection of any kind; (7) uncaught handler installed too late (onCreate only).

**Part B+C:** safe getters + settings v2 migration (corrupt/unknown keys reset) in all three prefs files; cache self-heals (delete+recreate); global handler first-thing in attachBaseContext; Throwable catch-alls on all executors/live paths; StartupGuard (2 consecutive unhealthy starts → Safe Mode); Safe Mode banner in MainActivity (ErrorLog trace + Copy + Exit + recreate); MANUAL_TESTS.md rewritten for the 4 device checks; 166/166 tests green (+17 new: SettingsMigrationTest, StartupGuardTest, cache-recovery); assembleDebug/lintVitalRelease green; full `lint` still blocked by the offline ui-geometry cache (env).

**Version:** 0.9.1 / versionCode 15. Signed release built per standing rule; Dropbox keeps only the newest APK.

## 23. Wrong-thread crash: live loop touched views from `manhwalens-capture` (2026-11-12) — v0.9.2

**Crash (user-reported, no other info):** `ViewRootImpl$CalledFromWrongThreadException: Only the original thread that created a view hierarchy can touch its views. Expected: main Calling: manhwalens-capture` at `LensButtonView.setLiveState` ← `FloatingButtonService.setLiveStatus` ← `liveTick` ← service onCreate+1s.

**Fixes:** new `util/MainThread.kt` (single funnel, inline-on-main) + `util/Coalescer.kt` (status coalescing) + `live/LiveAutoStart.kt` (restore policy). All view/window touches route through MainThread: LensButtonView.setLiveState (self-marshals + assert), CaptureDebugWindow (show/updateBoxes/updateFrame/setOverlayVisible/toggleArabicOverlay/hide + debug asserts), showDebugWindow, launchConsent Toast. Live loop starts only with a capture session; onDestroy stops it first; live-tick failure stops Live safely (persisted off + ERROR status + ErrorLog) and keeps the process alive. Hide-before-capture now latched (150 ms). Debug builds: StrictMode penaltyLog + `WRONG THREAD` assert logs.

**Verified:** 175/175 unit tests green (+9: CoalescerTest, LiveAutoStartTest); assembleDebug green; lintVitalRelease green; full `lint` still blocked by offline `ui-geometry:1.9.1` (env). Robolectric unavailable offline → thread behavior covered by CoalescerTest + debug asserts (see BLOCKERS/DECISIONS). Device verification: MANUAL_TESTS.md v0.9.2 items 1–4.

**Version:** 0.9.2 / versionCode 16. Signed release built; Dropbox keeps only the newest APK.
## 24. v0.9.3 — In-place bubble patch rework (2026-11-12)

The full rework landed (parts 0/A–D, commits 82e486f … 81b0caa): full-frame
overlay gone; per-bubble in-place Arabic patches only; patch-aware diff masking;
scroll-riding patches; flood-bounded bubble expansion with ring-classify fill /
inpaint; Gemini image-out cleanup (default OFF, tap-translate only); Live pause
while ManhwaLens is visible; BLACK (FLAG_SECURE) frames skip OCR; "patches
painted = N of M" trace; per-cycle timer reset; BlockFilter ignore list wired
into the LIVE path too; PatchOverlayWindow reads made synchronous
(`runOnMainSync`) fixing scroll-ride being silently dead; ignore-list final
semantics (whole normalized text / whole words in short edge blocks only).
Docs: DECISIONS.md (parts 0–E), MANUAL_TESTS.md (Redmi 13C + Brave checklist);
BLOCKERS.md: lintVitalRelease banned on this box; lintDebug blocked by the
offline `ui-geometry:1.9.1` cache gap; on-device verification pending.
Version: **0.9.3 / code 17**, same release keystore as v0.9.2 (update-installable).
Build note: `--no-daemon` + `-Dorg.gradle.jvmargs=-Xmx1024m` REQUIRED on this box —
the mandated 1536m in gradle.properties re-creates the §20 OOM (daemon
disappeared at `:app:dexBuilderRelease`, twice). Kept gradle.properties at 1536m
per user mandate; the -D override is recorded in BLOCKERS.md.
Dropbox `/ManhwaLens/` keeps only the newest APK (v0.9.2 and the temporary
debug upload deleted to trash; local `~/ManhwaLens-v0.9.2-release.apk` deleted).
