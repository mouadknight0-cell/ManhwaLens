# BLOCKERS.md (2026-11-12)

- `./gradlew lint` / `lintDebug` / `lintRelease` fail at `:app:generateDebugAndroidTestLintModel`:
  `Could not resolve androidx.compose.ui:ui-geometry:1.9.1` — NOT in the offline Gradle
  cache (`~/.gradle/caches/modules-2/files-2.1/androidx.compose.ui.ui-geometry` does not
  exist; offline sandbox, no network). Unrelated to code changes; this config cannot
  resolve on this box. **Verified instead: `./gradlew :app:lintVitalRelease` → BUILD SUCCESSFUL.**
  Fix on an online machine/CI: run `./gradlew lint` once to populate the cache.
- Redmi 13C device checks (overlay timing, scroll behavior, battery) cannot be executed
  from the sandbox — see MANUAL_TESTS.md; expected trace numbers are in the final report.
- R8/minify still disabled on this box (2 vCPU / 1.9 GB); release builds are signed but not
  minified — unchanged known constraint.
- v0.9.1 Safe Mode / migration behavior must be verified ON DEVICE (install v0.9.0 → install v0.9.1 over it → open; see MANUAL_TESTS.md 1–2). Sandbox cannot reproduce MIUI launch kills — the guard is the safety net, not a root-cause proof.
- Robolectric is NOT in the offline Gradle cache — the background-thread→view regression test ships as pure-JVM `CoalescerTest` + `LiveAutoStartTest` + debug-only `WRONG THREAD`/StrictMode penaltyLog assertions instead of a Robolectric launch test. On an online machine, `./gradlew :app:testDebugUnitTest` once with Robolectric or an instrumented run of MainActivity would close the loop.
- Live/overlay GPU behavior on HyperOS (INVISIBLE visibility path, overlay revalidation) still needs on-device confirmation — MANUAL_TESTS.md v0.9.2 items 2–4.

## 2026-11-12 addendum (in-place patch rework)

- A `lintVitalRelease` run earlier today consumed the entire 2 vCPU / 2 GB sandbox for ~3 hours (all I/O starved; even `ls` and `git` stopped returning). Process was found via `ps aux` and killed (PID 17840) this session. **User mandate (2026-11-12): lintVitalRelease is NEVER run on this box again.** `android { lint { checkReleaseBuilds = false } }` is now set, and gradle.properties uses `-Xmx1536m`; all release builds skip lint automatically.
- `./gradlew :app:lintDebug --offline` → BUILD FAILED in ~16 s: `No cached version of androidx.compose.ui:ui-geometry:1.9.1 available for offline mode` (same gap as before; `ui-geometry` is not in the offline Gradle cache; only `:app:lintVitalRelease` ever worked here, which is now banned). Lint verification for this build is therefore NOT available in the sandbox. On an online machine/CI: `./gradlew lint` once to populate the cache, then `./gradlew :app:lintDebug`.
- On-device verification of ALL in-place patch behavior (Parts 0–D) is impossible from the sandbox — see MANUAL_TESTS.md (Redmi 13C + Brave checklist).
- R8/minify still disabled on this box (memory) — unchanged.
- Part D (Gemini image-out) has never been exercised against the real API from here: no key, no network beyond the Gradle cache. Wire format (body shape, inline image parsing) is covered only by unit tests with fake responses. On device: enable only with a real key; expect possible first-attempt model 404 (settings auto-falls back to the alternate; pick an image-capable model explicitly if cleanup never engages — check `ai cleanup` logs).

- Release dexing on this box: `./gradlew assembleRelease --no-daemon` with the mandated `org.gradle.jvmargs=-Xmx1536m` → "Gradle build daemon disappeared unexpectedly" at `:app:dexBuilderRelease` (twice, with ~1.5 GB free). Workaround (verified, v0.9.3 built this way): add CLI override `-Dorg.gradle.jvmargs=-Xmx1024m` (or build on a ≥4 GB machine). gradle.properties was left at 1536m per user mandate.

## 2026-11-12 addendum (v0.9.4 — live-loop starvation fix)

- **Fresh sandbox, no toolchain.** The workspace started empty and the project had to be
  re-downloaded from the connected Dropbox (`/ManhwaLens/ManhwaLens-v0.9.3-source.zip` →
  `/home/user/ManhwaLens`, `.git` was not in the archive so a baseline commit was created from
  the extracted tree). The box had only OpenJDK 11 and NO Android SDK, while the project needs
  JDK 17 + `compileSdk 36`. Resolved (retry 1 of 5): `apt-get install openjdk-17-jdk-headless`
  is NOT available in this image (`E: Unable to locate package`), so Temurin JDK 17 was unpacked
  to `/opt/jdk-17` and Android command-line tools + `platform-tools`, `platforms;android-36`,
  `build-tools;35.0.0` to `/opt/android-sdk`; Gradle user home moved to `/opt/gradle-cache`;
  `local.properties` (git-ignored) points at `/opt/android-sdk`. Everything is OUTSIDE
  `/home/user` on purpose so the workspace snapshot stays far below the 128 MB / 10 000-file cap.
  Commands used: `JAVA_HOME=/opt/jdk-17 GRADLE_USER_HOME=/opt/gradle-cache ./gradlew --no-daemon
  -Dorg.gradle.jvmargs=-Xmx1024m …`. No `java`/`gradle` process was ever left stuck
  (checked with `ps aux` before each build); no shell hang occurred.
- **Lint remains banned and unavailable.** No lint task was run (user mandate). The offline
  `androidx.compose.ui:ui-geometry` gap from the previous addendum still applies on this box.
- **On-device proof still required.** MediaProjection frame-delivery behaviour (does the mirror
  really redraw within 400 ms after our overlay goes INVISIBLE on HyperOS?), the VirtualDisplay
  recreate-once watchdog, overlay/GPU timing and battery impact cannot be exercised in the
  sandbox — see MANUAL_TESTS.md "THE 9 CHECKS FOR THIS BUILD" (Redmi 13C + Brave). If
  `cleanCaptureTimeouts` climbs on the device, the mirror is not redrawing on hide: raise
  `LiveLoopDriver.CLEAN_CAPTURE_TIMEOUT_MS` (400 ms) and re-check.
- **Known behavioural gap, deliberately not changed:** if the user scrolls away and then back to
  the EXACT same page after the patches were cleared, the last-translated thumbnail still matches,
  so the page is skipped as "same page" and stays in the original until the page actually changes.
  Same as v0.9.3; out of scope for this fix (noted in DECISIONS.md Part A).
- **Release build not produced.** Only `assembleDebug` was requested/built (2 GB RAM box; release
  dexing needs the `-Dorg.gradle.jvmargs=-Xmx1024m` override documented above). No release APK was
  uploaded to Dropbox, so the "keep only the newest release APK" rule did not apply this session.
- **Debug dexing on this box (v0.9.4).** `assembleDebug` with `-Dorg.gradle.jvmargs=-Xmx1024m`
  died at `:app:mergeExtDexDebug` ("Gradle build daemon disappeared unexpectedly", 2 vCPU /
  1.9 GB, no swap). Retry 2 of 5 succeeded with **`--max-workers=1` +
  `-Dorg.gradle.jvmargs="-Xmx1200m -XX:MaxMetaspaceSize=320m"`** (BUILD SUCCESSFUL in 25 s):
  serialising the dexing workers so each one gets the whole heap is what fits in 1.9 GB.
  Use the same flags for any further build on this machine.

## 2026-11-12 addendum (v0.9.5 — live-loop silent-stop audit, self-check, foreground policy)

- **Build flags on this box (unchanged from v0.9.4):** `:app:compileDebugKotlin` ~37 s / `:app:testDebugUnitTest` ~23 s / `:app:assembleDebug` ~35 s all green with `./gradlew --no-daemon --max-workers=1 -Dorg.gradle.jvmargs="-Xmx1200m -XX:MaxMetaspaceSize=320m"` (env `JAVA_HOME=/opt/jdk-17 GRADLE_USER_HOME=/opt/gradle-cache ANDROID_HOME=/opt/android-sdk`). Use the same flags for any further build.
- **Lint remains banned and unavailable.** `lintVitalRelease` is NEVER run on this box again (user mandate, 2026-11-12). The offline `androidx.compose.ui:ui-geometry:1.9.1` gap persists; no lint task was run this session.
- **Release build not produced.** Only `assembleDebug` was requested/built (`apk/ManhwaLens-v0.9.5-debug.apk`, 62,665,554 B). No release APK was built or uploaded to Dropbox, so the "keep only the newest release APK" rule did not apply this session; `/ManhwaLens/` still holds only `ManhwaLens-v0.9.3-release.apk` as the newest release copy.
- **On-device proof still required.** The 4 v0.9.5 MANUAL_TESTS checks (Enable Live + consent / Auto translation / Live self-check + Copy report / Scroll and stop) need a real MediaProjection session — Redmi 13C + Brave, per MANUAL_TESTS.md. Sandbox cannot reproduce HyperOS frame-delivery timing, overlay/GPU behavior, or battery impact.
- **Robolectric not available.** v0.9.5 Part 4 substitutes a pure-JVM end-to-end simulation (`LiveEndToEndSimulationTest`, 47 tests) for the Robolectric launch test; `Total 330 tests, 0 failures`.
- **`BLOCKERS.md` itself:** this v0.9.5 addendum added 2026-11-12; all earlier entries unchanged.

## v0.9.9 (2026-11-13)

No step needed the 5-retry limit; nothing was abandoned.

Open (cannot be closed from the build box — needs the phone):
- No device/emulator here, so nothing in v0.9.9 was run on Android. The real answer to "which
  separator survives ML Kit" comes only from the benchmark on the Redmi 13C
  (MANUAL_TESTS v0.9.9 step 1). Until a separator is proven there, runtime stays parallel x3.
- The Live two-phase swap (ML Kit draft → Gemini in place) is untested on a device.

## v0.10.0 sandbox verification (2026-11-13)

- Source was recovered from Dropbox `ManhwaLens-v0.9.14-source.zip` and restored into the current GitHub branch. The separate `debug.keystore`, debug APK, and nested archive `.git` were not copied into version control.
- This checkout's sandbox has no `java` executable and no Android SDK at `/opt/android-sdk`; `./gradlew :app:testDebugUnitTest` and `:app:assembleDebug` cannot be run until JDK 17 + Android SDK 36 are available.
- Added pure-JVM tests for LibreTranslate request batching/response order, HTTPS validation, API error classification, and Arabic output screening. They are authored but NOT executed in this environment.
- No trusted LibreTranslate endpoint or physical Android device is available here. Validate remote HTTPS behavior, fallback, overlay timing, and Live mode on a device before release; checklist: `MANUAL_TESTS.md` M0.1–M0.6.
- Open-source OCR replacement is intentionally deferred until PaddleOCR is benchmarked for CER, bubble recall, P95 latency, APK/model size, and memory on the target device.

## 2026-11-13 GitHub Actions build

- First cloud attempt failed in `android-actions/setup-android@v3`; upgraded the workflow actions and pinned `ubuntu-24.04`.
- GitHub Actions run `43478059287` then passed both `:app:testDebugUnitTest` and `:app:assembleDebug`. It produced `ManhwaLens-v0.10.0-debug.apk` inside artifact `ManhwaLens-debug-apk` (artifact archive: 61,444,466 bytes).
- The artifact is available at https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43478059287. This sandbox cannot copy it into Dropbox because GitHub redirects artifact downloads to a blob host outside the allowed outbound hosts.
- This is a debug build, not a release-signed APK. It has not been tested on a physical Android device or against a trusted LibreTranslate server.
