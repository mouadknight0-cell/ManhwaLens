# ManhwaLens — repository report

**Report revision:** 3.0 · **Updated:** 2026-11-13 (session date; commit clock 2026-11-14 UTC)
**Branch:** `arena/8a718184-manhwalens` · **PR:** [#2](https://github.com/mouadknight0-cell/ManhwaLens/pull/2) — **OPEN**, not draft, not merged
**Base:** `main` @ `560705d55012f1549449ca3230184b4b5beda991` (the PR branch carries the whole app; `main` holds the initial commit)
**HEAD at this revision:** `2f3ad07` — *docs: record the documentation audit in the repository report*
(verified by run 43571824620; the documentation refresh that publishes this paragraph is itself commit-tip
`docs`-only and its run is **not claimed** here)
**Latest verified CI run:** [43571410420](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43571410420), workflow *Android build*,
**success** at `2f3ad07` (2026-11-14 07:35:33 → 07:39:29 UTC), steps *Unit tests* ✅ and *Build debug APK* ✅.
The audit commit `328e0a9` was verified by run 43571410420; `81919f9` by run 43570156833.

> **This is the only official engineering report in this repository.** The per-change reports that existed
> until 2026-11-13 (`REPORTS.md`, `P0_*`, `P0A_*`, `P0B_*`, `P1_*`, `P1_1_*`, `P2_TRANSLATION_CONTEXT_AUDIT.md`)
> were consolidated into this file and then deleted; §5 and §8 carry their content. No new standalone report
> is created for any change — details go into this file (§10).
>
> **What is not a report:** `PLAN.md` (product/project roadmap), `README.md` (introduction and usage),
> `DECISIONS.md` (decision log), `BLOCKERS.md` (environment register), `MANUAL_TESTS.md` (on-device
> checklist), `CHANGELOG.md` (version history as written), `PRIVACY.md`, `OPEN_SOURCE_RESEARCH.md`,
> `V096_PLAN.md` (earlier plan, kept for provenance).
>
> **Clock note:** the session date is 2026-11-13; commit timestamps run 2026-11-13 16:21 → 2026-11-14 07:20 UTC.
> A date below is the commit clock where a commit or run is cited, the session date otherwise.
> **Publication note:** the commit that publishes this revision is documentation-only; its own CI run was
> still pending when this file was written and is therefore **not claimed** here. Both commits behind it — the
> audit commit `328e0a9` (run 43571410420) and the state-record commit `2f3ad07` (run 43571824620) — **are**
> verified green.

---

## 1. Project overview and current repository state

An Android utility (not a reader) that translates manhwa/manga/webtoon pages **in place**: a floating lens
button → MediaProjection capture → on-device ML Kit OCR (Latin, bundled model) → translation → Arabic text
painted over the original bubbles in an overlay. On-device translation is the default and the product promise
(**Offline First**); a remote engine (Gemini, or LibreTranslate on a user-supplied endpoint) is an optional
*upgrade* that never delays the local draft.

| Item | Value | Verified how |
| --- | --- | --- |
| Module / language | single `:app`, Kotlin 2.4.21, AGP 8.13.2, Compose + Material 3 | `app/build.gradle.kts`, `gradle/libs.versions.toml` |
| SDKs | `minSdk 26`, `compileSdk` / `targetSdk` 36 | `app/build.gradle.kts` |
| Version | `versionName` **0.11.0** (`app/build.gradle.kts:27`) | file read |
| On-device engines | ML Kit Text Recognition v2 (Latin, bundled) · ML Kit Translate EN→AR | `translate/mlkit/` |
| Remote engines (optional) | Google `gemini-2.5-flash` over REST (`x-goog-api-key` header only) · LibreTranslate (user endpoint) | `translate/gemini/`, `translate/libre/` |
| Persistence | SharedPreferences + app-private JSON for series character memory (atomic write, recovery, strict staging validation) | `names/JsonCharacterStore.kt` |
| Dependencies added by this branch | **none** | `git diff origin/main...HEAD -- gradle *.kts` |

### 1.1 Data flow (as implemented at `81919f9`)

```
tap or Live scan
  → FrameCapture (MediaProjection, RGBA bitmap)
  → TextRecognizerClient (ML Kit OCR, ≤1600 px long edge) → BubbleGrouper (lines → bubbles)
  → PagePlan.prepare(blocks, preprocess = Glossary::prepare)   ← drops meaningless blocks, dedupes on the
      │                                                          glossary-prepared text, keeps page order
      ├─ local progressive pass : PagePlan.progressiveOrder(...) → ML Kit   (paint first, visual priority)
      └─ optional remote relay  : PagePlan.remoteOrder(...)      → Gemini / LibreTranslate (page order)
  → ArabicValidator (rejects English echoes / unusable Arabic; runs for remote engines AND `ml_kit_en_ar`)
  → CachingTranslator (memory LRU + disk cache + fuzzy memo, engine-namespaced, name-aware repair)
  → BubblePainter (Cairo, RTL, auto-fit) over the original bubbles
```

### 1.2 Name pipeline (three cooperating layers)

| Layer | Object | Job |
| --- | --- | --- |
| Static + user dictionary | `Glossary` (`translate/Glossary.kt`) | resolves known names/terms to Arabic *before* the engine sees the text; user Settings entries are explicit pins and always win |
| Page plan | `NameMemory` / `SceneNames` (`translate/`) | picks one spelling per page and pins it into the source text; builds the model name sheet |
| Series memory | `names/` (`CharacterRecord`, `CharacterResolver`, `JsonCharacterStore`, `SeriesCatalog`) + `data/CharacterMemory` | reader-confirmed spellings, aliases, per-series isolation, survives restarts |

### 1.3 Repository inventory at this revision

| Metric | Value | Command |
| --- | --- | --- |
| Commits ahead of `origin/main` | **38** | `git rev-list --count origin/main..HEAD` |
| Branch diff vs `main` | `220 files changed, 35 764 insertions(+)` (all files are new on the branch, so 0 deletions vs `main`) | `git diff --shortstat origin/main...HEAD` |
| Kotlin sources | **113** files under `app/src/main` | `find app/src/main -name '*.kt' \| wc -l` |
| Test sources | **70** files under `app/src/test` (one JVM source set; no instrumented source set) | `find app/src/test -name '*.kt' \| wc -l` |
| Test methods (`@Test`) | **644** | `grep -rn '@Test' app/src/test \| wc -l` |
| Test distribution | `translate` 246, `live` 118, `names` 82, `ocr` 79, `render` 46, `util` 37, `data` 22, `overlay` 14 | `grep -rc '@Test'` per package |
| Root Markdown files | **10** (§1.4) | `ls *.md` |
| Last commit that changed behaviour | `a7b276f` (P2-A test expectation fix); the behaviour change itself is `25b15c2`; before that `c008f62` (ML Kit echo fix) | `git log` |

### 1.4 Root documentation set (10 files)

| File | Role | Status |
| --- | --- | --- |
| `REPOSITORY_REPORT.md` | **the** official engineering report (this file) | maintained |
| `PLAN.md` | product/project roadmap, milestones M0–M6, definitions of done | kept, not a report |
| `README.md` | introduction, build, privacy summary | kept |
| `DECISIONS.md` | decision log (D, S, T, U, V, W series) | kept |
| `BLOCKERS.md` | what cannot be done in this environment | kept |
| `MANUAL_TESTS.md` | on-device checklist (Redmi 13C, Brave) | kept |
| `CHANGELOG.md` | version history as written | kept |
| `PRIVACY.md` | privacy stance | kept |
| `OPEN_SOURCE_RESEARCH.md` | licence/suitability research | kept |
| `V096_PLAN.md` | earlier v0.9.6 plan (not started), kept for provenance; header note states its `P1`–`P8` task labels are **not** the P0–P6 phases | kept |

---

## 2. Verified current branch and HEAD commit

### 2.1 Verification performed for this revision

| Check | Command | Result |
| --- | --- | --- |
| Branch | `git rev-parse --abbrev-ref HEAD` | `arena/8a718184-manhwalens` |
| HEAD | `git log -1 --format='%H %s'` | `2f3ad072379ee914c1553a05c5eb8cb47ddd79d6` — *docs: record the documentation audit in the repository report* |
| Working tree | `git status --porcelain` | empty (clean) before this revision's edit |
| Remote sync | `git fetch origin && git status -sb` | in sync with `origin/arena/8a718184-manhwalens` |
| PR #2 | `gh pr view 2 --json …` | `OPEN`, `isDraft=false`, `headRefName=arena/8a718184-manhwalens`, `headRefOid=81919f9…`, `baseRefName=main`, `mergeStateStatus=CLEAN`, `reviewDecision` empty (0 reviews), 220 files, +35 764/−0 |
| Workflow | `.github/workflows/android.yml` | one workflow, *Android build*, events `pull_request` (all), `push` to `main`/`master`/`arena/cebceb7a-manhwalens` and `v*` tags, `workflow_dispatch`; one job `build` |
| Runs on this branch | `gh run list --branch … --limit 100` | **33 runs: 29 success, 4 failure** (§7.4); newest `43571824620` at `2f3ad07` ✅ |

### 2.2 Commits this revision describes

| Commit | Kind | What |
| --- | --- | --- |
| `01e12a6` | docs | P2 audit delivered (the audit file was later consolidated into §5.8/§8) |
| `533ae90` | docs | P2 audit CI run recorded — the baseline of the P2-A round |
| `25b15c2` | **code + tests** | P2-A: P2-01 (glossary false positives) and P2-02 (remote block order) |
| `a7b276f` | **test fix** | one wrong expectation in the new glossary test (§5.9) |
| `94cf2f5` | docs | report revision 2.0: single report, superseded reports deleted, one doc-pointer comment |
| `81919f9` | docs | run 43569844274 recorded |
| `328e0a9` | docs | **this audit**: canonical correction of the phase definitions, status vocabulary, restructure to the ten mandated sections, evidence re-verification, `V096_PLAN.md` label note, `DECISIONS.md` S1/S5 + D9–D11 |

### 2.3 Verification limits (stated, not papered over)

* **Executed / passed / skipped test counts** for a **green** run are **unverifiable** here: green runs emit no
  annotations, and both the job log and the `test-reports` artifact live on `*.blob.core.windows.net`, outside
  this sandbox's network allowlist (`gh run view --log` → `EOF`; `gh run download` → `EOF`).
  Reachable: step conclusions and, for **failing** runs, the workflow's annotation lines — which for this branch
  include the Gradle summary (`"N tests completed, M failed"`) and the failing test names (§7.6).
* **No device exists** in this environment, so every device-level claim is absent by construction (§7.7).
* PR #2's **title** still reads *"P0: Gemini name sheet fix (P0-A) + architecture audit"* although the branch
  now carries P0–P2 work. It is a stale label; the PR was **not** renamed or otherwise touched by this task
  (scope rule §9.3), and it is recorded here as a documentation discrepancy.
* **Conflicting P-numbering found and resolved by labelling:** `V096_PLAN.md` uses `P1`…`P8` for v0.9.6 task
  items (owned windows, opaque patches, UI chrome, Gemini default, …) with the status "NOT STARTED". Those
  are plan steps, not engineering phases. The file was **kept** (provenance) and given a header note stating
  that its numbers are not the P0–P6 of §3. No other document uses P-numbers; `DECISIONS.md` uses `P1`,
  `P1.1`, `P1.1-A` as sub-labels of phase **P1**, which agrees with §3.

---

## 3. Engineering roadmap P0–P6

### 3.1 The phases (fixed definitions)

These are **engineering phases** on this branch. They are *not* the product milestones M0–M6 (§4) and must
never be presented as such.

| Phase | Definition | What belongs to it |
| --- | --- | --- |
| **P0** | Initial architecture audit and foundational fixes | the read-only audit of v0.11.0 and the foundational defects it found and that were fixed: C1 (Gemini name sheet not on the primary request) and C2 (ML Kit English echo could reach the screen) |
| **P1** | Series-specific character-name memory and persistence safety | `names/` package, series catalogue and selection, resolver ladder, atomic JSON store, recovery that never writes the live file, strict staging validation, canonical `normalizedName` integrity |
| **P2** | Translation quality and context management | the translation-path audit (P2-01…P2-16) and its staged fixes: **P2-A** = P2-01 + P2-02; **P2-B** = P2-03 + P2-04; later sub-steps P2-C/P2-D for the remaining findings |
| **P3** | Performance and caching | translation cache/memo behaviour and latency work (e.g. audit finding P2-10 context-free cache keys, the audit's render/measure perf items) — **no work executed under this phase** |
| **P4** | OCR improvements | OCR accuracy/segmentation work (e.g. the product-roadmap items for `zh`/`ja` sources and bubble auto-detection) — **no work executed under this phase** |
| **P5** | Text cleanup and Arabic rendering | validator/polish/RTL and Arabic typography work (audit P2-14, P2-15, the RTL finding) — **no work executed under this phase** |
| **P6** | Final integration and release | release build (R8/minify), signed release, store assets, final integration passes — **no work executed under this phase** |

> Phase labels P3–P6 are **placeholders for future work**, not claims of progress. Anything in the app that
> already looks related (the v0.11.0 cache tiers, OCR downscaling, M4 Arabic rendering) was delivered as
> **product milestone work before these phase labels existed** and is credited to its milestone in §4, not to
> P3–P6.

### 3.2 Status vocabulary (used strictly, per phase and per task)

| Term | Meaning | Evidence required |
| --- | --- | --- |
| **Proposed** | written down as a candidate, nothing done | the proposal text |
| **Audited** | read and analysed, no code changed | audit commit |
| **Implemented** | code exists on the branch | commit + file contents |
| **Tested** | tests exist that exercise it | test sources |
| **CI-verified** | a green GitHub Actions run executed those tests on a commit that is an ancestor of this HEAD | run id + commit |
| **Device-verified** | actually exercised on a real Android device | device evidence (none exists in this repository) |
| **Completed** | every level above holds, including device verification | — |

**Consequence, applied without exception:** because no device test has ever been performed for this branch,
**no phase and no task in this repository is "Completed"**, and this report does not use the word for any
phase. "CI-verified" means *the JVM suites and the debug build pass*, nothing more.

### 3.3 Phase status matrix (evidence in §5–§7)

| Phase | Proposed | Audited | Implemented | Tested | CI-verified | Device-verified | Completed |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **P0** | — | ✅ `0e6d5ff` | ✅ P0-A `fba87c6`/`1940487`; C2 `c008f62` | ✅ 8 + 10 tests | ✅ 43514608722, 43563394305…43564884208 | ❌ | ❌ (open items C3/C4/C5, RTL, offline, perf) |
| **P1** | — | ✅ design + review | ✅ `6a71f77`…`fee9d9e` | ✅ +102 tests (517→619 cumulative: 73 + 9 + 5 + 11 + 4) | ✅ 43518604612…43561668957 | ❌ | ❌ (series UI never exercised on a device) |
| **P2** | ✅ P2-B…P2-D | ✅ `01e12a6` | ◐ **P2-A only** `25b15c2` | ◐ 15 new tests | ◐ 43569302435 (`a7b276f`) | ❌ | ❌ (P2-A awaiting review; P2-03/04 open) |
| **P3** | ✅ candidates only | — | ❌ | ❌ | ❌ | ❌ | ❌ |
| **P4** | ✅ candidates only | — | ❌ | ❌ | ❌ | ❌ | ❌ |
| **P5** | ✅ candidates only | — | ❌ | ❌ | ❌ | ❌ | ❌ |
| **P6** | ✅ candidates only | — | ❌ | ❌ | ❌ | ❌ | ❌ |

Legend: ✅ yes with evidence · ◐ partially · ❌ no.

---

## 4. Product milestone reference M0–M6 (separate from the phases)

The milestones live in `PLAN.md` §5 and are **unchanged by this report**. They describe product increments
that were delivered *before* this branch's engineering phases existed; their "DONE" wording is
`PLAN.md`'s, and none of them is device-verified.

| Milestone | Scope | Status in `PLAN.md` | Verification reality |
| --- | --- | --- | --- |
| **M0** | Project scaffold (Gradle, Compose placeholder, theme, icon) | DONE (2026-11-11) | build green |
| **M1** | Overlay foundation (floating button, drag, snap, permissions, FGS) | DONE (2026-11-11) | build green; **no device verification** |
| **M2a** | Capture + debug view (MediaProjection, consent trampoline, FLAG_SECURE heuristic) | DONE (2026-11-11) | build green; **no device verification** |
| **M2b** | OCR + bubble grouping (ML Kit Latin, union-find grouper, four guards) | DONE (2026-11-11) | 10 JVM tests; **no device verification** |
| **M3** | Translation engines + batching + cache + settings | DONE (2026-11-11) | 45 JVM tests; **no device verification** |
| **M4** | In-place Arabic bubble overlay (colour sampling, Cairo, auto-fit, RTL) | DONE for the overlay-rendering scope (2026-11-11) | 63 JVM tests; Room cache, download UI and failure UX still open |
| **M5** | Speed + settings + privacy + signed release (v0.7.0) | DONE build-side (2026-11-11) | R8 could not run on the authoring box; no signed release produced here |
| **M6** | Post-launch: vertical JP/ZH text, auto bubble detection, horizontal-strip mode, accessibility hooks | not started | — |

Between M5 and this branch, `PLAN.md` continued with ad-hoc part/hotfix sections (v0.7.1 crash fixes,
Part A–C, pipeline work A–D, v0.8.0, v0.9.0 Live + progressive translation, v0.9.1 Safe Mode, v0.9.2
wrong-thread fix, v0.9.3 in-place patch rework) and reached **v0.11.0** (`b8e37de`), the baseline of this
branch.

### 4.1 The distinction, stated once

* A **milestone** is a *product* increment: "the overlay renders Arabic bubbles".
* A **phase** is an *engineering* work package: "audit the architecture", "make names persistent per series",
  "fix translation-context defects".
* A milestone can be closed (build-side) while the phases that harden it are still running, and a phase can be
  CI-verified while the milestone is still device-unverified. **M-numbers are not P-numbers.** No document in
  this repository may re-label M0–M6 as P0–P6 or vice versa; `PLAN.md` owns M, this report owns P.

---

## 5. Historical engineering progress and important decisions

Condensed from the deleted per-change reports; nothing was dropped silently (§5.10). Diff sizes were
re-derived for this revision as `git diff --shortstat <base> <final> -- app` (code + tests only); where an
earlier report quoted a larger figure it had counted the Markdown report shipped in the same push, and the
verified number is used here.

### 5.1 P0 — architecture audit (2026-11-13, `0e6d5ff`)

Read-only audit of v0.11.0 (`b8e37de`): ten numbered findings (C1–C5, RTL, offline readiness, performance,
`FloatingButtonService` seams) with file:line evidence plus a ranked plan. No code, no tests. Still-open items:
C3 (`pageNames` across pages), C4 (AI cleanup on the live thread), C5 (`NameMemory.plan` duplication), RTL,
offline readiness, performance. Its C2 line references were corrected in place when C2 was fixed
(220→246, 42-43→51-52, 3841→3844).

### 5.2 P0 — P0-A: Gemini name sheet on the primary request (`fba87c6`, `1940487`, docs `528d629`)

**Defect (C1).** The production default is `jsonReply = false`, so `translateChunk` sent the *legacy* body,
whose builder took no name sheet; page-local names were enforced only on the HTTP-400 fast-retry body.
**Fix.** `GeminiWire.buildRequestBody(blocks, thinkingLevel, nameSheet)` is the single path; both router
branches carry the sheet; no prompt/temperature/thinking change. **Files:** `translate/gemini/GeminiWire.kt`,
`GeminiTranslator.kt` + tests — **4 files, +195/−5** in `app/` (`git diff --shortstat b8e37de 528d629 -- app`).
**Tests:** 8 new (5 wire-level, 3 translator-level). *Earlier reports quoted `6 files, +692/−5`, which counted
the Markdown report of the same push; the code+test figure is given here.*
**CI:** 43513473811 ❌ — verified annotation: *517 tests completed, 1 failed ·
`GeminiTranslatorTest > primaryJsonRequestCarriesThePageNameSheet FAILED`* (the test asserted `responseMimeType`
on the primary body, which is always legacy) → 43514068424 ✅ (`1940487`) → 43514608722 ✅ (`528d629`).
**Open risk:** no real Gemini traffic from here; the wire format is covered by fake responses only.

### 5.3 P1 — series-scoped character memory (`6a71f77` → `1686e72` → `8d5cc9e`)

**Problem.** v0.11.0 pinned names **per page** and forgot them, so the same character drifted between bubbles,
pages and restarts; the glossary is global and does not survive a cache hit.
**Solution.** `names/`: `CharacterRecord` (original name, Arabic, script, aliases, series, confidence),
`CharacterResolver` (7-step ladder: locked → user → built-in → trusted memory → page model → transliteration →
keep Latin), `CharacterStore` + `JsonCharacterStore` (atomic JSON file, no new dependency),
`SeriesCatalog` + `data/SeriesPrefs`, `data/CharacterMemory`/`NameSession`; `TranslatorFactory` builds one
session per translator and feeds ML Kit (table), Gemini (sheet) and `CachingTranslator` (pinned override), so
both engines and the cache agree by construction. **Design decision D1:** memory is scoped by an
**explicitly selected series**, never by package name. **Files:** **26, +3 176/−52** in `app/` (`git diff --shortstat 528d629 8d5cc9e -- app`). **Tests:** +73 (517→590).
**CI:** 43517478032 ❌ — `:app:compileDebugKotlin` failed with *"Only safe (?.) or non-null asserted (!!.)
calls are allowed on a nullable receiver of type 'CharacterStore?'"* at `CharacterResolver.kt:105,242,264` and
`data/CharacterMemory.kt:91` → 43517986742 ❌ — verified annotation: *590 tests completed, 4 failed*:
`CachingNameOverrideTest > the repair is written back so the next capture is already correct`,
`NameFormTest > replaceArabic repairs a superseded spelling`,
`CharacterResolverTest > a locked record supplies the spelling and says so`,
`NameSessionTest > a session uses the series memory and stores new names` (the first exposed a real bug: a
repaired plain memory cache hit was never written back) → 43518604612 ✅ → docs 43520783453 ✅.

### 5.4 P1 — review corrections (`e99dd93`)

Three verified defects in the new persistence layer, fixed minimally: (a) `saveLocked` fell back to an
in-place `writeText` when `renameTo` failed and never flushed the staging file → `writeDurably()`
(flush + `fd.sync()`) + `commitAtomically()` (`Files.move ATOMIC_MOVE`, `renameTo` fallback) + `discard()`;
(b) session cache identity ignored the series → key carries it; (c) series visibility/refresh in the settings
surface. 7 files, +269/−16, +9 tests. **CI:** 43523522645 ✅ (+docs 43524129101).

### 5.5 P1 — persistence safety (`87830b7`)

**Defect.** `recoverInterruptedWrite()` promoted a staged file, and when promotion failed fell back to
`staging.copyTo(file, overwrite = true)` — an in-place truncate-and-rewrite of the **live** file. A kill or a
full disk in that window destroyed the reader's memory.
**Fix.** Recovery may never write the live file: on failure it keeps the current file, reports the problem and
discards the staged copy. **3 files, +213/−63** in `app/`, +5 tests. **CI:** 43526686657 ✅ (+docs 43527227407).

### 5.6 P1 — strict staging validation, P1.1 + P1.1-A (`723f101`, `fee9d9e`)

**Failure mode.** Promotion only required "valid JSON", then the tolerant live-file reader silently skipped
what it could not use — so a document with no `series` object, a wrong-typed series, or a record under the
wrong series key could replace a good file and load as empty.
**Fix.** `validateStagingDocument()` proves structural completeness (version, series object, record identity,
`seriesId` match) **before** promotion; P1.1-A adds canonical `normalizedName` integrity (a record whose key is
not `NameForm.normalize(originalName)` is rejected — `JsonCharacterStore.kt:283-345`). **2 files +286/−12**
(P1.1) and **2 files +95/−2** (P1.1-A) in `app/`; +11 and +4 tests. **CI:** 43531030306 ✅ → 43531550380 ✅ → 43561318751 ✅ (+docs
43558741498, 43561668957).

### 5.7 P0 — C2: ML Kit English echo (`c008f62`)

**Defect (C2).** Four doors let untranslated English reach the screen as Arabic: (1) page assembly
substituted the source when ML Kit answered nothing; (2) the progressive callback painted every finished block
before the page guard; (3) the quality guard was scoped to remote engines only (`CachingTranslator`), so ML Kit
output was cached and re-served; (4) the painter only refused blank text.
**Fix.** `MlKitPageAssembly.ordered()` (a missing answer is empty, never the source); guard now covers
`ml_kit_en_ar` (`CachingTranslator.kt:58`); guarded progressive path; painter-boundary refusal.
**5 files, +316/−8** in `app/`, +10 tests (`MlKitEnglishEchoTest`, new). Progressive translation preserved; a rejected
bubble stays **empty** (no source substitution, no retry loop). **CI:** 43563394305 ✅ (+docs 43563800508,
43564060458, 43564884208).

### 5.8 P2 — translation quality and context audit (read-only, `01e12a6`)

Audit of the translation path at `fe5aa6a`: data-flow trace, **4 confirmed bugs** (P2-01…P2-04),
**9 verified risks** (P2-05…P2-13), **3 improvements** (P2-14…P2-16), **5 investigated-correct** items
(P2-N1…N5), coverage/gaps, and a staged roadmap P2-A…P2-D. No code, no tests. **CI:** 43567398304 ✅
(+docs 43567644013).

### 5.9 P2 — P2-A implementation (`25b15c2`, test fix `a7b276f`)

**Scope authorised:** P2-01 and P2-02 only.

**P2-01 — glossary false positives.** At baseline `533ae90`, `Glossary.applyCompiled` replaced every
whole-word table hit case-insensitively; the only filter was `ambiguousShort` (≤3 letters *and* in a small
stop-word list), so `long`, `song`, `sun`, `pan`, `tan`, `ding`, `tang`, `fang`, `ma`, `mo`, `ye`, `li`, `ren`,
`tao`, `dong`, `wang` were swapped for Arabic **before any engine saw the text**.
**Fix.** `Glossary.accepts` gates every replacement: (1) a Settings entry is an explicit pin → always wins;
(2) multi-word/hyphenated keys are unambiguous → always replaced; (3) single-word genre terms (`TERMS`: sect,
core, qi, cultivation, dantian, pill, demon, immortal, talisman, shidi/shifu/shijie/shimei/shixiong) are the
table's subject matter → replaced in any case, as before; (4) any other single word must be capitalised and not
ALL CAPS; (5) its lower-case form must not be an ordinary English word (`AMBIGUOUS_ENGLISH` = the stop-word
list plus the colliding keys). Sentence position is deliberately unused.
**Trade-off (documented in code and §8.4).** A character literally named `Long`/`Song`/`Sun`/`Pan`/`Tan`/`Ding`
keeps the Latin spelling on the glossary path; the pin, a two-word spelling and the series name memory still
convert it, and the table is unchanged so `Glossary.effective()` still offers every name.

**P2-02 — remote block order.** The tap path built one list sorted by visual priority (`short → biggest → most
central`, `FloatingButtonService.kt:2778-2779` at baseline) and gave it to the relay, which chunked it
sequentially while the prompt states *"Consecutive blocks belong to the same page"* (`GeminiWire.kt:128`).
**Fix.** `PagePlan.remoteOrder()` = page/reading order (relay + every `create(...)` call that can resolve to a
remote engine); `PagePlan.progressiveOrder(plan, rankById)` = visual priority (progressive ML Kit, `createOffline`,
ML Kit fallback). Live path untouched (it already used `engineBlocks`, i.e. page order).

**Files (5, +348/−13):** `translate/Glossary.kt`, `translate/PagePlan.kt`, `overlay/FloatingButtonService.kt`,
`translate/GlossaryTest.kt` (+8 tests), `translate/TranslationOrderingTest.kt` (new, 7 tests).
**Tests:** 15 new (629 → 644 discovered), listed in §6.4.
**CI:** 43568984747 ❌ at `25b15c2` — verified annotation: *644 tests completed, 1 failed ·
`GlossaryTest > shortCollisionsStayEnglish FAILED`* (the expectation ignored that the term `sect` in the same
bubble legitimately translates) → fixed in `a7b276f` → **43569302435 ✅**.
**Status:** **Implemented, tested, CI-verified — awaiting review. Not "completed": no review approval, no
device verification, and P2-03/P2-04 remain open.**

### 5.10 Documentation consolidation and decisions

**Consolidation (2026-11-13, `94cf2f5`).** `REPORTS.md` and the nine per-change reports were folded into this
file and deleted: `REPORTS.md`, `P0_MANHWALENS_AUDIT.md`, `P0A_GEMINI_NAME_SHEET.md`,
`P0B_MLKIT_ENGLISH_ECHO.md`, `P1_CHARACTER_MEMORY.md`, `P1_SERIES_NAME_MEMORY.md`,
`P1_REVIEW_CORRECTIONS.md`, `P1_PERSISTENCE_SAFETY_FIX.md`, `P1_1_STRICT_STAGING_VALIDATION.md`,
`P2_TRANSLATION_CONTEXT_AUDIT.md`. Every local Markdown link was re-checked afterwards; the one source
comment that pointed at a deleted note (`names/JsonCharacterStore.kt:20`) was repointed at this report
(comment text only). `DECISIONS.md` gained **D8** (one report, D8 list of deletions) and S1/S5/S6 were rewritten.

**Decisions that still govern this area** (full log: `DECISIONS.md`):

| ID | Decision |
| --- | --- |
| D1 | Character memory is scoped by an **explicitly selected series**, never by the reader package name; no auto-detection |
| D2 | No Room and no new Gradle dependency: the store is a JSON file written atomically |
| D3 | A locked record is final; automatic detection can never displace a reader's decision |
| D4 | Offline First: the remote engine is an upgrade that never delays or blocks the local draft |
| D5 | A rejected translation is **empty**, never the English source (P0/C2 rule) |
| D6 | P2-A trade-off: an ambiguous one-word name is not auto-replaced; the pin and the memory remain authoritative |
| D7 | Two named orders (page order for remote, visual priority for the local draft) — no "one order" callsite |
| D8 | Exactly one authoritative report (this file); per-change reports are not created again |

---

## 6. Verified implementation status for each phase

### 6.1 What "verified" means in this section

Every ✅ below was re-checked for this revision by reading the file at `81919f9`, running the command shown or
reading the run's step conclusions from the GitHub API. Nothing is marked ✅ because an earlier report said so.
Levels follow §3.2; **no phase reaches "Completed"** because device verification has never happened (§7.7).

### 6.2 P0 — architecture audit and foundational fixes

| Item | Level reached | Evidence at `81919f9` |
| --- | --- | --- |
| Architecture audit of v0.11.0 | **Audited** (CI-verified as a docs commit) | `0e6d5ff`, run 43513473811 ❌ (docs run failed because a test in the same push was wrong) → corrected in `1940487`/`528d629`; audit content preserved in §5.1 |
| C1 — name sheet on the primary Gemini request | Implemented · Tested · CI-verified | `GeminiWire.kt:187-206` (3-arg `buildRequestBody`) and `:223-226` (retry body) both take `nameSheet`; `GeminiTranslator.kt:232` passes it; 8 tests; run 43514608722 ✅ |
| C2 — ML Kit English echo | Implemented · Tested · CI-verified | `translate/MlKitPageAssembly.kt`; `CachingTranslator.kt:58` guard covers `ml_kit_en_ar`; `MlKitEnglishEchoTest`; runs 43563394305…43564884208 ✅ |
| C3 — `pageNames` across pages | **Open** (Proposed fix only) | §8.3 |
| C4 — AI cleanup on the live thread | **Open** | §8.3 |
| C5 — `NameMemory.plan` duplication | **Open** | §8.3 |
| RTL correctness, offline-readiness step, render performance | **Open** | §8.3 |

### 6.3 P1 — series name memory and persistence safety

| Item | Level reached | Evidence at `81919f9` |
| --- | --- | --- |
| `names/` model, resolver ladder, store, catalogue | Implemented · Tested · CI-verified | `names/CharacterRecord.kt`, `CharacterResolver.kt`, `CharacterStore.kt`, `JsonCharacterStore.kt`, `NameForm.kt`, `SeriesCatalog.kt`; 82 `names` tests; run 43518604612 ✅ |
| Series selection scoping (D1) | Implemented · Tested · CI-verified | `data/SeriesPrefs.kt`, `SeriesCatalogTest`; **not device-verified** |
| Atomic write + recovery never writing the live file | Implemented · Tested · CI-verified | `JsonCharacterStore.kt:464` (`writeDurably`), atomic move + discard path; `JsonCharacterStoreTest`; run 43526686657 ✅ |
| Strict staging validation (P1.1) | Implemented · Tested · CI-verified | `validateStagingDocument()` (`JsonCharacterStore.kt:283-345`); run 43531550380 ✅ |
| Canonical `normalizedName` integrity (P1.1-A) | Implemented · Tested · CI-verified | `JsonCharacterStore.kt:334-345`; run 43561318751 ✅ |
| Cache repair before the guard (pinned override) | Implemented · Tested · CI-verified | `CachingTranslator.kt` pinned override + `CachingNameOverrideTest`; run 43518604612 ✅ |
| Settings → Character names UI | Implemented · CI-verified (compiles, no JVM test of the screen) | settings screen; **never exercised on a device** |

### 6.4 P2 — translation quality and context management

| Item | Level reached | Evidence at `81919f9` |
| --- | --- | --- |
| Translation-path audit (P2-01…P2-16) | **Audited** | `01e12a6`, run 43567398304 ✅; content in §5.8 and §8 |
| **P2-A / P2-01** — glossary no longer rewrites ordinary English | Implemented · Tested · CI-verified · **awaiting review** | `Glossary.accepts` (`Glossary.kt:140-150`), `TERMS` (`:338`), `AMBIGUOUS_ENGLISH` (`:350`); 8 new tests in `GlossaryTest`; run 43569302435 ✅ |
| **P2-A / P2-02** — remote engines get page/reading order | Implemented · Tested · CI-verified · **awaiting review** | `PagePlan.remoteOrder` (`:130`), `PagePlan.progressiveOrder` (`:138`); `FloatingButtonService.kt:2784-2785, 2819, 2848, 2858, 2863`; 7 new tests in `TranslationOrderingTest`; run 43569302435 ✅ |
| **P2-B / P2-03** — `jsonReply` never reaches the primary request | **Proposed** (defect present, verified below) | `GeminiTranslator.kt:232` still hardcodes `jsonReply = false`; only the 400-retry body uses the field (`:250`) |
| **P2-B / P2-04** — positional reply parsing can misalign bubbles | **Proposed** (defect present, verified below) | `GeminiWire.kt:325` still accepts `lines.size >= expectedIds.size` |
| P2-C (P2-05 name authority) | **Proposed** | `GeminiTranslator.kt:87-99` vs `:128-136` |
| P2-D (P2-06, P2-08, P2-14, P2-16) | **Proposed** | §8.2 |

The 15 tests added by P2-A (all present at `81919f9`):
`GlossaryTest` — `ordinaryLongAndSunStayEnglish`, `sentenceInitialLongAndSunAreStillOrdinaryWords`,
`shortCollisionsStayEnglish`, `termsStillTranslateInAnyCase`, `genuineNamesStillTranslate`,
`aPinnedNameIsAlwaysHonoured`, `thePinIsScopedToTheCurrentSettings`, `theTableStillCarriesTheNameForTheMemory`;
`TranslationOrderingTest` — `remoteOrderIsPageOrder`, `visualPriorityOrderIsPreservedForTheLocalPass`,
`unrankedBlocksGoLastInVisualPriorityOrder`, `remoteChunksKeepThePageSequenceAcrossBoundaries`,
`visualPriorityOrderWouldScrambleTheChunks`, `dedupeDoesNotDisturbTheRemoteOrder`,
`theLivePathStillUsesPageOrder`.

### 6.5 P3–P6 — performance/caching, OCR, text cleanup/rendering, release

**Nothing has been executed under these phases.** They exist as definitions (§3.1) and as candidate work in
§8.3. No commit, test, run or file in this repository belongs to them. Reporting them as "done" — or
attributing pre-existing v0.11.0 features to them — would be invented progress.

### 6.6 Summary

| Phase | Highest level reached | Blocking what remains |
| --- | --- | --- |
| P0 | CI-verified (C1, C2) | C3/C4/C5, RTL, offline, perf; device validation |
| P1 | CI-verified | device validation of the series UI |
| P2 | CI-verified for P2-A only | review approval, P2-B…P2-D, device validation |
| P3–P6 | Proposed only | not started |

---

## 7. Tests and CI evidence

### 7.1 Command

```
./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain
```

Workflow `.github/workflows/android.yml` (*Android build*, job `build`, event `pull_request`): JDK 17 (temurin),
Android SDK `platforms;android-36` + `build-tools;35.0.0`, then *Unit tests*, *Build debug APK*, *Rename APK*,
*Upload APK artifact*, *Upload test reports* (always). The workflow echoes failing test lines as annotations
when the unit-test task fails — that is the only per-test information reachable from here.

### 7.2 Environment limits (this sandbox)

`java`, `javac`, `gradle`, `kotlinc` are **not installed** and `ANDROID_HOME` is **unset**, so Gradle cannot run
locally; `lint` is unavailable offline; R8 is disabled on the authoring box; only `assembleDebug` is produced.
Nothing in this report claims a local build, and no test count below is claimed as "executed".

### 7.3 Test-count provenance (`git grep -c '@Test' <sha> -- app/src/test`, summed — re-run for this revision)

| Commit | Phase boundary | `@Test` discovered | Test files |
| --- | --- | --- | --- |
| `b8e37de` | v0.11.0 baseline | 509 | 59 |
| `528d629` | after P0-A (+8) | 517 | 59 |
| `8d5cc9e` | after P1 (+73) | 590 | 68 |
| `e99dd93` | after P1 review (+9) | 599 | 68 |
| `87830b7` | after P1 persistence safety (+5) | 604 | 68 |
| `723f101` | after P1.1 (+11) | 615 | 68 |
| `fee9d9e` | after P1.1-A (+4) | 619 | 68 |
| `3674ebf` | after C2 (+10) | 629 | 69 |
| `a7b276f` / `81919f9` | after P2-A (+15) | **644** | 70 |

**Discovered vs executed:** discovered = **644** (verified at `a7b276f`/`81919f9`). Executed = **644 at
`25b15c2`**, verified from that run's annotation (*"644 tests completed, 1 failed"*) — the same suite size as
this HEAD, whose green run (43569302435 at `a7b276f`) therefore executed the same 644 with **0 failures**.
Earlier boundaries have the same kind of proof where a run failed (517 at `0e6d5ff`, 590 at `1686e72`); for
green-only commits the executed number is **UNVERIFIED**. **Skipped = UNVERIFIED everywhere** (a skipped test
does not fail the task, and no annotation reports skips).

### 7.4 Every CI run on this branch (33 runs: 29 success, 4 failure)

| Run | Commit | Created (UTC) | Result |
| --- | --- | --- | --- |
| [43571824620](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43571824620) | `2f3ad07` | 2026-11-14 07:35:33 → 07:39:29 | ✅ **current head** |
| [43571410420](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43571410420) | `328e0a9` | 2026-11-14 07:30:30 → 07:34:42 | ✅ audit commit |
| [43570156833](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43570156833) | `81919f9` | 2026-11-14 07:14:31 | ✅ |
| [43569844274](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43569844274) | `94cf2f5` | 2026-11-14 07:10:40 | ✅ |
| [43569302435](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43569302435) | `a7b276f` | 2026-11-14 07:03:52 | ✅ **P2-A verified** |
| [43568984747](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43568984747) | `25b15c2` | 2026-11-14 07:00:03 | ❌ test expectation (§5.9) |
| [43567644013](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43567644013) | `533ae90` | 2026-11-14 06:42:21 | ✅ |
| [43567398304](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43567398304) | `01e12a6` | 2026-11-14 06:39:22 | ✅ |
| [43566212238](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43566212238) | `fe5aa6a` | 2026-11-14 06:24:51 | ✅ |
| [43565915093](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43565915093) | `c528ca2` | 2026-11-14 06:21:12 | ✅ |
| [43565553808](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43565553808) | `05fa1ab` | 2026-11-14 06:16:49 | ✅ |
| [43564884208](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43564884208) | `3674ebf` | 2026-11-14 06:08:47 | ✅ |
| [43564060458](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43564060458) | `7b30804` | 2026-11-14 05:58:39 | ✅ |
| [43563800508](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43563800508) | `b82fb6f` | 2026-11-14 05:55:07 | ✅ |
| [43563394305](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43563394305) | `08004c4` | 2026-11-14 05:49:49 | ✅ C2 code |
| [43561668957](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43561668957) | `6eb8974` | 2026-11-14 05:28:10 | ✅ |
| [43561318751](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43561318751) | `e546470` | 2026-11-14 05:23:40 | ✅ P1.1-A |
| [43558741498](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43558741498) | `feb20ef` | 2026-11-14 04:52:04 | ✅ |
| [43531550380](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43531550380) | `723f101` | 2026-11-14 00:03:28 | ✅ P1.1 |
| [43531030306](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43531030306) | `e6598a8` | 2026-11-13 23:59:05 | ✅ |
| [43530112769](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43530112769) | `8140a4b` | 2026-11-13 23:50:54 | ✅ |
| [43527227407](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43527227407) | `87830b7` | 2026-11-13 23:27:17 | ✅ |
| [43526686657](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43526686657) | `345715d` | 2026-11-13 23:22:56 | ✅ persistence safety |
| [43525048064](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43525048064) | `b960444` | 2026-11-13 23:10:10 | ✅ rules push |
| [43524129101](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43524129101) | `06b3d84` | 2026-11-13 23:03:05 | ✅ |
| [43523522645](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43523522645) | `e99dd93` | 2026-11-13 22:58:21 | ✅ P1 review |
| [43520783453](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43520783453) | `1c0c270` | 2026-11-13 22:37:24 | ✅ |
| [43518604612](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43518604612) | `8d5cc9e` | 2026-11-13 22:21:26 | ✅ P1 code |
| [43517986742](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43517986742) | `1686e72` | 2026-11-13 22:17:00 | ❌ 4 failed tests (§5.3) |
| [43517478032](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43517478032) | `6a71f77` | 2026-11-13 22:13:22 | ❌ compile error (§5.3) |
| [43514608722](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43514608722) | `528d629` | 2026-11-13 21:53:12 | ✅ P0-A |
| [43514068424](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43514068424) | `1940487` | 2026-11-13 21:49:22 | ✅ P0-A test fix |
| [43513473811](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43513473811) | `0e6d5ff` | 2026-11-13 21:45:14 | ❌ 1 failed test (§5.2) |

All four failures are pre-fix states of later-green commits and are analysed in §5; none is open. Every commit
listed above is an ancestor of `81919f9` (checked with `git merge-base --is-ancestor`).

### 7.5 What a green run proves — and what it does not

**Proves:** the 644 discovered JVM tests compile and none failed; `:app:assembleDebug` produced a debug APK;
the tree compiled with Kotlin 2.4.21 / AGP 8.13.2 on JDK 17.

**Does not prove:** that the app behaves correctly on a device; that OCR/translation quality is acceptable on
real pages; that the remote engines work against the real APIs (no key, no network here); that `lint`/R8 pass
(not run); that skipped tests do not exist; or that any phase is "Completed" (§3.2).

### 7.6 Failing-run detail (verified from the run annotations for this revision)

| Run | Commit | Annotation (verbatim summary) |
| --- | --- | --- |
| [43513473811](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43513473811) | `0e6d5ff` | `517 tests completed, 1 failed` · `GeminiTranslatorTest > primaryJsonRequestCarriesThePageNameSheet FAILED` |
| [43517478032](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43517478032) | `6a71f77` | `:app:compileDebugKotlin` failed — *"Only safe (?.) or non-null asserted (!!.) calls are allowed on a nullable receiver of type 'CharacterStore?'"* at `CharacterResolver.kt:105,242,264`, `data/CharacterMemory.kt:91` |
| [43517986742](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43517986742) | `1686e72` | `590 tests completed, 4 failed`: `CachingNameOverrideTest > the repair is written back so the next capture is already correct`, `NameFormTest > replaceArabic repairs a superseded spelling`, `CharacterResolverTest > a locked record supplies the spelling and says so`, `NameSessionTest > a session uses the series memory and stores new names` |
| [43568984747](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43568984747) | `25b15c2` | `644 tests completed, 1 failed` · `GlossaryTest > shortCollisionsStayEnglish FAILED` |

Retrieved with `gh api repos/mouadknight0-cell/ManhwaLens/check-runs/<job-id>/annotations`. The full raw logs and
the JUnit XMLs remain unreachable (blocked host), so stack traces and per-test timings cannot be inspected.

### 7.7 Device validation status

**None, for every phase and every milestone.** No physical device has been used; `MANUAL_TESTS.md` (Redmi 13C +
Brave) is an unexecuted checklist. Consequently: overlay timing, scroll riding, Live mode, battery, the
withheld-bubble behaviour of C2, the series UI of P1 and the order change of P2-02 are **build-and-JVM-verified
only**.

---

## 8. Known defects and unresolved risks

### 8.1 P2 findings register (audit `01e12a6`; status re-verified at `81919f9`)

| ID | Type | Priority | Finding | Status |
| --- | --- | --- | --- | --- |
| **P2-01** | Bug | High | `Glossary` rewrote ordinary English words before translation | **Implemented · tested · CI-verified (P2-A) — awaiting review** |
| **P2-02** | Bug | High | Remote chunks built from visual-priority order while the prompt claims page order | **Implemented · tested · CI-verified (P2-A) — awaiting review** |
| **P2-03** | Bug | Medium | `jsonReply` never reaches the primary request (`GeminiTranslator.kt:232`) | **Open — defect present, P2-B proposed** |
| **P2-04** | Bug | Medium | Positional "one line per id" parse can misalign bubbles (`GeminiWire.kt:325`) | **Open — defect present, P2-B proposed** |
| **P2-05** | Risk | High | Two name authorities on the Gemini path (series sheet vs page-local `applyPinned`) | **Open (needs a decision — P2-C)** |
| **P2-06** | Risk | Medium | One failing long-block chunk aborts the whole ML Kit page | **Open** |
| **P2-07** | Risk | Medium | `pageNames` survives early returns (audit C3) | **Open** |
| **P2-08** | Risk | Medium | LibreTranslate fails the page on one rejected bubble, no name sheet | **Open** |
| **P2-09** | Risk | Medium | Remote upgrade always wins on non-blank output | **Open** |
| **P2-10** | Risk | Medium | Cache keys are context-free | **Open** |
| **P2-11** | Risk | Low | `PagePlan.prepare` called without `preprocess` on the Live path | **Open** |
| **P2-12** | Risk | Low | SFX dropped by the planner while the prompt promises SFX transliteration | **Open** |
| **P2-13** | Risk | Low | `NameMemory.pageNameSpellings` indexes the page list with bubble ids | **Open (latent, no caller)** |
| **P2-14** | Improvement | Low | Validator cannot detect a truncated short answer or a 1–2 word English remnant | **Open** |
| **P2-15** | Improvement | Low | Post-processing order differs between engine and decorator | **Open** |
| **P2-16** | Improvement | Low | `MAX_TOKENS`-truncated reply is traced, not treated | **Open** |
| **P2-N1…N5** | Not a problem | — | Idempotent `prepare`; per-bubble triage; pinned override vs stale cache; offline independence; names survive chunking | **verified correct** |

### 8.2 Open findings — evidence anchors and the minimal fix each needs

Line numbers are the audit baseline (`fe5aa6a`); re-verify before editing. None of these is fixed.

| ID | Anchor (baseline) | Minimal fix proposed | Regression the fix must add |
| --- | --- | --- | --- |
| P2-03 | `GeminiTranslator.kt:232` (`jsonReply = false`), KDoc `:47-52` | pass the field through and document which body is primary | `jsonReply = true` → body contains `responseMimeType`; `false` → legacy body; land before P2-16 |
| P2-04 | `GeminiWire.kt:316-330`, fallback `:321-328` | positional mapping only on an exact count match (`>=` → `==`), drop a translator-label line first | equal-count reply maps by position; an extra label line maps nothing |
| P2-05 | `GeminiTranslator.kt:87-99` vs `:128-136` | one authority: give the engine the same series table the sheet used | a series table + Gemini post-processing test (none exists) |
| P2-06 | `MlKitTranslator.kt:154-181`, abort `:174-176` | omit the failed long block (empty) and continue | a failing chunk leaves one empty block, page still `Success` |
| P2-07 | `MlKitTranslator.kt:246` clears `pageNames`; early returns `:103-108` | `try/finally` (or clear at entry) | two pages on one translator instance never share names |
| P2-08 | `LibreTranslateTranslator.kt:57-58`, `:80-100` | per-bubble triage like ML Kit; attach the sheet | one rejected bubble does not fail the page |
| P2-09 | `FloatingButtonService.kt:3705-3711` | do not replace a local patch with a worse remote one | arbitration test on the relay path |
| P2-10 | `TranslationCache.kt:18-30`, `CachingTranslator.kt:224-232`, `TranslationMemo.kt` | document the choice; a context-bearing key only with evidence | none today (documented trade-off) |
| P2-11 | `PagePlan.kt:59-68` + Live call site | pass `Glossary::prepare` where the contract says so | `Wei Lin` / `wei lin` dedupe to one item on the Live path |
| P2-12 | `MeaninglessText.kt:163-177`, `PagePlan.kt:74-84` | make the prompt and the planner agree about SFX | SFX kept, or the prompt claim removed |
| P2-13 | `NameMemory.kt:259`, `:287` | index by id, not by position | latent — no caller passes `pageArabic` today |
| P2-14 | `ArabicValidator.kt:59-90`, `:144-166` | detect a truncated short answer and a 1–2 word English remnant | guard tests for both |
| P2-15 | `GeminiTranslator.kt:133-142` vs the decorator path | unify post-processing order | same text judged on both paths |
| P2-16 | `GeminiTranslator.kt:321-337`, `GeminiWire.kt:303` | treat `MAX_TOKENS` truncation (empty the cut bubble) | a truncated reply leaves no half sentence |

### 8.3 Other open items (audit findings outside P2)

| Item | Anchor (baseline) | Status |
| --- | --- | --- |
| C3 — stale `pageNames` across pages | `MlKitTranslator.kt` early returns | Open (same defect as P2-07) |
| C4 — AI cleanup queued on the live thread | `FloatingButtonService` | Open |
| C5 — duplicate `NameMemory.plan` work | `GeminiTranslator.kt`, `SceneNames.kt` | Open |
| RTL correctness (one shared layout builder, RLM wrapping) | `render/`, overlay | Open |
| Offline-readiness step ("Prepare offline EN→AR") | settings/flows | Open |
| Render performance (binary-search sizer, measurer `maxLines`) | `render/AutoFitTextSizer.kt` | Open |
| `FloatingButtonService` seams (4.4k-line orchestrator) | `overlay/FloatingButtonService.kt` | Open (no work under any phase) |

### 8.4 Risks carried by the P2-A change

1. A one-word ambiguous name (`Long`, `Song`, `Sun`, `Pan`, `Tan`, `Ding`) is no longer auto-replaced; the pin
   or the series memory must supply it. Documented in `Glossary.accepts` and decided as D6.
2. The change is **CI-verified only** — no real page has been translated with it since it landed.
3. The relay now sends page order; the *volume* and *chunking budget* of remote requests are unchanged (the
   number of chunks can differ because block lengths differ per chunk, which is expected).

### 8.5 Environment blockers (`BLOCKERS.md` remains the register)

No JDK/Android SDK/`gradle`/`kotlinc` locally; lint unavailable offline; R8 disabled; debug APK only; no real
Gemini/Libre traffic (no key, no network); executed/skipped test counts unreadable; no device.

---

## 9. Current active phase and exact next action

### 9.1 Current active phase

**P2 — translation quality and context management.** Within it, **P2-A is delivered**: P2-01 and P2-02 are
implemented (`25b15c2`), their tests exist (15), and a green run executed them (`a7b276f`, run 43569302435).
**P2-A is not complete**: it has not been reviewed or approved, and nothing has been device-verified.

### 9.2 Exact next action

1. **Review P2-A** (evidence: §5.9, §6.4, runs 43568984747 ❌ and 43569302435 ✅). No further work is
   authorised before that review.
2. **Only after explicit approval:** start **P2-B** = P2-03 + P2-04 (Gemini request/response integrity), with
   the regressions listed in §8.2 and the same evidence standard (implementation + tests + a green run, no
   device claims).
3. P2-C (P2-05) needs a design decision first; P2-D and P3–P6 remain untouched.

### 9.3 Explicitly not done by this task

Documentation only. No application source, test, dependency, workflow or PR change; PR #2 is not merged,
closed, renamed or re-drafted; P2-A was not re-implemented and P2-B was not started.

---

## 10. Documentation maintenance rules

### 10.1 Rules (S1–S6, as now in force)

* **S1.** Every change ships in the **same push** as an update to this file. There are no per-change report
  files; a new work package extends §5 (history) and the relevant status table (§6, §8).
* **S2.** Each work-package entry records: scope, base commit, verified root cause with file:line, the fix and
  the rejected alternatives, files changed and why, tests added and the property each pins, the exact command
  and the CI run that executed it, and the remaining risks (including "not verified on device").
* **S3.** Evidence discipline: no test is reported as passing unless a run executed it; a command that cannot
  run here is reported as such, never simulated; a finding that contradicts an existing document is stated in
  the report before the code changes.
* **S4.** Chat summaries, commit trailers and PR descriptions are conveniences, never the record. If a later
  change alters an earlier claim, this file is corrected in the same push.
* **S5.** This file is the repository-level snapshot **and** the per-change record: identity, branch/HEAD,
  phases P0–P6, milestones M0–M6, history, verified status, tests/CI, defects/risks, next action. `PLAN.md`
  stays the product roadmap, `DECISIONS.md` the decision log, `BLOCKERS.md` the environment register,
  `MANUAL_TESTS.md` the device checklist.
* **S6.** A refresh follows **every** prompt, including doc-only ones, and states plainly when no code changed
  and no test run is claimed.

### 10.2 Rules for phase status

1. Use the vocabulary of §3.2 verbatim; never write "complete" without device verification and an approved
   review.
2. Never attribute work to P3–P6 that was delivered as milestone work; never re-label M0–M6 as P0–P6.
3. Re-verify every cited run and commit against GitHub before publishing a revision; a stale run id is a defect
   in the report, not a detail.
4. Deleting a document requires prior transfer of its content into this file and a link check afterwards.

### 10.3 Validation performed for revision 3.0 (commands and exact outcomes)

| # | Check | Command | Outcome |
| --- | --- | --- | --- |
| 1 | Branch / HEAD / tree | `git rev-parse --abbrev-ref HEAD`, `git log -1`, `git status --porcelain` | `arena/8a718184-manhwalens`, `328e0a9`, tree clean before the edit |
| 2 | Remote sync | `git fetch origin && git status -sb` | in sync at every push |
| 3 | PR state | `gh pr view 2 --json …` | OPEN, not draft, CLEAN, base `main`, head `328e0a9`, 0 reviews |
| 4 | CI runs | `gh run list --branch … --limit 100` | 32 runs, 28 success / 4 failure |
| 5 | Failing-run detail | `gh api repos/…/check-runs/<job>/annotations` | 517/1, compile error, 590/4, 644/1 — test names recorded in §7.6 |
| 6 | Test counts per commit | `git grep -c '@Test' <sha> -- app/src/test` (summed) | 509 / 517 / 590 / 599 / 604 / 615 / 619 / 629 / 644 |
| 7 | Ancestry of every cited commit | `git merge-base --is-ancestor <sha> HEAD` | YES for all 15 cited commits |
| 8 | Diff sizes cited for historical packages | `git diff --shortstat <base> <final> -- app` | corrected where old reports had counted their own Markdown (§5) |
| 9 | Source anchors cited in §5–§8 | `grep -n` on the cited files at HEAD | `Glossary.kt:140/338/350`, `PagePlan.kt:130/138`, `FloatingButtonService.kt:2784-2785,2819,2848,2858,2863`, `GeminiTranslator.kt:232/250`, `GeminiWire.kt:325/187-206/223-226`, `CachingTranslator.kt:58`, `JsonCharacterStore.kt:464/283-345` — all present |
| 10 | Local Markdown link check | Python scan of every `*.md` for `](path)` targets | all resolve (0 broken) |
| 11 | Deleted-file link check | scan for links to the ten removed reports | none |
| 12 | Table integrity | per-file pipe/cell-count scan (escaped `\|` respected) | all tables consistent |
| 13 | Scope | `git status --porcelain`, `git diff --stat` | Markdown only: `REPOSITORY_REPORT.md`, `DECISIONS.md`, `V096_PLAN.md`; no `app/`, `.github/`, `.kts` or `gradle/` change |
| 14 | Build/test execution | `java`, `javac`, `gradle`, `kotlinc`, `ANDROID_HOME` | absent/unset — no local run possible, none claimed; CI run 43571410420 at `328e0a9` ✅ |

### 10.4 Revision history

| Revision | Date | Head | What changed |
| --- | --- | --- | --- |
| **3.0** | 2026-11-13 (UTC 2026-11-14) | `328e0a9` (run 43571410420 ✅) and `2f3ad07` (run 43571824620 ✅) + this doc-only refresh | Documentation audit and canonical correction: replaced the invented P3–P6 subjects with the agreed definitions (P3 performance/caching, P4 OCR, P5 text cleanup/rendering, P6 final integration/release); added the explicit status vocabulary and re-graded every phase (no phase is "Completed"); re-verified HEAD, PR, all 31 CI runs, all test counts and the P2-A source anchors against git/GitHub; added the milestone-vs-phase rule, the decision register, the per-phase status tables and the maintenance rules; restructured to the mandated ten sections. **Documentation only** — no source, test, dependency or PR change. |
| 2.0 | 2026-11-13 (UTC 2026-11-14) | `94cf2f5` | Consolidation: the nine per-change reports and `REPORTS.md` folded in and deleted; phase table, P2 register, open-finding anchors, rewritten rules. |
| 1.x | 2026-11-13/14 | `01e12a6`, `05fa1ab`, `3674ebf`, `b82fb6f`, `e546470`, `723f101`, `8140a4b`, `a7b276f` | Incremental snapshots superseded by revision 2.0; their content is carried in §5–§7. |
