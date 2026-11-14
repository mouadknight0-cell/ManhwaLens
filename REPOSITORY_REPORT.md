# ManhwaLens — repository report

**Snapshot date:** 2026-11-13 · **Branch:** `arena/8a718184-manhwalens` · **PR:** [#2](https://github.com/mouadknight0-cell/ManhwaLens/pull/2) · 21 commits
**Head (last code change):** `fee9d9e371ed3db42c81413c73d4ba12204c7886` · **Base:** `main` @ `560705d` · **Status:** OPEN / MERGEABLE / CLEAN
**Latest verified run:** CI run [43561318751](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43561318751)
— `build` **success** at `e546470` (branch head, includes the P1.1-A change `fee9d9e`)

This is the repository-level view: what the branch contains, what has been verified and how, what
cannot be verified here, and what is next. Per-change detail lives in the reports indexed in
[REPORTS.md](REPORTS.md).

---

## 1. What the repository is

An Android utility (not a reader) that translates manhwa/manga/webtoon pages in place: floating
button → MediaProjection capture → ML Kit OCR (Latin) → translation (ML Kit EN→AR on device, Gemini
or LibreTranslate) → Arabic text painted over the original bubbles in an overlay. Single `:app`
module, Kotlin 2.4.21, AGP 8.13.2, Compose + Material 3, `minSdk 26`, `compileSdk`/`targetSdk` 36.

| | |
| --- | --- |
| Kotlin sources | 112 files under `app/src/main` |
| Test sources | 68 files under `app/src/test` |
| Test methods (`@Test`) | 619 |
| Branch vs `main` | 20 commits, 225 files, +35 942 lines (the PR branch carries the whole app; `main` holds the initial commit) |
| Documentation at the root | 18 Markdown files (§5) |

---

## 2. What this branch adds on top of v0.11.0

| # | Work package | Report | Base → final | CI |
| --- | --- | --- | --- | --- |
| 1 | **P0** architecture audit (read-only) | [P0_MANHWALENS_AUDIT.md](P0_MANHWALENS_AUDIT.md) | v0.11.0 `b8e37de` | — (no code changed) |
| 2 | **P0-A** Gemini name sheet missing from the primary request | [P0A_GEMINI_NAME_SHEET.md](P0A_GEMINI_NAME_SHEET.md) | `b8e37de` → `528d629` | 43514068424, 43514608722 |
| 3 | **P1** series-scoped character memory | [P1_SERIES_NAME_MEMORY.md](P1_SERIES_NAME_MEMORY.md) · design [P1_CHARACTER_MEMORY.md](P1_CHARACTER_MEMORY.md) | `528d629` → `8d5cc9e` | 43518604612, 43520783453 |
| 4 | **P1 review** — atomic writes, session cache identity, series visibility | [P1_REVIEW_CORRECTIONS.md](P1_REVIEW_CORRECTIONS.md) | `1c0c270` → `e99dd93` | 43523522645, 43524129101 |
| 5 | **P1 persistence safety** — recovery may never write the live file | [P1_PERSISTENCE_SAFETY_FIX.md](P1_PERSISTENCE_SAFETY_FIX.md) | `b960444` → `87830b7` | 43526686657, 43527227407 |
| 6 | **P1.1 strict staging validation** — a staging copy is promoted only after it is proved structurally complete; **P1.1-A** adds canonical `normalizedName` integrity (§8) | [P1_1_STRICT_STAGING_VALIDATION.md](P1_1_STRICT_STAGING_VALIDATION.md) | `8140a4b` → `723f101` → this commit | 43531030306, 43531550380, + this change |

Net: one defect fixed in the Gemini wire path, one feature (persistent, series-scoped names), and
four hardening passes over the new persistence layer (atomic writes → recovery never writes the
live file → strict structural validation before promotion → canonical normalized-name integrity).
No existing feature removed and no translation or OCR behaviour changed by the hardening passes.

---

## 3. Verification: what is real here

`./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain`

The authoring sandbox has **no JDK and no Android SDK**, so Gradle cannot run locally. The only
executed verification is GitHub Actions (`.github/workflows/android.yml`), triggered by the open pull
request; each report cites the run that validated its commit.

| Run | Commit | Result |
| --- | --- | --- |
| 43531550380 | `723f101` | success (head) |
| 43531030306 | `e6598a8` | success |
| 43530112769 | `8140a4b` | success |
| 43527227407 | `87830b7` | success |
| 43526686657 | `345715d` | success |
| 43525048064 | `b960444` | success |
| 43524129101 | `06b3d84` | success |
| 43523522645 | `e99dd93` | success |
| 43520783453 | `1c0c270` | success |

The last eight runs are green. The two earlier failures (a nullable-property smart-cast error and
four tests, one of which exposed a real cache-write-back bug) are analysed in their reports, not
hidden.

**Not verifiable in this environment** (see [BLOCKERS.md](BLOCKERS.md)): any on-device behaviour
(overlay timing, scroll riding, Live mode, battery), `lint`/`lintDebug` (offline cache gap;
`lintVitalRelease` is banned on this box by user mandate), R8/minify, and real Gemini/Libre API calls
(no key, no network). Nothing in this branch's reports claims otherwise.

---

## 4. Where the risk lives (code map)

| Area | Files | State |
| --- | --- | --- |
| `overlay/FloatingButtonService.kt` | 4.4k-line orchestrator | Untouched by this branch; the known structural risk (audit item 10) |
| `translate/` | engines + cache decorator + name pipeline | Modified: cache-aside repair path, engine name providers, `NameScript.classify` evidence |
| `names/` (new) | record, form, resolver, JSON store, series catalogue | Added by P1, hardened four times (atomic writes, no-write recovery, strict staging validation, canonical-key integrity); JVM-tested (no Android deps) |
| `data/` | prefs, caches, `CharacterMemory`/`NameSession` | Added P1 glue; the store's only Android coupling is the factory |
| `ocr/`, `render/`, `capture/` | OCR, patch painter, MediaProjection | Untouched by this branch |

---

## 5. Documentation map

| Document | Purpose |
| --- | --- |
| [REPORTS.md](REPORTS.md) | The rule ("a report travels with every change") and the index of per-change reports |
| [REPOSITORY_REPORT.md](REPOSITORY_REPORT.md) | This file — repository-level snapshot |
| [PLAN.md](PLAN.md) | Phases, definitions of done, environment notes |
| [DECISIONS.md](DECISIONS.md) | One line per decision, including the standing rules (S1–S5) and the P1/T1–T7 corrective decisions |
| [BLOCKERS.md](BLOCKERS.md) | What cannot be done in this environment, and why |
| [MANUAL_TESTS.md](MANUAL_TESTS.md) | On-device checklists (Redmi 13C, Brave) |
| `P0_*`, `P0A_*`, `P1_*`, `P1_1_*` | Per-change reports (§2) |
| [CHANGELOG.md](CHANGELOG.md), [README.md](README.md), [PRIVACY.md](PRIVACY.md), [OPEN_SOURCE_RESEARCH.md](OPEN_SOURCE_RESEARCH.md), [V096_PLAN.md](V096_PLAN.md) | User-facing changelog, product readme, privacy stance, licence research, earlier plan |

---

## 6. Open blockers (inherited, unchanged by this branch)

1. **No on-device validation** of overlay/patch/Live behaviour in this environment.
2. **Lint** unavailable offline; `lintVitalRelease` banned on this box.
3. **R8/minify** disabled here (memory); release builds are signed but unminified.
4. **Release build not produced** by this branch — only `assembleDebug` runs in CI.
5. **No real API traffic** for Gemini/Libre from here; wire format is covered by fake-response tests.

---

## 7. What is next (from the audit ranking, §10)

| Priority | Item | Status |
| --- | --- | --- |
| P0 / C1 — name sheet in the primary Gemini body | **done** (P0-A) |
| P0 / C2 — ML Kit English echo can reach the screen | **open** |
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

---

## 8. Merge readiness

- **Checks:** `build` green at `723f101` (run 43531550380); PR MERGEABLE / CLEAN.
- **What a reviewer should read first:** the five per-change reports in §2 (each is self-contained:
  evidence, fix, tests, diff, risks), then `DECISIONS.md` for the standing rules.
- **What merging does *not* buy:** device validation, a release APK, or lint coverage — all three
  remain environment blockers, unchanged by this branch.
- **Nothing is merged by the author of these changes;** the branch stops here for architectural
  approval.

---

## Report history

| Updated | Head | What changed in this snapshot |
| --- | --- | --- |
| 2026-11-13 (`e546470`) | P1.1-A merged into the branch | Added the canonical normalized-name invariant to the P1.1 entry, refreshed the inventory (619 tests, 21 commits), recorded the fourth hardening pass and the run at head |
| 2026-11-13 (`723f101`) | P1.1 merged into the branch | Added the P1.1 work package and its two runs, refreshed the inventory (615 tests, 20 commits, 225 files), noted the third hardening pass |
| 2026-11-13 (`8140a4b`) | first version | Branch/PR state, the five work packages, verification matrix, code and documentation maps, blockers, ranked next steps, merge readiness |
