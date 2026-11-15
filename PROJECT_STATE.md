# ManhwaLens — Project State

Last updated: 2026-11-15 (UTC)
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
