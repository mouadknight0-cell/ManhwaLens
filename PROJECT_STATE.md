# ManhwaLens — Project State

Last updated: 2026-11-15 (UTC)
Purpose: durable handoff record between ChatGPT conversations. GitHub source code and CI are authoritative; this file is a navigation aid, not proof that a build or device test passed.

## Repository
- Repository: https://github.com/mouadknight0-cell/ManhwaLens
- Main branch HEAD observed: 23904fb0b11caf55e0f0297585e3e47b80fba766
- Active development PR: #4 — https://github.com/mouadknight0-cell/ManhwaLens/pull/4
- PR #4 head ref: refs/pull/4/head
- PR #4 is 125 commits ahead of main and 0 behind at the time of this snapshot.
- Development branch observed: feature/v0.12.4-bergamot-experiment
- PR #4 describes the v0.12.6 development line; app/build.gradle.kts on PR head reports versionName 0.12.6 and versionCode 46.
- Main CHANGELOG currently reports v0.12.2. Do not confuse main's version with the PR head's version.

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
