# ManhwaLens — repository report

**Snapshot:** session date 2026-11-13 · **Branch:** `arena/8a718184-manhwalens` · **PR:** [#2](https://github.com/mouadknight0-cell/ManhwaLens/pull/2) — **OPEN**, not draft
**Head at this snapshot:** `05fa1ab` (*docs: repository documentation consistency audit*, commit clock 2026-11-14 UTC) — the last **code** commit remains `3674ebf` (*docs: refresh the report after every prompt (rule S6)*, 2026-11-14 06:08 UTC); the last commit that changed application code is `c008f62` (P0/C2)
**Base:** `main` @ `560705d55012f1549449ca3230184b4b5beda991` · **Branch ahead (at `3674ebf`):** 29 commits, 228 files, +36 706 / −0 · **Working tree:** clean before this documentation commit, which adds Markdown only
**Latest verified run:** [43565553808](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43565553808) — workflow *Android build*, conclusion **success** at `05fa1ab` (steps *Unit tests* ✅, *Build debug APK* ✅, APK artifact uploaded); the run before it, [43564884208](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43564884208), verified `3674ebf`

This is the repository-level view and the authoritative current-state document: what the branch contains,
what has been verified and how, what cannot be verified in this environment, and what is next. Per-change
detail lives in the reports indexed in [REPORTS.md](REPORTS.md). §9 records this snapshot's own consistency
audit: what was stale, what it was corrected to, and what remains **UNVERIFIED**.

> Clock note: this session's date is 2026-11-13, while commit timestamps (UTC) run 2026-11-13 16:21 →
> 2026-11-14 06:08. Dates below are the commit clock where a commit is cited, the session date otherwise.

---

## 1. What the repository is

An Android utility (not a reader) that translates manhwa/manga/webtoon pages in place: floating
button → MediaProjection capture → ML Kit OCR (Latin) → translation (ML Kit EN→AR on device, Gemini
or LibreTranslate) → Arabic text painted over the original bubbles in an overlay. Single `:app`
module, Kotlin 2.4.21, AGP 8.13.2, Compose + Material 3, `minSdk 26`, `compileSdk`/`targetSdk` 36.

| | | Verified how |
| --- | --- | --- |
| Kotlin sources | **113** files under `app/src/main` | `find app/src/main -name '*.kt' \| wc -l` |
| Test sources | **69** files under `app/src/test` | `find app/src/test -name '*.kt' \| wc -l` |
| Test methods (`@Test`) at this head | **629** | `grep -rn '@Test' app/src/test \| wc -l` |
| Branch vs `main` (at `3674ebf`) | **29** commits, **228** files, **+36 706** lines (the PR branch carries the whole app; `main` holds the initial commit) | `git rev-list --count origin/main..3674ebf`, `git diff --stat origin/main...3674ebf` |
| Documentation at the root | **19** Markdown files (§6) | `ls *.md \| wc -l` |
| PR #2 (at `3674ebf`) | OPEN · MERGEABLE · CLEAN · 0 reviews · 29 commits · 228 files · +36 706 / −0 | `gh pr view 2 --json …` |

---

## 2. What this branch adds on top of v0.11.0

| # | Work package | Report | Base → final (code commit) | Diff | CI |
| --- | --- | --- | --- | --- | --- |
| 1 | **P0** architecture audit (read-only) | [P0_MANHWALENS_AUDIT.md](P0_MANHWALENS_AUDIT.md) | v0.11.0 `b8e37de` | none (no code changed) | — (no code changed) |
| 2 | **P0-A** Gemini name sheet missing from the primary request | [P0A_GEMINI_NAME_SHEET.md](P0A_GEMINI_NAME_SHEET.md) | `b8e37de` → `528d629` | 6 files, +692 / −5 | 43514068424, 43514608722 |
| 3 | **P1** series-scoped character memory | [P1_SERIES_NAME_MEMORY.md](P1_SERIES_NAME_MEMORY.md) · design [P1_CHARACTER_MEMORY.md](P1_CHARACTER_MEMORY.md) | `528d629` → `8d5cc9e` (docs `1c0c270`) | 27 files, +3 307 / −52 | 43518604612, 43520783453 |
| 4 | **P1 review** — atomic writes, session cache identity, series visibility | [P1_REVIEW_CORRECTIONS.md](P1_REVIEW_CORRECTIONS.md) | `1c0c270` → `e99dd93` (docs `06b3d84`) | 7 files, +269 / −16 | 43523522645, 43524129101 |
| 5 | **P1 persistence safety** — recovery may never write the live file | [P1_PERSISTENCE_SAFETY_FIX.md](P1_PERSISTENCE_SAFETY_FIX.md) | `b960444` → `87830b7` (docs `345715d`) | 6 files, +348 / −63 | 43526686657, 43527227407 |
| 6 | **P1.1 strict staging validation** — a staging copy is promoted only after it is proved structurally complete; **P1.1-A** adds canonical `normalizedName` integrity (§8 of that report) | [P1_1_STRICT_STAGING_VALIDATION.md](P1_1_STRICT_STAGING_VALIDATION.md) | `8140a4b` → `723f101` → `fee9d9e` (docs `feb20ef`, `e546470`, `6eb8974`) | P1.1 5 files +451/−12 · P1.1-A 6 files +206/−13 | 43531030306, 43531550380, 43561318751, 43561668957 |
| 7 | **P0/C2** ML Kit English echo eliminated — no source substitution, guard extended to `ml_kit_en_ar`, guarded progressive path, painter-boundary defence | [P0B_MLKIT_ENGLISH_ECHO.md](P0B_MLKIT_ENGLISH_ECHO.md) | `6eb8974` → `c008f62` (docs `08004c4`, `b82fb6f`, `7b30804`, `3674ebf`) | 6 files, +510 / −8 | 43563394305, 43563800508, 43564060458, 43564884208 |

Net: one defect fixed in the Gemini wire path, one feature (persistent, series-scoped names), four
hardening passes over the new persistence layer (atomic writes → recovery never writes the live file →
strict structural validation before promotion → canonical normalized-name integrity), and one output-guard
fix (ML Kit). No existing feature removed; no translation or OCR behaviour changed by the hardening passes;
the P0/C2 change touches only the assembly/guard/painter boundaries, not the engines' batching or prompts.

---

## 3. Verification: what is real here

`./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain`

The authoring sandbox has **no JDK, no `javac`, no `gradle` and no Android SDK**
(`which java javac gradle` → nothing; `ANDROID_HOME` unset), so Gradle cannot run locally. The only
executed verification is GitHub Actions (`.github/workflows/android.yml`, workflow *Android build*), on
the `pull_request` event; each report cites the run that validated its commit.

### 3.1 Every run on this branch (verified via the Actions API, not from memory)

| Run | Commit | Conclusion | Note |
| --- | --- | --- | --- |
| [43564884208](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43564884208) | `3674ebf` | **success** | current head; unit tests + APK |
| [43564060458](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43564060458) | `7b30804` | success | |
| [43563800508](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43563800508) | `b82fb6f` | success | |
| [43563394305](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43563394305) | `08004c4` | success | P0/C2 code + docs |
| [43561668957](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43561668957) | `6eb8974` | success | |
| [43561318751](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43561318751) | `e546470` | success | P1.1-A verified here |
| [43558741498](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43558741498) | `feb20ef` | success | |
| [43531550380](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43531550380) | `723f101` | success | P1.1 |
| [43531030306](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43531030306) | `e6598a8` | success | |
| [43530112769](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43530112769) | `8140a4b` | success | |
| [43527227407](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43527227407) | `87830b7` | success | persistence safety |
| [43526686657](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43526686657) | `345715d` | success | |
| [43525048064](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43525048064) | `b960444` | success | rules push |
| [43524129101](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43524129101) | `06b3d84` | success | |
| [43523522645](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43523522645) | `e99dd93` | success | P1 review |
| [43520783453](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43520783453) | `1c0c270` | success | P1 |
| [43518604612](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43518604612) | `8d5cc9e` | success | P1 code |
| [43517986742](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43517986742) | `1686e72` | ❌ **failure** | 590 tests, 4 failed — analysed in [P1_SERIES_NAME_MEMORY.md](P1_SERIES_NAME_MEMORY.md) |
| [43517478032](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43517478032) | `6a71f77` | ❌ **failure** | nullable smart-cast compile error — analysed in that report |
| [43514608722](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43514608722) | `528d629` | success | P0-A code |
| [43514068424](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43514068424) | `1940487` | success | P0-A second attempt |
| [43513473811](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43513473811) | `0e6d5ff` | ❌ **failure** | 517 tests, 1 failed (the new P0-A test) — analysed in [P0A_GEMINI_NAME_SHEET.md](P0A_GEMINI_NAME_SHEET.md) |

Totals on this branch: **22 runs — 19 success, 3 failure**. The three failures are pre-fix states of
later-green commits, each identified and analysed in its report; none is open. The base `main` commit
`b8e37de` was green on the v0.11.0 tag push (run 43504057987).

**Not verifiable in this environment** (see [BLOCKERS.md](BLOCKERS.md)): any on-device behaviour (overlay
timing, scroll riding, Live mode, battery), `lint`/`lintDebug` (offline cache gap; `lintVitalRelease` is
banned on this box by user mandate), R8/minify, and real Gemini/Libre API calls (no key, no network from
here). Nothing in this branch's reports claims otherwise.

---

## 4. Test counts — scope, provenance, and what is UNVERIFIED

Four numbers appear across the history (509, 517/590/599/604/615/619, 629). They are **not contradictory**:
each is the source-level `@Test` count at a different commit, and every one was re-derived for this snapshot
with `git grep -c '@Test' <sha> -- app/src/test` (summed):

| Commit | Phase boundary | `@Test` discovered | Unit-test task in CI |
| --- | --- | --- | --- |
| `b8e37de` | v0.11.0 | 509 | green (run 43504057987, tag push) — matches CHANGELOG's "509" |
| `528d629` (= `0e6d5ff`, `1940487`) | after P0-A (+8) | 517 | green (43514608722) |
| `8d5cc9e` | after P1 (+73) | 590 | green (43518604612) |
| `e99dd93` | after P1 review (+9) | 599 | green (43523522645) |
| `87830b7` / `8140a4b` | after P1 persistence safety (+5) | 604 | green (43527227407) |
| `723f101` | after P1.1 (+11) | 615 | green (43531550380) |
| `fee9d9e` / `6eb8974` | after P1.1-A (+4) | 619 | green (43561318751) |
| `3674ebf` (head) | after P0/C2 (+10) | **629** | green (43564884208) |

Scope of each number: **every Kotlin test in the single `:app` module** (`app/src/test`), one JVM source
set, `testDebugUnitTest`. No instrumented/Android test source set exists. At this head: `translate` 231,
`live` 118, `names` 82, `ocr` 79, `render` 46, `util` 37, `data` 22, `overlay` 14 — total 629.

**Discovered vs executed** (this is the honest split):

| Metric | Value | Evidence status |
| --- | --- | --- |
| Tests discovered (source) | **629** | VERIFIED — `grep -rn '@Test' app/src/test` at `3674ebf` |
| Tests executed | **UNVERIFIED number** | The *Unit tests* step ran `:app:testDebugUnitTest` and concluded **success** (step log via Actions API); the exact executed count is **not observable from here** |
| Tests passed | **no failing test** (task-level) | `testDebugUnitTest` fails on any test failure, and it succeeded — so failures = 0 among executed |
| Tests failed | **0** | same inference at this head; the 3 historical failures are listed in §3.1 |
| Tests skipped | **UNVERIFIED** | skipped tests do not fail the task; the JUnit XMLs are in the uploaded artifact, which cannot be downloaded here |

Why the numbers cannot be read: the run's `test-reports` artifact (98 KB) downloads from
`productionresultssa*.blob.core.windows.net`, outside this sandbox's network allowlist
(`gh run download` → `EOF`), and `gh run view --log` returns `EOF` for the same reason. The workflow only
echoes per-test lines when the task **fails**, so green runs leave no count in the API. This limitation is
stated rather than papered over: no report in this repository claims an executed count for a green run
beyond the inference above.

---

## 5. Where the risk lives (code map)

| Area | Files | State |
| --- | --- | --- |
| `overlay/FloatingButtonService.kt` | 4.4k-line orchestrator | Touched by P0/C2 only (one painter-boundary guard + its log line); the known structural risk (audit item 10) is unchanged |
| `translate/` | engines + cache decorator + name pipeline + `MlKitPageAssembly` | Modified by P0-A, P1, P0/C2 |
| `names/` (new in P1) | record, form, resolver, JSON store, series catalogue | Added by P1, hardened four times (atomic writes, no-write recovery, strict staging validation, canonical-key integrity); JVM-tested (no Android deps) |
| `data/` | prefs, caches, `CharacterMemory`/`NameSession` | Added P1 glue; the store's only Android coupling is the factory |
| `ocr/`, `render/`, `capture/` | OCR, patch painter, MediaProjection | Untouched by this branch |

---

## 6. Documentation map

| Document | Purpose |
| --- | --- |
| [REPORTS.md](REPORTS.md) | The rule ("a report travels with every change", S1–S6) and the index of per-change reports, with each phase's status |
| [REPOSITORY_REPORT.md](REPOSITORY_REPORT.md) | This file — repository-level snapshot and the authoritative phase status |
| [PLAN.md](PLAN.md) | Phases, definitions of done, environment notes |
| [DECISIONS.md](DECISIONS.md) | One line per decision, including the standing rules S1–S6, T1–T7, U1–U6, V1–V5, W1–W6 |
| [BLOCKERS.md](BLOCKERS.md) | What cannot be done in this environment, and why |
| [MANUAL_TESTS.md](MANUAL_TESTS.md) | On-device checklists (Redmi 13C, Brave) |
| [P0_MANHWALENS_AUDIT.md](P0_MANHWALENS_AUDIT.md), [P0A_GEMINI_NAME_SHEET.md](P0A_GEMINI_NAME_SHEET.md), [P0B_MLKIT_ENGLISH_ECHO.md](P0B_MLKIT_ENGLISH_ECHO.md), [P1_CHARACTER_MEMORY.md](P1_CHARACTER_MEMORY.md), [P1_SERIES_NAME_MEMORY.md](P1_SERIES_NAME_MEMORY.md), [P1_REVIEW_CORRECTIONS.md](P1_REVIEW_CORRECTIONS.md), [P1_PERSISTENCE_SAFETY_FIX.md](P1_PERSISTENCE_SAFETY_FIX.md), [P1_1_STRICT_STAGING_VALIDATION.md](P1_1_STRICT_STAGING_VALIDATION.md) | Per-change reports (§2) |
| [CHANGELOG.md](CHANGELOG.md), [README.md](README.md), [PRIVACY.md](PRIVACY.md), [OPEN_SOURCE_RESEARCH.md](OPEN_SOURCE_RESEARCH.md), [V096_PLAN.md](V096_PLAN.md) | User-facing changelog (version history, kept as written), product readme, privacy stance, licence research, earlier plan |

All links above resolve to files that exist at this head (`ls *.md` = 19 files, all listed).

---

## 7. Open blockers (inherited, unchanged by this branch)

1. **No on-device validation** of overlay/patch/Live behaviour in this environment — including the P0/C2
   withholding behaviour (§8, P0/C2 risk).
2. **Lint** unavailable offline; `lintVitalRelease` banned on this box.
3. **R8/minify** disabled here (memory); release builds are signed but unminified.
4. **Release build not produced** by this branch — CI runs `assembleDebug` only.
5. **No real API traffic** for Gemini/Libre from here; wire format is covered by fake-response tests.
6. **Executed/skipped test counts unreadable** (artifact host outside the sandbox allowlist) — §4.

---

## 8. Phase status (authoritative)

Status vocabulary is fixed: **VERIFIED COMPLETE** = implemented, its tests ran in a green CI run on a
commit that is an ancestor of this head, and no claim in its report is contradicted by this audit.
"Complete" never means "device-validated": no phase in this repository has device validation (§7, item 1).
Control commits are ancestors of `3674ebf` — verified with `git merge-base --is-ancestor` for
`e546470`, `6eb8974`, `fee9d9e`, `c008f62`, `08004c4`, `b82fb6f`, `7b30804` (all YES).

| Phase | Status | Code commit(s) | Changed files | Tests added | Tests executed (CI) | Remaining risk |
| --- | --- | --- | --- | --- | --- | --- |
| **P0 architecture audit** | **VERIFIED COMPLETE** | `b8e37de` (read-only, no code) | 1 doc | 0 | n/a (no code) | Its line numbers drift as code changes; C2's were corrected in place (220→246, 42-43→51-52, 3841→3844). Open audit items C3, C4, C5, RTL, offline, perf remain **PENDING** |
| **P0-A Gemini name sheet** | **VERIFIED COMPLETE** | `528d629` | 6 files, +692/−5 | +8 (509→517) | run 43514608722 green; 517 discovered, executed count UNVERIFIED (§4) | No real Gemini API call from here; wire format covered by fake-response tests only |
| **P1 series name memory** | **VERIFIED COMPLETE** | `8d5cc9e` (+docs `1c0c270`) | 27 files, +3 307/−52 | +73 (517→590) | runs 43518604612, 43520783453 green | Device UX of series selection unverified; memory is manual-scoped by design (no auto-detection) |
| **P1 review corrections** | **VERIFIED COMPLETE** | `e99dd93` (+docs `06b3d84`) | 7 files, +269/−16 | +9 (590→599) | runs 43523522645, 43524129101 green | Atomic-rename fallback path is only exercised through the `atomicMove` test seam |
| **P1 persistence safety** | **VERIFIED COMPLETE** | `87830b7` (+docs `345715d`) | 6 files, +348/−63 | +5 (599→604) | runs 43526686657, 43527227407 green | Real-world crash/kill timing during promotion not reproducible offline |
| **P1.1 strict staging validation** (incl. **P1.1-A**) | **VERIFIED COMPLETE** | `723f101`, `fee9d9e` (+docs `feb20ef`, `e546470`, `6eb8974`) | 5 files +451/−12; 6 files +206/−13 | +11 (604→615), +4 (615→619) | runs 43531030306, 43531550380, 43561318751, 43561668957 green | `NameForm.normalize` changes would reject older documents — deliberate future-migration concern (V5) |
| **P0/C2 ML Kit English echo** | **VERIFIED COMPLETE** | `c008f62` (+docs `08004c4`, `b82fb6f`, `7b30804`, `3674ebf`) | 6 files, +510/−8 | +10 (619→629) | runs 43563394305, 43563800508, 43564060458, 43564884208 green | Visual behaviour of a withheld bubble is **not device-verified**; ML Kit echo rate unmeasured |

No phase is **IMPLEMENTED BUT NOT VERIFIED**, **PARTIALLY COMPLETE**, **PENDING** or **BLOCKED** except the
audit's *open items* listed in §10 (C3, C4, C5, RTL, offline readiness, perf, `FloatingButtonService` seams),
which were never started.

---

## 9. Documentation consistency audit (this snapshot)

### 9.1 Was inspected

`REPOSITORY_REPORT.md`, `REPORTS.md`, `P0_MANHWALENS_AUDIT.md`, `P0A_GEMINI_NAME_SHEET.md`,
`P1_SERIES_NAME_MEMORY.md`, `P1_REVIEW_CORRECTIONS.md`, `P1_PERSISTENCE_SAFETY_FIX.md`,
`P1_1_STRICT_STAGING_VALIDATION.md`, `P0B_MLKIT_ENGLISH_ECHO.md`, `CHANGELOG.md`, `DECISIONS.md`,
`MANUAL_TESTS.md` — **all 12 exist** (no missing file to report), plus `PLAN.md`, `BLOCKERS.md`,
`P1_CHARACTER_MEMORY.md` for cross-reference. Repository state was read from git and the GitHub API, not
from the documents themselves.

### 9.2 Inconsistencies found and their resolution

| # | Inconsistency | Resolution | Evidence |
| --- | --- | --- | --- |
| 1 | Header claimed head `fee9d9e`, 21 commits, latest run 43561318751 | Corrected: head `3674ebf`, **29** commits, latest run 43564884208 | `git rev-parse HEAD`; `git rev-list --count origin/main..HEAD`; Actions API |
| 2 | Inventory: 112 Kotlin sources, 68 test files, 25 commits, +36 673 lines, 18 docs | Corrected: **113**, **69**, **29**, **+36 706**, **19** | `find`/`ls`/`git diff --shortstat` |
| 3 | §3 run table stopped at run 43520783453 and said "the last eight runs are green" | Replaced by the complete 22-run table: **19 success, 3 failure**, each failure identified | Actions API listing of the branch |
| 4 | §7 said "P0 / C2 — open" while the C2 report and audit say fixed | Corrected to **done** (P0/C2), consistent with [P0B_MLKIT_ENGLISH_ECHO.md](P0B_MLKIT_ENGLISH_ECHO.md) and the audit's C2 row | run 43563394305 + §8 |
| 5 | §5 said the standing rules are "S1–S5" | Corrected to **S1–S6** (S6 = a report refresh after every prompt) | `DECISIONS.md` |
| 6 | `REPORTS.md` P0/C2 row ended at docs `b82fb6f` | Corrected to the phase's real doc commits (`08004c4`, `b82fb6f`, `7b30804`, `3674ebf`); run list extended to all four green runs | `git log` |
| 7 | `REPORTS.md` index had no status column, so its statuses could drift from this file | Added a **Status** column using this file's vocabulary; every row now agrees with §8 | [REPORTS.md](REPORTS.md) |
| 8 | Commit totals 21 / 25 appear in the history rows, and the P0/C2 report names commits as "25th / 26th / 27th" | Each is **historical**: the count actually true at `e546470` is 23 and at `b82fb6f` is 27, so the history rows are annotated in place rather than rewritten, and the P0/C2 report's ordinals are superseded by its updated §6 (tip = 29th, `3674ebf`) | `git rev-list --count origin/main..<sha>` |
| 9 | Test counts 615 / 619 / 629 read as contradictions | Resolved: they are the counts at `723f101` / `fee9d9e` / `3674ebf`; §4 tabulates all eight boundaries and the scope | `git grep -c '@Test'` per commit |
| 10 | Audit C2 evidence line numbers (220, 42-43, 3841) had drifted | Corrected in the audit to 246, 51-52, 3844-3846 with the original text preserved as history | audit §9.1 + [P0B](P0B_MLKIT_ENGLISH_ECHO.md) §1 |
| 11 | Documents are dated 2026-11-13 while commits are 2026-11-14 UTC | Not an error: documented once in the header clock note; no date rewritten | `git log --date=iso` |
| 12 | CHANGELOG says "509 unit tests pass" while this file says 629 | Both are correct in scope: CHANGELOG is the v0.11.0 entry (509 at `b8e37de`), this file is the head (629 at `3674ebf`). **No change made** — version history is preserved | §4 table |

### 9.3 Facts that remain UNVERIFIED

* **Executed / passed / skipped test counts** for every green run — artifact host outside the allowlist,
  green runs print no counts (§4). Only "0 failures" is inferred from task success.
* **Per-test identity of the 629 tests** at this head (names are in the JUnit XMLs, also unreachable).
* **Device behaviour of every phase**, including the P0/C2 withheld-bubble path and the P1 series UI.
* **Real Gemini / LibreTranslate traffic** (no key/network here).
* **Review status**: PR #2 has **0 reviews** — that is verified, but it means no human has signed off.

---

## 10. What is next (from the audit ranking, §10)

| Priority | Item | Status |
| --- | --- | --- |
| P0 / C1 — name sheet in the primary Gemini body | **done** (P0-A) |
| P0 / C2 — ML Kit English echo can reach the screen | **done** (P0/C2, `c008f62`) |
| P1 / C4 — AI cleanup queued on the live thread | **open** |
| P1 — RTL correctness (one shared layout builder, RLM wrapping) | **open** |
| P1 — offline readiness step ("Prepare offline EN→AR") | **open** |
| P2 / item 7 — name persistence per series | **done** (P1) |
| P2 / item 8 — de-duplicate `NameMemory.plan` (C5), clear `pageNames` in `finally` (C3) | **open** (partially obsolete: with a series selected the page-local plan usually does not run) |
| P2 — perf (binary-search `AutoFitTextSizer`, measurer `maxLines`) | **open** |
| P3 — orchestration seams out of `FloatingButtonService` | **open** |

The audit's item 7 proposed keying memory by the foreground package + title. P1 deliberately did
**not** do that: a package is not a series, so memory is scoped by an explicitly selected series
(decision D1 in [P1_SERIES_NAME_MEMORY.md](P1_SERIES_NAME_MEMORY.md)). That remains a deliberate
divergence from the audit and is the standing architectural choice for this area.

**Recommended next step:** **C4** — move AI cleanup off the live pipeline's single-thread executor
(`FloatingButtonService.kt:4053` vs `:1774`, `ioExecutor:232`), the last P0 defect in the audit that
affects the live path. It is small, local and testable with the existing seams; it needs its own phase
report before any code change.

---

## 11. Merge readiness

- **Checks:** green at the current head `3674ebf` (run 43564884208); the three preceding heads
  (`7b30804`, `b82fb6f`, `08004c4`) are green too. PR #2: **OPEN / MERGEABLE / CLEAN**, merge state CLEAN.
- **Review:** 0 reviews, no review decision, no labels — nothing has been human-approved.
- **What a reviewer should read first:** the seven per-change reports in §2 (each self-contained: evidence,
  fix, tests, diff, risks), then `DECISIONS.md` for the standing rules.
- **What merging does *not* buy:** device validation, a release APK, or lint coverage — all three remain
  environment blockers, unchanged by this branch.
- **Nothing is merged by the author of these changes;** the branch stops here for architectural approval.

---

## Report history

| Updated | Head | What changed in this snapshot |
| --- | --- | --- |
| 2026-11-13 (audit commit `05fa1ab` on top of `3674ebf`; commit clock 2026-11-14, green in run 43565553808) | documentation consistency audit | Re-derived every current-state claim from git and the GitHub API: head/commit count/PR state, the full 22-run CI table, the eight test-count boundaries with explicit scope, the discovered-vs-executed split, and the authoritative phase status table. Corrected the stale header, inventory, run table, C2 status, rules range and a non-existent run id; added §4 (test counts) and §9 (this audit); preserved historical rows with annotations. **No application source, test, Gradle or dependency change.** |
| 2026-11-13 (doc-only, on top of `7b30804`) | standing rule S6 recorded | Rule S6 in `DECISIONS.md` and `REPORTS.md`; no code change, no test run claimed beyond the CI run of that documentation commit (43564060458 green at `7b30804`). |
| 2026-11-13 (`b82fb6f`) | P0/C2 merged into the branch | Added the P0/C2 work package, refreshed the inventory (629 tests) — *historical: the commit count printed here as 25 was 27 at that head; corrected in the 2026-11-14 audit row* |
| 2026-11-13 (`e546470`) | P1.1-A merged into the branch | Added the canonical normalized-name invariant, refreshed the inventory (619 tests) — *historical: the commit count printed here as 21 was 23 at that head; corrected in the 2026-11-14 audit row* |
| 2026-11-13 (`723f101`) | P1.1 merged into the branch | Added the P1.1 work package and its runs, refreshed the inventory (615 tests, 20 commits, 225 files) |
| 2026-11-13 (`8140a4b`) | first version | Branch/PR state, the five work packages, verification matrix, code and documentation maps, blockers, ranked next steps, merge readiness |
