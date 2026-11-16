# ManhwaLens — Project State

Last updated: 2026-11-16 (UTC)
Purpose: durable handoff record between ChatGPT conversations. GitHub source code and CI are authoritative; this file is a navigation aid, not proof that a build or device test passed.

## Repository
- Repository: https://github.com/mouadknight0-cell/ManhwaLens
- Main branch last observed: 23904fb0b11caf55e0f0297585e3e47b80fba766; this work did not modify main.
- Active development PR: #4 — https://github.com/mouadknight0-cell/ManhwaLens/pull/4 — remains open Draft and stacked on PR #3. Do not merge it directly into main.
- Development branch: feature/v0.12.4-bergamot-experiment.
- v0.12.8 release target commit: 614250692d384beaa3bb9a818aebf89d23dbbce1.
- App metadata at the v0.12.8 release: applicationId com.manhwalens.app, versionName 0.12.8, versionCode 48.
- Published GitHub prerelease: https://github.com/mouadknight0-cell/ManhwaLens/releases/tag/v0.12.8.
- APK asset: ManhwaLens-v0.12.8-debug.apk (71,359,650 bytes; SHA-256 e28bcfc464ec39398d31ed04401dd34dd0c1d7c4b3fb31c280814737dc9530b5).
- Do not confuse the development branch/release version with main's older version.

## Product requirements (must preserve)
- Offline-first English-to-Arabic manhwa translation.
- ML Kit remains the default translation engine until real-device evidence supports changing it.
- Bergamot is optional/experimental; its failures or rejected output should fall back to offline ML Kit without discarding accepted bubbles.
- Keep OCR and translation evaluation separate.
- Releases must be real GitHub Releases with a discoverable APK, not only Actions artifacts.
- Updates must install over the existing app: preserve applicationId, signing identity and increase versionCode. Never claim update compatibility without comparing signing certificates and package/version metadata.
- User works from an Android phone; do not require a desktop workflow or ask for a project ZIP when GitHub is available.
- Do not claim device-level validation from unit tests or CI.

## Latest reviewed technical facts
- PR #4 adds/updates offline Bergamot integration, quality fallback, diagnostics benchmark and workflow/release changes.
- PR #4's quality benchmark is explicitly ML Kit-only; MANUAL_TESTS.md says it is not a Bergamot comparison.
- The 20-case quality report's token F1 is lexical overlap, not semantic accuracy.
- The supplied batching benchmark is labelled ML Kit EN→AR. Its Joined ### median (755 ms) belongs to the ML Kit joined-input strategy, not a verified Bergamot-vs-ML-Kit comparison.
- Translation-pair samples show errors in both engines; Bergamot may emit partial English/Arabic and ML Kit also has mistranslations. Do not promote either based on speed alone.
- Manual phone validation is still required for OCR accuracy, semantic translation quality, offline model availability, native compatibility, latency and RAM.

## Audit findings / open work
1. Release workflow gate: .github/workflows/android.yml is triggered by pull_request, but the Release decision/publish steps are not visibly restricted to trusted tag pushes or the intended development-branch push. Review and fix this before relying on automatic publication; PR builds should upload artifacts only and must never publish a Release.
2. Update signing: CI restores a stable experimental Android debug keystore from the repository. This can support updates between builds signed with that same debug key, but it is not proof of compatibility with a separately signed production APK. Keep debug and production signing identities distinct and document the transition.
3. Inspect actual GitHub Release tags/assets and latest workflow runs before announcing an installable release.
4. Build a same-corpus multi-engine evaluation for ML Kit and Bergamot: same source bubbles, identical preprocessing, cold/warm runs, per-case semantic human review, rejection/English-echo rate, omissions, latency and memory.
5. Inspect PaddleOCR vs any retained OCR path on the same screenshots and compare line accuracy, bubble grouping, speed and RAM. Avoid selecting defaults without device evidence.
6. Review settings/navigation regression requirements against the user's requested removals and Live Mode placement.
7. Verify APK package ID, versionCode, ABI support, size, signing certificate fingerprint and update-install behavior.

## Conversation handoff protocol
At the start of a new conversation:
1. Read this file from the active PR branch.
2. Check PR #4 head SHA, branch status, open PRs, latest Actions run/jobs/artifacts and actual Releases.
3. Continue from the first unchecked open-work item; do not redo completed work without evidence.
4. Update this file in the same development branch whenever architecture, release state, test results or next steps materially change.

At the end of each substantial session, append/update:
- current branch and commit SHA
- files changed
- tests/CI actually run and exact outcomes
- release/tag/APK status
- device checks still required
- next concrete action

## Reporting rules
Use explicit labels: Implemented, Unit-tested, CI-verified, Release-published, Device-verified, Not verified.
Never use one label as evidence for another.


## Session continuation — 2026-11-15

### Repository and release audit
- Re-read this file from `feature/v0.12.4-bergamot-experiment`; `PROJECT_STATE.md` is on the development branch, not on `main`.
- Confirmed `main` remains at v0.12.2 / versionCode 42; the PR #4 branch reports v0.12.6 / versionCode 46. Do not compare or merge against the wrong branch.
- Confirmed v0.12.6 is a published GitHub prerelease with `ManhwaLens-v0.12.6-debug.apk` attached (71,330,058 bytes; SHA-256 `9ccab560f0329e422420d921dfd028bcafb89a861788609279837b8621729cf8`). This is a discoverable Release asset, not merely an Actions artifact.
- The applicationId remains `com.manhwalens.app`; versionCode increases from 42 on main to 46 on the development branch. The CI experimental debug-keystore file has the same Git blob SHA on main and the development branch (`68bd7f5388218224353f11b456ff3c29fb44c64c`). This supports consistency of the CI debug-signing input, but is not a measured APK certificate comparison and does not establish compatibility with a separately signed production APK.
- PR #4 remains open/draft and is stacked on `feature/v0.12.3-paddle-default-settings`; PR #3 for v0.12.3 is also open. Keep this branch relationship explicit when reviewing/merging.

### Implemented in this session
- Fixed the Release workflow gate in `.github/workflows/android.yml` so both the release-decision step and APK publication step are skipped for every `pull_request` event. Publication is permitted only for explicit `v*` tag pushes or pushes/dispatches on the designated development branch `feature/v0.12.4-bergamot-experiment`.
- Commit: `aa0bb3ecf79ab9cc8130ea6a73e7f2065d8aff11` — `ci: prevent pull request builds from publishing releases`.
- Rationale: GitHub uses `refs/pull/<number>/merge` as `GITHUB_REF` for pull_request workflows; PR validation must build/test and upload artifacts only, never create or modify a GitHub Release.

### Verification at handoff
- New push workflow run #349: https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43716549464
- Last observed state: queued/in progress during runner setup; Unit tests and APK build had not yet completed. Therefore this workflow fix is Implemented, but not yet CI-verified.
- No new APK or release was produced by this session.
- Device verification remains Not verified: PaddleOCR accuracy/grouping, Bergamot JNI/model load and offline behavior, ML Kit-vs-Bergamot semantic quality, latency/RAM, and update-install behavior.
- Keep ML Kit as default. The current 20-case quality benchmark is ML Kit-only and token F1 is lexical overlap, not semantic accuracy.

### Next actions
1. Confirm run #349 and inspect its job steps; verify a PR-triggered run skips both Release steps.
2. Review the stacked PR #3/#4 relationship and ensure both are validated before merging to main.
3. Add or run a same-corpus ML Kit/Bergamot comparison with per-case human semantic review, rejection/echo/omission rates, cold/warm latency and memory; do not promote Bergamot from speed or lexical F1 alone.
4. Complete physical-device tests in `MANUAL_TESTS.md` before claiming Device-verified.


## Session continuation — 2026-11-15 (CI re-check)

### Current repository state
- PR #4 remains open and draft; head branch `feature/v0.12.4-bergamot-experiment`, head SHA `ead6737ffe66487a33acab3e3a390c567605192c`.
- PR #4 targets `feature/v0.12.3-paddle-default-settings` (base SHA `3454789f64f674333b86c5b4f0cc4b8549e06a07)); it is stacked on PR #3, which targets `main`. Do not merge PR #4 directly into main while ignoring the stack.
- PR #3 remains open, non-draft, and targets main. PR #4 is mergeable against its current base; this does not mean the whole stack is ready to merge.
- The PR #4 head is 89 commits and 31 changed files according to current GitHub PR metadata.

### CI re-check of release-gate change
- The first runs for commit `aa0bb3ecf79ab9cc8130ea6a73e7f2065d8aff11` were cancelled by a newer push; they are superseded and are not the final CI result.
- Latest push workflow run #43716605959 on head `ead6737ffe66487a33acab3e3a390c567605192c`: **success**. Unit tests, pinned PaddleOCR model download/checksum checks, Bergamot ARM64 runtime fetch/architecture check, debug APK build, APK artifact upload and test-report upload all succeeded.
- Latest PR workflow run #43716611176 on the same head: **success**. Unit tests and debug APK packaging succeeded. Both Release decision and Release publication steps were skipped on the pull_request event, confirming the gate works for PR validation.
- The push run uploaded `ManhwaLens-v0.12.6-debug-apk` artifact (70,020,405 bytes; SHA-256 `303dfcc393bb291d675c3ec105abb17b1a212f4bbd443a7497e6f1f649674e68`) and `test-reports`. These are Actions artifacts, not a new Release.
- Existing GitHub Release `v0.12.6` remains published as a prerelease with `ManhwaLens-v0.12.6-debug.apk` (71,330,058 bytes; SHA-256 `9ccab560f0329e422420d921dfd028bcafb89a861788609279837b8621729cf8`). Do not conflate this previously published APK with the new Actions artifact built at head `ead6737`.
- Release gate is now **CI-verified** on the tested PR event: PR workflow skipped both release steps; push workflow completed and skipped publication because the version's Release already exists.

### Engineering review
- Read `TranslationQualityBenchmark.kt`, `BenchmarkRunner.kt`, `QualityFallbackTranslator.kt`, `TranslationTypes.kt`, `TranslatorFactory.kt`, `BergamotPageTranslator.kt`, `MANUAL_TESTS.md` and the Settings benchmark call site.
- Confirmed the current 20-case quality benchmark invokes raw ML Kit only. It cannot be used to claim a Bergamot comparison.
- Confirmed the existing batching benchmark is explicitly ML Kit EN→AR and mutates the device's ML Kit batching policy. Keep it separate from engine-quality comparisons.
- The comparison runner must send identical case IDs/source strings to both engines, bypass result caches, preserve the selected runtime engine and batching preferences, and report per-case output plus guard acceptance/rejection, English-echo/blank/omission counts, cold/warm latency and memory where measurable. Token F1 remains lexical-only. Bergamot must remain optional and ML Kit remains default until real-device evidence supports a change.

### Status and next actions
1. **Implemented + CI-verified:** PR build cannot publish GitHub Releases.
2. **Release-published:** v0.12.6 exists with a downloadable APK; latest head CI artifact is a separate, non-release artifact.
3. **Next implementation:** add an isolated same-corpus ML Kit vs Bergamot comparison in Diagnostics; it must not alter runtime selection, caches, or batching policy. Add deterministic JVM tests for stable IDs, failure isolation, and no preference mutation.
4. Re-check PR #3 CI and preserve the stacked PR order before merging.
5. **Not device-verified:** PaddleOCR quality/grouping, Bergamot model/JNI loading and offline inference, semantic translation quality, latency/RAM, APK signing-certificate equivalence and update-install behavior.


## Session continuation — 2026-11-15 (autonomous handoff refresh)

### Fresh GitHub verification
- Repository: `mouadknight0-cell/ManhwaLens`; development branch: `feature/v0.12.4-bergamot-experiment`.
- PR #4 is still open and draft, targeting `feature/v0.12.3-paddle-default-settings`; head SHA observed: `55bb2e4e6252abc611eee3496030af3b8551ab10`. It reports 98 commits and 33 changed files. Keep the stacked PR relationship; do not merge PR #4 directly to main.
- App version on that head: v0.12.6, versionCode 46, applicationId `com.manhwalens.app`.
- The existing PROJECT_STATE file already records the v0.12.6 GitHub prerelease and APK asset, plus the release-gate fix. This session did not independently re-query Releases, so that prior release record is retained but not newly re-verified.

### Latest CI observed in this session
- PR validation run: https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43717777022 on `55bb2e4e6252abc611eee3496030af3b8551ab10` — success.
- Confirmed successful steps: PaddleOCR model downloads and SHA-256 validation; pinned Bergamot ARM64 runtime retrieval/architecture/size checks; JVM unit tests; debug APK assembly; APK artifact upload; test-report upload.
- Both Release decision and Release publication steps were skipped, as expected for a pull_request event. This confirms PR validation did not publish a Release.
- The workflow file still contains a development-branch push gate for `feature/v0.12.4-bergamot-experiment`; a PR run cannot establish that a new push will publish. The existing v0.12.6 Release is documented in the earlier state entry.

### Current engineering decision
- Keep ML Kit as the default offline translator; Bergamot remains optional/experimental.
- The current 20-case quality benchmark is ML Kit-only. Token F1 is lexical overlap, not semantic accuracy.
- The next highest-value engineering task is an isolated same-corpus ML Kit/Bergamot comparison that bypasses caches, does not mutate runtime engine selection or batching preferences, and records per-case output, guard outcomes, omissions/echoes and latency. Human semantic review and physical-device memory measurements remain necessary.
- PaddleOCR is the sole OCR backend in this branch; its actual page accuracy/grouping and performance remain device-validation items.

### Verification boundary
- CI-verified: JVM unit tests and debug APK assembly at the head SHA above.
- Release-published: previously recorded for v0.12.6; not newly verified in this session.
- Device-verified: not established for v0.12.6. No phone test is claimed.

### Next actions
1. Inspect the current source implementation and test coverage for the quality benchmark and Bergamot adapter before adding the multi-engine runner.
2. Implement the isolated same-corpus comparator with deterministic JVM-testable orchestration and no preference/cache side effects.
3. Run CI and inspect failures/logs; fix and rerun until green.
4. Re-verify the latest Release asset and APK metadata before asking for a phone installation.
5. Only then prepare a physical-device checklist and request phone testing for JNI/model availability, OCR, semantic quality, latency/RAM and update-install compatibility.


## Engineering continuation — fairer same-corpus comparison (2026-11-15)

### Implemented
- Reviewed the existing `EngineComparisonBenchmark` and its Settings integration. It already uses the same 20 prepared inputs for both engines, bypasses the app translation cache through raw engine callbacks, keeps runtime preferences out of the comparator, retries failed/malformed batches per case, and labels token F1 as lexical overlap only.
- Identified a benchmark fairness issue: all ML Kit passes ran before all Bergamot passes, making the comparison sensitive to systematic run-order and thermal drift.
- Changed the comparator to execute paired passes and alternate engine order on every pass (ML Kit→Bergamot, then Bergamot→ML Kit, repeated). First-pass timings remain separately reported; the next three passes are used for warm median/worst.
- Added a deterministic JVM regression test asserting the exact alternating execution order and updated the report text to disclose the control.

### Commits on development branch
- `9f083b831e6e66e2837bd9225398d7133105eca7` — `perf: alternate engine benchmark pass order`
- `16e7c02dc4267bc7338bdf9950016b8110d611c9` — `test: verify alternating engine benchmark order`
- `3ca0c45e99a04cb8c07ac21ef1137e4b4f7da9cd` — `docs: explain balanced engine benchmark order`

### Verification boundary
- Source and test changes are committed; CI for the latest head has not yet been observed in this session. Do not label this change CI-verified until the new push/PR workflow completes successfully.
- No physical-device benchmark was run. Alternating order reduces one source of bias but does not control ambient temperature, battery state, background load, or model warm-state differences.
- ML Kit remains the default; Bergamot remains experimental.

### Next actions
1. Verify CI for latest head `3ca0c45e99a04cb8c07ac21ef1137e4b4f7da9cd`; inspect test/build results and repair failures.
2. Review benchmark resource cleanup and output mapping under native failure, then retain device validation as a hard gate.
3. Update `REPOSITORY_REPORT.md` with the implementation and exact CI evidence after verification.


## Session continuation — 2026-11-15 (benchmark isolation and v0.12.7)

### Reconciled handoff state
- The previous handoff's item to implement a same-corpus ML Kit/Bergamot comparison is superseded: PR #4 already contains `EngineComparisonBenchmark`, a fixed 20-case corpus runner, four passes per engine (first pass + three warm passes), per-case output/guard metrics, batch recovery, and JVM regression tests.
- The comparison is exposed in Settings → Diagnostics as **Compare ML Kit vs Bergamot**. It uses raw local engine callbacks and bypasses the app translation cache; it does not switch the selected runtime engine.
- CI run [43716970386](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43716970386) on commit `94febc3d25247e1cb696927051e99e276486396d` completed successfully: unit tests, pinned PaddleOCR model downloads/checksums, Bergamot ARM64 runtime validation, debug APK build, APK artifact upload and test-report upload all passed. The run was a PR validation run; it did not publish a Release. APK artifact size: 70,020,409 bytes. No test count is asserted because the JUnit report has not been extracted.
- Existing v0.12.6 remains the published prerelease with its APK. The 70 MB artifact from run 43716970386 is a separate Actions artifact, not the published v0.12.6 APK.

### Engineering finding and fix — diagnostic interference
- Found that the comparison called `MlKitTranslator.warmUp()`, which can start the background ML Kit separator self-test. That self-test uses the same process-wide `translateRaw` client and could run concurrently with the comparison, contaminating latency and resource observations.
- Added `warmUpForBenchmark()` to prepare the shared ML Kit model without scheduling a new batching self-test.
- Added a process-wide diagnostic lock. The background separator self-test and the explicit ML Kit/Bergamot comparison both use it, preventing their measured requests from overlapping.
- Settings now runs the benchmark-only ML Kit warm-up and all comparison passes under this lock. Runtime engine selection and saved batching policy are not changed by the comparison itself.

### Changes pushed to the development branch
- `e906fd4d40bb4efd4a1988360e445e22997ea6cb` — isolate ML Kit warm-up and self-test behind a diagnostic lock.
- `3dbfa11e88d291113498d84d1154812cabb1951a` — serialize the explicit engine comparison and use benchmark-only warm-up.
- `a7b314b4cc7869a38fe39ce32ddffbb4b5a9c9b2` — prepare v0.12.7 / versionCode 47.
- `e83687b83161c3966463dd7a16b515ab628cd76a` — document the benchmark isolation fix in CHANGELOG.
- `97ef808863a8c2fc85433ee948f98f128ccd67b8` — update the device checklist for the multi-engine comparison and isolation behavior.
- Branch: `feature/v0.12.4-bergamot-experiment`. No changes to `main`, no PR merge.

### Verification state
- The source and documentation updates are **Implemented**; CI for the new v0.12.7 head is pending and must be checked before claiming CI-verified or Release-published.
- Prior baseline CI remains successful at run 43716970386 / commit 94febc3; it does not verify the new commits.
- The workflow is configured to publish a real prerelease from a push to the designated development branch after tests and APK packaging pass. Confirm the resulting tag and attached APK before asking for installation.
- No physical-device test is claimed. Bergamot JNI/model inference, PaddleOCR accuracy, semantic quality, latency/RAM and update-install compatibility remain **Not device-verified**.

### Next actions
1. Inspect the latest push and PR workflow runs for the new v0.12.7 head; require green unit tests and APK packaging and verify PR runs skip Release publication.
2. Confirm GitHub Release `v0.12.7` exists with the APK attached; verify package ID, versionCode, ABI and signing identity against the previously installed build before claiming update compatibility.
3. Once CI and Release are confirmed, prepare the v0.12.7 phone test from `MANUAL_TESTS.md`: run the same-corpus comparison offline, copy the report and screenshots, and return actual phone results.
4. Keep ML Kit default and Bergamot experimental until semantic review and real-device reliability/performance evidence support a decision.
5. Preserve PR #3 → PR #4 stacked order; do not merge PR #4 directly to main while PR #3 remains its base.


### Final refinement before CI re-run
- Commit `a7a90475051ae09f6b06c7e4b01b33b14bd6ff03` moves the benchmark-only ML Kit warm-up inside the same diagnostic lock as the full comparison. This closes the remaining window in which a pending batching self-test could overlap the warm-up request.
- Updated the device checklist and both engineering records to state that the lock covers warm-up as well as all measured passes.
- Earlier PR workflow run `43718698863` was cancelled by subsequent branch updates before its build job began; it is not a test result. The latest branch state still requires a fresh green CI run.

## Verified release handoff — v0.12.7 (2026-11-15)

### Final head and code change
- Development branch head: `213cb486297d231f1e2edb3616b14bc5a9d715e5`.
- Final concurrency refinement: a queued ML Kit batching self-test rechecks `policy.needsSelfTest()` after it acquires the exclusive diagnostic gate. If a foreground benchmark has already written a fresh policy choice while the self-test was waiting, the stale self-test exits without overwriting it.
- ML Kit stays the default offline translator; Bergamot remains optional/experimental. No engine promotion is inferred from CI.

### CI evidence for the exact final head
- Push workflow [43719094340](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43719094340): **success**.
- PR validation [43719099811](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43719099811): **success**.
- Both runs completed pinned PaddleOCR model retrieval/checksum checks, pinned Bergamot ARM64 runtime validation, JVM unit tests, and debug APK assembly. The push run also uploaded APK/test-report artifacts and published the Release. The PR run correctly skipped both Release-decision and Release-publication steps.
- The earlier run 43718878249 was cancelled during APK assembly after tests passed because a newer branch head superseded it; it is not the final verification result.

### Published artifact
- GitHub Release: [v0.12.7](https://github.com/mouadknight0-cell/ManhwaLens/releases/tag/v0.12.7) — published prerelease, not a draft.
- APK: `ManhwaLens-v0.12.7-debug.apk`; 71,347,910 bytes.
- GitHub asset SHA-256: `8f0eb02118c2cb70a93995acd6a79e8554459012b7bf57e0746bae2f6db6c84d`.
- App metadata: applicationId `com.manhwalens.app`, versionName `0.12.7`, versionCode `47`, ABIs `arm64-v8a` and `armeabi-v7a`.
- Previous published v0.12.6 metadata: same applicationId, versionCode 46.
- Signing continuity evidence: `ci/experimental-debug.keystore.base64` has the same Git blob SHA (`68bd7f5388218224353f11b456ff3c29fb44c64c`) at the v0.12.6 build commit and the v0.12.7 head. Both versions' CI workflow restores this file as the Android debug keystore before `:app:assembleDebug`. This supports in-place update compatibility; an actual Android install-over-existing-app test remains outstanding.

### Verification boundary / next action
- CI-verified: JVM tests, pinned external runtime/model validation, debug APK assembly, artifact upload and Release publication at the exact head above.
- Not device-verified: PaddleOCR accuracy/grouping, JNI inference, offline model availability on the phone, translation semantics, latency/RAM, and actual update installation.
- Next required human/device check: install v0.12.7 over the existing v0.12.6 app without uninstalling; then test Offline mode, run Settings → Diagnostics → Compare ML Kit vs Bergamot with both models preinstalled, and collect the report plus latency/RAM observations. Do not switch the default engine based on CI or lexical scores alone.
- PR #4 remains open Draft, targeting `feature/v0.12.3-paddle-default-settings`; preserve the stacked PR order and do not merge it directly to main.


## Session continuation — 2026-11-15 (diagnostic isolation and v0.12.8)

### Root cause of the benchmark misunderstanding
- The ordinary Diagnostics batching benchmark and 20-case quality check call raw ML Kit directly, even when Bergamot is selected in Offline translation settings.
- The ordinary batching benchmark also updates the saved ML Kit batching policy (parallel level / validated joined separator); this does not change the selected translation engine or tune Bergamot.
- The shared ML Kit warm-up reason previously used the same generic comparison label for both diagnostic paths. That label was ambiguous and did not prove that the paired comparison had run.

### Implemented on the development branch
- Renamed the action to ML Kit batching benchmark. English and Arabic descriptions state which engine it calls and that it updates only ML Kit batching policy.
- Split the diagnostic warm-up reason so the batching benchmark and paired comparison are distinguishable.
- Added a separate Bergamot-only benchmark over the fixed 20-case EN→AR corpus. It calls the native Bergamot adapter only, without ML Kit invocation, ML Kit fallback, translation cache or preference mutation. It reports the first startup pass separately from three warm passes and includes per-case quality output.
- Added explicit localized labels/descriptions for three separate actions: ML Kit batching benchmark, Bergamot-only benchmark, and ML Kit-vs-Bergamot comparison.
- Corrected paired comparison's ML Kit dispatch: it snapshots the device's saved BatchPolicy and uses a validated joined request only when safe, otherwise the saved parallel level. Missing ML Kit outputs are retried individually once.
- The paired report records the actual ML Kit dispatch and raw-engine/no-cross-engine-fallback semantics, and clarifies that timings are page-batch wall times. The comparator does not mutate selected engine or saved batching policy.
- Added JVM tests for Bergamot-only invocation isolation, stable corpus ordering, single-engine batch failure recovery, joined/parallel dispatch, concurrency bounds and targeted ML Kit retries.

### Exact verification
- PR validation run 43724428631 on the v0.12.8 release-target commit: SUCCESS. Unit tests, pinned PaddleOCR model/checksum validation, Bergamot ARM64 runtime validation, debug APK assembly and artifact upload succeeded. Release steps were correctly skipped for a pull_request event.
- An earlier CI attempt failed at Android resource compilation due an apostrophe in an English string; that resource was corrected. A later CI attempt failed Kotlin compilation because the RunResult constructor used the wrong named argument; corrected in commit c60f207a70b2bd47e23697d80058a7188f1f3807. These failed attempts are not the final result.
- GitHub Release API verified v0.12.8 exists as a published prerelease, not a draft. Release ID 446118801, target commit 614250692d384beaa3bb9a818aebf89d23dbbce1, with APK asset ManhwaLens-v0.12.8-debug.apk and SHA-256 listed above.
- CI restores the stable experimental debug keystore. The applicationId is unchanged and versionCode increased from 47 to 48. This supports, but does not prove, in-place update compatibility; actual certificate extraction/comparison and phone installation are still outstanding.

### Verification boundaries and next action
- Implemented + Unit-tested + CI-verified: diagnostic labeling/isolation, Bergamot-only benchmark runner, paired ML Kit dispatch and selective retry.
- Release-published: v0.12.8 GitHub prerelease with discoverable APK asset.
- Not device-verified: Bergamot JNI inference/output quality and latency, new diagnostics on the user's phone, model download/offline behavior, OCR accuracy/grouping, RAM use, and update-over-existing-app installation.
- Next device evidence: install v0.12.8 over the existing app without uninstalling; run Bergamot-only with network disabled and send its copied report. Paired comparison can follow once both offline models are installed. Do not promote Bergamot to default from speed or lexical F1 alone.


## Session continuation — 2026-11-15 (APK update-signing guard)

### User-reported issue
- User reports Android repeatedly asks to uninstall the existing ManhwaLens app before installing each new APK. This is an update-compatibility blocker; do not tell the user to uninstall as a routine workaround.
- Android in-place update requires the same applicationId, a signing certificate accepted as the same signing identity, and a strictly higher versionCode.

### Findings
- Current development branch keeps applicationId `com.manhwalens.app`; v0.12.8 uses versionCode 48.
- CI restores `ci/experimental-debug.keystore.base64` as the Android debug keystore before building. The tracked key file has the same Git blob SHA (`68bd7f5388218224353f11b456ff3c29fb44c64c`) at tags v0.12.6, v0.12.7 and v0.12.8. This proves the CI input is stable across those tags, but does not yet prove the installed phone APK was signed by it.
- Therefore the exact cause for the user's installed APK is not yet Device-verified. Likely causes include an older APK signed with a different local/CI debug key, or a package/version mismatch. Do not claim the issue is solved until the APK certificates are compared or an in-place install succeeds on the phone.

### Implemented
- Commit `c2b26c15208469d42be8dcca0c40cca19fa14ef6`: added a CI gate in `.github/workflows/android.yml` before artifact/release publication. It inspects current APK package name, versionCode and signer SHA-256; downloads the most recently published release APK; verifies package identity and strictly increasing versionCode; and fails the workflow if the signer certificate differs. PR builds remain excluded from this publishing compatibility gate.

### Verification status
- Implemented: CI signing-continuity gate.
- Not yet CI-verified: the newly added gate has not yet completed a push workflow run.
- Not verified: comparison against the APK currently installed on the user's phone; actual in-place update test.
- If the gate fails against the previous published release, stop publication and investigate/restore the correct signing key. Never silently publish an APK that requires uninstalling the previous release.

### Next actions
1. Inspect the push workflow triggered by commit `c2b26c15208469d42be8dcca0c40cca19fa14ef6`; fix any workflow-script issue and confirm certificate continuity against the latest release.
2. If CI confirms v0.12.8 and the prior release use the same certificate, ask the user to try installing the APK directly over the existing app. If Android still requests uninstall, obtain the exact installer error and identify the currently installed version/signature before changing release signing.
3. Do not increment app version or publish another APK until the update gate passes.


## Signing policy — future update-compatible releases (2026-11-15)

### User decision
- The user explicitly accepts that the currently installed/current release may be ignored for update compatibility. The requirement applies to all future releases: each new APK must install over the preceding future release without uninstalling or losing app data.
- Do not spend further work trying to make v0.12.8 compatible with a different historical signing identity.

### Verified CI signing evidence
- The single tracked CI signing input is `ci/experimental-debug.keystore.base64`; its Git blob SHA is identical at tags v0.12.7, v0.12.8 and the active development branch: `68bd7f5388218224353f11b456ff3c29fb44c64c`.
- Workflow run [43726600572](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43726600572) on head `f15bd7623aff2ae4e7657a7c9009dc95b355ebc3` passed unit tests and assembled the APK, but the new update-compatibility gate correctly blocked publication because the newly built APK signer SHA-256 was `b22d6e73e3fa929ffd4f0afa428a20fbc522f193809974f9203bfd41a39ed5f7`, while v0.12.7 was signed with `5462bf33998170673eb32dcc2cd37e47d1cd67f51c46046a2faa202977a92253`.
- This is a historical signing transition, not a reason to rotate or delete the current key. The next future release must establish the stable baseline using the current tracked key, then every subsequent APK must match it.

### Mandatory release contract
- Keep exactly one CI debug signing keystore input and restore it before every debug APK build; never regenerate or replace it during routine builds.
- Preserve `applicationId=com.manhwalens.app` and increment `versionCode` strictly for every published APK.
- Before publication, extract signer SHA-256 from the candidate APK and compare it to the most recent future-baseline release APK; reject mismatches, package changes or non-increasing version codes.
- Once the first future baseline release is published, every later release must pass the same signer/package/version gate. A failed gate means no Release publication and no request for phone installation.
- The user has accepted a one-time break from the currently installed historical build; do not claim the first future APK updates v0.12.8. Explain the one-time install transition only when that APK is ready.

### Status
- Implemented: CI key restoration and APK metadata/signature continuity gate.
- CI-observed: current branch build is blocked solely by the expected mismatch with historical v0.12.7; unit tests and APK assembly succeeded.
- Not yet verified: successful publication of the first future baseline APK and actual install-over-existing-future-release on a phone.
- Next action: continue engineering on the active branch, increment app version for the next release, confirm the candidate signer matches the v0.12.8/current-key build baseline, then publish only after the gate passes.


## Deep source audit continuation — 2026-11-15

### Current development head
- Branch: `feature/v0.12.4-bergamot-experiment`
- Current head: `3e1a2e72a5cb8a905cb37e7c80de4992b740d2d7`
- PR #4 remains the active stacked development PR; do not merge it directly into main.

### Implemented source fixes
1. **Arabic rendering — commit `2123436dd867b48e17873371639fb64c69baa775`.**
   - Force RTL paragraph direction in both text measurement and the actual fill/outline StaticLayouts.
   - Remove the four-line drawing cap so the number of measured and rendered lines matches.
   - If the complete translation cannot fit at minimum size, withhold the patch and retain the original artwork rather than painting a truncated sentence.
   - PR validation run #43739136295 succeeded (Unit tests and Debug APK build). The push workflow did not pass the old update gate at that head; it was not a Kotlin compilation/test failure.

2. **Release signing and update gate — commit `d1a086173b0ebbe1b730294184b2b5f823e01af0`.**
   - Set next app metadata to versionName `0.12.9`, versionCode `49`.
   - Explicitly bind the Debug build to `ci/experimental-debug.keystore` (PKCS#12) rather than relying on implicit AGP debug-key discovery through `~/.android/debug.keystore`.
   - Added an APK signer fingerprint check against that exact keystore before upload/publish.
   - Fixed the current release-tag lookup by passing `APP_VERSION` / `VERSION_TAG` through the shell environment; do not embed the version expression in a quoted string with an extra backslash.
   - Kept the previously recorded user-authorized one-time transition: v0.12.9 may establish the future signing baseline after checking package/versionCode continuity against v0.12.8; after that, every new APK must match the preceding future release certificate exactly. Do not claim v0.12.9 updates the historical v0.12.8.
   - The repository key fingerprint is `0e36ccf390d6b2ac1696a8b19d173f448837703250baf42d37dbe25e6d109152`. The v0.12.8 published APK fingerprint was measured as `a2a5188b32649600b739d006c41e5509dcc05270afaaf41723da46c5e3bf8dc5`; they differ, as anticipated by the recorded one-time transition.

3. **PaddleOCR native memory lifecycle — commit `3e1a2e72a5cb8a905cb37e7c80de4992b740d2d7`.**
   - Serialize OCR inference and shared PaddleOCR teardown through the same process-wide lock.
   - Expose `TextRecognizerClient.releaseShared()`, which clears the shared reference and closes ONNX Runtime sessions after any active inference completes.
   - Schedule PaddleOCR release off the main thread alongside Bergamot release on `TRIM_MEMORY_RUNNING_LOW` and service shutdown.
   - This addresses retained ONNX sessions; device RAM profiling is still required to establish the actual reclaimed amount and cold-reload cost.

### CI state at this handoff
- Latest push run #43740757702 and PR validation run #43740762499 target head `3e1a2e72a5cb8a905cb37e7c80de4992b740d2d7`; both were in progress when this note was written. Inspect exact jobs/steps before claiming success.
- No v0.12.9 Release or new APK has been published by these commits.
- No phone/device verification has been performed.

### Deep audit findings still open
- **OCR batching:** `PaddleOCRConfig.recBatchSize` is 1. Recognition therefore invokes ONNX once per detected text crop. `OCREngine` supports batching, but any increase needs a benchmark on a fixed screenshot corpus with cold/warm latency and peak RAM, because batch padding grows to the widest crop.
- **OCR data evidence:** current unit tests validate decoder/config/resize/grouping/filtering logic, not end-to-end word accuracy on real manga screenshots. No controlled ML Kit-versus-Paddle screenshot corpus result is available. Keep OCR selection and confidence threshold open pending evidence.
- **Bubble grouping:** `BubbleGrouper` performs pairwise O(n²) union-find and blocks components with excessive horizontal center spread. It intentionally permits vertical chain merging; tests cover horizontal bridges, but vertically adjacent distinct bubbles with similar centers and screenshot-based grouping remain untested. This is a risk to measure, not a confirmed defect.
- **Arabic CTC order:** decoder reverses RTL visual runs while preserving Latin/number runs. Verify diacritics/combining marks against the actual pinned model dictionary before altering Unicode handling.
- **Name learning:** `NameMemory.plan` accepts optional page-level Arabic decisions, but the observed production call through `CharacterResolver.resolve` currently plans without pageArabic. The page-model-learning branch appears dormant unless a separate call site supplies it. `pageNameSpellings` indexes the supplied text list using the bubble ID directly; verify the ID/list index contract and add tests before treating the suspected off-by-one as a defect.
- **Translation quality:** ArabicValidator and quality fallback are guardrails, not semantic evaluation. The 20-case report's token F1 is lexical overlap only. ML Kit remains the reader's default; Bergamot remains diagnostic/experimental and must not be promoted without same-corpus human semantic review, echo/omission rates, latency and memory.
- **Device testing:** PaddleOCR model accuracy, OCR bubble detection, Arabic visual quality, Bergamot JNI, true offline model availability, RAM/latency and future-release in-place update remain Not verified.

### Next actions
1. Confirm runs #43740757702 and #43740762499. The push job must pass unit tests, Debug APK build, exact pinned-key signer check, and v0.12.9 baseline metadata gate. PR validation should skip publication.
2. If the signer step fails, do not publish; inspect actual build APK certificate and Gradle signing configuration. Never bypass the expected-key check.
3. After CI success, audit remaining OCR/translation paths and add targeted unit tests where a deterministic defect is demonstrated.
4. Only after the first future baseline APK is published and its metadata/signature are verified should a phone installation be requested. The actual in-place update between future releases still needs user-side device verification.


## v0.12.9 future signing baseline — published and CI-verified (2026-11-15)

### Release
- GitHub Release: https://github.com/mouadknight0-cell/ManhwaLens/releases/tag/v0.12.9
- State: published prerelease on the main GitHub Releases page.
- Asset: `ManhwaLens-v0.12.9-debug.apk` (71,367,838 bytes).
- APK SHA-256: `0234f60ac8477d0b6045626a1c6a8c2e9bc951f8bf9f42776e86068db93f6a89`.
- Package: `com.manhwalens.app`; versionName `0.12.9`; versionCode `49`.
- Certificate SHA-256 measured from the actual published APK: `0e36ccf390d6b2ac1696a8b19d173f448837703250baf42d37dbe25e6d109152`.
- The APK certificate matches the tracked PKCS#12 signing input and the CI's explicit expected-certificate value exactly.
- This is the first future baseline after the one-time historical signing transition from v0.12.8. Do not claim it can update v0.12.8; subsequent releases must match v0.12.9's certificate and preserve package identity with a strictly greater versionCode.

### CI evidence
- Push workflow run #43740922862: https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43740922862 — **success**.
- PR validation run #43740957137: https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43740957137 — **success**; release publication and update-compatibility steps were correctly skipped for pull_request.
- Unit test report artifact #14172605498: **779 tests, 0 failures, 0 ignored**.
- Push workflow succeeded through: Unit tests, Debug APK build, APK rename, pinned-certificate validation, v0.12.8 package/version continuity check, APK artifact upload, release decision and GitHub Release publication.
- The workflow log explicitly confirms signer SHA-256 `0e36ccf390d6b2ac1696a8b19d173f448837703250baf42d37dbe25e6d109152` and the one-time baseline transition against v0.12.8.

### Source changes included in v0.12.9
- Renderer fix commit: `2123436dd867b48e17873371639fb64c69baa775`.
- Explicit debug signing / v0.12.9 baseline / tag lookup fix: `d1a086173b0ebbe1b730294184b2b5f823e01af0`.
- PaddleOCR ONNX session lifecycle fix: `3e1a2e72a5cb8a905cb37e7c80de4992b740d2d7`.
- Project handoff audit note commit: `d9fe90ed60617f6646eb7f76c652adc752192429`.

### Verification boundary
- Implemented: full text RTL rendering, no four-line truncation, preserve original art when Arabic cannot fit; safe PaddleOCR/ONNX release; explicit stable debug-signing configuration and certificate gate.
- Unit-tested / CI-verified: 779 unit tests and Debug APK build on the same source head as v0.12.9.
- Release-published: yes, v0.12.9.
- Not device-verified: real screenshot OCR accuracy, bubble grouping precision, Arabic placement/legibility on varied phones, actual memory reclamation, startup/cold OCR latency and installation behavior on a physical phone.

### Next engineering priorities
1. Build a labeled fixed screenshot corpus and measure PaddleOCR text CER/word accuracy, missed detections, false detections, grouping, confidence-threshold rejects and per-page time. Current OCR path is Paddle-only; there is no automatic ML Kit fallback inside TextRecognizerClient.
2. Compare Paddle recBatchSize 1 vs small batches on-device, measuring peak memory and total page latency before changing the default.
3. Add vertical-neighbor / stacked-bubble false-merge regression cases and screenshot-level grouping evaluation.
4. Inspect Arabic CTC visual-order reversal against the exact pinned model dictionary, especially combining marks/diacritics.
5. Reconcile the dormant pageArabic name-learning path with actual bubble ID indexing and production call sites before activating it.
6. Perform physical-device validation. Never call CI proof of device behavior.


## Session continuation — 2026-11-15 (protocol adoption, v0.12.10 CI repair)

### Repository state re-verified
- Repository: https://github.com/mouadknight0-cell/ManhwaLens (public; connected GitHub permissions include push/admin).
- Default branch: `main`. Active branch: `feature/v0.12.4-bergamot-experiment`.
- PR #4 is open, Draft, and targets `feature/v0.12.3-paddle-default-settings`; it remains stacked on PR #3. Do not merge PR #4 directly to main.
- PR #4 head before this session's repair: `1858ed31568205648fb85a7855ed1befbd7b3644`; PR metadata reported 242 commits and 71 changed files.
- App candidate on that head: `applicationId=com.manhwalens.app`, `versionName=0.12.10`, `versionCode=50`. This is a development candidate, not a published release.
- Latest verified GitHub Release remains v0.12.9 (published prerelease) with APK `ManhwaLens-v0.12.9-debug.apk`, SHA-256 `0234f60ac8477d0b6045626a1c6a8c2e9bc951f8bf9f42776e86068db93f6a89`. No v0.12.10 Release/APK was present in the latest Releases API response.

### Current candidate changes reviewed
- v0.12.10 changelog proposes allowing ML Kit EN→AR model download over Wi-Fi or mobile data during first-use translation and warm-up; adding exact-source corrections for `No.` → `لا.`, `Run!` → `اهرب!`, and `KRAK` → `طَق!`; applying those corrections after name repair on fresh and cached results; persisting corrected cache values.
- ML Kit remains the default offline translator. Bergamot remains optional/experimental. No engine promotion decision is justified by this commit.
- `ShortDialogueOverrides.apply` explicitly maps exact normalized source `KRAK` to `طَق!`. The fuzzy memo test must therefore expect the corrected visible value, while separately asserting that no second engine call occurs.

### CI failure and repair
- Latest pre-repair PR validation run: [43750781659](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43750781659), event `pull_request`, head `1858ed31568205648fb85a7855ed1befbd7b3644` — **failure**.
- Job steps confirm pinned PaddleOCR model downloads/checksums and Bergamot ARM64 runtime validation succeeded. Unit tests failed: **786 tests completed, 1 failed** — `TranslationMemoTest.cachingTranslatorUsesTheMemoItIsGiven`. APK build and later signing/release steps were skipped because tests failed. Release steps were not run.
- Root cause: the test had been changed to expect raw `AR:KRAK!`, but the production cache-hit path intentionally applies `ShortDialogueOverrides` and returns `طَق!`. This is a stale test expectation, not evidence that the override implementation failed.
- Fixed the regression assertion to expect `طَق!` while preserving the assertion that the fuzzy memo avoids a second engine call.
- Repair commit: `1a2d5721835f310b927ca4e96d30abef694a0bd0` — `test: align memo expectation with source-aware SFX correction`.
- Status after this commit: **Implemented** (test correction pushed). CI re-run on the new head is pending verification; do not claim tests/build pass until a new run completes.

### Verification boundary
- GitHub repository/PR/branch/release metadata: freshly inspected.
- CI pre-repair: failed as detailed above; no APK produced from that run.
- Test fix: pushed, not yet CI-verified at the time of this note.
- v0.12.10: not Release-published; no phone installation should be requested yet.
- Physical-device OCR accuracy, bubble grouping, Arabic semantic quality, Bergamot JNI/offline behavior, memory/latency and future-release update installation remain **Not device-verified**.

### Next engineering actions
1. Inspect the new push and PR workflow runs after commit `1a2d5721835f310b927ca4e96d30abef694a0bd0`; verify all 786+ unit tests and APK assembly.
2. If green, inspect test report and verify package/version/signing checks; confirm the development-branch workflow publishes v0.12.10 only once, as a GitHub prerelease with APK asset.
3. Audit the progressive overlay callback path to ensure it applies the same source-aware short-dialogue corrections as final and cache-hit paths; add a deterministic regression test if a real discrepancy exists.
4. Continue OCR accuracy work with a fixed labeled screenshot corpus; no end-to-end PaddleOCR word accuracy or grouping result is currently evidenced by CI.
5. Request phone testing only after a verified v0.12.10 Release APK exists and its package/version/signing continuity is checked.


## Session continuation — translation diagnostics supplied by user (2026-11-15 20:02 UTC)

### Evidence received
- User supplied 59 local translation-pair diagnostic entries from Xiaomi 23106RN0DA / Android 14, plus ML Kit batching and 20-case quality benchmark output. These are user-provided device captures; they are not independently reproduced by CI or by this session.
- All 59 pair records use `ml_kit_en_ar`; most are status OK, with two shown QUALITY_REJECTED entries (`PFFFFT ACK!!` and UI text `odf`) rejected as UNTRANSLATED_ECHO. This demonstrates that the Arabic guard catches obvious English echoes but allows many semantically incorrect Arabic outputs.
- Clear examples of accepted but incorrect output include `LOOKS LIKE IT'S DONE!` → `يبدو أنه انتهز!`, `Live mode OFF` → `وضع حية قبالة`, `No.` → `رقم`, and `Run!` → `تشغيل!` (the latter two in the raw quality corpus). Other pair examples expose malformed OCR input: `YOL`, `LNINJURED`, `UNINOURED`, `B055 15`, `HuNTEr's`, and fragmented text across bubbles. Translation and OCR defects must be measured separately.
- The 20-case ML Kit quality report: 20/20 completed; Arabic guard accepted 19, rejected/missing 1; exact normalized matches 2/20; mean Arabic token F1 52.0%. F1 is lexical overlap only and is not semantic accuracy. This is not a Bergamot comparison and does not measure post-processing/correction quality if the benchmark calls raw ML Kit.

### Batching result and policy
- Same 20-bubble ML Kit EN→AR benchmark: Sequential median/worst 1146/1275 ms; Parallel x6 1163/1753 ms; Joined ` | ` 644/649 ms with 20/20 mapped; Joined `###` 1035/1496 ms; Joined `<1>` 751/760 ms.
- Joined ` | ` is 43.9% faster than sequential median and 44.6% faster than Parallel x6 median in this sample. Joined newline variants and `@@@` fail the result mapping (19 bad); do not use them.
- Decision: retain Joined ` | ` as the measured ML Kit batching policy for now. This is a single device/corpus benchmark; validate delimiter collision, empty outputs, reordered/omitted segments, embedded vertical bars, long combined payloads, truncation, and per-bubble error isolation before treating it as universally safe. Do not increase parallelism based on this dataset.

### Engineering interpretation / next implementation priorities
1. Keep the Arabic quality guard, but do not treat `status=OK` or guard acceptance as translation correctness. Add a second semantic/linguistic quality layer or curated source-aware corrections for high-confidence short commands, negations, idioms and UI state labels; corrections must be exact/context-scoped, not broad substitutions.
2. The existing v0.12.10 candidate already contains exact-source corrections for `No.` → `لا.`, `Run!` → `اهرب!`, and `KRAK` → `طَق!`. Verify their placement on fresh, cached and progressive overlay paths. The fixed quality benchmark currently reports raw ML Kit and therefore must remain explicitly labelled as raw-engine output; add a separate post-processing evaluation to measure visible FINAL AR.
3. Extend the fixed corpus with the observed failures: short `No.`, `Run!`; idiom `piece of cake`; `looks like it's done`; `Live mode OFF`; sound effects; broken OCR inputs; proper names/ranks; multi-bubble fragments. Record raw candidate, final output, guard result, correction ID, cache state, and per-case semantic review.
4. Fix OCR separately: preserve line/bubble context and detect likely OCR corruption (e.g. `B055` vs `BOSS`, `YOL` vs `YOU`, `LNINJURED`/ `UNINOURED`). Do not silently rewrite uncertain source text; retain raw OCR and apply confidence-aware correction only when evidence is strong.
5. Add explicit tests for Joined ` | ` parsing: exactly N outputs, empty segment, delimiter in source/output, reordered/missing/extra segments, malformed model response, and fallback/retry behavior while preserving successful bubbles.
6. Keep ML Kit as default and Bergamot experimental. The supplied logs contain no paired Bergamot results and cannot support an engine ranking.

### Repository / CI state at this update
- Development branch: `feature/v0.12.4-bergamot-experiment`; PR #4 remains open Draft and stacked on PR #3; do not merge directly to main.
- Latest branch head observed: `d4de5f3cc77f1ad761d8469334aa7ecb3904cefe` (documentation commit). PR validation run #43752989626 was observed in progress; no success/failure conclusion was available at this update.
- Latest published GitHub Release observed: v0.12.9 prerelease with APK. v0.12.10 is not yet confirmed published; do not request installation until its Release APK, package/version and signer continuity are verified.
- No source code was changed in this interaction. The diagnostics and analysis are documented only. User phone results are recorded as supplied evidence, not as independently reproduced test results.
- Device: user-provided diagnostics from Xiaomi 23106RN0DA / Android 14. No additional device test was run by this session.

### Verification boundary and next action
- User-provided device diagnostics: received and analyzed; not independently reproducible from CI.
- Joined ` | ` batching: benchmarked in supplied device report, map OK 20/20; broader adversarial parser validation remains pending.
- Translation semantics: insufficient; mean lexical F1 52.0%, no semantic human-scoring data.
- OCR quality: unresolved; malformed OCR strings visible in the same capture.
- Next: inspect current source implementations and test coverage for batching parser, `ShortDialogueOverrides`, progressive/cache paths and quality benchmark; implement regression tests and a post-processing evaluation, then re-run CI.


## Engineering continuation — fixes from 59-pair device diagnostics (2026-11-15)

### Source changes implemented on the development branch
- Expanded exact, source-aware final-output corrections in `ShortDialogueOverrides` for observed mistranslations:
  - `Looks like it's done!` → `يبدو أن الأمر انتهى!`
  - `Ha, this is a piece of cake.` → `ها، هذا سهل للغاية.`
  - `The magical core` → `النواة السحرية.`
  - Exact observed longer sentence about receiving the magical core → `هذا ما تحصل عليه بعد قتل الوحش: النواة السحرية.`
- Added unit tests for each correction. Matching is based on normalized exact English source, not the wrong Arabic output; longer unrelated sentences remain untouched.
- Added `live mode on` and `live mode off` to ManhwaLens-owned UI strings so this exact status label is dropped instead of translated as page text; added a regression test.
- Expanded the fixed raw ML Kit reference corpus from 20 to 23 cases with the observed idiom, completion phrase and fantasy terminology; updated stable-ID/category tests. This corpus still measures raw ML Kit + Glossary output, not post-processed visible output; the report must continue to say so.

### Commits
- `3e10f36347fadeedfeaed79cbebdff5a88e294ab` — source-aware translation corrections.
- `7818a88d10a98270fc97a3168a7c923ba3554ff1` — correction regression tests.
- `77d9144c451822ad64ffc864bc85d5dcda51618b` — filter Live mode status label.
- `0fbbcbab6beb132982f38b51611fad5a73e181f1` — owned-UI regression test.
- `1c3b690f06575a4359a76fb4497ab650933b29fc` — expand raw quality corpus to observed failures.
- `911ac443e3b20d347be3945e313789fe3f2d9373` — update corpus test expectations.
- Latest observed PR #4 head: `911ac443e3b20d347be3945e313789fe3f2d9373`.

### CI state at this update
- Push run #43753300917 on the latest head: build job in progress; not yet verified.
- PR run #43753306049 on the latest head: queued; not yet verified.
- Earlier per-commit runs were cancelled by newer pushes; they are not pass/fail evidence for the final head.
- No APK/release was produced or verified by these source edits. Latest published release remains v0.12.9; v0.12.10 is not confirmed published.

### Analysis / limitations
- The 59-entry capture contains non-manhwa screen content (notification shade, Android/app UI, launcher/menu text) mixed with comic dialogue. The OCR pipeline has top/bottom edge filtering and own-window filtering, but arbitrary external app/launcher surfaces are not reliably identifiable by exact text. Do not globally suppress common words or translate every screen block as manga without a screen-context policy.
- OCR strings such as `YOL`, `LNINJURED`, `UNINOURED`, and `B055 15` are upstream recognition errors or segmentation defects; translation corrections cannot safely reconstruct them without contextual OCR evidence. Preserve raw OCR and measure recognition separately.
- The joined ` | ` batching result remains 644 ms median, 649 ms worst, 20/20 mapped in the supplied sample; newline and `@@@` mappings failed. Keep ` | ` pending broader adversarial tests and repeatable device runs.
- The 20-case run's 52.0% token F1 and 2/20 exact matches are raw lexical indicators, not semantic scores. The corpus is now 23 cases on source; the next run will provide a new raw baseline after CI and on-device execution.

### Next actions
1. Wait for final-head push and PR CI; inspect unit-test result and APK packaging, repair any failure.
2. Add adversarial tests for PIPE splitting (empty, extra, missing, reordered, marker collisions and fallback) before treating the delimiter as generally robust.
3. Add a separate post-processing/visible-output quality report, so raw engine output and final overlay text are never conflated.
4. Build a labeled OCR screenshot corpus and evaluate PaddleOCR CER/WER, detection recall/precision, grouping and screen-edge/UI contamination.
5. Keep ML Kit default and Bergamot experimental; no paired Bergamot semantic results were supplied.
6. Do not request phone installation until a verified v0.12.10-or-later GitHub Release APK and signing/version continuity are confirmed.


## Engineering continuation — repair expanded-corpus CI regressions (2026-11-15 19:45 UTC)

### CI evidence and root causes
- The latest push/PR build on commit `d57311b597864e16d9e1a165c217f1b008fec139` failed: 789 unit tests completed, 6 failed.
- Failures were stale 20-case assertions after the reference corpus had expanded to 23, a paired-engine test callback that did not apply the benchmark's Bergamot single-line normalization, and an exact source correction for `The magical core` being blocked because `Glossary.prepare` rewrote `core` to `الجوهرة` before the exact-source lookup.
- These are test/normalization defects; they do not establish a runtime translation engine failure.

### Fixes committed
- Commit `8585dfab05ff88dacc4ea66c52cd79e1e35594b1`:
  - Update quality benchmark expectations from 20 to 23 cases.
  - Make the paired-engine test resolve each prepared input using the same `BergamotTextPreparation.asSingleLine(Glossary.prepare(...))` transformation as production.
  - Match source-aware dialogue corrections against normalized raw OCR source, not Glossary-mutated text. This lets the exact fantasy phrase `The magical core` override the generic glossary term `core`.
  - Update the magical-core regression test to exercise the glossary-collision case.
- New push and dynamic validation were queued for this commit at the time of this report update; no pass result is claimed yet.

### Release gate
- App version metadata is `0.12.10`, versionCode `50`.
- Latest published Release remains `v0.12.9`; it contains `ManhwaLens-v0.12.9-debug.apk`.
- No v0.12.10 APK has been verified or published. Do not ask for installation until unit tests, build, signer fingerprint, package/versionCode and update compatibility all pass.

### Next actions
1. Verify CI on commit `8585dfab...`; inspect exact failures if any remain.
2. Update this report after CI and after any source changes.
3. Add a dedicated post-processing/visible-output quality evaluation separate from raw ML Kit benchmark results.
4. Continue adversarial Joined-pipe mapping tests and OCR dataset evaluation.



## Engineering continuation — autonomous leadership protocol reaffirmed (2026-11-15 20:43 UTC)

### User directive
- Reaffirmed the standing engineering mandate: treat every ManhwaLens-related message as a continuation of one project; verify repository state before acting; update this handoff report after every project interaction, including analysis/decisions without source changes; distinguish verified work from pending/unverified work; prioritize OCR and Arabic translation quality; never fabricate execution or device-test claims.
- Release requirements reaffirmed: every candidate release must be a visible GitHub Release (pre-release allowed), include an installable APK, preserve `com.manhwalens.app`, monotonically increase versionCode, and retain the v0.12.9 signing certificate for upgrade continuity. Request physical-phone testing only when a verified APK is ready and a behavior cannot be proven in CI.
- No source-code change was made as a result of this protocol message; this section records governance and current repository evidence only.

### Repository state freshly checked
- Active branch: `feature/v0.12.4-bergamot-experiment`.
- Current branch HEAD: `a35a659ac0e644fff18d20d6a697137a2068b789`, commit message: `docs: record v0.12.10 validation failures and fixes`.
- HEAD parent: `8585dfab05ff88dacc4ea66c52cd79e1e35594b1` (the expanded-corpus validation repair commit).
- PR #4 remains open and Draft; it targets `feature/v0.12.3-paddle-default-settings`, not main. Do not merge it directly to main or bypass the stacked-PR sequence.
- GitHub Actions for HEAD are currently running, not yet concluded:
  - Push run #43753928167: `in_progress`.
  - Pull-request run #43753933415: `in_progress`.
  - Both runs target exactly `a35a659ac0e644fff18d20d6a697137a2068b789`. No pass/fail conclusion is claimed.
- Latest GitHub Releases API response confirms v0.12.9 is still the newest published release and contains `ManhwaLens-v0.12.9-debug.apk`. No v0.12.10 release or APK is present.
- PROJECT_STATE.md was fetched from the active branch at this HEAD (blob SHA `6bdfa9a654d25fd58530ed2e9fc20be921bfa297`); the preceding report had stopped at the pre-verification note for commit 8585dfab. This section supersedes that stale “queued” status with the current exact HEAD and active workflow IDs, but does not imply the workflows have completed.

### Current phase and next actions
1. Wait for and inspect both final-HEAD workflow runs; retrieve failed job logs if either concludes unsuccessfully.
2. If CI succeeds, verify unit-test count/report, APK build, package ID, versionName/versionCode, signer SHA-256 against the v0.12.9 baseline, artifact integrity and update compatibility before any release publication.
3. Do not publish or request phone installation until all release gates pass. If a physical test becomes necessary, publish v0.12.10 as a GitHub pre-release with its APK and concise test instructions.
4. Continue engineering after CI: validate Joined ` | ` parser adversarial cases; add separate raw-engine versus final-visible-output quality evaluation; build a labeled OCR screenshot corpus and measure detection/CER/WER/grouping. Keep ML Kit default and Bergamot experimental until paired semantic and resource measurements justify a change.

### Verification boundary
- Verified in this interaction: branch HEAD and parent; PR #4 state/base; two workflow run IDs/status/head SHA; latest release tag and APK asset; report blob SHA.
- Not yet verified: final CI outcome, APK for v0.12.10, release publication, signer/package/version checks for a new APK, physical-device behavior, OCR accuracy, and semantic translation improvement.


## Engineering continuation — v0.12.10 built and published (2026-11-15 20:5x UTC)

### Release verification
- GitHub Actions push workflow run #43754084691 completed with conclusion `success` on source commit `84858fd64ae067d245ae693598038bfa1b6670c5`.
- Build job completed successfully. Verified steps: unit tests, Debug APK build, APK signing certificate check, update-compatibility check against v0.12.9, APK artifact upload, test-report upload, and GitHub Release publication.
- Dynamic validation run #43754084176 completed successfully on the same source commit.
- Commit-associated validation run #43754089272 completed successfully on the same source commit.
- APK metadata from the Gradle source: package `com.manhwalens.app`, versionName `0.12.10`, versionCode `50`.
- CI explicitly verified update compatibility against v0.12.9: versionCode `49 -> 50`, package `com.manhwalens.app`, signer SHA-256 `0e36ccf390d6b2ac1696a8b19d173f448837703250baf42d37dbe25e6d109152` (same signing baseline).
- Published GitHub pre-release: https://github.com/mouadknight0-cell/ManhwaLens/releases/tag/v0.12.10
- APK asset: `ManhwaLens-v0.12.10-debug.apk`, 71,369,014 bytes.
- APK SHA-256 reported by GitHub asset digest: `a3b879e090aee65d7f9b8ce84207ead652826afa677023036782f6677e5877a1`.
- Direct APK: https://github.com/mouadknight0-cell/ManhwaLens/releases/download/v0.12.10/ManhwaLens-v0.12.10-debug.apk
- Release API confirms tag `v0.12.10`, target commit `84858fd...`, `prerelease=true`, `draft=false`, and the APK asset exists. It appears as the newest release.
- CI job log confirms the update-compatibility check succeeded; no claim is made that a human installed or exercised the APK.

### What this release contains from the source history
- Source-aware translation correction changes and regression tests.
- Filter for the app-owned `Live mode off` status label so it is not treated as manga dialogue.
- Expanded raw ML Kit reference corpus from 20 to 23 cases and updated benchmark/test expectations.
- Paired-engine test preparation aligned with Bergamot single-line normalization.
- Source correction lookup now uses normalized raw OCR source before glossary mutation, fixing the `The magical core` / `core` collision.
- This release's passing CI establishes automated tests/build/signing/update checks, not a semantic quality gain on real manga pages.

### Pull request / integration state
- PR #4 remains open and Draft, based on `feature/v0.12.3-paddle-default-settings`; do not merge to main without respecting the stacked branch sequence.
- At verification, branch HEAD was `84858fd64ae067d245ae693598038bfa1b6670c5`. This report update itself creates a subsequent documentation-only commit.
- Previous workflows on `a35a659...` were cancelled after newer pushes; they are not counted as validation. Final source-head runs above are the accepted evidence.

### Required device validation now
- A real-device check is now justified because CI cannot prove installation over the existing app, first-run ML Kit model download on the user's network, OCR/overlay behavior, or runtime memory/stability on the user's Xiaomi Android 14 device.
- Ask the user to install v0.12.10 directly over v0.12.9 without uninstalling, then test the 30 MB ML Kit model download with Wi-Fi and (if needed) mobile data, perform an EN→AR page scan, and report whether the install preserved app data and whether download/translation succeeded. Capture app diagnostics/logs if failure occurs.
- Do not state these device behaviors are verified until the user reports results.

### Next engineering actions
1. Await user device results for update installation, ML Kit model download and a representative manga scan.
2. Independently continue source audit and test development: adversarial Joined-pipe mapping tests; separate raw-engine and final-visible-output quality evaluation; OCR labeled image corpus with detection recall/precision and CER/WER; bubble grouping/order regression tests.
3. Keep ML Kit default and Bergamot experimental until paired semantic quality, latency and RAM evidence supports a change.
4. Refresh this report after every subsequent ManhwaLens-related interaction, including discussions that do not change source code.


## Engineering continuation — joined PIPE adversarial test coverage (2026-11-15)

### Source audit
- Reviewed active-branch implementations of `BatchSplitter.kt`, `BatchPolicy.kt`, `MlKitTranslator.kt`, `CachingTranslator.kt`, `ShortDialogueOverrides.kt`, and `ArabicValidator.kt` (not the default-branch search excerpts).
- Confirmed joined mode is opt-in per-device: `BatchPolicy.current()` returns a proven, device-bound strategy only; otherwise ML Kit uses parallel per-block translation. Runtime mapping failures quarantine only the failed separator for seven days and trigger a new self-test. The user's diagnostic showing `Joined | (verdict applied)` is therefore consistent with a device-local persisted PIPE choice, not a global app default.
- Confirmed `MlKitTranslator` validates the full joined response through `BatchSplitter.validate` before emitting any progressive results; failed mapping is redone per-block.
- Audit finding: PIPE input collision is explicitly guarded by `sourcesSafe`; existing tests covered broad strategies but did not directly pin several PIPE-specific adversarial conditions. Mapping validation is structural (count, empties, Arabic presence, length bounds, marker residue); it cannot prove semantic order when a model returns the correct number of plausible but swapped translations. Do not describe structural mapping as semantic correctness.

### Test-only change
- Commit `9a9f213775112703dbca78647103c5c154e5b38c` adds four PIPE-specific JVM regression tests:
  1. reject source text already containing the pipe marker;
  2. reject an extra pipe inside a translated segment;
  3. reject a missing separator;
  4. accept whitespace variations around the separator.
- No production behavior changed in this commit.
- Current branch HEAD observed after this change: `9a9f213775112703dbca78647103c5c154e5b38c`.
- CI for this test commit has not completed at report time: push run #43754406712 pending; dynamic run #43754407036 queued. Prior commit `fad697400545028845e7fc9ad58d39bc7de9e6f1` workflows were still running when last checked; they are superseded for final-head evidence.

### Release status remains
- v0.12.10 is already published and verified as a GitHub pre-release, APK asset present, versionCode 50, package `com.manhwalens.app`, signer fingerprint matching v0.12.9, CI update-compatibility gate passed.
- APK SHA-256: `a3b879e090aee65d7f9b8ce84207ead652826afa677023036782f6677e5877a1`.
- This later test-only commit does not change the published v0.12.10 asset; workflow configuration explicitly skips republishing an existing tag for later branch commits.

### Next actions
1. Verify the four new PIPE tests and full build on final commit `9a9f213...`; inspect and repair any failures.
2. Keep the user's v0.12.10 install/upgrade + first-run ML Kit model-download + real manga scan as the required device validation gate; automated CI cannot replace it.
3. Continue the separate visible-output quality audit and OCR dataset work without conflating raw engine scores, post-processing, OCR errors, and semantic accuracy.


## Engineering continuation — Solo Leveling chapter 3 device diagnostics and fuzzy-cache collision fix (2026-11-15)

### User-provided device evidence
- User supplied a new ML Kit EN→AR batching benchmark from Xiaomi 23106RN0DA / Android 14: Joined `•` median 725 ms / worst 740 ms, map OK, 20/20 mapped; Parallel x2 median 1637 ms / worst 1676 ms. Joined `•` is about 55.7% faster than Parallel x2 in this sample. Joined `<1>` (750 ms), `[1]` (766 ms), and `###` (906 ms) also mapped 20/20; `@@@`, newline, and double newline failed. This is one device benchmark, not a semantic-order proof or cross-device guarantee.
- User supplied a 23-case raw ML Kit quality run: 22 accepted by the Arabic guard, 1 rejected, 2/23 exact normalized matches, mean token F1 49.6%. F1 is lexical overlap only, not semantic accuracy.
- The pair log contains strong OCR noise (`YOL`, `LNINJURED`, `B055 15`, `MINLTES`, fragmented lines) and non-manhwa screen/UI/notification text. Do not attribute all resulting errors to ML Kit translation.
- Clear semantic false-accepts include `No.` → `رقم`, `Run!` → `تشغيل!`, and context-sensitive dialogue rendered literally. Arabic-character presence is not a semantic quality test.
- Critical cache anomaly: distinct short sources such as `AH...` and `YEAH` appeared with an identical unrelated Arabic result in CACHE_HIT entries (also other short replies reused a prior line). This indicates an incorrect memory hit rather than an ordinary engine mistranslation.

### Confirmed root cause and source fix
- Audited `TranslationMemo.kt`: fuzzy keys remove vowels and punctuation and collapse repeated letters. Consequently `AH...` and `YEAH` both reduce to the one-letter shape `h`; very short unrelated dialogue can therefore collide.
- Commit `e7c9ba5b1fe00a12535ad9528eb94d3ddd6297b7` changes fuzzy memo admission and lookup to require a consonant shape of at least 3 characters and at least 2 distinct letters. Unsafe short shapes are neither stored nor read from the fuzzy tier. Exact cache behavior is unchanged.
- Commit `6797b64e3b26203ced3a7484f559cb22d61a6ce3` adds regression tests for `AH...` vs `YEAH`, other ambiguous short dialogue, and an end-to-end CachingTranslator assertion that both distinct strings reach the engine instead of cross-serving.
- This intentionally preserves the useful `KRAK!!` / `Kraak` fuzzy hit (shape `krk`) while rejecting one- and two-letter ambiguous shapes.
- No production cache clear or migration was added. Existing bad fuzzy memo entries are process-memory-only and disappear on process restart; exact memory/disk cache remains separate. If a wrongly served fuzzy result was promoted into exact/disk cache by an earlier runtime, that case needs separate consideration and tests before asserting all stale entries are gone.

### CI / repository state
- Active branch: `feature/v0.12.4-bergamot-experiment`.
- Branch head verified through GitHub API: `6797b64e3b26203ced3a7484f559cb22d61a6ce3`.
- Android build workflow run #43755298440 targets exactly this head and was pending at the last check. Do not claim the new tests or build passed until the run completes.
- PR #4 remains open Draft and targets `feature/v0.12.3-paddle-default-settings`; preserve stacked-PR integration order.
- No new APK or Release was created by these commits. Existing v0.12.10 pre-release remains the last verified APK and is not rebuilt by this source branch commit.

### Next actions
1. Verify CI run #43755298440 and inspect the unit-test/build result. Fix failures before further changes.
2. Add a focused test proving fuzzy memo hits cannot be promoted into an exact cache entry under a different source key; verify whether prior process-local fuzzy collisions can pollute persistent exact storage.
3. Add regression coverage for the observed CACHE_HIT anomalies (`AH...`, `YEAH`, `SORRY.`, `YEAH.`) and ensure trace source/result identity remains correct.
4. Improve semantic guard: treat short ambiguous phrases as context-sensitive, and never equate “contains Arabic” with “meaning correct”. Keep exact source overrides narrow and corpus-tested.
5. Add a post-processed visible-output corpus distinct from the raw ML Kit corpus; include chapter-3 dialogue and SFX, and score OCR source quality separately from translation quality.
6. Keep Joined `•` as the best device-reported separator candidate, but do not change the shipped default solely from this one run. The device-local benchmark verdict is already applied on the user's device.
7. Continue OCR/UI contamination and bubble segmentation investigation; the supplied log includes external screen content and badly fragmented bubbles.


### Follow-up hardening — fuzzy-hit promotion removed (2026-11-15)
- Further audit of `CachingTranslator` found that an accepted fuzzy memo hit was copied into the exact in-memory cache under the *new* OCR source. That could convert one approximate match into repeated exact hits for a different source.
- Commit `2282f55960183d7d90706e251168265117bd829f` removes fuzzy-hit promotion. Source-exact memory/disk hits retain their existing behavior; fuzzy hits remain fuzzy and are re-evaluated against each OCR variant.
- Commit `e82fcf06e1c8f25a1067f84a73a35614548216c2` extends the KRAK/Kraak integration test: a fuzzy hit must not create an exact cache entry for Kraak, and a repeated Kraak request must still be served by the memo without another engine call.
- Latest branch head verified: `e82fcf06e1c8f25a1067f84a73a35614548216c2`. No workflow run for this exact head was visible at the time of the check. The earlier run #43755298440 targets `6797b64e...` only and was still in progress; it is not final-head validation.
- The latest report update itself is on the same branch and must be followed by CI verification for the final source/test head. No APK/release is claimed.


### CI refresh after fuzzy-cache hardening (2026-11-15)
- Final source/test head `e82fcf06e1c8f25a1067f84a73a35614548216c2` now has three GitHub Actions runs:
  - #43755359766 — `in_progress`
  - #43755355690 — `in_progress`
  - #43755355154 — `in_progress`
- Earlier runs on superseded heads `6797b64e...` and `2282f559...` were cancelled or are not final-head evidence. Run #43755297786 on `6797b64e...` completed successfully, but it predates the no-promotion hardening; do not use it to claim the latest source passed.
- Documentation commit at the time of this check: `1deff5155c22b1f664c2e2ad2047f5ea800ff8b9`. The three active runs target the source/test head `e82fcf...`, not this docs-only commit.
- CI has not yet supplied a final success/failure result for the fuzzy-cache fix. No new release APK is built or published.


## Engineering continuation — fuzzy cache canonicalization narrowed after deeper collision audit (2026-11-15)

### CI result for the previous fuzzy-cache fix
- Verified branch head at that stage: `78ade224440db02dff777941fe98658165cd8d4d`.
- Push Android build #43755384744: success; build job, Unit tests, Debug APK build, pinned experimental debug-certificate check and APK artifact upload all succeeded. The release publish step was skipped as expected for this branch.
- PR validation #43755388781: success; Unit tests and Debug APK build succeeded.
- Dynamic dependency validation #43755384401: success.
- This validates the short-shape guard and no-fuzzy-promotion tests present at that HEAD, not the later canonicalization refinements below.

### Deeper collision finding and production changes
- The earlier fix rejected 1- and 2-character consonant skeletons, but the shape function still removed all vowels and word boundaries. This could make unrelated terms such as `first` and `forest` share a consonant skeleton; the restriction on minimum shape length did not prevent this class of collision.
- Commits `841b6450167b56f066644f47a8154f208226a328` and `b1dc72f00b6c4013da61b4c7cf96fed96dc8128d` change fuzzy canonicalization to preserve vowel identity and whitespace boundaries, while still ignoring punctuation/case and collapsing adjacent repeated letters. Expected safe pair: `KRAK!!` / `Kraak` → `krak`. Expected non-collisions: `first` / `forest`, `a man` / `aman`, `AH` / `YEAH`.
- Commit `b31c50791eac4321c3da03723ca402d299b13ca9` fixes memo-hit classification in `CachingTranslator`: it now consumes `TranslationMemo.Hit.exact` instead of treating every memo hit as fuzzy. Exact memo hits can be promoted to the exact in-memory cache; fuzzy near-matches cannot.
- Commits `bafb2a28db089b940909246bac4ef732d1616864`, `ebf95459405218c8ef8e5b895241b4e3bbe0f50a`, and `302c1234b1197a7555b0380d91e0ac0158dba090` add/adjust regressions for vowel and word-boundary preservation, ambiguous short phrases, fuzzy-hit non-promotion, and exact memo-hit promotion.

### Current verification boundary
- The above canonicalization and exact/fuzzy classification commits were made after the successful CI head `78ade224...`; they are NOT covered by that earlier success.
- CI must be checked against the final source/test head after these changes and the subsequent report commit. No APK or release is claimed from these refinements.
- No real-device retest has been performed. The user's 725 ms Joined bullet benchmark remains device-supplied evidence; this source work does not change the batching policy.

### Next engineering actions
1. Verify current-head Android push + PR workflows; fix any unit-test or compilation regressions.
2. Review the memo's fuzzy key ownership/collision behavior and test it with a mixed set of common English dialogue and short SFX.
3. Retain fuzzy matching only when canonical forms are equal under the conservative rules; do not reintroduce vowel deletion or token-boundary deletion for recall.
4. Reassess the short dialogue overrides and Arabic quality guard using the supplied No/Run/OK/AH examples. Rule-based Arabic presence is not semantic validation.
5. Build a post-processed output benchmark from real chapter snippets while keeping OCR recognition errors separately labeled.


### Additional fuzzy-cache hardening and diagnostic visibility (2026-11-15)
- Commit `c6dc625561fe679dab13f4762e775578fcb28721` adds distinct per-pair diagnostic status `FUZZY_HIT` with detail `served from fuzzy memo`; true in-memory/disk/exact-memo results remain `CACHE_HIT`. This makes future user reports distinguish approximate reuse from exact cache retrieval.
- Commit `deb47cfa90607f813e73a78428eb3ca45b288b0d` tests the separate `OK` / `FUZZY_HIT` trace statuses and asserts that each trace retains its actual source text.
- Commit `1374f906e11ab08c789de913570ce20c1271ad5f` measures minimum fuzzy-key length and distinct characters after removing spaces, so whitespace cannot artificially make an ambiguous key eligible.
- Commit `f8edcd8f777a079fcda98e2dea57ae46e02ef259` adds a regression for weak shapes containing whitespace.
- Source/test changes for exact-source chapter-3 repairs:
  - Commit `e4d4ee8f450cf391b79ad1f836274683f4ce2962` adds narrow exact-source repairs for observed malformed/awkward strings: ALL CLEAR / LET'S GO, WHAT'RE YOL THINKING ABOUT, ABOUT 40 MINLTES, B055 15 / BOSS IS, HOW LONG DID WE WALK, ABOUT WHAT, I'M FINE..., TO BE HONEST..., IF YOU'RE THAT SORRY..., and WHAT EVEN MADE YOU SAY YES...
  - Commit `34682fc80b0486e786faa5c0ee456d1c2d79bc6e` adds regression assertions for these exact normalized-source variants. These overrides are source-specific; no global OCR word substitution or general translation claim is made.
- Latest source/test head verified: `34682fc80b0486e786faa5c0ee456d1c2d79bc6e`.
- CI on this exact source/test head was still running at the last check:
  - Push #43756727552 — in progress.
  - PR #43756732195 — in progress.
  - Dynamic #43756727237 — in progress.
- Earlier dynamic run #43756611369 succeeded on head `f8edcd8...`, but Android build workflows on that superseded head were cancelled. No completed Android-build success is claimed for the new trace/override changes yet.
- The report update itself creates a new documentation-only head and new CI; final validation must be checked against that newest head.


## Engineering continuation — chapter 3 cache, dialogue and Android UI OCR fixes validated (2026-11-15)

### Final-head CI verification
- Active branch HEAD checked: `2b47b8cb66364f26f20a3ab45750662abbe532e3`.
- Android push workflow #43756991576 completed `success` on exactly that HEAD.
  - Unit tests: success.
  - Debug APK build and rename: success.
  - Pinned experimental debug signing-certificate check: success; SHA-256 `0e36ccf390d6b2ac1696a8b19d173f448837703250baf42d37dbe25e6d109152`.
  - Update-compatibility validation step: success.
  - APK and test-report artifacts uploaded successfully.
  - The release-publication decision step succeeded and publishing was skipped because the existing v0.12.10 tag already exists. This run did NOT create a new Release or replace the v0.12.10 asset.
- PR workflow #43756999835 completed `success` on the same HEAD; unit tests and debug APK build passed. Update compatibility and release publish are skipped in the PR event by workflow design.
- Dynamic dependency validation #43756991568 completed `success` on the same HEAD.
- Push APK artifact metadata: artifact `ManhwaLens-v0.12.10-debug-apk`, ZIP size 70,060,487 bytes, digest `5a795bcf90d722d0b4b343cc148fa647ba2adcc81db3cf67ffb926dce64ba0b5`. This is a CI artifact, not a published new APK Release.
- PR #4 remains open Draft; base remains `feature/v0.12.3-paddle-default-settings`. Keep stacked-PR sequence intact.

### Changes included on the validated head
- Fuzzy memo canonicalization now preserves vowels and word boundaries, ignores punctuation/case, and collapses adjacent repeated letters. Minimum distinctive shape excludes whitespace. The AH/YEAH weak-key and first/forest / a man/aman collision cases are regression-tested.
- `CachingTranslator` distinguishes exact memo hits from fuzzy hits and does not promote fuzzy hits into exact cache keys. Translation pair diagnostics now emit `FUZZY_HIT` for approximate memo results and preserve the actual source string.
- Exact-source post-processing repairs include the chapter-3 OCR strings listed in the preceding section. Commit `d75c60c6c762a569abeb688a1800ae9d9886edb8` aligns override keys with the actual normalization of apostrophes (e.g. I'M → I M), so the tests address the same canonical key production computes.
- Commit `aad049187e2e18890f0c64cf774d4f9a298b1144` clarifies the progressive callback's exact/fuzzy memo semantics in source documentation.
- Commit `5e0f05b828c2b93d0fadc152f36cb7b7f18fad4a` adds a narrow `SYSTEM_UI` verdict to `OcrGarbageClassifier`: clock + known Algerian carrier, exact mobile-data usage text, and known ManhwaLens notification signatures are dropped before translation.
- Commit `2b47b8cb66364f26f20a3ab45750662abbe532e3` adds positive/negative tests for these patterns. Ordinary dialogue containing words such as "today", "ManhwaLens" or "mobilis" alone remains retained.
- These OCR rules target only the UI signatures visible in the supplied capture. They do not solve general Android notification-shade or third-party app UI detection, and no OCR CER/WER or recall improvement has been measured.

### Release and device-test boundary
- Latest published release remains v0.12.10 prerelease with its existing APK; release API confirms draft=false, prerelease=true, APK asset present. No new release was published by this work.
- CI verification is not a physical-device test. No claim is made that the new fuzzy memo, output repairs or SYSTEM_UI filter have been tested on the user's phone.
- The user-supplied Joined `•` benchmark (725 ms median, 20/20 mapped) remains a device measurement. No batching policy change was made in this interaction.

### Next engineering actions
1. Add a visible-output regression corpus that records raw OCR source, candidate engine text, post-processed text, cache tier, and expected Arabic meaning separately. Include a distinction between OCR-corrupted source and valid source with mistranslation.
2. Further constrain fuzzy memo use only if new collision cases remain; current canonicalization deliberately keeps useful KRAK/Kraak reuse while protecting vowels, spaces, and source identity.
3. Extend UI contamination testing with captured bounding boxes / frame zones before adding broader text rules; do not blacklist generic words or app names without geometry.
4. Keep raw ML Kit lexical benchmark (49.6% F1 on 23 cases) separate from any future visible-output score. No semantic quality gain is claimed from these exact overrides or CI success.


## Engineering continuation — chapter 3 diagnostics, three-stage quality scoring and raw pair-trace correction (2026-11-15)

### User device evidence incorporated
- New device benchmark: Joined `•` 725 ms median / 740 ms worst, mapped 20/20; parallel x2 1637 / 1676 ms. Joined `•` was ~55.7% faster than parallel x2 in this device run. This remains one-device timing evidence and proves structural mapping only, not semantic ordering. No global batching-policy change was made.
- New raw ML Kit diagnostic: 23 reference cases, 22 Arabic-guard accepted, 1 rejected, 2 exact matches, mean token F1 49.6%. F1 is lexical overlap only and is not semantic accuracy.
- The pair capture exposed semantic false-accepts (`No.` → `رقم`, `Run!` → `تشغيل!`), OCR noise (`YOL`, `B055 15`, `MINLTES`, `LNINJURED`), and non-manhwa Android/app UI. These categories must remain distinct in evaluation.

### Fuzzy memo and chapter-3 correction work
- Earlier fixes retained exact cache behavior while restricting fuzzy near-matching: preserve vowels and whitespace boundaries, ignore punctuation/case, collapse adjacent repeated letters, and reject weak canonical shapes. Useful `KRAK!!` / `Kraak` reuse remains; unrelated strings such as `AH...` / `YEAH`, `first` / `forest`, and `a man` / `aman` no longer share an eligible fuzzy key.
- Approximate results are not promoted to exact cache keys. Exact memo hits are handled separately from fuzzy hits, and pair diagnostics emit `FUZZY_HIT` for fuzzy memo reuse.
- Exact-source repairs were added and regression-tested for observed chapter-3 lines, including ALL CLEAR / LET'S GO, WHAT'RE YOL THINKING ABOUT, ABOUT 40 MINLTES, B055 15 / boss, HOW LONG DID WE WALK, ABOUT WHAT, I'M FINE..., TO BE HONEST..., IF YOU'RE THAT SORRY..., and WHAT EVEN MADE YOU SAY YES...
- Narrow `SYSTEM_UI` OCR classification now filters the observed clock+carrier line, explicit mobile-data usage lines, and identified ManhwaLens capture notifications. Tests also protect ordinary dialogue containing generic terms such as "today", "ManhwaLens", or "mobilis". This does not establish general notification-shade detection or measurable OCR recall/precision.

### Three-stage translation quality diagnostic
- Updated `TranslationQualityBenchmark` to evaluate the same single raw model response at three distinct stages:
  1. RAW ENGINE CANDIDATE — original ML Kit output;
  2. CLEANED CANDIDATE — shared cleanup of labels, typography and dialogue noise;
  3. FINAL VISIBLE OUTPUT — cleanup plus exact source dialogue correction and final guard.
- Each stage reports acceptance, rejection, exact matches and lexical token F1. The report lists weakest raw cases separately from weakest visible cases, so source correction cannot disguise a model error.
- Series NameSession overrides are explicitly excluded from this standalone diagnostic; only exact source-specific dialogue overrides are included.
- Tests show `No.` raw `رقم` is accepted by the Arabic-script guard despite the wrong meaning, while the visible stage becomes `لا.`; `Run!` raw `تشغيل!` becomes `اهرب!`; `KRAK` raw `krak` is rejected while its visible output becomes `طَق!`.
- A regression asserts exactly 23 engine calls for 23 cases: cleanup and post-processing do not call ML Kit again.
- Relevant commits: `2b640954c9ffe2ff13b1e3ff68c3ae029671b489`, `0167b624503db43589388350c79649a56072a41f`, `89e9c133b94cff9f3818a53f0158bd86bb80147d`, `383e535b122dce8f41b3db3506f88ea0b3a590ae`, `a265e111c9dcabac999c584ae03082352cbf3552`, `271395f3fd07ee6af98422433594a7162a2609ac`.

### Translation pair log correctness fix
- Found that `CachingTranslator` was passing the already-cleaned/name-repaired/source-corrected text into `candidateArabic`, despite the report header claiming it was the engine candidate. This made the log unable to show precisely what ML Kit returned before app processing.
- Commit `b2894213f05e3f243c18fef0af31639c4c8a3fcc` now preserves the raw delegate output by block ID before post-processing and records that as the fresh candidate; the returned/accepted post-processed text remains `finalArabic`.
- For cache hits, the pre-current-processing value is preserved as candidate and the accepted value after current name/dialogue correction is final. Fuzzy entries remain labeled `FUZZY_HIT`; exact cache hits remain `CACHE_HIT`.
- Commit `b22cfdb471d46608418285acdd908639619bda88` clarifies the trace data-class contract. Commit `c905aa0edb715de010f824dca2e65eedef5dfe55` updates the report header. Commit `b495d9e2026ecaa188ca2764d3f112c6bce5ab10` adds a test asserting raw `رقم` versus final `لا.` on a fresh translation, and the exact cached value on the subsequent cache hit. Commit `ca7eac725207f92a2d11396cde0e4a948f198612` notes that older local pair records may predate raw-candidate tracing and may therefore show post-processed candidate text.
- No log data migration is attempted; old records remain readable and are explicitly annotated as legacy in the generated report.

### CI and release status
- On source/test head `271395f3fd07ee6af98422433594a7162a2609ac`, Push run #43757764999 completed success: unit tests, debug APK build, signer check, update compatibility, APK artifact and test-report upload all succeeded. Dynamic run #43757765124 succeeded. PR run #43757770613 was cancelled by later pushes and is not counted.
- On pair-trace source head `b2894213f05e3f243c18fef0af31639c4c8a3fcc`, Push #43758014004, PR #43758018699 and Dynamic #43758013740 all completed success. This validates the initial raw-candidate-preservation change, prior to follow-up tests/docs.
- The latest branch HEAD at this report update is `ca7eac725207f92a2d11396cde0e4a948f198612`. Workflows targeting this exact final head are still in progress:
  - Push #43758291781
  - PR #43758297362
  - Dynamic #43758291558
  Earlier runs for superseded heads were cancelled and are not treated as final-head evidence.
- PR #4 remains open Draft and stacked on `feature/v0.12.3-paddle-default-settings`.
- Latest published Release remains v0.12.10 prerelease with its existing APK. No new release was published and no new versionCode/APK should be presented as available from these commits.

### Verification boundary and next actions
1. Verify all three workflows on exact head `ca7eac...`; inspect any failed test/build logs and repair before treating the pair-log correction as validated.
2. After final-head CI, add/retain a future device test request only when a new installable Release APK is published; the current work does not create a new public release.
3. On the next device capture, inspect `CANDIDATE AR` versus `FINAL AR` for fresh and cache/fuzzy hits to validate the new trace semantics.
4. Re-run the three-stage quality benchmark on-device and compare raw/cleaned/visible results against the prior 23-case 49.6% raw lexical baseline; do not call any F1 change a semantic accuracy gain without human review.
5. Build an OCR-labeled page corpus with screenshots, box coordinates and ground-truth text before tuning PaddleOCR detection/recognition or BubbleGrouper thresholds.


## Verification refresh — repository state checked after user follow-up (2026-11-15 22:14 +01:00)

- Re-queried the live GitHub branch and confirmed current HEAD at the time of review: `00742cfade73d33f888be3686342fe2a22ef83ed` (documentation-only commit recording the chapter-3 quality/trace work).
- All three workflows on this exact HEAD completed successfully:
  - Android build / push #43758351474 — success.
  - Android build / pull_request #43758356361 — success.
  - Automatic Dependency Submission / dynamic #43758351325 — success.
- This validates CI for the current branch snapshot, including unit tests/build/signing/update checks in the Android build workflow. It does not constitute physical-device testing or semantic translation validation.
- PR #4 remains open Draft, mergeable, and targets `feature/v0.12.3-paddle-default-settings`; preserve the stacked-branch integration order.
- Release state unchanged: v0.12.10 remains the latest published prerelease APK. No new APK or release is claimed from this work.
- Next engineering priority: validate cache provenance and post-processing against a fresh device pair log using the corrected raw-candidate/final-output fields; then collect OCR ground truth with bubble bounding boxes before changing PaddleOCR or grouping thresholds.

## Engineering continuation — exact-source dialogue correction (2026-11-15)

### Implemented
- Source/test commit: 31d467b5ad0f382f190d43517854211e19aa9c90 — fix: correct observed behind-me dialogue wording.
- Added one narrow exact-source repair for the device-observed line "Stay behind me and don't say a word.", replacing the awkward Arabic candidate seen in the trace ("البقاء ورائي ولا تقل كلمة.") with the natural reference wording "ابقَ خلفي ولا تنطق بكلمة.".
- The correction is applied only when the normalized English source matches the complete phrase. No global vocabulary substitution or fuzzy source matching was added.
- Added a direct regression test and extended the three-stage quality benchmark test to prove the raw model candidate remains visible as the raw score while the final visible output receives the exact-source repair.
- Files changed:
  - app/src/main/java/com/manhwalens/app/translate/ShortDialogueOverrides.kt
  - app/src/test/java/com/manhwalens/app/translate/ShortDialogueOverridesTest.kt
  - app/src/test/java/com/manhwalens/app/translate/TranslationQualityBenchmarkTest.kt

### Verification on source commit
- Android push run #43762854838 and PR run #43762861270 both reached and passed the Unit tests step on source commit 31d467b5.
- Android push run #43762854838 completed **success** on this exact source commit: Unit tests, Debug APK build, pinned experimental debug-certificate verification, and update-compatibility check against the latest published release all passed; APK and test-report artifacts uploaded.
- PR validation run #43762861270 completed **success** on this exact source commit: Unit tests, Debug APK build, certificate verification and artifacts passed; the update-compatibility step was skipped for the pull_request event by workflow design.
- Automatic Dependency Submission #43762855422 completed **success**. The Release publication step on the push run was skipped because v0.12.10 already exists; no new GitHub Release was created.
- Automatic Dependency Submission run #43762855422 completed successfully on the same source commit.
- No Release was created and no APK was promoted for installation.
- Latest published prerelease remains v0.12.10 with its existing APK. This dialogue-only fix does not justify a new public release by itself.

### OCR decision
- Reviewed the live PaddleOCR adapter, detection resize path, capture pipeline, BubbleGrouper, BlockFilter, OcrGarbageClassifier, and CaptureDiagnostics.
- Capture diagnostics already retain each recognized line's text, confidence and bounding box, plus grouped/kept block bounds. This is the evidence needed to distinguish recognition errors from grouping/filtering errors.
- Did not change detector/recognizer confidence thresholds, image resize limits, or bubble grouping geometry without a screenshot-linked ground-truth set. Such changes could trade missed text for false detections and cannot be safely selected from translation-pair text alone.

### Release / device boundary
- PR #4 remains open Draft and stacked on feature/v0.12.3-paddle-default-settings; preserve the stack.
- No physical-device validation is claimed. No installation request is made for this source-only correction.
- Next action: verify the Android push and PR workflows on the latest branch head, then use a new capture diagnostic containing OCR confidence/coordinates and translation candidate/final pairs before tuning PaddleOCR or grouping behavior.

## Engineering continuation — PaddleOCR capture telemetry (2026-11-15)

### Implemented
- Main telemetry commit: 8f5b1e2c4847e5dafb1dbdacb5f8daddd66cbd38 — adds PaddleOCR per-run telemetry to the opt-in capture diagnostic report.
- Follow-up test import commit: 5384e34475af7fb57d79406da955d3e6c412177e.
- Final effective-config commit: 9fc562b7a0cd48e704298761f01472011cdf6ec8 — pins the existing detector defaults explicitly and records them.
- Capture report now includes:
  - detector, recognizer and end-to-end engine timings, plus wrapper elapsed time and cold model-load time;
  - preprocess, inference and postprocess timings for detection and recognition;
  - detector and recognizer input tensor shapes;
  - recognized candidate count and candidates rejected by the recognition-confidence threshold;
  - actual recognition/detection thresholds, unclip ratio, detector resize type/limits, and inference thread count.
- Current values were made explicit to match the existing library defaults/configuration: detection threshold 0.30, box threshold 0.60, unclip ratio 1.50, recognition threshold 0.50, resize policy min/1280 capped at 1920, four threads. No confidence threshold, OCR acceptance rule, grouping geometry or model was changed by this telemetry work.
- The OCR stats are observational and are only added to the existing opt-in local capture report. The report remains bounded; raw OCR line text, per-line confidence, bounding boxes, grouped/kept blocks, and translation candidate/final diagnostics remain available.
- Files changed for telemetry:
  - app/src/main/java/com/manhwalens/app/ocr/OcrRunStats.kt (new)
  - app/src/main/java/com/manhwalens/app/ocr/TextRecognizerClient.kt
  - app/src/main/java/com/manhwalens/app/ocr/CaptureOcrProcessor.kt
  - app/src/main/java/com/manhwalens/app/util/CaptureDiagnostics.kt
  - app/src/test/java/com/manhwalens/app/util/CaptureDiagnosticsTest.kt

### Verification on final telemetry commit 9fc562b7
- Push workflow #43763634465 completed **success**:
  - Unit tests passed.
  - Debug APK build passed.
  - Experimental signing-certificate verification passed.
  - Update compatibility with the latest published release passed.
  - APK and test-report artifacts uploaded.
  - Release publish step was skipped by workflow policy; no public release was created.
- PR workflow #43763638905 completed **success**:
  - Unit tests, Debug APK build, signing-certificate verification and artifact uploads passed.
  - Update-compatibility check was skipped on the PR event by workflow design.
- Automatic Dependency Submission #43763634405 completed **success**.
- CI APK artifact: ManhwaLens-v0.12.10-debug-apk, 70,070,074 bytes, SHA-256 8d879c3198c41ae56e3cfcfcb9a87257ffe0f8c1e96e3f43a358beb4f516b108. This is an expiring Actions artifact, **not** a GitHub Release and not a recommended user installation.
- The preceding exact-source dialogue fix commit 31d467b5 also passed Push and PR CI, including update compatibility on Push.

### Release and device status
- Latest public release remains prerelease v0.12.10 with its existing APK; no newer release was created.
- PR #4 remains open Draft, targeting feature/v0.12.3-paddle-default-settings; preserve this stacked PR structure.
- No physical-device test is claimed. Current changes are verified by CI only.
- Next evidence needed for OCR tuning is one opt-in device capture report from a future test build: OCR lines with confidence/coordinates, grouped and kept blocks, PaddleOCR stage stats/config, and translation raw/final pairs. This allows the failure stage to be identified before changing OCR thresholds or grouping geometry.


## Engineering continuation — protocol reaffirmed and repository revalidation (2026-11-16 05:51 UTC)

### User directive
- User restated the autonomous engineering protocol: treat each ManhwaLens message as continuation, verify live GitHub state, and update this handoff report after every project interaction. This section records the protocol reaffirmation; no user approval is needed for ordinary technical decisions.

### Live repository state checked in this interaction
- Development branch: `feature/v0.12.4-bergamot-experiment`.
- Verified branch HEAD: `200c06d0b30a73c706dd04aa2df7ae43ddcf8cc2` (commit message: docs: record PaddleOCR telemetry and verified CI results).
- PR #4 remains open Draft, head `200c06d0b30a73c706dd04aa2df7ae43ddcf8cc2`, base `feature/v0.12.3-paddle-default-settings`; preserve the stacked PR relationship.
- GitHub Actions on this exact HEAD: push run #43763927080 SUCCESS; PR run #43763932247 SUCCESS; automatic dependency submission #43763926671 SUCCESS. The PR run confirms validation on pull_request; these CI results do not constitute physical-device validation.
- Latest published Release verified through Releases API: v0.12.10 prerelease, APK asset `ManhwaLens-v0.12.10-debug.apk` (71,369,014 bytes), URL https://github.com/mouadknight0-cell/ManhwaLens/releases/download/v0.12.10/ManhwaLens-v0.12.10-debug.apk. This is a published Release, not merely an Actions artifact.

### Source review in this interaction
- Re-read `TranslationMemo.kt`, the cache lookup path in `CachingTranslator.kt`, and `TranslationMemoTest.kt` at the verified HEAD.
- Confirmed the fuzzy memo keeps vowels and word boundaries, requires at least three alphanumeric characters and two distinct letters for fuzzy reuse, and retains exact lookup separately.
- Confirmed the cache-hit path distinguishes fuzzy from source-exact hits; fuzzy results are not promoted into the exact cache, while source-exact memo/disk hits may be promoted after validation. Fresh engine output records the raw candidate separately from the corrected final Arabic value.
- No deterministic new defect was established by this read-only audit, so no speculative production-code edit was made in this interaction. No local test suite was run in this interaction; CI outcomes above belong to the exact existing HEAD.

### Status and next engineering action
- Implemented/CI-verified: current branch state and prior cache-provenance protections, as recorded in earlier entries; latest HEAD's push and PR workflows both pass.
- Not device-verified: actual OCR accuracy, bubble grouping, semantic Arabic translation, Bergamot offline inference, latency/RAM and real-device update installation.
- Next: inspect the cache persistence and diagnostic trace implementations together with the fresh-vs-cached post-processing tests; add a regression test only for a demonstrated stale-persistent-cache or provenance defect. Then continue with OCR screenshot ground-truth evaluation; do not alter PaddleOCR thresholds or grouping without labeled screenshot evidence.
- No phone installation requested in this interaction.


## Engineering continuation — persistent-cache hardening and v0.12.11 release (2026-11-16)

### Confirmed defects and implemented fixes
- **Persistent cache write-back:** when a source-exact cached entry is repaired by current source-aware dialogue/name handling, the corrected value is now written to both the in-memory exact cache and the persistent disk cache. This prevents a stale pre-fix value from being reloaded on every process restart. Commit: `0ab7569f9e75a456d45f302dbbe7adfb2184d9c6`.
- **Cached-output sanitation parity:** cache hits now pass through `TranslationOutputSanitizer.clean` before name repair, exact source corrections and the Arabic quality guard, matching fresh engine results. The original cached candidate is retained separately for diagnostics. Commit: `68e44b75f22f64794b90d4f2e70fed0b319ffbc1`.
- **Blank legacy cache safety:** if sanitation turns an old entry into blank output, the pinned name override is not called. The invalid disk entry is removed and the fresh engine result is used instead; the accepted fresh result replaces the old blank entry. Commit: `cc21b4058d89d12edbd3f24b5b84b9d21a6d1852`.
- **Cache provenance:** translation-pair trace detail now distinguishes in-memory exact, persistent exact, exact memo and fuzzy memo hits while preserving the existing status codes. Commits: `95cea1d245542cf46c35dd22188162a75569a601`, tests `09496a819529b6c57a725985bf559f362c377b79` and `5ba3142d0173734b3eb4aeb082e3ecaca6343f7e`.
- Regression tests added in `CachingTranslatorPersistentCacheTest.kt` cover source-exact correction surviving a new cache instance/restart, sanitation of old persistent values, and blank-entry replacement. The cache provenance tests also cover exact-memo and disk hits.

### Test-failure investigation and final verification
- An intermediate run on `f02d6d2a40fe00602828ec361267148a0602c6d1` reported 809 tests with one failure. The downloaded Gradle HTML report showed the failure was a **test expectation defect**, not a production failure: the test expected the persistent cache to remain null after the app correctly deleted an invalid blank entry, called the engine, and persisted the fresh Arabic translation `إيرين`.
- Corrected the test to expect the fresh translation to replace the invalid legacy value: commit `3e61faf858ad17b9e1f969b65ad09cee9803eb37`.
- Final tested source head before release preparation: `3e61faf858ad17b9e1f969b65ad09cee9803eb37`.
- Push workflow [#43813900351](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43813900351): **success**. PR workflow [#43813907113](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43813907113): **success**.
- Downloaded and inspected the Gradle test report artifact: **809 tests, 0 failures, 0 ignored** (reported test duration 1.869 s). All three new persistent-cache regression tests passed.

### v0.12.11 release candidate and published release
- Updated `app/build.gradle.kts` to `versionName = "0.12.11"`, `versionCode = 51`; kept `applicationId = "com.manhwalens.app"`.
- Updated `CHANGELOG.md` for cache-integrity fixes and the PaddleOCR capture telemetry included in this source line.
- Updated `MANUAL_TESTS.md`: P4-A now requires `[OCR ENGINE STATS] present=true`, PaddleOCR stage/cold-load timings, tensor/config/low-confidence counts, and raw OCR line `confidence=` plus bounding-box `box=` fields. Added v0.12.11 update/cache verification steps.
- Release-preparation commit: `5118b0aba8fba777de8db3c75fb698c40feb2d32` (`release: prepare v0.12.11 cache and OCR telemetry build`).
- Push workflow [#43814268875](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43814268875): **success**. PR workflow [#43814273798](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43814273798): **success**. Dependency submission #43814268570: success.
- Verified build metadata and update continuity from the workflow log: package `com.manhwalens.app`; `versionCode 50 → 51`; APK signer SHA-256 `0e36ccf390d6b2ac1696a8b19d173f448837703250baf42d37dbe25e6d109152`, matching the fixed future signing baseline.
- GitHub Release [v0.12.11](https://github.com/mouadknight0-cell/ManhwaLens/releases/tag/v0.12.11) is **published** as a prerelease, not a draft. APK asset: `ManhwaLens-v0.12.11-debug.apk`, 71,382,318 bytes, SHA-256 `1ff45531f4c537d816801cb57b1ee4c75981cf7851f241b14fed29adfd540637`.
- Direct APK: https://github.com/mouadknight0-cell/ManhwaLens/releases/download/v0.12.11/ManhwaLens-v0.12.11-debug.apk
- The CI Actions APK artifact (70,070,781 bytes; SHA-256 `2697513357fbb6d1823df3eee11b5ead19a6c2916d6f429df4009d543c4361d4`) is not the Release asset; do not confuse the two.

### OCR/translation verification boundaries
- The v0.12.11 Release includes the PaddleOCR capture telemetry previously added to the development branch. The older v0.12.10 tag did not contain `OcrRunStats.kt`; use v0.12.11 for the updated evidence report.
- No OCR threshold, recognizer model, grouping geometry, translation engine default or batching policy was changed in this session. ML Kit remains default; Bergamot remains optional/experimental.
- CI and JVM tests do not prove screenshot-level OCR quality, semantic Arabic correctness, bubble-grouping precision/recall, overlay behavior, native offline inference, RAM/latency or actual phone update installation. No physical-device test is claimed.

### Device handoff and next step
- **Real-device check is now required:** install v0.12.11 directly over v0.12.10 without uninstalling. CI verified package/version/signer continuity; the phone must confirm Android performs an in-place update and preserves app data.
- Then follow the updated v0.12.11 / P4-A checklist: enable Diagnostics Debug mode, pause Live, do tap captures on 3–5 representative pages, and copy the report containing OCR engine stats, raw line confidence/bounds, grouped and kept blocks, ML Kit raw candidates and overlay text. Keep original screenshots for bounding-box/grouping failures; turn Debug mode OFF afterwards and share only reports/page text the user is comfortable sharing.
- Next engineering task after receiving the report: compare raw OCR against screenshots and stage telemetry, then separate recognition errors, grouping/filter false positives, and translation/post-processing errors before changing any threshold or model.
- Current branch release head at publication: `5118b0aba8fba777de8db3c75fb698c40feb2d32`; PR #4 remains open Draft and stacked on `feature/v0.12.3-paddle-default-settings`. Main was not modified.


## Engineering status check — 2026-11-16 (user asked current progress)

### Live state revalidated
- Development branch: `feature/v0.12.4-bergamot-experiment`.
- Branch HEAD at start of this check: `1c345cd59341c95e1d9c2ea67f98dcc6eea39804`. This is newer than the v0.12.11 release-preparation commit `5118b0aba8fba777de8db3c75fb698c40feb2d32`; no source changes or release changes are inferred from the newer HEAD without reviewing its diff.
- PR #4 remains open Draft, mergeable against its current stacked base `feature/v0.12.3-paddle-default-settings`. Main was not changed.
- Push run #43814268875 and PR run #43814273798 both completed with conclusion **success** on the v0.12.11 release-preparation SHA. The final test report recorded **809 tests, 0 failures, 0 ignored**.
- GitHub Release `v0.12.11` was re-queried and confirmed published as a prerelease with uploaded APK asset `ManhwaLens-v0.12.11-debug.apk` (71,382,318 bytes; SHA-256 `1ff45531f4c537d816801cb57b1ee4c75981cf7851f241b14fed29adfd540637`). Direct URL: https://github.com/mouadknight0-cell/ManhwaLens/releases/download/v0.12.11/ManhwaLens-v0.12.11-debug.apk.
- Release build metadata and update compatibility were CI-verified: applicationId `com.manhwalens.app`, versionCode 50 → 51, signer SHA-256 `0e36ccf390d6b2ac1696a8b19d173f448837703250baf42d37dbe25e6d109152`.

### Current project stage
- **Implemented + unit-tested + CI-verified:** persistent-cache sanitation and correction write-back, blank legacy cache invalidation/replacement, and accurate cache-tier provenance. The regression suite includes restart/persistent-cache cases.
- **Release-published:** v0.12.11 is available; it includes PaddleOCR capture telemetry for raw line confidence/bounds, detector/recognizer timing and effective configuration, in addition to the cache fixes.
- **Not device-verified:** no claim is made that the APK has been installed on the user's phone or that OCR/Arabic semantic quality has been validated on real pages.
- No OCR threshold, bubble grouping geometry, ML Kit default, Bergamot status or batching policy was changed based solely on CI.

### Required next action / user handoff
1. Install v0.12.11 directly over v0.12.10 (do not uninstall) and confirm Android accepts it as an update and retains app data.
2. Follow the v0.12.11 P4-A steps in `MANUAL_TESTS.md`: enable Diagnostics Debug mode, pause Live mode, capture 3–5 representative pages, and share the copied report with OCR engine stats, raw OCR confidence/bounding boxes, grouped/kept blocks, ML Kit raw candidates and final overlay. Preserve screenshots for geometry/grouping errors; disable Debug mode afterwards.
3. After the report arrives, classify failures by stage (recognition, grouping/filtering, translation/post-processing) and implement the next evidence-backed correction. Do not tune OCR thresholds without labeled screenshot evidence.
- **Intervention required now:** real-device update and capture test only. No further technical approval is needed.

## Engineering continuation — new Xiaomi ML Kit batching and quality evidence (2026-11-16)

### User-provided real-device diagnostics
- Device: Xiaomi 23106RN0DA, Android 14; ML Kit EN→AR only. This is device-provided evidence, not an independent device session by the assistant.
- Latest 20-bubble / 5-pass batching benchmark: Joined HASH (###) median 706 ms, worst 793 ms, 20/20 mapped on all measured passes; Parallel x6 median 1117 ms, worst 1328 ms. HASH is 36.8% faster by median in this sample. Joined PIPE median 809 ms; BRACKET_NUM 813 ms; ANGLE_NUM 875 ms; BULLET 1153 ms. AT/newline/double-newline failed mapping (1 != 20).
- Device reports policy now applied as Joined HASH. Keep this choice device-local and subject to existing re-test/quarantine behavior; do not promote a global default from one device/sample.
- The prior 2026-11-15 benchmark on the same device had Joined BULLET 725 ms vs Parallel x2 1637 ms. The changed winner demonstrates why the strategy must be benchmarked/re-proven per device/model state rather than hard-coded globally.
- Latest quality check: raw and cleaned candidates accepted 22/23, exact 2/23, mean token F1 49.6%; final visible output accepted 23/23, exact 9/23, mean token F1 74.8%, with seven source-specific corrections. F1 remains lexical overlap, not semantic accuracy. The 25.2-point rise is attributable to local correction/guard stages, not an improvement in ML Kit itself.
- Visible-output weaknesses remain: It hurts… but I can still stand (F1 0.14), I've been waiting… (0.40), and Where are you going? Come back here. (0.50). Name-heavy case #10 remains weak (0.55). Do not count acceptance or Arabic-script presence as semantic correctness.
- Translation-pair samples show OCR contamination from Android status/telecom UI, ManhwaLens overlay, ChatGPT/Brave UI and other app chrome; recognition errors (YOL, LNINJURED, MINLTES, B055 15) and sentence fragmentation. The supplied excerpt also contains stale-looking AH/YEAH cache results identical to a previous unrelated response; this is a serious provenance regression candidate despite prior fuzzy-cache hardening. It requires checking whether the values are exact persistent cache hits, exact memo hits, or a different source-ID/cache-key collision using the new trace provenance before changing cache logic.
- The linked chapter page was supplied as context only; no independent page inspection is claimed.

### Source review and action in this interaction
- Re-read BatchSplitter.kt, BatchPolicy.kt, MlKitTranslator.kt and BatchSplitterTest.kt on the development branch.
- Confirmed HASH is an existing supported strategy (hash3), joins using newline + ### + newline, and MlKitTranslator only attempts the selected joined strategy when BatchSplitter.sourcesSafe(...) permits it; unsafe source text falls back to per-block translation. The source guard treats # as a marker character, conservatively preventing HASH batching when a source contains any #.
- Added two JVM regression tests in commit a84fa560909bfd18589be6c4a4eac093b690e0d8: HASH strategy refuses source strings containing ### or #, while accepting ordinary sources; and an extra ### inside a translated segment is rejected as a mapping-count mismatch.
- These are test additions only. They do not alter production strategy selection or global defaults.

### Verification boundary and next actions
- New test commit is pushed to feature/v0.12.4-bergamot-experiment; CI result for this commit is not yet verified in this entry.
- Device policy Joined ### is observed applied on the user's Xiaomi; not generalized to other devices.
- ML Kit remains the default translator; Bergamot remains experimental. No OCR thresholds/grouping geometry were changed because the report lacks paired screenshots and ground-truth box labels.
- Next: inspect CI for commit a84fa560909bfd18589be6c4a4eac093b690e0d8; then trace the AH/YEAH wrong results against the diagnostic provenance fields and audit cache key construction, persistent cache, memo and stable block identity. Separately, build a labeled screenshot OCR set to classify OCR recognition vs filtering/grouping vs translation errors. Do not tune OCR thresholds from text-only logs.

### CI rerun update — 2026-11-16
- Push workflow #43818215304 initially had its build job cancelled during Android SDK setup before unit tests began; no test result was inferred from that attempt.
- Successfully requested a rerun of the cancelled build job (job 133135019763). At the time of this update, the workflow is pending; unit tests and APK build remain unverified until the rerun completes.
- PR #4 remains open Draft, base feature/v0.12.3-paddle-default-settings, and its current head is the documentation commit 2e2f4b43617db07aa374cd5181fc7fed127141c8. No merge or release action was taken.

## Session continuation — 2026-11-16 (independent engineering protocol and CI repair)

### Verified repository state
- Repository: `mouadknight0-cell/ManhwaLens`; default branch `main`; development branch `feature/v0.12.4-bergamot-experiment`.
- PR #3 remains open and targets `main`; PR #4 remains open Draft and targets PR #3. Preserve this stacked relationship; do not merge PR #4 directly into `main`.
- Before this repair, PR #4 head was `934573156239a010e3e3f99d073a38b1433424cd`. The commit only changed `PROJECT_STATE.md`; app metadata on the branch is `0.12.11` / versionCode `51`, applicationId `com.manhwalens.app`, minSdk 26, ARM64 + ARMv7.
- `PROJECT_STATE.md` is intentionally maintained on the development branch; a 404 on `main` is not evidence of a missing development state file.

### CI failure diagnosed from workflow artifact
- Android build run #787 / run ID `43818326700` failed in `:app:testDebugUnitTest`: 810 tests completed, 1 failed; APK build and signing/update checks were skipped as downstream steps.
- Downloaded and inspected the `test-reports` artifact (ID `14202939122`). The sole failure is JUnit `initializationError` for `BatchSplitterTestKt`: two `@Test` functions (`hashStrategy_refusesSourcesContainingHashCharacters` and `hashStrategy_rejectsAnExtraHashSeparatorInsideTranslatedText`) were placed after the `BatchSplitterTest` class closing brace, causing static top-level test methods to be rejected by JUnit 4.
- This is a test-file structural defect, not a production `BatchSplitter` failure. The existing `BatchSplitterTest` class itself had 25 passing tests; the new top-level test holder failed discovery.

### Fix implemented
- Moved both HASH-strategy regression tests inside `BatchSplitterTest` by correcting the class closing-brace placement in `app/src/test/java/com/manhwalens/app/translate/BatchSplitterTest.kt` (actual repository path: `app/src/test/java/com/manhwalens/app/translate/BatchSplitterTest.kt`).
- Commit: `b751a78e70ba4fcdb47543066d8968fde7cc1335` — `test: keep hash batching cases inside JUnit test class`.
- This fix is pushed to the development branch through GitHub's file update API. It is Implemented; the new commit's CI has not yet been verified.

### Verification boundary
- CI failure is confirmed and its single cause is identified from the uploaded Gradle HTML report.
- Previous run #787 did not produce an APK; no Release was published by that run.
- No physical-device test was run. OCR accuracy, grouping, Arabic semantic quality, Bergamot JNI/offline behavior, memory/latency and update installation remain Not device-verified.
- ML Kit remains the default offline translator; Bergamot remains experimental. Do not infer engine quality from this unit-test repair.

### Next actions
1. Inspect push and PR workflow runs for commit `b751a78e70ba4fcdb47543066d8968fde7cc1335`; require all unit tests and APK packaging to pass.
2. If another failure occurs, inspect the exact test report artifact before changing production code.
3. Reconcile the latest actual GitHub Release and APK asset, package/version metadata and signing certificate continuity before preparing any phone test.
4. Continue with source-aware translation quality and labeled OCR evaluation; do not change OCR thresholds without paired screenshot/ground-truth evidence.

## Verification continuation — 2026-11-16 (CI green and v0.12.11 release integrity)

### Final-head CI results
- Development branch / PR #4 head: `1b3b08a49bccb4c2e3f996a61eb1405e021f9c37`.
- Push workflow #794 (`43822475270`) and PR workflow #795 (`43822478624`) both completed successfully on the same final head.
- Inspected the uploaded Gradle test report from #795: **811 tests completed, 0 failed, 0 skipped**.
- Both workflows completed the pinned PaddleOCR model checksum checks, Bergamot ARM64 runtime validation, `:app:assembleDebug`, and pinned experimental debug signing-certificate check.
- The prior JUnit class-boundary defect is therefore repaired and CI-verified; no production `BatchSplitter` logic was changed for that repair.

### APK / Release / update compatibility evidence
- Build #794 successfully ran the non-PR update-compatibility gate and verified against v0.12.10:
  - package ID: `com.manhwalens.app`
  - versionCode: `50 -> 51`
  - signing certificate SHA-256: `0e36ccf390d6b2ac1696a8b19d173f448837703250baf42d37dbe25e6d109152` (same signer on both releases per workflow comparison).
- GitHub Release `v0.12.11` is published as a prerelease and contains `ManhwaLens-v0.12.11-debug.apk` (71,382,318 bytes; SHA-256 `1ff45531f4c537d816801cb57b1ee4c75981cf7851f241b14fed29adfd540637`).
- Downloaded the #795 Actions APK artifact and hashed the contained APK; it exactly matches the published Release asset SHA-256 and file size. The binary built and checked in #795 is byte-identical to the published v0.12.11 APK.
- PR #4 workflow #795 intentionally skips the Release compatibility/publication steps because it is a pull_request event; push workflow #794 executed the update gate and published the Release.
- The `PROJECT_STATE.md` / `REPOSITORY_REPORT.md` append from the prior interaction stating that this head's CI was pending is superseded by this verified result.

### Repository status and validation boundary
- PR #3 remains open targeting `main`; PR #4 remains open Draft targeting PR #3. Keep the stack; do not merge PR #4 directly into `main`.
- Release-published: v0.12.11. CI-verified: 811 JVM unit tests, model/runtime fetch verification, debug APK build, pinned signer check and update compatibility gate on the branch push.
- Device-verified: **Not verified**. CI cannot prove PaddleOCR page recognition, bubble geometry, Arabic semantic quality, Bergamot on-device JNI/model inference, latency/RAM or actual install-over-existing-app behavior.
- ML Kit remains the default offline translator; Bergamot remains optional/experimental.

### Next engineering action
1. Resume the unresolved diagnostic provenance investigation from the earlier device logs, especially `AH` / `YEAH` output mismatches. Trace stable bubble IDs and source text through in-memory/persistent cache, memo/fuzzy matching, and final overlay paths before altering cache keys or translation rules.
2. Continue OCR evaluation separately using paired screenshots and labeled text/box ground truth; do not infer OCR accuracy from translation-pair text alone.
3. Request user phone verification only after the source changes are CI-green and a specific behavior requires device evidence.


## Engineering continuation — translation memo provenance instrumentation (2026-11-16)

### Analysis
- Re-reviewed the cache-aside path, `TranslationMemo`, `CachingTranslator`, `PagePlan`, `BubbleGrouper`, `CaptureDiagnostics`, and the tap/Live call sites.
- The earlier device note's repeated `AH` / `YEAH` block numbers alone do not establish a cache collision: `BubbleGrouper` generates 1-based IDs independently per capture, while `TranslationPairLog` appends records with timestamps and does not key/overwrite by block ID. Live region-reuse boxes receive negative IDs; new OCR blocks keep positive IDs.
- Existing memo tests already assert that `AH...` does not fuzzy-hit `YEAH`, and that `NO.` does not fuzzy-hit `RUN`. Those tests do not reveal which original source populated an arbitrary fuzzy shape slot, because `TranslationMemo.Hit` exposed only Arabic and an exact/fuzzy flag.

### Implemented (pending CI)
- `TranslationMemo` entries now retain their source string and `Hit` exposes `matchedSource` alongside Arabic and exact/fuzzy status.
- `CachingTranslator` includes the matched source in cache-pair `detail` for exact/fuzzy memo hits. Existing status codes and translation selection are unchanged. The source text is compacted and limited to 52 characters so it fits the bounded diagnostic field.
- Added assertions for exact and fuzzy matched-source provenance in `TranslationMemoTest`.
- Commits:
  - `cf8f320546cb6e7ed6cdfda89af0cbc66c4caa21` — expose memo hit source.
  - `057a4f334c041e81d0cb69a84e99390e56b28e7a` — log memo matched source.
  - `4b2c0e6d4a4e5035c14d88532f45eb70bd4e7da6` — test memo source provenance.
- Current PR #4 head at documentation time: `4b2c0e6d4a4e5035c14d88532f45eb70bd4e7da6`; push workflow #803 and PR workflow #804 were queued/running. CI is not yet verified for these changes.

### Engineering interpretation
- This is instrumentation, not evidence that an `AH` / `YEAH` collision has been reproduced or fixed. No fuzzy matching threshold, cache key, persistent-cache behavior, OCR ID scheme, batching policy, or visible translation output was changed.
- If a future capture shows an incorrect `FUZZY_HIT`, compare `EN` against `detail=...matched=...`: a different matched source is direct evidence of which memo entry supplied the candidate. Exact disk/memory cache hits remain distinguishable by their existing detail strings.
- Keep ML Kit as the default offline translator and Bergamot experimental. Keep Joined HASH device-local; 20/20 mapping validates segment count/guards, not semantic alignment if a model reorders joined responses.

### Verification boundary and next actions
1. Require CI green for the matched-source instrumentation and new assertions.
2. Inspect the next real-device pair report only if an actual mismatch remains; use the new matched-source field to isolate fuzzy memo reuse from exact-cache hits and bubble-ID reuse.
3. Continue OCR accuracy work only with paired screenshots and labeled ground-truth text/boxes; translation-pair text alone cannot justify changing OCR thresholds.
4. No phone intervention requested yet. Real-device validation remains necessary later for OCR, semantic translation, native Bergamot behavior, latency/RAM, and update installation.


## Diagnostic grouping continuation — 2026-11-16

### Implemented (CI pending)
- Added `requestId` to `TranslationPairTrace`, generated once per `CachingTranslator.translatePage` call. IDs include a process-random prefix plus an atomic sequence, so request IDs do not repeat after an app-process restart within the same retained diagnostic log.
- `TranslationPairLog` now appends requestId as the 10th encoded field and prints `request=...` in the report header. Legacy 9-field entries remain readable and are labeled `request=legacy`.
- Added a JVM test that all pair events from one page share one request ID, and separate page calls receive different IDs.
- This resolves a diagnostic ambiguity: bubble IDs are 1-based per OCR capture and may legitimately repeat across pages; a request ID groups the records belonging to one `translatePage` call. It does not alter bubble identity, caches, matching, or translation output.
- Commits: `926aa5b77209597b0a7e74a2e66133e3362d92d1`, `a4de950205178fc8461a91269488d885b6c83a71`, `ff80b94bc69db43a98ce2468939b598fdba1d4f9`, `bd6945cd18b324c10959f9eee663d9d5847d7a81`, `ccb6181b82d8e470c8ba8fac11a6c033eb97be49`.
- Translation memo source provenance remains included in `detail=...matched=...` for exact/fuzzy memo hits.

### Verification boundary
- Push workflow #808 was already in progress on an earlier intermediate head; final source+documentation commits now require a fresh CI run. No CI success is claimed for these latest additions yet.
- Existing direct tests still rule out `AH...` being fuzzy-served for `YEAH`; the new diagnostics will identify a matched source if any other fuzzy hit is wrong, while request IDs distinguish pages with repeated numeric bubble IDs.
- No physical-device test was run. No OCR thresholds, bubble grouping, cache keys, fuzzy match rules, translation corrections, batching policy, or runtime engine default were changed.

### Next actions
1. Verify CI on the latest PR #4 head, including the new request grouping and memo-source tests.
2. Once CI is green, the next device pair report should be interpreted by `request=...`, `block #...`, `EN`, and `detail=...matched=...`; distinguish wrong OCR source, fuzzy source reuse, exact cache reuse, and model mistranslation before changing behavior.
3. Keep ML Kit default and Bergamot experimental. Use screenshots/ground truth for OCR evaluation; text-only translation logs cannot justify OCR threshold changes.

### CI correction — cache-hit request ID propagation (2026-11-16)
- PR workflow #823 / run `43824001315` completed with 812 tests and one failure: `CachingTranslatorTest.observerRecordsFreshFinalPairAndSubsequentCacheHit`, assertion at line 72. The failing assertion showed the cache-hit trace was missing `requestId`.
- Root cause: the fresh engine, failure and missing-result trace constructors received the page request ID, but the cache-hit `TranslationPairTrace` constructor had not received it.
- Fixed the cache-hit trace constructor to pass the same `traceRequestId` used by the rest of the page's records.
- Fix commit: `55b50914170326b63271be835e8a607d2a9761f5` — `fix: attach page request ID to cache-hit traces`.
- This is a diagnostic metadata correction only. It does not alter cache lookup, memo matching, Arabic output, engine dispatch or bubble IDs.
- The previous #822 push run was against the pre-fix head. The latest head after this note will require a fresh Push and PR CI run; no green result is claimed for the request-ID change yet.

## CI verified — translation-cache provenance diagnostics (2026-11-16)

### Final verified CI on v0.12.11 metadata
- Push workflow #827 (`43824312263`) and PR workflow #828 (`43824320821`) both completed successfully on head `335c36aebfeab140e52e575b880c5652924547c5`.
- Inspected the uploaded Gradle HTML report directly: **812 tests, 0 failures, 0 ignored**, 100% success.
- Model checksum/runtime setup, `:app:testDebugUnitTest`, `:app:assembleDebug`, and pinned experimental debug certificate verification all succeeded.
- Push build produced `ManhwaLens-v0.12.11-debug.apk`, 71,384,902 bytes; SHA-256 `1be1a7dd3e11d3556b80f30a89aca4c7d76bb6297847980a2d9ce8e3bdae4063`. This is an Actions validation artifact, not a published Release. Release v0.12.11 already exists and was intentionally not republished.

### Instrumentation completed
- Fuzzy memo entries retain the source which populated their shape slot; exact/fuzzy hit details include `matched=...`.
- Translation pair records now carry a process-unique page `requestId` across engine results, cache hits, failures and missing results. Old 9-field log records stay readable as `request=legacy`.
- Tests verify both matched-source provenance and per-request grouping. The cache-hit propagation omission found in PR CI was fixed, then the full 812-test suite passed.
- Historical device evidence from the 2026-11-15 Solo Leveling chapter-3 pair report **did show** distinct short sources such as `AH...` and `YEAH` served the same unrelated Arabic from `CACHE_HIT`. The prior fuzzy shape algorithm removed vowels/punctuation, making both collapse to the same weak shape. The source fix now requires a safe shape (at least 3 alphanumeric characters and 2 distinct characters), and tests verify `AH...` cannot fuzzy-hit `YEAH`. A separate hardening change prevents fuzzy results from being promoted into exact cache keys. The current `matched=...` detail and per-page `request=...` ID let the v0.12.12 phone report establish whether a similar result is still occurring and which exact source supplied it.

### v0.12.12 device-validation build
- Bumped app metadata to `versionName=0.12.12`, `versionCode=52` so the diagnostic update can install over v0.12.11 (versionCode 51) without replacing/reusing the existing v0.12.11 tag.
- Added `MANUAL_TESTS.md` section `v0.12.12 — translation-cache provenance capture`. Preserve the translation cache during reproduction; clear only the pair log, capture the previous failing page, and share both the translation-pair report and (if OCR input is wrong) the Last capture report.
- v0.12.12 CI and release are pending on the next final branch head. Do not install until the v0.12.12 Release APK is published and its package/version/signature continuity gate passes.

### Next engineering decision
1. Complete CI + Release v0.12.12 (expected versionCode 51 → 52, same package `com.manhwalens.app` and pinned signing certificate).
2. Obtain the v0.12.12 pair report and classify any wrong result as exact memory/disk cache, exact memo, fuzzy memo (using `matched`), or fresh engine output before touching translation behavior.
3. Keep ML Kit as the default offline translator and Bergamot experimental. No fuzzy thresholds, cache key semantics, OCR thresholds, bubble geometry, or batching policy changed in this diagnostic-only patch.
4. No real-device validation is claimed yet.

## v0.12.12 release completed — 2026-11-16

- Final release CI: Push workflow #834 (`43826352333`) completed successfully on `18f90a108e69044fa1298f1970a218454f7617cf`; PR workflow #835 also completed successfully. All model/runtime setup, 812 unit tests, debug APK build, pinned experimental signer verification, package/version/signer update compatibility, artifact upload and GitHub Release publication steps passed.
- Final test report was downloaded and inspected: **812 tests, 0 failures, 0 ignored**, 2.869 seconds reported by Gradle.
- Update compatibility gate output: `v0.12.11` → `v0.12.12`, package `com.manhwalens.app`, versionCode `51 → 52`, certificate SHA-256 `0e36ccf390d6b2ac1696a8b19d173f448837703250baf42d37dbe25e6d109152`.
- Published prerelease: https://github.com/mouadknight0-cell/ManhwaLens/releases/tag/v0.12.12
- APK asset: `ManhwaLens-v0.12.12-debug.apk`; 71,384,898 bytes; SHA-256 `d9ce84716675f08e5bbf7ac29643afa04d936f16c0e56dcf141838935a5c06d0`.
- Downloaded the Actions APK artifact and independently hashed its contained APK; it is byte-identical to the published Release asset (same 71,384,898 bytes and SHA-256).
- **Ready for real-device diagnostic verification; not yet device-verified.** Follow `MANUAL_TESTS.md` → `v0.12.12 — translation-cache provenance capture`. Install over v0.12.11 without uninstalling; preserve translation cache, clear only pair history, reproduce the previous short-dialogue issue, copy the translation-pair report, and include Last capture diagnostics if source OCR is wrong. Disable Debug mode after.
- Keep PR #4 open Draft stacked on PR #3; no merge to main. ML Kit remains default offline; Bergamot stays experimental.

## Final-head verification — v0.12.12 device diagnostic handoff (2026-11-16)

- Latest branch head: `c22d66db7d29f27138176ff6a76678e12f5b1811`. Push workflow #837 (`43826864158`) and PR workflow #838 (`43826867055`) both completed successfully on this head.
- Downloaded and inspected the #837 Gradle HTML test artifact (`14205964577`): **812 tests, 0 failures, 0 ignored, 100% successful**, 1.862s reported.
- Downloaded #837 APK artifact (`14206548176`), extracted its APK and independently computed SHA-256: `d9ce84716675f08e5bbf7ac29643afa04d936f16c0e56dcf141838935a5c06d0`; size 71,384,898 bytes. It exactly matches the published `v0.12.12` Release asset digest and size.
- Release asset URL: `https://github.com/mouadknight0-cell/ManhwaLens/releases/download/v0.12.12/ManhwaLens-v0.12.12-debug.apk`; Release page: `https://github.com/mouadknight0-cell/ManhwaLens/releases/tag/v0.12.12` (published prerelease).
- Release publication workflow #834 (`43826352333`) verified update compatibility against v0.12.11: package `com.manhwalens.app`, versionCode `51 → 52`, signing certificate SHA-256 unchanged at `0e36ccf390d6b2ac1696a8b19d173f448837703250baf42d37dbe25e6d109152`; the workflow published the tag and APK. Later #837 branch validation correctly skipped republishing an existing v0.12.12 tag, but still built the same byte-identical APK and rechecked the pinned signer.
- Compared the current branch head against the release-tag commit `18f90a108e69044fa1298f1970a218454f7617cf`: only two documentation files changed (`PROJECT_STATE.md`, `REPOSITORY_REPORT.md`), with zero app source/build changes after the released binary.
- PR #3 remains open, non-draft, from `feature/v0.12.3-paddle-default-settings` to `main`. PR #4 remains open Draft from `feature/v0.12.4-bergamot-experiment` to `feature/v0.12.3-paddle-default-settings`; keep the stack and do not merge either PR as part of this diagnostic handoff.

### Device gate — ready, not yet tested
- No device testing is claimed. The remaining high-value evidence is the actual previous short-dialogue reproduction on the physical phone.
- Install v0.12.12 directly over v0.12.11; do not uninstall and do not clear translation cache. In Settings → Diagnostics turn Debug mode ON and clear only the translation-pair history. Keep ML Kit selected; reproduce the same page/dialogue; copy the translation-pair report. Preserve `request=...`, `block #...`, `status`, `detail=...matched=...`, `EN`, `CANDIDATE AR`, and `FINAL AR` fields. Include Last capture / pipeline report only where OCR text or grouping is wrong. Turn Debug mode OFF afterward.
- Classification guide: wrong `EN` versus screenshot = OCR; correct `EN` + `FUZZY_HIT` with a different `matched` source = fuzzy memo reuse; `CACHE_HIT` = exact in-memory/disk cache; fresh `OK` = engine/post-processing. Compare request IDs before interpreting repeated block numbers.
- Next engineering work after receiving the report: make the smallest evidence-based correction only if a concrete mismatch is confirmed. Continue treating Arabic semantic correctness as a manual evaluation dimension; lexical token F1 is not a semantic score.

## v0.12.13 — native OCR memory-lifecycle hardening (2026-11-16)

### Source changes implemented; CI pending
- `QuadTextCrop.crop`: all temporary point/transform Mats now release in `finally`; warped output ownership is transferred only when returned; a failed vertical rotation releases its destination.
- `OCREngine`: batch crop generation now sits inside the crop batch's `try/finally`, so previously created crops are released even if a later perspective crop throws.
- `RecPreprocessor`: validates non-empty three-channel input crops, tracks all created resize/float/padding Mats and releases them in reverse order in `finally`; per-batch channel splits are released even if split/read/copy fails. BGR channel order, 48px resize, 320px base width, padding, normalization and tensor layout are unchanged.
- `DetPreprocessor`: validates non-empty source, and releases RGB conversion, resize, float and split-channel Mats on all exits. Normalization and detector tensor layout are unchanged.
- `ImageUtils.resizeToMultipleOf32`: releases the allocated destination if OpenCV resize throws.
- Commits: `006020511bc525218dd6cb5ffecf231ff0fd8bc4`, `c07bfead64e6f950a86083c7e10d6247a719c168`, `fef0c81415b05fe10470ddd7b698b71571b359ea`, `e8932f619a0d6c80e533d88cdf9243c6f8e9626a`, `557c1c2a16d8442a9498ec5aa19a11b0d59b5955`, `6d9e6fff831b8c0f2dc36a3c1b104265760d9cd5`.
- Bumped version metadata to `0.12.13` / `versionCode 53` for a safe update over v0.12.12 / code 52. Added `MANUAL_TESTS.md` section `v0.12.13 — repeated-page OCR memory stability`.
- This is resource-lifecycle hardening; no OCR detector/recognizer thresholds, tensor values/shapes, crop geometry, box ordering, bubble grouping or translation output policy were intentionally changed.

### Verification boundary
- CI for the latest head is pending. No build, release, device test or improvement in real-world OCR accuracy is claimed yet.
- Native memory behavior after repeated captures requires phone validation; see the v0.12.13 manual test section. Do not install until the v0.12.13 Release is published and package/version/signature continuity is verified.

### Additional detector post-processing cleanup
- Extended native-resource hardening to `DBPostProcessor`: owns probability/mask/hierarchy/dilation Mats in one cleanup scope, releases the dilation kernel and failed dilation destination, and releases the per-contour score mask/polygon/ROI even when `fillPoly` or `Core.mean` throws.
- This preserves thresholding, dilation kernel size, contour discovery, scoring, unclip behavior and box coordinates. Commit: `b7ecd44a01d5e1fb72a7b6a819bcbd8ad72af624`; follow-up partial-dilation cleanup hardening: `94be8d944d1355c1be47b1b7f87c455b2d26f716`.

## v0.12.13 release verified — native OCR memory hardening (2026-11-16)

### CI and test evidence
- Push workflow #871 (`43836404334`) and PR workflow #872 (`43836409254`) completed successfully on head `78155310cb167ff5629b8a7403e0671e2594c579`.
- Downloaded and inspected the #871 Gradle HTML report artifact (`14209764947`): **812 tests, 0 failures, 0 ignored**, 1.996s reported; all package groups show 100% success.
- `:app:assembleDebug`, model/runtime setup and pinned experimental signer verification all succeeded.

### Release and update compatibility
- Published prerelease `v0.12.13`: `https://github.com/mouadknight0-cell/ManhwaLens/releases/tag/v0.12.13`.
- APK: `ManhwaLens-v0.12.13-debug.apk`, 71,388,978 bytes; SHA-256 `daaf7c9776afbe0522568ef2de551a8106e9c373a20bbde091011b6eb9582c90`.
- Downloaded the #871 Actions APK artifact (`14211665290`), extracted it and independently hashed the APK. It is byte-identical to the published Release asset (same size and SHA-256).
- Update gate output verified previous release `v0.12.12` → `v0.12.13`, package `com.manhwalens.app`, versionCode `52 → 53`, signer SHA-256 unchanged at `0e36ccf390d6b2ac1696a8b19d173f448837703250baf42d37dbe25e6d109152`.
- Release asset URL: `https://github.com/mouadknight0-cell/ManhwaLens/releases/download/v0.12.13/ManhwaLens-v0.12.13-debug.apk`.

### Native OCR lifecycle scope
- `QuadTextCrop`, `OCREngine` batch crop ownership, `RecPreprocessor`, `DetPreprocessor`, `ImageUtils` and `DBPostProcessor` now release temporary OpenCV Mats/ROIs/channels/kernels on both success and exception paths.
- No OCR score thresholds, crop geometry, tensor shapes, BGR channel order, normalization, reading order, bubble grouping, or translation logic was changed.
- This repair improves native-memory safety by code inspection and CI build/test success; it does **not** yet prove reduced memory use or long-session stability on a physical phone.

### Device verification — next required gate
- Install v0.12.13 directly over v0.12.12; do not uninstall or clear app data.
- Follow `MANUAL_TESTS.md` → `v0.12.13 — repeated-page OCR memory stability`: keep PaddleOCR for OCR, ML Kit for translation, turn Debug mode on, capture a dense page repeatedly (5 times), then 5–10 varied pages without restarting the app. Copy Last capture / pipeline report and note device model, Android version, responsiveness and system app-memory before/after if available. Turn Debug mode off after.
- No physical-device test is claimed. Do not adjust OCR thresholds or promote alternative OCR engines based on this resource-lifecycle patch.

### Repository topology
- Keep PR #3 open toward `main` and PR #4 open Draft stacked on PR #3. No merge was performed.


## Session continuation — protocol adoption and repository re-audit (2026-11-16 11:40 UTC+1)

### Actual repository state rechecked
- Repository metadata confirms public repository `mouadknight0-cell/ManhwaLens`, default branch `main`; the engineering handoff files `PROJECT_STATE.md` and `REPOSITORY_REPORT.md` are maintained on `feature/v0.12.4-bergamot-experiment`, not on `main`. A request for `PROJECT_STATE.md` on `main` returns 404; do not mistake this for a missing project handoff.
- Development branch head recorded and fetched: `78155310cb167ff5629b8a7403e0671e2594c579` (`docs: include detector score mask cleanup`). The latest commit only updates the engineering report; it does not change app source.
- Latest recorded release remains v0.12.13, versionCode 53, package `com.manhwalens.app`; published prerelease APK URL: https://github.com/mouadknight0-cell/ManhwaLens/releases/download/v0.12.13/ManhwaLens-v0.12.13-debug.apk. Release/CI evidence is recorded in the preceding v0.12.13 section.
- Workflow run #872 (`43836409254`) for the exact head was re-fetched and reports completed/success. The prior state records push run #871 (`43836404334`) success and the inspected 812/812 test report. Combined commit status endpoint returned no status entries; this is not interpreted as a failure or as extra CI evidence.
- Branch listing confirms `feature/v0.12.4-bergamot-experiment`, `feature/v0.12.3-paddle-default-settings`, three arena branches and `main`. PR #3/#4 remain the documented stacked development path; no merge or source change was made in this interaction.

### Current engineering gate
- v0.12.13 is **CI-verified and Release-published**, but **not device-verified**.
- The sole immediate user-side evidence still required is the v0.12.13 repeated-page PaddleOCR/OpenCV memory-stability check described in `MANUAL_TESTS.md`: install over v0.12.12, capture one dense page five times and 5–10 varied pages without restarting, then return the Last capture/pipeline report plus device model, Android version, responsiveness and memory observations if available.
- Do not alter OCR thresholds, tensor geometry, reading order or translation policy before this resource-lifecycle validation. ML Kit remains the default translator; Bergamot remains experimental.

### Protocol update
- This interaction revalidated repository state and handoff. No app code, tests or release assets were changed. The next state update is recorded in this file and `REPOSITORY_REPORT.md`.


## Current continuation state — Solo Leveling chapter 4 follow-up (2026-11-16)

- Latest source/test changes are on `feature/v0.12.4-bergamot-experiment`; latest observed commit at this checkpoint: `deb5a382dec72c1ede59da67fb6bdc39a9073e7e`.
- User-supplied Xiaomi 23106RN0DA / Android 14 benchmark confirms Joined ` | ` mapping 20/20 at median 573 ms (worst 576 ms), versus sequential 969 ms and parallel x2 979 ms. User reports this verdict is applied. Other joined separators tested that returned one segment are not acceptable.
- 23-case quality report: raw/cleaned mean token F1 49.6%; final visible mean token F1 74.8%, exact 9/23. Visible score includes seven exact source repairs; token F1 is lexical overlap, not semantic accuracy.
- 59 translation-pair records reveal accepted-but-weak Arabic outputs; Arabic guard is not a semantic evaluator. OCR source artifacts include `OLT`, `BLYING`, `QLICKLY`, and `STATLES`; avoid global OCR substitutions without checking source images.
- A separate three-block request `ed782881-p17` took 20,430 ms; investigate only with its stage trace, not by changing batching based on this number alone.
- Implemented exact-source chapter-4 repairs in `ShortDialogueOverrides.kt` and added JVM regression assertions in `ShortDialogueOverridesTest.kt`. Updated `MANUAL_TESTS.md` with a device recheck plan that preserves translation cache and clears only pair history.
- CI/build for the latest branch tip is pending/unverified. No new APK, version bump, or GitHub Release has been created. Do not request device recheck until CI passes and an update-compatible prerelease APK is published.
- Next engineering action: inspect the Actions run for `deb5a382dec72c1ede59da67fb6bdc39a9073e7e`; fix any unit-test/build failures, then bump the patch version and publish only after verifying CI and signer/package continuity.
