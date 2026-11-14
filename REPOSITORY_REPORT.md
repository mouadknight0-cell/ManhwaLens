# ManhwaLens — repository report

**Report revision:** 3.3 · **Updated:** 2026-11-13 (session date; commit clock 2026-11-14 UTC)
**Branch:** `arena/8a718184-manhwalens` · **PR:** [#2](https://github.com/mouadknight0-cell/ManhwaLens/pull/2) — **OPEN**, not draft, not merged
**Base:** `main` @ `560705d55012f1549449ca3230184b4b5beda991` (the PR branch carries the whole app; `main` holds the initial commit)
**HEAD at this revision:** `eac0d0c` — *fix(names, gemini): P2-18 ambiguity gate, P2-17 non-JSON 400 retry,
P2-19 dead path* (verified by run 43585853784; the commit that publishes this revision is `docs`-only on top of
it, and **its own run is not claimed** here)
**Latest verified CI run:** [43585853784](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43585853784), workflow *Android build*,
**success** at `eac0d0c` (2026-11-14 10:33:33 → 10:36:40 UTC, 3m07s), steps *Unit tests* ✅ and *Build debug APK* ✅.
Earlier green runs behind it: `3c1f64c` → 43575632319 (the §5.12 review, docs), `de77ef1a` → 43574339617
(report 3.1, docs), `a2882f16` → 43573847121 (P2-B code), `e0e6c7d` → 43572172053.

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
> **Clock note:** the session date is 2026-11-13; commit timestamps run 2026-11-13 16:21 → 2026-11-14 10:31 UTC.
> A date below is the commit clock where a commit or run is cited, the session date otherwise.
> **Publication note:** the commit that publishes this revision is documentation-only — the code tree is
> byte-identical to `eac0d0c`, so nothing in `app/`, `gradle/` or `.github/` changes with it. Its own CI run was
> still pending when this file was written and is therefore **not claimed** here. Every code commit behind it
> **is** verified green: `eac0d0c` (run 43585853784) is the newest, and before it `3c1f64c` (43575632319),
> `de77ef1a` (43574339617) and `a2882f16` (43573847121).

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

### 1.1 Data flow (as implemented at `a2882f16`)

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

Gemini reply parsing (`GeminiWire.parseJsonTranslations`, P2-04) accepts the explicit-id shapes first
(`{"t":{…}}`, id-keyed objects, `[{id,ar}]`, `[id] text` lines) and only falls back to a positional
"one Arabic line per id" mapping when the line count matches the id count **exactly** and the structure is
unambiguous; otherwise the reply is treated as unparsable and the caller re-asks/falls back (§5.11).

### 1.2 Name pipeline (three cooperating layers)

| Layer | Object | Job |
| --- | --- | --- |
| Static + user dictionary | `Glossary` (`translate/Glossary.kt`) | resolves known names/terms to Arabic *before* the engine sees the text; user Settings entries are explicit pins and always win |
| Page plan | `NameMemory` / `SceneNames` (`translate/`) | picks one spelling per page and pins it into the source text; builds the model name sheet |
| Series memory | `names/` (`CharacterRecord`, `CharacterResolver`, `JsonCharacterStore`, `SeriesCatalog`) + `data/CharacterMemory` | reader-confirmed spellings, aliases, per-series isolation, survives restarts |

### 1.3 Repository inventory at this revision

| Metric | Value | Command |
| --- | --- | --- |
| Commits ahead of `origin/main` | **45** through `eac0d0c`, 46 with this report commit | `git rev-list --count origin/main..HEAD` |
| Branch diff vs `main` | `220 files changed, 36 821 insertions(+)` at `eac0d0c` (all files are new on the branch, so 0 deletions vs `main`) | `gh pr view 2 --json changedFiles,additions,deletions` |
| Kotlin sources | **113** files under `app/src/main` | `find app/src/main -name '*.kt' \| wc -l` |
| Test sources | **70** files under `app/src/test` (one JVM source set; no instrumented source set) | `find app/src/test -name '*.kt' \| wc -l` |
| Test methods (`@Test`) | **676** | `grep -rn '@Test' app/src/test \| wc -l` |
| Test distribution | `translate` 270, `live` 118, `names` 82, `ocr` 79, `render` 46, `util` 37, `data` 22, `overlay` 14 | `grep -rc '@Test'` per package |
| Root Markdown files | **10** (§1.4) | `ls *.md` |
| Last commit that changed behaviour | `eac0d0c` (P2-18 + P2-17 + P2-19); before that `a2882f16` (P2-B: P2-03 + P2-04), `a7b276f` (P2-A test expectation fix) and `25b15c2` (P2-A itself) | `git log` |

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
| HEAD | `git log -1 --format='%H %s'` | `eac0d0c19cf2a135cbc14af2196639e4bd997579` — *fix(names, gemini): P2-18 ambiguity gate, P2-17 non-JSON 400 retry, P2-19 dead path* |
| Working tree | `git status --porcelain` | empty (clean) before this revision's edit |
| Remote sync | `git fetch origin && git status -sb` | in sync with `origin/arena/8a718184-manhwalens` |
| PR #2 | `gh pr view 2 --json …` | `OPEN`, `isDraft=false`, `headRefName=arena/8a718184-manhwalens`, `headRefOid=eac0d0c…`, `baseRefName=main`, `mergeStateStatus=CLEAN`, `reviewDecision` empty — **0 reviews, 0 review comments, 0 issue comments** (re-checked for this revision), 45 commits, 220 files, +36 821/−0 |
| Workflow | `.github/workflows/android.yml` | one workflow, *Android build*, events `pull_request` (all), `push` to `main`/`master`/`arena/cebceb7a-manhwalens` and `v*` tags, `workflow_dispatch`; one job `build` |
| Runs on this branch | `gh run list --branch … --limit 100` | **38 runs: 34 success, 4 failure** (§7.4); newest `43585853784` at `eac0d0c` ✅ |

### 2.2 Commits this revision describes

| Commit | Kind | What |
| --- | --- | --- |
| `01e12a6` | docs | P2 audit delivered (the audit file was later consolidated into §5.8/§8) |
| `533ae90` | docs | P2 audit CI run recorded — the baseline of the P2-A round |
| `25b15c2` | **code + tests** | P2-A: P2-01 (glossary false positives) and P2-02 (remote block order) |
| `a7b276f` | **test fix** | one wrong expectation in the new glossary test (§5.9) |
| `94cf2f5` | docs | report revision 2.0: single report, superseded reports deleted, one doc-pointer comment |
| `81919f9` | docs | run 43569844274 recorded |
| `328e0a9` | docs | the audit: canonical correction of the phase definitions, status vocabulary, restructure to the ten mandated sections, evidence re-verification, `V096_PLAN.md` label note, `DECISIONS.md` S1/S5 + D9–D11 |
| `2f3ad07` | docs | run 43571410420 recorded (close of the audit refresh) |
| `e0e6c7d` | docs | final state of the documentation audit (`2f3ad07` corrected to the verified `328e0a9`) |
| `a2882f16` | **code + tests** | **P2-B**: P2-03 (`jsonReply` now reaches the primary request) and P2-04 (safe, non-misaligning reply parsing) + 24 tests (§5.11) |
| `de77ef1a` | docs | report revision 3.1: P2-B recorded (§5.11) |
| `3c1f64c` | docs | report revision 3.2: independent review of P2-01…P2-04 (§5.12), three new findings (§8.1), report corrections (§5.0, §5.9, §8.2, §7.4) — run 43575632319 ✅ |
| `eac0d0c` | **code + tests** | **P2-18** (the name planner no longer offers an ambiguous English word as a character name), **P2-17** (the HTTP-400 retry now sends a genuinely different, non-JSON body), **P2-19** (dead glossary path deleted) + 8 new tests (§5.13) |
| *this commit* | docs | report revision 3.3: the three review findings recorded as fixed (§5.13), register/status/tests/next-action re-verified at `eac0d0c` |

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
| **P2** | ✅ P2-C…P2-D | ✅ `01e12a6` | ◐ **P2-A** `25b15c2` + **P2-B** `a2882f16` + **review fixes** `eac0d0c` | ◐ 47 new tests (15 + 24 + 8) | ◐ 43569302435 (`a7b276f`), 43573847121 (`a2882f16`), 43585853784 (`eac0d0c`) | ❌ | ❌ (P2-A/P2-B reviewed in §5.12 — **not approved**; the three findings are now fixed, §5.13; P2-C/P2-D untouched) |
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

### 5.0 Branch history before P0 (verified for this revision)

The branch does not begin at the P0 audit; it begins with the v0.11.0 import. Comparing every commit on the
branch against this file (§10.3a row 6 of revision 3.2) found that three of these early commits had **never been recorded**.
They are listed here — not re-graded, just documented, since this review is not allowed to change code.

| Commit | Date (UTC) | What it is | Size (`git show --stat`) |
| --- | --- | --- | --- |
| `ec59e7e` | 2026-11-13 20:08:58 | v0.11.0 — opaque one-patch-per-bubble rendering, name consistency, noise removal, faster page plan; the import of the whole app, the workflow and the root docs | 198 files, +30 312 |
| `6296f80` | 2026-11-13 20:13:01 | CI: surface Kotlin compile/test errors as annotations — the `grep … echo "::error::…"` step in `.github/workflows/android.yml:52-56` that §7.6 depends on | 1 file, +14/−1 |
| `f28f56c` | 2026-11-13 20:15:39 | one-line compile fix: import `SceneNames` in `translate/mlkit/MlKitTranslator.kt` | 1 file, +1 |
| `b8e37de` | 2026-11-13 20:19:29 | docs: README 0.11.0 summary (already used as the test-count baseline in §7.3) | 1 file, +19/−1 |

**None of `ec59e7e`, `6296f80` or `f28f56c` has a CI run of its own:** the first run on this branch is
43513473811 at `0e6d5ff` (§7.4), so those three are covered only *transitively* (§3.2 allows this — the run's
commit is an ancestor of this HEAD). Their content is therefore **build-verified only as part of a later
tree**, and, for `6296f80`, only as a change to CI tooling that this sandbox cannot execute locally.

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

**Files (5, +347/−13 — re-measured for this revision as `git diff --shortstat 533ae90 25b15c2 -- app`; the
earlier "+348" was off by one):** `translate/Glossary.kt`, `translate/PagePlan.kt`, `overlay/FloatingButtonService.kt`,
`translate/GlossaryTest.kt` (+8 tests), `translate/TranslationOrderingTest.kt` (new, 7 tests).
**Tests:** 15 new (629 → 644 discovered), listed in §6.4.
**CI:** 43568984747 ❌ at `25b15c2` — verified annotation: *644 tests completed, 1 failed ·
`GlossaryTest > shortCollisionsStayEnglish FAILED`* (the expectation ignored that the term `sect` in the same
bubble legitimately translates) → fixed in `a7b276f` → **43569302435 ✅**.
**Status:** **Implemented, tested, CI-verified.** Reviewed on 2026-11-13 (§5.12): the fix is correct and the
tests pin the intended behaviour, but the review is **not an approval** — two residual findings were raised
(P2-18, P2-19) and nothing is device-verified. (P2-03/P2-04 were still open at this point; they were closed
later by P2-B, §5.11.)

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

### 5.11 P2 — P2-B implementation: request integrity and safe reply parsing (`a2882f16`)

**Scope authorised:** P2-03 and P2-04 only — no prompt, model-default, OCR, persistence, UI or engine change,
no new dependency, no new reply protocol. `Glossary.kt`, `PagePlan.kt` and `FloatingButtonService.kt` (P2-A)
were **not** touched.

**P2-03 — `jsonReply` never reached the primary request.**
*Root cause (verified at `e0e6c7d`):* `GeminiTranslator.translateChunk` called
`GeminiWire.buildRequestBody(chunk, THINKING_LEVEL, sheet, jsonReply = false)` — a hardcoded literal — so a
caller constructed with `jsonReply = true` still sent the **legacy numbered-lines body** on the first attempt;
only the HTTP-400 retry (`buildFastBody(chunk, sheet, jsonReply = jsonReply)`) honoured the field. The JSON
reply mode was therefore unreachable in production for any caller that asked for it.
*Fix:* the field is passed through (`GeminiTranslator.kt:239-241`). Nothing else changed: the constructor
default stays `false` (`TranslatorFactory.kt:107-113` passes no flag, so the shipped request is byte-identical to
before), the 400-retry body still comes from `buildFastBody`, and each body keeps its own settings — legacy:
`temperature 0.3` + `thinkingLevel low`; JSON: `temperature 0.2` + `thinkingBudget 0` + `responseMimeType`.
*Rejected alternative:* flipping the default to `true` — that is a product/behaviour decision (the flag's KDoc
and every installed model expectation say legacy), not this defect.

**P2-04 — positional reply parsing could silently misalign every bubble.**
*Root cause (verified at `e0e6c7d`):* the fallback in `GeminiWire.parseJsonTranslations` filtered the reply's
lines (trim, strip `-*"`, keep lines containing any char in `U+0600…U+06FF`) and then mapped
`if (lines.size >= expectedIds.size) expectedIds[i] -> lines[i]`. Three ways it corrupted a page:
(1) a standalone label such as `الترجمة:` **is** a line with Arabic characters, so it was mapped to the first
bubble and every real translation moved down one id; (2) the `>=` comparison accepted **any** number of extra
lines (caption, explanation, rule) and still shifted; (3) the caller could not tell a shifted mapping from a
correct one, so it painted it.
*Fix (all inside `GeminiWire`):* the positional path is now a separate `positionalMap` (`GeminiWire.kt:429`)
over `replyLines` (`:378`), which
1. strips bullets (`-` `*` `•` `·` `–` `—`), rule lines (`---`, `***`, `___`), wrapping quotes
   (`"`, `'`, `«`, `»` and a stray backtick), list markers (`1.`, `2)`, `3-`) and a label **prefix**
   (`الترجمة: مرحبا` → `مرحبا`) — `LABEL_PREFIX` `:351`;
2. drops **standalone labels** (`الترجمة`, `ترجمة`, `النص`, `الإجابة`, `الجواب`, `الرد`, `بالعربية`,
   `Arabic`, `Translation`, `Output` … `STANDALONE_LABEL` `:344`) and short colon-tailed captions
   (`هذه هي الترجمة:`) — `isLabelLine` `:412`;
3. drops lines with no Arabic **letter** (`ARABIC_LETTER` `:358`), i.e. English preambles and stray formatting;
4. requires the number of usable lines to equal the number of expected ids **exactly** (missing *and* extra
   both refuse the reply);
5. if the model numbered every line, accepts the numbering only when it equals the expected ids or is a clean
   `1..N`, and refuses a partly-numbered, gapped or out-of-order numbering;
6. otherwise returns `null`, which the translator already treats as a parse error → it re-asks the missing ids
   once and, if that fails, returns the existing `PageTranslation.Failure(PARSE_ERROR)` so the on-device draft
   stays on screen. No new protocol, no new failure type, no new dependency.
Explicit shapes are untouched and still win in this order: `{"t":{…}}` / `{"translations":…}` / id-keyed
object / `[{id,ar}]` → `[id] text` lines (`parseNumberedLines`, honoured even when partial, so the existing
missing-id re-ask still recovers them) → positional.
*Rejected alternatives:* (a) changing `>=` to `==` only — a label line then still consumes a bubble's slot and
the reply is rejected wholesale instead of parsed, i.e. the format is lost, not fixed; (b) fuzzy line-to-id
matching or a confidence score — unevidenced heuristics on a path with no test data; (c) asking the model for a
new reply protocol — explicitly out of scope and it would break installed-model behaviour.

**Files (4, +385/−11 — `git diff --numstat e0e6c7d a2882f16 -- app`):**
`translate/gemini/GeminiTranslator.kt` (+13/−4: the propagation, plus KDoc that now states which body is
primary), `translate/gemini/GeminiWire.kt` (+121/−7: `positionalMap`, `replyLines`, the label/artifact helpers,
KDoc), `translate/gemini/GeminiWireTest.kt` (+135: 17 tests), `translate/gemini/GeminiTranslatorTest.kt`
(+116: 7 tests). No other file changed (`git status --porcelain` at commit time listed exactly these four).

**Tests: 24 new (644 → 668 discovered).** Each wire test asserts the **actual id → translation map**, not just
"non-empty": exact positional mapping; Arabic label `الترجمة:`; English `Translation:`; label used as a prefix
on real dialogue; extra explanatory line (rejected); extra translation line (rejected); missing line
(rejected); blank lines/bullets/quotes/rules (`---`, `***`, `___`) ignored; explicit `[id]` lines out of order;
the JSON object shape; the bare id-keyed object and `[{id,ar}]` array; mixed explicit + bare lines (only the
explicit ids kept — the bare line is never positionally assigned); `1.`/`2.` numbering that matches the ids;
numbering that disagrees (rejected); partly numbered (rejected); a single-bubble reply; a reply of labels and
artifacts only (nothing parsed). Translator-level: `jsonReply = true` → primary body contains
`responseMimeType`, `temperature 0.2`, `thinkingBudget 0`; `jsonReply = false` → `thinkingLevel low`,
`temperature 0.3`, no `responseMimeType`; the **default constructor** still sends the legacy body; HTTP-400
retry still executes (2 requests) for both flag values; a label-prefixed positional reply maps 1→first
line, 2→second line end to end; an ambiguous positional reply yields `PARSE_ERROR` with exactly one request
and nothing painted. Names listed in §6.4.

**Commands and outcomes (this sandbox).** `java`, `javac`, `gradle`, `kotlinc` and `ANDROID_HOME` are all
absent, so **no test or build was executed locally and none is claimed**: the focused command
`./gradlew :app:testDebugUnitTest --tests '*GeminiTranslatorTest' --tests '*GeminiWireTest'` could not be run
here at all. What was run: a byte-level review of the diff, a brace/paren/string balance scan of the four
files (all clean), and a Python transliteration of `replyLines`/`positionalMap` executed against the 14
mapping cases in the new tests (14/14 matched the expected maps — a design check of the algorithm, **not** a
test run). The authoritative execution is CI:
`./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain`.

**CI:** [43573847121](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43573847121) at `a2882f16`
— job `build` **success** in 3m29s, steps *Unit tests* ✅, *Build debug APK* ✅, *Upload test reports* ✅
(2026-11-14 08:01:57 → 08:05:29 UTC). Executed/skipped counts remain unverifiable for a green run (§2.3):
the annotation API returns nothing for a green run and the `test-reports` artifact download fails with `EOF`.

**Status:** **Implemented · tested · CI-verified.** Reviewed on 2026-11-13 (§5.12): propagation is correct and
the default is preserved, but the review is **not an approval** — one new finding was raised (P2-17) and
nothing is device-verified. P2-05…P2-16 (P2-C/P2-D) remain untouched.

### 5.12 Independent technical review of P2-01…P2-04 (read-only, this revision)

**Method and limits.** Every item was re-read at `de77ef1a` in the source *and* in its tests, then re-checked
against the GitHub API (commits, diffs, runs, PR). **Nothing was executed here** — there is no JDK, Gradle,
`kotlinc` or Android SDK in this sandbox (§7.2) — so every "verified" below means *verified by reading the code
and the CI record*, never *verified by running it*. No source file and no test was modified by this review, and
no fix was applied (§8.2 lists the minimal fix each finding needs).

#### 5.12.1 Verdicts

| Item | Verdict | Reviewer's summary |
| --- | --- | --- |
| **P2-01** — glossary no longer rewrites ordinary English | **Correct as implemented · 2 residual findings (P2-18, P2-19)** | the gate is real, narrowly scoped and well pinned by tests; the *planner* was not gated (P2-18) and a dead sibling function still bypasses the gate (P2-19) |
| **P2-02** — remote engines get page order | **Correct · report scope correction on P2-11** | page order reaches the relay and the chunker, visual priority stays on the local pass, Live is untouched; but `preprocess` is missing at **both** capture call sites, not only Live |
| **P2-03** — `jsonReply` reaches the primary request | **Correct · 1 new finding (P2-17)** | the flag is propagated without changing the default; the HTTP-400 ladder is now a duplicate request when the flag is `true` |
| **P2-04** — positional reply parsing | **Correct · 1 documented limitation** | labels/artifacts/exact-count/numbering are all handled and the failure path is the existing safe one; a *JSON* renumbering is still not recovered (safe failure, not a mispaint) |

#### 5.12.2 P2-01 — glossary correctness

*Verified in code:* `Glossary.accepts` (`Glossary.kt:140-150`) is the only gate on the compiled table and it
runs for **every** match in `applyCompiled` (`:99-108`); rule order is pin → multi-word/hyphen → `TERMS`
(`:338`) → capitalised and not ALL CAPS → not in `AMBIGUOUS_ENGLISH` (`:350`). Keys are replaced
case-insensitively as whole words (`compiled()` `:62-70`), longest key first, so `Song Lan` still wins over
`Song`; `refresh()` drops the compiled cache, so a Settings change re-scopes the pins immediately.
*Verified in tests:* the 8 new `GlossaryTest` cases assert the **complete output string** — ordinary lowercase
(`How long until the sun sets?`), sentence-initial capitalised (`Long ago, …`), the short collisions
`pan/tan/ding/Song`, `TERMS` in any case, genuine two-word names, an explicit pin, the pin's scope, and that
`effective()` still offers every name for the memory layer. The mixed expectation in
`shortCollisionsStayEnglish` (`He sang a Song for the طائفة.`) is the honest one: the noun stays English, the
genre term still translates.
*Edge cases checked by reading:* `length < 2` guard; ALL-CAPS rejection (SFX); multi-word/hyphen keys bypass the
ambiguity list deliberately; a blank Arabic entry can never replace anything; `TERMS` are replaced in any case
**by design**, so an ordinary sentence containing `spirit`, `core` or `pill` can still be altered — the same
behaviour as before the fix, recorded as a residual risk, not a defect of it.
*Residual (P2-18):* the planner is not gated by the same rule (details in §8.2).
*Residual (P2-19):* `applyToSource`/`replaceWord` (`Glossary.kt:90-98`, `:152-156`) keep a weaker filter and
have **no caller** in `app/` (verified with `grep -rn "applyToSource" app/`).

#### 5.12.3 P2-02 — translation ordering

*Verified in code:* `PagePlan.remoteOrder(plan) = engineBlocks(plan)` (`PagePlan.kt:130`) and `engineBlocks`
maps `plan.items` in first-appearance order (LinkedHashMap, `prepare` `:65-94`), i.e. page order;
`progressiveOrder` (`:138-139`) sorts by rank with unranked last (`sortedBy` is stable, so ties keep page
order). Tap path: `remoteBlocks = remoteOrder(...)` is what `relayRemoteTranslation(..., unique)` sends
(`FloatingButtonService.kt:2784`, `:3706`), while `blocksToSend = progressiveOrder(...)` feeds only the local
ML Kit pass (`:2837`, `:2852`, `:2861`, `:2897`). Live path is unchanged: `engineBlocks(pagePlan)` at `:3298`
→ `startLiveRemote(..., unique)` at `:3316` → `remote.translatePage(unique)` at `:3575`.
*Verified in tests:* 7 new tests pin page order, priority order, unranked-last, chunk boundaries across
requests (flattened order equals the page, each chunk ascending, no id twice), the old scrambled behaviour as a
negative test, dedupe fan-out (`id 3` and `id 17` receive the same Arabic) and `remoteOrder == engineBlocks`.
*No text loss or misattribution:* `engineBlocks` is 1:1 with `plan.items`, `expand`/`arabicById` return an entry
for every `idToKey` id and fan the answer out to every duplicate id; the relay paints per id
(`painter.onRemote(tb)`), so a request order can never paint the wrong bubble.
*Report scope correction (P2-11):* `PagePlan.prepare(..., preprocess = Glossary::prepare)` is documented
(`PagePlan.kt:61-63`) as what the pipeline passes, but **neither** production call site passes it (tap
`:2747-2750`, Live `:3282-3286`). Effect: `MeaninglessText.classify` and the dedupe key run on unprepared
text while each engine applies `Glossary.prepare` afterwards — a key/classification mismatch, not an ordering
or data-loss problem. P2-11 stays open with a corrected description (§8.2).

#### 5.12.4 P2-03 — `jsonReply` propagation

*Verified in code:* the primary body is built with the caller's flag (`GeminiTranslator.kt:239-241`), the
constructor default is still `false` (`:56`) and `TranslatorFactory.kt:107-113` passes no flag, so the shipped
request is unchanged; the HTTP-400 retry still runs (`:258-262`) and the 404 switch still reuses the same body.
*Verified in tests (5 new):* `true` → `responseMimeType` + `temperature 0.2` + `thinkingBudget 0` on the
**primary** body and a JSON reply parsed into the right bubbles; `false` → `thinkingLevel low` +
`temperature 0.3` and no `responseMimeType`; the default constructor stays legacy; the retry runs twice for
both flag values; the JSON reply shape is parsed.
*New finding (P2-17):* when `jsonReply = true` the primary body and the 400-retry body are **identical**
(`buildRequestBody(..., jsonReply = true)` routes to `buildFastBody(..., jsonReply = true)`, and the retry is
`buildFastBody(..., jsonReply = true)` again), so the retry cannot recover from a 400 caused by JSON mode — it
re-sends the same request. The existing test `http400RetryStillRunsWithJsonReplyTrue` pins that fact rather
than catching it. Also noted: `GeminiWire.buildRequestBody(..., jsonReply: Boolean = true)` (`:227`) defaults to
JSON while the translator's field defaults to legacy — an invitation to a future misuse. Severity **Low**
(the shipped default is `false`); the fix must not change the default (§8.2).

#### 5.12.5 P2-04 — positional reply parsing

*Verified in code:* `parseJsonTranslations` keeps the precedence JSON object → `[id] text` lines →
`positionalMap` (`GeminiWire.kt:323-343`); `replyLines` (`:378-407`) strips bullets/rules/quotes/list markers and
label prefixes, drops standalone labels (`:344`), colon-tail captions (`:412-418`) and lines without an Arabic
letter (`:358`); `positionalMap` (`:429-443`) requires an **exact** line count, accepts numbering only when it
equals the expected ids or is a clean `1..N`, and refuses mixed numbering. Every rejection path returns `null`,
which the translator already maps to "re-ask the missing ids, then `PARSE_ERROR`" — so the on-device draft stays
visible (Offline First unchanged, D4).
*Verified in tests (17 wire + 2 translator):* the wire cases assert the **whole id → Arabic map** for the exact,
label, prefix, artifact and numbering cases and `null` for the extra/missing/ambiguous ones; the two translator
cases assert per-bubble Arabic end to end (id 1 → first line, id 2 → second line) and the failure path with
**exactly one** request and nothing painted.
*Edge cases checked by reading:* `\r\n` (each line is trimmed), em-dash/bullet quote markers (stripped, meaning
kept), `«»`, rule lines `---`/`***`/`___`, `1.`/`2)`/`3-` markers, a label used as a prefix (`الترجمة: مرحبا`),
Arabic-Indic colons (`：`), a reply of labels only (nothing parses), and a single-bubble page (one line is
still unambiguous).
*Documented limitation (not a defect):* a **JSON** reply that renumbers its keys from 1 (`{"1":…}` for ids
19…27) is not recovered positionally, although the same renumbering in plain lines is — `jsonMap` wins
unconditionally. The outcome is a safe page failure plus the local draft, never a mispaint. Likewise a reply
that both adds an Arabic caption and merges two bubbles keeps the exact count and stays undetectable without a
protocol change (§8.5 item 4).
*Test gaps found (no test added — this task may not edit tests):* reversed numbering (`2,1`) has no explicit
case (it is rejected by construction); nothing asserts that the primary and retry bodies **differ** (that is
P2-17's future regression test); `\r\n` and `«»` are handled but untested.

#### 5.12.6 Evidence re-verified for this review

| Claim | How it was checked | Result |
| --- | --- | --- |
| P2-A introduced 5 files, 15 tests | `git diff --numstat 533ae90 25b15c2 -- app`, `git diff -U0 … \| grep -c '^+    fun '` | 5 files, **+347/−13** (the report said +348 → corrected in §5.9), 15 new `fun`s, `@Test` 629 → 644 |
| P2-B introduced 4 files, 24 tests | `git diff --numstat e0e6c7d a2882f16 -- app` | 4 files, +385/−11, 24 new tests (17 + 7), `@Test` 644 → 668 |
| Every commit on the branch is recorded | `git log origin/main..HEAD` vs a scan of this file | 43 commits before this one; **3 were missing** (`ec59e7e`, `6296f80`, `f28f56c`) → now documented in §5.0 |
| Every cited run exists and is green | `gh run list` + SHA match per run id | 36 runs: 32 ✅ / 4 ❌; every cited id matches its commit |
| 7 commits have no run of their own | set difference runs↔commits | `ec59e7e`, `6296f80`, `f28f56c`, `b8e37de`, `fba87c6`, `c008f62`, `fee9d9e` — each contained in a later verified commit (§7.4 note) |
| PR #2 state | `gh pr view 2`, `gh api …/pulls/2` | OPEN, not draft, CLEAN, 0 reviews, 0 review comments, 0 issue comments, 43 commits, +36 462/−0 |
| Working tree | `git status --porcelain` | clean before this revision's edit (docs only) |

#### 5.12.7 What this review does **not** claim

No test was executed here, no build was produced here, no device was used, no review approval exists on
GitHub (0 reviews) and **P2-A and P2-B are not approved**. The green run 43574339617 proves that the 668
discovered `@Test` compile and that none failed on `de77ef1a`; it does not prove that any of the reviewed
behaviour is right on a real page. The three findings P2-17/P2-18/P2-19 were **open and unfixed** by design at this review; *(note added in
revision 3.3: all three were fixed and CI-verified in `eac0d0c`, §5.13)*.


### 5.13 P2 review fixes — P2-18, P2-17, P2-19 (`eac0d0c`, this revision)

**Scope.** The three findings raised by the §5.12 review, fixed **in the order the brief set**
(P2-18 → P2-17 → P2-19). One code commit, `eac0d0c`, touching eight files (three application, four test,
one decision log), CI run **43585853784** ✅ (*Unit tests* ✅ + *Build debug APK* ✅, 3m07s). No dependency,
Gradle, workflow, UI, OCR or other-engine change; P2-C/P2-D were **not** started; PR #2 was not merged,
closed, renamed or re-drafted.

#### 5.13.1 P2-18 — an ambiguous English word could still become a character name

**Cause.** P2-01 (`Glossary.accepts`, `Glossary.kt:142-152` — `:140-150` before this commit's deletions)
stopped *replacing* "Long/Sun/Song/Pan/Tan/Ding"
in the source text, but nothing downstream was gated. `NameMemory.plan` builds candidates from `phrases()`
plus `scan()`, and those words are built-in table keys, so `userHit` resolved them (`Origin.BUILT_IN`) and the
pinning loop (`NameMemory.kt:289-330`) accepted them. From there the word reached the model twice: as a line of
the page name sheet (`NameMemory.sheet`, handed to Gemini by `GeminiTranslator.kt:97-98`) and as a
post-processing rewrite (`NameMemory.applyPinned`, `GeminiTranslator.kt:129-141`). `scan` additionally lists
`long/sun/song/pan/tan/tang/tao` in `KNOWN_SENTENCE_NAMES` (`NameMemory.kt:161-172`), so even a sentence-initial
"Long ago…" was a candidate. The ML Kit path was already safe by accident — `SceneNames.pin` skips anything the
glossary already knows — which is why only the Gemini sheet/`applyPinned` path was exposed.

**Fix.** One shared predicate, `Glossary.isAmbiguousEnglish(word)` (`Glossary.kt:103-108`), consults the
existing `AMBIGUOUS_ENGLISH` set (P2-01's own list — no second list). `NameMemory.plan` calls it through
`isAmbiguousAutomaticName(name, origin)` (`NameMemory.kt:318-324`, helper `:344-359`) and `continue`s on a
**single-token automatic** candidate. Exempt by construction: `Origin.USER` (the reader's own Settings line),
multi-word/hyphenated spellings ("Song Lan", "Han Ji-woo"), and series memory — `CharacterResolver.resolve`
re-adds trusted records in its own records pass (`CharacterResolver.kt:130-150`), so a remembered "Sun" still
resolves. Character-memory scope, D1/D3/D6 and the P1 priority ladder are unchanged.
`NameForm.AMBIGUOUS_ENGLISH_WORDS` was deliberately left alone: it answers a different question ("is this a
given-name word?") for the resolver's `persistable` flag, and merging the two would have widened this fix into
a name-authority change.

**Rejected alternatives.** (a) Filtering inside `Glossary.effective()` — would have emptied the table that the
resolver and every multi-word name depend on. (b) Gating every occurrence of the set, including inside
"Song Lan" — breaks `GlossaryTest.songLanMultiWordWinsOverTheSongNoun`. (c) A fresh ambiguity list in
`NameMemory` — the exact drift that produced the finding. (d) Suppressing the name at the `sheet()` /
`applyPinned` boundary — the pinned list is also the series-memory input, so that would have changed memory
scope silently.

**Files.** `Glossary.kt` (+17/−29: the new predicate, minus the P2-19 path), `NameMemory.kt` (+24),
`GeminiTranslator.kt` (+10/−3, §5.13.2), `DECISIONS.md` (+2).

**Tests (executed by CI run 43585853784).** `NameMemoryTest` — `theAmbiguityGateOnlySilencesRealCandidates`
(proves the two words are ambiguous **and** that the scanner would offer them, so the gate is what removes
them), `ambiguousEnglishWordsAreNotAutoPinnedAsNames` ("The Sun was hot and the road was long." + "Long ago, …"
→ no table entry, no pinned entry and no sheet line for Sun/Long), `aUserEntryForAnAmbiguousWordIsStillPinned`
(`Sun = شمسي` → pinned, `Origin.USER`, sheet line present), `multiWordNamesWithAnAmbiguousWordStillPin`
("Song Lan" → سونغ لان, no bare "Song"), `unambiguousSingleWordNamesAreUnaffected` ("Ming" → مينغ,
`Origin.BUILT_IN`); `GlossaryTest` — `thePlannerSharesTheGlossaryAmbiguityRule` (six words ambiguous,
`prepare` leaves the bare word alone, `"X Lan"` is not ambiguous, "Wei" still translates); `CharacterResolverTest`
— `a remembered name survives the page planner's ambiguity gate` (a series record "Sun" still reaches the page
table with `reason = "series memory"`).

**Residual risk.** A character genuinely named "Sun"/"Long"/"Song"… is no longer auto-named on the Gemini path
unless the spelling is multi-word ("Long Wei"), the reader pinned it in Settings, or the series memory already
holds it — the documented P2-01 trade-off, now applied consistently. Single-token table keys that sit in
`AMBIGUOUS_ENGLISH` beyond the four the finding named ("Wang", "Li", "Ren", "Ma", "Tao", "Dong", "Mo", "Ye")
are gated too; that is intended, since they are ordinary English words as well.

#### 5.13.2 P2-17 — the HTTP-400 retry repeated the request that had just failed

**Cause.** `GeminiWire.buildRequestBody(jsonReply = true)` returns `buildFastBody(...)` with its own default
`jsonReply = true` (`GeminiWire.kt:227-229`). With `jsonReply = true` the primary body and the 400 retry body
were therefore produced by the same function with the same arguments — byte-identical
(`GeminiTranslator.kt:239-241` vs `:259`). A 400 caused by `responseMimeType` was retried with
`responseMimeType` again.

**Fix.** The retry always asks for the legacy fast body:
`GeminiWire.buildFastBody(chunk, sheet, jsonReply = false)` (`GeminiTranslator.kt:266`, rationale at
`:258-263`; the constructor KDoc `:53-55` updated). The primary request still honours the caller's flag, the
constructor default stays `false`, there is still exactly one retry, and the error policy is untouched.

**Rejected alternatives.** (a) Dropping the retry when `jsonReply = true` — loses the recovery for models that
reject `thinkingConfig`. (b) Building the retry through `buildRequestBody(..., thinkingLevel = null)` — would
also change the primary path's tuning. (c) Any additional retry or backoff — explicitly out of scope and a
change of error policy.

**Tests.** `GeminiTranslatorTest.http400RetryStillRunsWithJsonReplyTrue` (primary has `responseMimeType` and
`temperature 0.2`; retry has **no** `responseMimeType`, has `thinkingBudget 0` and `temperature 0.2`, no
`thinkingLevel`; `primary != retry`; the retry's own reply still parses into both bubbles),
`jsonRetryBodyCarriesThePageNameSheetToo` (P0-A's guarantee kept — every sheet line is still in the retry — with
the now-false "the retry is JSON" assertion removed), `http400RetryStillRunsWithJsonReplyFalse` (unchanged: both
bodies legacy, retry `thinkingBudget 0`), and the new `onlyHttp400IsRetriedWithTheLegacyBody`
(401/403/429/500/503 → exactly **one** request each, still a `Failure`).

**Residual risk.** A model that rejects both the JSON mode and the legacy fast body now fails after two
*different* requests instead of two identical ones — no worse. The 404 model-switch step still reuses the `body`
variable, so a 404 that follows a 400 retry re-sends the legacy body; that predates this change, is not a
P2-17 regression, and is recorded here rather than fixed (§8.4).

#### 5.13.3 P2-19 — the dead glossary path that bypassed the gate

**Cause.** `Glossary.applyToSource` (formerly `:85-98`), `replaceWord` (`:152-156`) and `ambiguousShort`
(`:164-165`) formed a second, whole-word replacement path that never called `accepts` (it guarded only
≤3-letter tokens against `ENGLISH_WORDS`) — i.e. it would have re-introduced P2-01 for anyone who called it.
`grep -rn "applyToSource" app/` at `3c1f64c` returned the definition only: no production caller, no test caller.
`DECISIONS.md:687` was the only other mention.

**Fix.** All three deleted, together with their orphaned KDoc; `transliterateUnknown` (which `applyToSource`
also called) stays private and is still reached through `applyCompiled`. `accepts` + `AMBIGUOUS_ENGLISH` is now
the single replacement rule. `DECISIONS.md` H4 keeps its historical text and gains a two-line note that the path
was removed as dead code.

**Rejected alternative.** Routing `applyToSource` through `accepts` — that would preserve an unused public API,
a second entry point and a second set of rules to keep in sync, with no caller to justify it.

**Tests.** No test referenced the deleted functions. That the live rules still govern replacement is pinned by
the whole `GlossaryTest` suite (19 tests, including `ordinaryLongAndSunStayEnglish`,
`sentenceInitialLongAndSunAreStillOrdinaryWords`, `shortCollisionsStayEnglish`, `genuineNamesStillTranslate`,
`aPinnedNameIsAlwaysHonoured`, `songLanMultiWordWinsOverTheSongNoun`, `theTableStillCarriesTheNameForTheMemory`)
plus the new `thePlannerSharesTheGlossaryAmbiguityRule` — all executed in run 43585853784.

**Residual risk.** A future "replace everything" helper (batch tooling, migration) must be written against
`accepts`; the deletion makes that requirement explicit instead of leaving a tempting shortcut in place.

**Verification for this work package** (commands and outcomes in §10.3):
`./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain` was **NOT executed locally** —
this sandbox has no JDK (`java: command not found`; the wrapper exits 1 with *"JAVA_HOME is not set and no 'java'
command could be found in your PATH"*). It was executed by **CI run 43585853784** at `eac0d0c`: job `build`
success, *Unit tests* ✅, *Build debug APK* ✅. Discovered `@Test` 668 → **676** (+8). Per-test
executed/passed/skipped counts remain **UNVERIFIED** (§2.3), and **no device claim** is made anywhere.

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

| Item | Level reached | Evidence at `eac0d0c` |
| --- | --- | --- |
| Translation-path audit (P2-01…P2-16) | **Audited** | `01e12a6`, run 43567398304 ✅; content in §5.8 and §8 |
| **P2-A / P2-01** — glossary no longer rewrites ordinary English | Implemented · Tested · CI-verified · **reviewed (§5.12), not approved** (its two follow-ups are now fixed, §5.13) | `Glossary.accepts` (`Glossary.kt:140-150`), `TERMS` (`:338`), `AMBIGUOUS_ENGLISH` (`:350`); 8 new tests in `GlossaryTest`; run 43569302435 ✅; review says the fix is correct and the gate is only in the glossary |
| **P2-A / P2-02** — remote engines get page/reading order | Implemented · Tested · CI-verified · **reviewed (§5.12), not approved** | `PagePlan.remoteOrder` (`:130`), `PagePlan.progressiveOrder` (`:138`); `FloatingButtonService.kt:2784-2785, 2819, 2848, 2858, 2863`; 7 new tests in `TranslationOrderingTest`; run 43569302435 ✅; review says page order is correct end to end and Live is untouched (P2-11 scope corrected in §8.2) |
| **P2-B / P2-03** — `jsonReply` never reaches the primary request | Implemented · Tested · CI-verified · **reviewed (§5.12), not approved** (P2-17 fixed in §5.13.2) | `GeminiTranslator.kt:239-241` now passes the field to `GeminiWire.buildRequestBody`; 5 new tests in `GeminiTranslatorTest`; run 43573847121 ✅ (§5.11); review confirms the propagation and the preserved default, and finds the 400 ladder degenerate when the flag is `true` |
| **P2-B / P2-04** — positional reply parsing can misalign bubbles | Implemented · Tested · CI-verified · **reviewed (§5.12), not approved** | `GeminiWire.parseJsonTranslations` → `positionalMap` (`GeminiWire.kt:323-443`): label/artifact stripping, exact count match, numbering check; 17 new tests in `GeminiWireTest` + 2 end-to-end in `GeminiTranslatorTest`; run 43573847121 ✅ (§5.11); review found no misalignment path, one documented limitation (JSON renumbering) and three test gaps |
| **P2-18 (review fix)** — no ambiguous English word becomes a page name | Implemented · Tested · CI-verified (§5.13.1) | `Glossary.isAmbiguousEnglish` (`Glossary.kt:103-108`), `NameMemory.isAmbiguousAutomaticName` / gate (`NameMemory.kt:318-324`, `:344-359`); 7 new tests; run 43585853784 ✅ |
| **P2-17 (review fix)** — the 400 retry is a genuinely different, non-JSON body | Implemented · Tested · CI-verified (§5.13.2) | `GeminiTranslator.kt:258-266`; 1 new test + 2 tightened; run 43585853784 ✅ |
| **P2-19 (review fix)** — no second glossary replacement path | Removed · Tested · CI-verified (§5.13.3) | `Glossary.kt` −29 lines, no caller existed; `GlossaryTest` 19 tests green in run 43585853784 ✅ |
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

The 8 tests added by the review fixes (§5.13, `eac0d0c`):
`NameMemoryTest` — `theAmbiguityGateOnlySilencesRealCandidates`, `ambiguousEnglishWordsAreNotAutoPinnedAsNames`,
`aUserEntryForAnAmbiguousWordIsStillPinned`, `multiWordNamesWithAnAmbiguousWordStillPin`,
`unambiguousSingleWordNamesAreUnaffected`; `GlossaryTest` — `thePlannerSharesTheGlossaryAmbiguityRule`;
`CharacterResolverTest` — `a remembered name survives the page planner's ambiguity gate`; `GeminiTranslatorTest` —
`onlyHttp400IsRetriedWithTheLegacyBody`. Two existing 400 tests (`http400RetryStillRunsWithJsonReplyTrue`,
`jsonRetryBodyCarriesThePageNameSheetToo`) were **tightened**, not weakened: they now require the two bodies to
differ and the retry to carry no `responseMimeType`.

The 24 tests added by P2-B (§5.11):
`GeminiWireTest` — `exactPositionalReplyMapsEachBubbleInOrder`,
`arabicStandaloneLabelIsDroppedInsteadOfBecomingABubble`, `englishStandaloneLabelIsDroppedInsteadOfBecomingABubble`,
`labelPrefixedOnARealLineIsStrippedAndKept`, `extraExplanatoryLineRejectsTheReplyInsteadOfShifting`,
`extraTranslationLineIsRejected`, `missingTranslationLineIsRejected`, `blankLinesAndFormattingArtifactsAreIgnored`,
`explicitIdReplyIsHonouredEvenOutOfOrder`, `jsonObjectReplyIsStillParsed`, `bareIdKeyedObjectAndArrayReplyStillParse`,
`mixedExplicitAndBareLinesKeepsOnlyTheExplicitIds`, `numberMarkedLinesAreMappedByTheirOwnNumbers`,
`numberingThatDisagreesWithTheIdsIsRejected`, `partlyNumberedReplyIsRejected`,
`positionalReplyForASingleBubbleStillWorks`, `replyWithOnlyLabelsAndArtifactsParsesNothing`;
`GeminiTranslatorTest` — `jsonReplyTrueSendsTheJsonPrimaryRequest`, `jsonReplyFalseKeepsTheLegacyPrimaryRequest`,
`defaultConstructorKeepsTheLegacyPrimaryRequest`, `http400RetryStillRunsWithJsonReplyTrue`,
`http400RetryStillRunsWithJsonReplyFalse`, `positionalReplyWithALabelStillMapsEveryBubbleEndToEnd`,
`ambiguousPositionalReplyIsRejectedInsteadOfShifted`.

### 6.5 P3–P6 — performance/caching, OCR, text cleanup/rendering, release

**Nothing has been executed under these phases.** They exist as definitions (§3.1) and as candidate work in
§8.3. No commit, test, run or file in this repository belongs to them. Reporting them as "done" — or
attributing pre-existing v0.11.0 features to them — would be invented progress.

### 6.6 Summary

| Phase | Highest level reached | Blocking what remains |
| --- | --- | --- |
| P0 | CI-verified (C1, C2) | C3/C4/C5, RTL, offline, perf; device validation |
| P1 | CI-verified | device validation of the series UI |
| P2 | CI-verified + reviewed (P2-A, P2-B); the 3 review findings are fixed and CI-verified (`eac0d0c`) | human approval, P2-C/P2-D, device validation |
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
| `a7b276f` / `81919f9` | after P2-A (+15) | 644 | 70 |
| `a2882f16` | after P2-B (+24) | 668 | 70 |
| `eac0d0c` | after the P2 review fixes (+8: 5 + 1 + 1 + 1) | **676** | 70 |

**Discovered vs executed:** discovered = **676** at this HEAD (`grep -rn '@Test' app/src/test` summed).
**Executed = 676 is UNVERIFIED for this commit**: run 43585853784 is green, and a green run emits no annotation
and its JUnit XMLs are unreachable (§2.3), so the report states only what is provable — the *Unit tests* step
succeeded on the tree that contains those 676 tests, i.e. none of them failed. That includes the 8 tests added
by §5.13: they compiled and passed in **CI**, which is the only executor available here (no JDK in this
sandbox). The last run with a readable executed count is `25b15c2` (*"644 tests completed, 1 failed"*, §7.6),
which proves the older suite size. **Skipped = UNVERIFIED everywhere** (a skipped test does not fail the task,
and no annotation reports skips).

### 7.4 Every CI run on this branch (38 runs: 34 success, 4 failure)

| Run | Commit | Created (UTC) | Result |
| --- | --- | --- | --- |
| [43585853784](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43585853784) | `eac0d0c` | 2026-11-14 10:33:33 → 10:36:40 | ✅ **current head — P2-18 + P2-17 + P2-19 (§5.13)** |
| [43575632319](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43575632319) | `3c1f64c` | 2026-11-14 08:23:50 → 08:28:00 | ✅ §5.12 review (docs, revision 3.2) |
| [43574339617](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43574339617) | `de77ef1a` | 2026-11-14 08:08:03 → 08:12:12 | ✅ docs revision 3.1 |
| [43573847121](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43573847121) | `a2882f16` | 2026-11-14 08:01:57 → 08:05:29 | ✅ P2-B code (§5.11) |
| [43572172053](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43572172053) | `e0e6c7d` | 2026-11-14 07:40:11 | ✅ documentation audit close |
| [43571824620](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43571824620) | `2f3ad07` | 2026-11-14 07:35:33 → 07:39:29 | ✅ documentation audit |
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
listed above is an ancestor of `eac0d0c` (checked with `git merge-base --is-ancestor`).

**Seven of the 45 commits on this branch have no run of their own** — `ec59e7e`, `6296f80`, `f28f56c`
(pre-P0, §5.0), `b8e37de` (README), and three code commits: `fba87c6` (P0-A fix), `c008f62` (C2 fix),
`fee9d9e` (P1.1-A fix). A run is created per pushed head, so an intermediate commit of a multi-commit push is
executed only as part of the next verified tree that contains it (that is exactly what §3.2's "ancestor" wording
allows). Each of the three code commits is contained in a later green run — `1940487` (43514068424), `08004c4`
(43563394305) and `e546470` (43561318751) respectively — so none of them is unverified, but **no run was ever
executed at those exact trees**.

### 7.5 What a green run proves — and what it does not

**Proves:** the JVM test sources of that commit (676 `@Test` at `eac0d0c`) compile and none failed; `:app:assembleDebug` produced a debug APK;
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

### 8.1 P2 findings register (audit `01e12a6`; status re-verified at `eac0d0c`)

| ID | Type | Priority | Finding | Status |
| --- | --- | --- | --- | --- |
| **P2-01** | Bug | High | `Glossary` rewrote ordinary English words before translation | **Implemented · tested · CI-verified (P2-A) · reviewed (§5.12): correct — not approved; its follow-ups P2-18/P2-19 are fixed (§5.13)** |
| **P2-02** | Bug | High | Remote chunks built from visual-priority order while the prompt claims page order | **Implemented · tested · CI-verified (P2-A) · reviewed (§5.12): correct — not approved** |
| **P2-03** | Bug | Medium | `jsonReply` never reached the primary request (`GeminiTranslator.kt:232` at the audit baseline) | **Fixed in P2-B (`a2882f16`) · tested · CI-verified · reviewed (§5.12): correct — not approved; follow-up P2-17 fixed (§5.13)** |
| **P2-04** | Bug | Medium | Positional "one line per id" parse could misalign bubbles (`GeminiWire.kt:325` at the audit baseline) | **Fixed in P2-B (`a2882f16`) · tested · CI-verified · reviewed (§5.12): correct, one limitation — not approved** |
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
| **P2-17** | Bug (review) | Low | With `jsonReply = true` the HTTP-400 retry re-sends a **byte-identical** body (`GeminiTranslator.kt:239-241` vs `:259`) | **Fixed in `eac0d0c` · tested · CI-verified (run 43585853784) — §5.13.2** |
| **P2-18** | Risk (review) | Medium | The name planner/sheet still treats ambiguous capitalised words (`Long`, `Sun`, `Song`, `Pan`…) as names; P2-01 gated only the glossary | **Fixed in `eac0d0c` · tested · CI-verified (run 43585853784) — §5.13.1** |
| **P2-19** | Risk (review) | Low | `Glossary.applyToSource`/`replaceWord` bypass `accepts` and have no caller (dead code that would reintroduce P2-01 if reused) | **Removed in `eac0d0c` · tested · CI-verified (run 43585853784) — §5.13.3** |
| **P2-N1…N5** | Not a problem | — | Idempotent `prepare`; per-bubble triage; pinned override vs stale cache; offline independence; names survive chunking | **verified correct** |

### 8.2 Open findings — evidence anchors and the minimal fix each needs

Line numbers are the audit baseline (`fe5aa6a`); re-verify before editing. P2-03 and P2-04 were fixed by P2-B
(`a2882f16`, §5.11); P2-17, P2-18 and P2-19 were fixed by `eac0d0c` (§5.13) and their rows below record what was
actually built against what had been proposed. Every other row is still open.

| ID | Anchor (baseline) | Minimal fix proposed | Regression the fix must add |
| --- | --- | --- | --- |
| P2-03 **(fixed, `a2882f16`)** | `GeminiTranslator.kt:232` (`jsonReply = false`), KDoc `:47-52` | pass the field through and document which body is primary | `jsonReply = true` → body contains `responseMimeType`; `false` → legacy body; land before P2-16 |
| P2-04 **(fixed, `a2882f16`)** | `GeminiWire.kt:316-330`, fallback `:321-328` | positional mapping only on an exact count match, after stripping labels/artifacts — a changed comparison alone is not a fix | equal-count reply maps by position; a label line maps nothing; missing/extra lines map nothing |
| P2-05 | `GeminiTranslator.kt:87-99` vs `:128-136` | one authority: give the engine the same series table the sheet used | a series table + Gemini post-processing test (none exists) |
| P2-06 | `MlKitTranslator.kt:154-181`, abort `:174-176` | omit the failed long block (empty) and continue | a failing chunk leaves one empty block, page still `Success` |
| P2-07 | `MlKitTranslator.kt:246` clears `pageNames`; early returns `:103-108` | `try/finally` (or clear at entry) | two pages on one translator instance never share names |
| P2-08 | `LibreTranslateTranslator.kt:57-58`, `:80-100` | per-bubble triage like ML Kit; attach the sheet | one rejected bubble does not fail the page |
| P2-09 | `FloatingButtonService.kt:3705-3711` | do not replace a local patch with a worse remote one | arbitration test on the relay path |
| P2-10 | `TranslationCache.kt:18-30`, `CachingTranslator.kt:224-232`, `TranslationMemo.kt` | document the choice; a context-bearing key only with evidence | none today (documented trade-off) |
| P2-11 | `PagePlan.kt:61-63` (KDoc) + **both** capture call sites: `FloatingButtonService.kt:2747-2750` (tap) and `:3282-3286` (Live) — scope corrected by this review, the report previously said "Live path" only | pass `preprocess = Glossary::prepare` at both sites | `Wei Lin` / `wei lin` dedupe to one item on both capture paths |
| P2-12 | `MeaninglessText.kt:163-177`, `PagePlan.kt:74-84` | make the prompt and the planner agree about SFX | SFX kept, or the prompt claim removed |
| P2-13 | `NameMemory.kt:259`, `:287` | index by id, not by position | latent — no caller passes `pageArabic` today |
| P2-14 | `ArabicValidator.kt:59-90`, `:144-166` | detect a truncated short answer and a 1–2 word English remnant | guard tests for both |
| P2-15 | `GeminiTranslator.kt:133-142` vs the decorator path | unify post-processing order | same text judged on both paths |
| P2-16 | `GeminiTranslator.kt:321-337`, `GeminiWire.kt:303` | treat `MAX_TOKENS` truncation (empty the cut bubble) | a truncated reply leaves no half sentence |
| P2-17 **(fixed, `eac0d0c`)** | `GeminiTranslator.kt:239-241` (primary) vs `:256-262` (400 retry); `GeminiWire.kt:227` (router default `jsonReply = true`) | proposed: retry with `jsonReply = false`; **built**: `buildFastBody(chunk, sheet, jsonReply = false)` at `GeminiTranslator.kt:266`, constructor default untouched, no extra retry | with `jsonReply = true` a 400 produces two **different** bodies, the retry has no `responseMimeType`, the retry's reply still parses, and 401/403/429/500/503 are not retried (`onlyHttp400IsRetriedWithTheLegacyBody`) |
| P2-18 **(fixed, `eac0d0c`)** | `NameMemory.kt:161-172` (`KNOWN_SENTENCE_NAMES`), `NameScript.kt:208-216` (`PINYIN_SYLLABLES`), `NameMemory.plan` `:251-335` with its pinning loop at `:289-330` (no ambiguity gate), consumed by `NameMemory.sheet` `:413-416` and `GeminiTranslator.kt:132-141` (`applyPinned`) | proposed: expose the ambiguity test and skip a single-token candidate unless USER/series; **built**: `Glossary.isAmbiguousEnglish` (`Glossary.kt:103-108`) + `NameMemory.isAmbiguousAutomaticName` gate (`NameMemory.kt:318-324`, `:344-359`); USER and multi-word exempt, series memory untouched | `The Sun was hot and the road was long.` → no sheet line and no pinned entry for `Sun`/`Long`; "Song Lan" still pins; a USER `Sun = …` still pins; a series-memory "Sun" still resolves; "Ming" unchanged |
| P2-19 **(removed, `eac0d0c`)** | `Glossary.kt:90-98` (`applyToSource`) and `:152-156` (`replaceWord` / `ambiguousShort`) | delete the dead path, or route it through `accepts`; **chosen**: delete (no caller existed) | no production caller (`grep -rn "applyToSource" app/` → definition only, now nothing); the live rule stays `accepts` + `AMBIGUOUS_ENGLISH`, pinned by the 19-test `GlossaryTest` suite |

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

### 8.5 Risks carried by the P2-B change

1. **The shipped Gemini request is unchanged** (the default is still `false`), so P2-03 buys correctness of the
   flag, not a faster or better page in the app as shipped. Whether the app should *choose* the JSON body is a
   separate product decision (and the prompt/quality side of it belongs to P2-C/P2-D).
2. **A reply the model renumbers from 1 while the chunk's ids are 19…27 is now accepted positionally** (clean
   `1..N` numbering plus an exact count match). Any other disagreement (`1,3` for ids `1,2`, mixed numbering,
   gaps, duplicates) is refused — the previous code accepted all of them and shifted.
3. **A legitimate bubble that ends in a colon and is ≤5 words** (e.g. `اسمع:`) is treated as a caption and
   dropped, which turns that reply into a parse error (retry, then the on-device draft). Chosen deliberately:
   a refused reply costs a retry, a misaligned one paints the wrong text over art permanently.
4. **Two labels plus an equal number of translations can still not be distinguished from a merged/split
   reply** — the filter removes standalone labels, but a reply that both adds an Arabic caption *and* merges
   two bubbles keeps the line count and remains undetectable without a protocol change (out of scope).
5. **This is CI-verified only**: no real Gemini traffic exists in this environment (no key, no network) and no
   device has run the app, so the new parse path has never seen a real model reply.
6. **Renumbering is only recovered in plain lines, not in JSON** (§5.12.5): a `{"1":…}` object for a chunk whose
   ids are 19…27 maps nothing, fails the page safely and falls back to the on-device draft. Closing that
   asymmetry means teaching `jsonMap` a positional interpretation — deliberately not done here.
7. **Every run on this branch comes from the `pull_request` event.** The workflow's `push` allowlist names
   `main`, `master`, `arena/cebceb7a-manhwalens` and `v*` tags (`.github/workflows/android.yml:5-6`), i.e. a
   *different* arena branch, so a push to this branch is verified only because PR #2 is open. Closing the PR
   would silently stop CI verification of this branch.

### 8.6 Risks carried by the P2 review fixes (`eac0d0c`)

1. **Ambiguity trade-off now applies on the Gemini path too.** A one-word character name that is also an
   everyday English word (`Sun`, `Long`, `Song`, `Pan`, `Tan`, `Ding`, and everything else in
   `AMBIGUOUS_ENGLISH` — `Wang`, `Li`, `Ren`, `Ma`, `Tao`, `Dong`, `Mo`, `Ye`, …) is no longer auto-pinned.
   Escape hatches, none of which needs a code change: a multi-word spelling ("Long Wei"), the reader's own
   Settings line, and the series memory of a selected series. This is the same trade-off §8.4 records for
   P2-01, now consistent across layers.
2. **Two ambiguity concepts still exist, deliberately.** `Glossary.AMBIGUOUS_ENGLISH` (table keys that are
   ordinary words — what P2-01/P2-18 consult) and `NameForm.AMBIGUOUS_ENGLISH_WORDS` (given-name words —
   consulted by `CharacterResolver.decide` for `persistable`). They answer different questions and merging
   them would be a name-authority change (P2-05/P2-C), so a future reader must not "unify" them by accident.
3. **The 404 step still reuses `body`.** If a chunk gets 400 → retry → 404, the model-switch request carries
   the legacy body. Pre-existing, harmless (the body is still valid) and out of P2-17's scope.
4. **No per-test evidence.** CI green proves the 676 tests compiled and none failed; it does not prove which
   of them ran or that the new behaviour is right on a real page (§2.3, §7.5).

### 8.7 Environment blockers (`BLOCKERS.md` remains the register)

No JDK/Android SDK/`gradle`/`kotlinc` locally; lint unavailable offline; R8 disabled; debug APK only; no real
Gemini/Libre traffic (no key, no network); executed/skipped test counts unreadable; no device.

---

## 9. Current active phase and exact next action

### 9.1 Current active phase

**P2 — translation quality and context management.** Three sub-steps are delivered:
**P2-A** = P2-01 + P2-02 (`25b15c2`, test fix `a7b276f`, 15 tests, run 43569302435 ✅),
**P2-B** = P2-03 + P2-04 (`a2882f16`, 24 tests, run 43573847121 ✅) and the **review fixes**
`eac0d0c` = P2-18 + P2-17 + P2-19 (8 new tests, 2 tightened, run 43585853784 ✅, §5.13).
The independent review of 2026-11-13 (§5.12) found the four earlier fixes **correct as implemented** and raised
three findings; **all three are now fixed and CI-verified**. Nothing is approved: GitHub still holds
**0 reviews**, and nothing is device-verified. **P2 is not complete and no phase is "Completed"** (§3.2).
P2-C (P2-05, needs a decision), P2-D (P2-06/P2-08/P2-14/P2-16) and P3–P6 remain untouched.

### 9.2 Exact next action

1. **~~Decide the three review findings~~ — done in `eac0d0c` (§5.13)**, in the required order
   P2-18 → P2-17 → P2-19, each with the regression test §8.2 had prescribed.
2. **Obtain the human approval that does not exist yet** for P2-A, P2-B **and the review fixes** (evidence: §5.9, §5.11, §5.12,
   §6.4, runs 43568984747 ❌, 43569302435 ✅, 43573847121 ✅). A green run is not an approval.
3. **Only then** P2-C = P2-05 (series sheet vs page-local `applyPinned`): open with a decision, not with code.
   P2-18 removed one *consumer* of the page-local table (the ambiguous words), but the two-authority question
   itself is untouched: the series sheet still competes with `applyPinned`.
4. **Device validation** of P2-A (ordering, glossary output on a real page) and P2-B (reply parsing against a
   real model) is still outstanding for both; `MANUAL_TESTS.md` is the only checklist.
5. P2-D (P2-06, P2-08, P2-14, P2-16) and P3–P6 stay unstarted; each needs its own scope approval.

### 9.3 Explicitly not done by this task

*No documentation-only restriction this time:* this revision's work package **is code** (commit `eac0d0c`).
The bounds that were respected:

* exactly the three findings, in the order P2-18 → P2-17 → P2-19 — no other P2 item was touched;
* **no** dependency/Gradle/workflow change, **no** UI, OCR or other-engine change, **no** broad refactoring;
* **no** character-memory scope change and **no** change to D1/D3/D6 or to the P1 priority ladder;
* **no** name-authority redesign (P2-05) and **no** P2-C or P2-D work;
* **no** extra retry, no change of the error policy (only the *body* of the single 400 retry changed);
* **no** second glossary replacement path — the dead one was removed instead;
* PR #2 was **not** merged, closed, renamed or re-drafted; `main` was not touched;
* the report commit that follows `eac0d0c` is documentation-only, so every application and test file is
  byte-identical to the CI-verified tree — and its own run is not claimed here.

**Test-execution honesty:** the new and old tests were executed **only in CI** (run 43585853784). Nothing was
executed in this sandbox — it has no JDK, no Gradle, no `kotlinc` and no Android SDK (§7.2) — and **no device
claim** is made for any change (§7.7).

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

### 10.3 Validation performed for revision 3.3 (commands and exact outcomes)

| # | Check | Command | Outcome |
| --- | --- | --- | --- |
| 1 | Branch / HEAD / tree | `git rev-parse --abbrev-ref HEAD`, `git rev-parse HEAD`, `git status --porcelain` | `arena/8a718184-manhwalens`, `eac0d0c19cf2a135cbc14af2196639e4bd997579`, tree clean before this report edit |
| 2 | **Mandatory build/test command, local attempt** | `./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain` | **NOT EXECUTED — impossible here.** Exit 1: `ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.`; `java -version` → `java: command not found`. No Gradle/`kotlinc`/Android SDK either (§7.2). Nothing is claimed from this attempt. |
| 3 | Same command, executed by CI | `gh run watch 43585853784 --interval 20 --exit-status` | exit 0 — job `build` **success**, steps *Unit tests* ✅ and *Build debug APK* ✅ (3m07s) at `eac0d0c`; identity in row 4 |
| 4 | Run identity and timing | `gh api repos/…/actions/runs/43585853784` | `success`, `head_sha=eac0d0c…`, created 2026-11-14 10:33:33Z, updated 10:36:40Z, attempt 1 |
| 5 | Test-count provenance | `grep -rn '@Test' app/src/test \| wc -l` at `eac0d0c` | **676** (668 at `3c1f64c`; +8: 5 `NameMemoryTest`, 1 `GlossaryTest`, 1 `CharacterResolverTest`, 1 `GeminiTranslatorTest`) |
| 6 | Tests added / tightened, per file | `git diff --numstat eac0d0c~1 eac0d0c`, `git diff -U0 eac0d0c~1 eac0d0c -- app/src/test \| grep -c '^+    fun '` | 4 test files, 8 new `fun`; `GeminiTranslatorTest` also **lost** two assertions that had encoded the P2-17 defect (`requestBodies[1].contains("responseMimeType")`, `retry.contains(responseMimeType)`) and gained stricter ones |
| 7 | Diff scope of the work package | `git diff --numstat 3c1f64c eac0d0c` | 8 files: `Glossary.kt` +17/−29, `NameMemory.kt` +24, `GeminiTranslator.kt` +10/−3, `DECISIONS.md` +2, `CharacterResolverTest.kt` +16, `GlossaryTest.kt` +28/−2, `NameMemoryTest.kt` +77, `GeminiTranslatorTest.kt` +32/−6. **No** `gradle/`, `.kts`, `.github/`, UI, OCR or engine file |
| 8 | P2-19 dead-code claim re-verified before deleting | `grep -rn "applyToSource\|replaceWord\|ambiguousShort" app/ *.md` (at `3c1f64c`) | definitions only in `Glossary.kt` + one `DECISIONS.md:687` mention; **no caller** in `app/src/main` or `app/src/test` |
| 9 | Only one ambiguity list consulted | `grep -rn "isAmbiguousEnglish\|AMBIGUOUS_ENGLISH" app/src/main` | `Glossary.isAmbiguousEnglish` (definition) + `accepts` + `NameMemory.isAmbiguousAutomaticName`; `NameForm.AMBIGUOUS_ENGLISH_WORDS` remains separate and untouched by design (§8.6 item 2) |
| 10 | P2-17 retry claim | `grep -n "buildFastBody" app/src/main/java/com/manhwalens/app/translate/gemini/GeminiTranslator.kt` | one retry call with `jsonReply = false` (`:266`, rationale `:258-263`); the primary call still passes the field (`:241`) |
| 11 | PR #2 untouched | `gh pr view 2 --json state,isDraft,mergeStateStatus,headRefOid,reviewDecision,changedFiles,additions,deletions,commits` | `OPEN`, `isDraft=false`, `CLEAN`, head `eac0d0c…`, **0 reviews**, 45 commits, 220 files, +36 821/−0 |
| 12 | Run inventory | `gh run list --branch … --limit 100 --json databaseId,headSha,status,conclusion` (Python count) | **38 runs: 34 ✅ / 4 ❌**; failures unchanged (`43513473811`, `43517478032`, `43517986742`, `43568984747`) |
| 13 | Commits without a run | set difference runs ↔ `git rev-list origin/main..HEAD` | **7 of 45** (`ec59e7e`, `6296f80`, `f28f56c`, `b8e37de`, `fba87c6`, `c008f62`, `fee9d9e`) — unchanged, each contained in a later green run (§7.4) |
| 14 | Ancestry of cited commits | `git merge-base --is-ancestor <sha> HEAD` | YES for `3c1f64c`, `de77ef1a`, `a2882f16`, `a7b276f`, `25b15c2` and every run SHA listed in §7.4 |
| 15 | Syntax sanity of the edited Kotlin | manual re-read of the full 8-file diff before committing; no compiler available locally | structurally sound by inspection; **the Kotlin compiler in CI is the authoritative check** and it passed (row 3) |
| 16 | Markdown integrity | Python scan of every `*.md` for local link targets (inline code spans ignored) and a backslash-aware pipe/cell count per table | run **after** this revision's edits: 0 broken links, 0 table-cell mismatches, §10.3a/b/c renumbered consistently |
| 17 | Scope of the report commit itself | `git status --porcelain`, `git diff --stat` | **one file**: `REPOSITORY_REPORT.md` — no `app/`, `gradle/`, `.kts` or `.github/` change |

### 10.3a Validation performed for revision 3.2 (kept for provenance)

| # | Check | Command | Outcome |
| --- | --- | --- | --- |
| 1 | Branch / HEAD / tree | `git rev-parse --abbrev-ref HEAD`, `git log -1`, `git status --porcelain` | `arena/8a718184-manhwalens`, `de77ef1a`, tree clean before the edit |
| 2 | Remote sync | `git fetch origin && git status -sb` | in sync with origin at the start of the task |
| 3 | PR state and reviews | `gh pr view 2 --json …`, `gh api repos/…/pulls/2` | `open`, `isDraft=false`, `CLEAN`, **0 reviews / 0 review comments / 0 issue comments**, 43 commits, 220 files, +36 462/−0 |
| 4 | CI runs | `gh run list --branch … --limit 100` (36 ids) + per-id SHA match | 36 runs, 32 ✅ / 4 ❌; every cited id matches its commit; newest 43574339617 ✅ at `de77ef1a` |
| 5 | Commits without a run | set difference runs↔commits | 7 of 43 (`ec59e7e`, `6296f80`, `f28f56c`, `b8e37de`, `fba87c6`, `c008f62`, `fee9d9e`) — documented in §7.4 with the run that contains each |
| 6 | Commit coverage of this report | `git log origin/main..HEAD` vs a scan of this file | 3 commits were **undocumented** (`ec59e7e`, `6296f80`, `f28f56c`) → added to §5.0; the rest match |
| 7 | P2-A scope and size | `git diff --numstat 533ae90 25b15c2 -- app`, `git diff -U0 … \| grep -c '^+    fun '` | 5 files, **+347/−13** (report said +348 → corrected), 15 new tests, `@Test` 629 → 644 |
| 8 | P2-B scope and size | `git diff --numstat e0e6c7d a2882f16 -- app` | 4 files, +385/−11, 24 new tests (17 + 7), `@Test` 644 → 668 |
| 9 | Reviewed source anchors | `grep -n` at `de77ef1a` | `Glossary.kt:90/100-108/140-150/152-156/338/350`; `PagePlan.kt:61-63/82-95/118/130/138-139`; `NameMemory.kt:161-172/289`; `NameScript.kt:208-216`; `GeminiTranslator.kt:56/129-141/239-241/256-262`; `GeminiWire.kt:227/323-443`; `FloatingButtonService.kt:2747-2750/2784-2785/2837/2852/2861/2897/3298/3316/3575/3706` — all present |
| 10 | Dead-code claim (P2-19) | `grep -rn "applyToSource" app/` | only the definition at `Glossary.kt:90`; no caller |
| 11 | Missing-`preprocess` claim (P2-11) | `grep -n "PagePlan.prepare(" app/src/main` | two production call sites (`:2747`, `:3282`), **neither** passes `preprocess` |
| 12 | Algorithm/behaviour claims | read-only review of code + tests (§5.12); no execution | the review is static by construction; the only executable evidence is run 43574339617 (job `build` success, *Unit tests* ✅, *Build debug APK* ✅) |
| 13 | Markdown link / table integrity | Python scan of every `*.md` for local link targets (inline code spans ignored); backslash-aware pipe/cell count per table | run **after** this revision's edits: 0 broken links, 0 table-cell mismatches, all ten top-level sections in order |
| 14 | Scope of this revision | `git status --porcelain`, `git diff --stat` | **one file**: `REPOSITORY_REPORT.md`; no `app/`, `.github/`, `.kts`, `gradle/` or dependency change |
| 15 | Build/test execution here | `which java javac gradle`, `$ANDROID_HOME` | absent/unset — no local run possible, none claimed; CI remains the only executor |

### 10.3b Validation performed for revision 3.1 (kept for provenance)

| # | Check | Command | Outcome |
| --- | --- | --- | --- |
| 1 | Branch / HEAD / tree | `git rev-parse --abbrev-ref HEAD`, `git log -1 --format='%H %s'`, `git status --porcelain` | `arena/8a718184-manhwalens`, `a2882f16`, tree clean before the edit |
| 2 | Remote sync | `git fetch origin && git status -sb` | in sync with `origin/arena/8a718184-manhwalens` before and after the push |
| 3 | PR state | `gh pr view 2 --json …` | `OPEN`, `isDraft=false`, `mergeStateStatus=CLEAN`, base `main`, head `a2882f16`, 0 reviews — untouched |
| 4 | CI runs | `gh run list --branch … --limit 100` (+ Python count) | 35 runs, 31 success / 4 failure; ids compared against this file: only `43573847121` was missing |
| 5 | New run | `gh run watch 43573847121 --interval 20 --exit-status` | exit 0 — job `build` success, *Unit tests* ✅, *Build debug APK* ✅ |
| 6 | Test counts | `git grep -c '@Test' <sha> -- app/src/test` summed | `e0e6c7d` 644 → `a2882f16` **668** (+24: 17 wire, 7 translator) |
| 7 | Diff of this work package | `git diff --numstat e0e6c7d a2882f16 -- app` | 4 files, +385/−11 (exactly the four Gemini files) |
| 8 | P2-A intact | `git diff --stat HEAD -- Glossary.kt PagePlan.kt FloatingButtonService.kt …Test.kt` | empty — P2-A files untouched |
| 9 | Source anchors cited in §5.11/§6.4/§8.2 | `grep -n` at `a2882f16` | `GeminiTranslator.kt:239-241`, `:259`; `GeminiWire.kt:323`, `:344`, `:351`, `:358`, `:364`, `:367`, `:378`, `:409`, `:412`, `:429` — all present |
| 10 | Syntax sanity of the edited Kotlin | Python brace/paren/string scan of the 4 files | balanced, no unterminated literal (in CI the Kotlin compiler is authoritative) |
| 11 | Algorithm design check | Python transliteration of `replyLines` + `positionalMap` over the 14 mapping cases in the new tests | 14/14 matched the expected id → translation maps (**not** a test run) |
| 12 | Ancestry of every cited commit | `git merge-base --is-ancestor <sha> HEAD` | YES for all 8 cited commits (`01e12a6`…`e0e6c7d`) and for `a2882f16` = HEAD |
| 13 | Local Markdown link check / table integrity | Python scan of every `*.md` for local link targets (inline code spans ignored); backslash-aware pipe/cell count per table | **re-run and passed**: 0 broken links, 0 table-cell mismatches |
| 14 | Build/test execution here | `which java javac gradle`, `$ANDROID_HOME` | absent/unset — no local run possible, none claimed; CI run 43573847121 is the executor |

### 10.3c Validation performed for revision 3.0 (kept for provenance)

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
| 10 | Local Markdown link check | Python scan of every `*.md` for local link targets | all resolve (0 broken) |
| 11 | Deleted-file link check | scan for links to the ten removed reports | none |
| 12 | Table integrity | per-file pipe/cell-count scan (escaped `\|` respected) | all tables consistent |
| 13 | Scope | `git status --porcelain`, `git diff --stat` | Markdown only: `REPOSITORY_REPORT.md`, `DECISIONS.md`, `V096_PLAN.md`; no `app/`, `.github/`, `.kts` or `gradle/` change |
| 14 | Build/test execution | `java`, `javac`, `gradle`, `kotlinc`, `ANDROID_HOME` | absent/unset — no local run possible, none claimed; CI run 43571410420 at `328e0a9` ✅ |

### 10.4 Revision history

| Revision | Date | Head | What changed |
| --- | --- | --- | --- |
| **3.3** | 2026-11-13 (UTC 2026-11-14) | `eac0d0c` (run 43585853784 ✅) + this doc-only refresh | **The three §5.12 findings fixed, in the mandated order.** P2-18: `Glossary.isAmbiguousEnglish` + an `NameMemory.plan` gate stop a single-token ambiguous English word from becoming a page name (USER entries, multi-word spellings and series memory exempt); P2-17: the HTTP-400 retry now sends the legacy non-JSON body, so it is a genuinely different request; P2-19: the caller-less `applyToSource`/`replaceWord`/`ambiguousShort` path deleted. New §5.13, §8.6; §3.3/§6.4/§6.6/§7.3/§7.4/§7.5/§8.1/§8.2/§9/§10.3 re-verified at `eac0d0c` (38 runs, 676 `@Test`, +8 tests). Application source changed in three files (+51/−32) and four test files; `DECISIONS.md` H4 notes the dead-code removal. **The mandatory Gradle command could not run locally** (no JDK) and was executed by CI only (run 43585853784 ✅). No dependency, Gradle, workflow or PR change; no device claim. |
| **3.2** | 2026-11-13 (UTC 2026-11-14) | `de77ef1a` (run 43574339617 ✅) + this doc-only refresh | **Independent technical review of P2-01…P2-04** (§5.12): all four fixes judged correct as implemented, **not approved**; three new findings recorded and left unfixed — P2-17 (degenerate 400 retry when `jsonReply = true`), P2-18 (the name planner/sheet is still not ambiguity-gated), P2-19 (dead glossary path bypasses the gate). Corrections: §5.0 documents three previously unrecorded pre-P0 commits (`ec59e7e`, `6296f80`, `f28f56c`); P2-A's diff is +347/−13 not +348/−13; P2-11's scope is both capture call sites, not only Live; §7.4 adds the 36th run and the seven commits with no run of their own; §8.5 gained the JSON-renumbering limitation and the PR-dependent CI trigger; §9 and §10.3 rewritten. **Documentation only** — no source, test, dependency, workflow or PR change. |
| **3.1** | 2026-11-13 (UTC 2026-11-14) | `a2882f16` (run 43573847121 ✅) + this doc-only refresh | **P2-B** recorded: P2-03 (`jsonReply` reaches the primary request) and P2-04 (safe positional parsing) implemented with 24 new tests; new §5.11, §8.5 risks, §9 rewritten, §6.4/§8.1/§8.2/§7.3/§7.4 re-graded and re-verified (35 runs, 668 `@Test`), §10.3 re-run for this revision. Application source changed in exactly four Gemini files (+385/−11); no dependency, workflow or PR change. |
| **3.0** | 2026-11-13 (UTC 2026-11-14) | `328e0a9` (run 43571410420 ✅) and `2f3ad07` (run 43571824620 ✅) + this doc-only refresh | Documentation audit and canonical correction: replaced the invented P3–P6 subjects with the agreed definitions (P3 performance/caching, P4 OCR, P5 text cleanup/rendering, P6 final integration/release); added the explicit status vocabulary and re-graded every phase (no phase is "Completed"); re-verified HEAD, PR, all 31 CI runs, all test counts and the P2-A source anchors against git/GitHub; added the milestone-vs-phase rule, the decision register, the per-phase status tables and the maintenance rules; restructured to the mandated ten sections. **Documentation only** — no source, test, dependency or PR change. |
| 2.0 | 2026-11-13 (UTC 2026-11-14) | `94cf2f5` | Consolidation: the nine per-change reports and `REPORTS.md` folded in and deleted; phase table, P2 register, open-finding anchors, rewritten rules. |
| 1.x | 2026-11-13/14 | `01e12a6`, `05fa1ab`, `3674ebf`, `b82fb6f`, `e546470`, `723f101`, `8140a4b`, `a7b276f` | Incremental snapshots superseded by revision 2.0; their content is carried in §5–§7. |
