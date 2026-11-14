# ManhwaLens — repository report

**Report revision:** 2.0 (consolidated) · **Updated:** session date 2026-11-13 (commit clock 2026-11-14 UTC)
**Branch:** `arena/8a718184-manhwalens` · **PR:** [#2](https://github.com/mouadknight0-cell/ManhwaLens/pull/2) — **OPEN**, not draft, not merged
**Base:** `main` @ `560705d55012f1549449ca3230184b4b5beda991` (the PR branch carries the whole app; `main` holds the initial commit)
**Head at this revision:** `94cf2f5` (*docs: consolidate every report into REPOSITORY_REPORT.md (revision 2.0)*) — it changes Markdown plus one source **comment** (a documentation pointer in `names/JsonCharacterStore.kt`), no behaviour; the last commit that changed behaviour is `25b15c2` / `a7b276f`
**Latest verified run:** [43569844274](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43569844274) — workflow *Android build*, conclusion **success** at `94cf2f5` (steps *Unit tests* ✅, *Build debug APK* ✅, APK + test-report artifacts uploaded); the P2-A code itself was verified by [43569302435](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43569302435) at `a7b276f`, also **success**

> **This is the only authoritative report in the repository.** Since 2026-11-13 (report revision 2.0) the
> per-change reports (`REPORTS.md`, `P0_*`, `P0A_*`, `P0B_*`, `P1_*`, `P1_1_*`, `P2_TRANSLATION_CONTEXT_AUDIT.md`)
> were folded into this file and deleted; §5 and §6 carry their content. The standing rule **S1–S6** was
> rewritten accordingly (§13). `PLAN.md` stays as the *forward-looking* roadmap (it is a plan, not a report);
> `README.md`, `PRIVACY.md`, `CHANGELOG.md`, `BLOCKERS.md`, `DECISIONS.md`, `MANUAL_TESTS.md`,
> `OPEN_SOURCE_RESEARCH.md` and `V096_PLAN.md` are registers/guides and were kept.
>
> Clock note: the session date is 2026-11-13 while commit timestamps (UTC) are 2026-11-13 16:21 → 2026-11-14 07:20.
> A date below is the commit clock where a commit is cited, the session date otherwise.
>
> The commit that publishes this refresh is documentation-only (it records run 43569844274, verified at
> `94cf2f5`). Its own CI run is **not claimed** here — it was still pending when this file was published.

---

## 1. What this repository is

An Android utility (not a reader) that translates manhwa/manga/webtoon pages **in place**: a floating lens
button → MediaProjection capture → on-device ML Kit OCR (Latin, bundled model) → translation → Arabic text
painted over the original bubbles in an overlay. On-device translation is the default and the product promise
(**Offline First**); a remote engine (Gemini, or LibreTranslate on a user-supplied endpoint) is an optional
*upgrade* that never delays the local draft.

| Item | Value |
| --- | --- |
| Module / language | single `:app`, Kotlin 2.4.21, AGP 8.13.2, Compose + Material 3 |
| SDKs | `minSdk 26`, `compileSdk` / `targetSdk` 36 |
| Version | `versionName` **0.11.0** (`app/build.gradle.kts:27`); debug builds only in CI |
| On-device engines | ML Kit Text Recognition v2 (Latin, bundled) · ML Kit Translate EN→AR |
| Remote engines (optional) | Google `gemini-2.5-flash` over REST (`x-goog-api-key` header only) · LibreTranslate (user endpoint) |
| Persistence | SharedPreferences (+ app-private JSON for series character memory, atomic writes) |
| Dependencies added by this branch | **none** |

### 1.1 Data flow (as implemented at `a7b276f`)

```
tap or Live scan
  → FrameCapture (MediaProjection, RGBA bitmap)
  → TextRecognizerClient (ML Kit OCR, ≤1600 px long edge)  → BubbleGrouper (lines → bubbles)
  → PagePlan.prepare(blocks, preprocess = Glossary::prepare)      ← drops meaningless blocks, dedupes on the
      │                                                            glossary-prepared text, keeps a page order
      ├─ local progressive pass  : PagePlan.progressiveOrder(...)  → ML Kit   (paint first, visual priority)
      └─ optional remote relay   : PagePlan.remoteOrder(...)       → Gemini / LibreTranslate (page order)
  → ArabicValidator (rejects English echoes / unusable Arabic)
  → CachingTranslator (memory LRU + disk cache + fuzzy memo, engine-namespaced, name-aware repair)
  → BubblePainter (Cairo, RTL, auto-fit) over the original bubbles
```

### 1.2 Name pipeline (three cooperating layers, unchanged by P2-A)

| Layer | Object | Job |
| --- | --- | --- |
| Static + user dictionary | `Glossary` (`translate/Glossary.kt`) | resolves known names/terms to Arabic *before* the engine sees the text; user Settings entries win |
| Page plan | `NameMemory` / `SceneNames` (`translate/`) | picks one spelling per page and pins it into the source text, model name sheets |
| Series memory | `names/` (`CharacterRecord`, `CharacterResolver`, `JsonCharacterStore`, `SeriesCatalog`) + `data/CharacterMemory` | reader-confirmed spellings, aliases, per-series isolation, survives restarts |

---

## 2. Branch, commits and inventory at this revision

| | |
| --- | --- |
| Commits ahead of `origin/main` | **37** at `94cf2f5` |
| Branch diff vs `main` | `230 files changed, 37 714 insertions(+)` (`git diff --shortstat origin/main...94cf2f5`; the consolidation removes 10 files from the tree and adds none) |
| Kotlin sources | **113** files under `app/src/main` |
| Test sources | **70** files under `app/src/test` (one JVM source set; no instrumented source set) |
| Test methods (`@Test`) | **644** at `a7b276f` (`grep -rn '@Test' app/src/test \| wc -l`) |
| Root Markdown files | **20** before this consolidation → **10** after (§5.9 lists what was deleted) |
| Last commit that changed behaviour | `a7b276f` (P2-A); before it `c008f62` (P0/C2) |

### 2.1 The commits this report describes

| Commit | Kind | What |
| --- | --- | --- |
| `533ae90` | docs | P2 audit commit recorded (baseline of the P2-A round) |
| `25b15c2` | **code + tests** | **P2-A / P2-01 + P2-02** implementation and their tests |
| `a7b276f` | **test fix** | one wrong expectation in the new glossary test (see §7.6) — *verified green* |
| `94cf2f5` | docs | report revision 2.0: single authoritative report, superseded reports deleted, one doc-pointer comment updated — verified green in run 43569844274 |

---

## 3. Product roadmap (from `PLAN.md` §5 — unchanged, kept there)

`PLAN.md` is the **product** plan. Its milestones are historical and are *not* the engineering phases of §4.

| Milestone | Scope | Status in `PLAN.md` | Verification reality |
| --- | --- | --- | --- |
| **M0** | Project scaffold (Gradle, Compose, theme, icon) | DONE (2026-11-11) | build green |
| **M1** | Overlay foundation (floating button, drag, snap, permissions, FGS) | DONE (2026-11-11) | build green; **no device verification** |
| **M2a** | Capture + debug view (MediaProjection, consent trampoline, FLAG_SECURE heuristic) | DONE (2026-11-11) | build green; **no device verification** |
| **M2b** | OCR + bubble grouping (ML Kit Latin, union-find grouper, 4 guards) | DONE (2026-11-11) | 10 JVM tests; **no device verification** |
| **M3** | Translation engines + batching + cache + settings (ML Kit, Gemini, error taxonomy, LRU) | DONE (2026-11-11) | 45 JVM tests; **no device verification** |
| **M4** | In-place Arabic bubble overlay (colour sampling, Cairo, auto-fit, RTL) | DONE for the overlay-rendering scope (2026-11-11) | 63 JVM tests; Room cache / download UI / failure UX still open |
| **M5** | Speed + settings + privacy + signed release (v0.7.0) | DONE build-side (2026-11-11) | R8 could not run on the authoring box; signed release not produced here |
| **M6** | Post-launch: vertical JP/ZH text, auto bubble detection, strip mode, accessibility hooks | not started | — |

The roadmap continued as ad-hoc part/hotfix sections in the same file (v0.7.1 crash fixes, Part A–C, pipeline
work A–D, v0.8.0, v0.9.0 Live + progressive translation, v0.9.1 Safe Mode, v0.9.2 wrong-thread fix, v0.9.3
in-place patch rework) and reached **v0.11.0** (`b8e37de`), the baseline this branch starts from. There is no
M7+ and no "P-numbered roadmap" in `PLAN.md` — the P-numbers below are the **engineering** phases this branch
has been executed in.

---

## 4. Engineering phases P0–P6 (verified status)

**Milestone vs phase.** A *milestone* (M0–M6) is a product increment, defined and closed in `PLAN.md`. A
*phase* (P0–P6) is an engineering work package on this branch, each with its own report, base commit, tests and
CI run. Milestone M5 being "done build-side" says nothing about phase P2, and a finished phase never implies
device validation (§10).

Status vocabulary — **VERIFIED COMPLETE**: implemented, tests executed by a green CI run on an ancestor of
this head, no claim contradicted by a later audit. **IN PROGRESS**: part of the phase is implemented and
verified, the rest is not started. **PENDING**: not started.

| Phase | Subject | Status | Evidence |
| --- | --- | --- | --- |
| **P0** | Architecture audit of v0.11.0 (read-only) | **VERIFIED COMPLETE** | §5.1; no code changed |
| **P0-A** | Gemini name sheet missing from the primary request | **VERIFIED COMPLETE** | `528d629`, run 43514608722 |
| **P0/C2** | ML Kit English echo reaching the screen | **VERIFIED COMPLETE** | `c008f62`, runs 43563394305 … 43564884208 |
| **P1** | Series-scoped character memory + review + persistence safety + strict staging (P1.1, P1.1-A) | **VERIFIED COMPLETE** | `8d5cc9e`, `e99dd93`, `87830b7`, `723f101`, `fee9d9e`; runs 43518604612 … 43561668957 |
| **P2** | Translation quality & context | **IN PROGRESS** — audit complete (`01e12a6`); **P2-01 + P2-02 fixed by P2-A** (`25b15c2`, `a7b276f`); P2-03…P2-16 open | §6, §7 |
| **P3** | Orchestration seams out of `FloatingButtonService` (audit item 10) | **PENDING** | §6 risk register |
| **P4** | Cached/context-aware translation (audit P2-10) | **PENDING** | — |
| **P5** | Resilience & validation polish (P2-06, P2-08, P2-14, P2-16) | **PENDING** | — |
| **P6** | Performance (binary-search text sizer, measurer `maxLines`) | **PENDING** | — |

P3–P6 are labels for *not-yet-started* work of the P2 audit's roadmap (P2-C, P2-D and the audit's ranked
items); nothing in this repository has been executed under those names, and no report claims otherwise.

---

## 5. Work history on this branch (condensed from the deleted per-change reports)

### 5.1 P0 — architecture audit (`P0_MANHWALENS_AUDIT.md`, deleted)

Read-only audit of v0.11.0 (`b8e37de`): 10 numbered findings (C1…C5, RTL, offline readiness, perf,
`FloatingButtonService` seams) with file:line evidence, plus a ranked plan. No code, no tests. Its open items
C3 (stale `pageNames`), C4 (AI cleanup on the live thread), C5 (`NameMemory.plan` duplication), RTL, offline
readiness and perf remain **PENDING** and are carried in §11. Its C2 evidence line numbers were corrected in
place when C2 was fixed (220→246, 42-43→51-52, 3841→3844).

### 5.2 P0-A — Gemini name sheet (`P0A_GEMINI_NAME_SHEET.md`, deleted)

**Defect.** The production default is `jsonReply = false`, so `translateChunk` sent the *legacy* body, whose
builder took no name sheet; page-local names were enforced only on the HTTP-400 fast-retry body. Evidence:
`GeminiTranslator.kt:216` → `GeminiWire` router → `promptFor(blocks)` without the sheet.
**Fix.** `buildRequestBody(blocks, thinkingLevel, nameSheet)` is now the only path; both bodies carry the
sheet; `GeminiWireTest` pins the sheet in the request body.
**Files:** `translate/gemini/GeminiWire.kt`, `GeminiTranslator.kt` + tests (6 files, +692/−5).
**Verified:** runs 43513473811 ❌ (the new test's own expectation, fixed) → 43514068424 ✅ → 43514608722 ✅.

### 5.3 P1 — series character memory (`P1_SERIES_NAME_MEMORY.md`, design `P1_CHARACTER_MEMORY.md`, deleted)

**Problem.** v0.11.0 pins names **per page** and forgets them: the same character drifted between bubbles,
pages and restarts; the glossary is global and does not survive a cache hit.
**Solution.** `names/` package: `CharacterRecord` (original name, Arabic, script, aliases, series, confidence),
`CharacterResolver` (resolution ladder: locked/user/confirmed → transliteration → built-in), `JsonCharacterStore`
(per-series JSON, atomic writes, recovery), `SeriesCatalog` (explicit series selection), `data/CharacterMemory` +
`NameSession` glue; the memory supplies the Gemini name sheet and an Arabic repair pass
(`NameForm.replaceArabic`) that runs after the engine and rewrites stale spellings before caching.
**Design decision D1:** memory is scoped by an **explicitly selected series**, not by package name (a package
is not a series). No auto-detection, no new dependency (no Room).
**Files:** 27 files, +3 307/−52; tests +73 (517→590).
**Verified:** 43517478032 ❌ (nullable smart-cast compile error) → 43517986742 ❌ (4 tests) → 43518604612 ✅ (feature) → 43520783453 ✅.

### 5.4 P1 review corrections (`P1_REVIEW_CORRECTIONS.md`, deleted)

Three verified defects in the new persistence layer, each fixed minimally: (a) `saveLocked` fell back to an
in-place `writeText` when `renameTo` failed, and never `fsync`-ed the staging file → replaced by
`writeDurably()` (flush + `fd.sync`) + `commitAtomically()` (`Files.move ATOMIC_MOVE`, `renameTo` fallback) +
`discard()`; (b) the session cache identity ignored the series → cache key now carries it; (c) series
visibility/refresh in the settings surface. 7 files, +269/−16, +9 tests.
**Verified:** run 43523522645 ✅ (+docs 43524129101).

### 5.5 P1 persistence safety (`P1_PERSISTENCE_SAFETY_FIX.md`, deleted)

**Defect.** `recoverInterruptedWrite()` promoted a staged file, and when that failed fell back to
`staging.copyTo(file, overwrite = true)` — an in-place truncate-and-rewrite of the **live** file. A kill or a
full disk in that window destroyed the reader's character memory.
**Fix.** The recovery path may never write the live file: failure means "keep the current file, report the
problem"; the staged copy is discarded. 6 files, +348/−63, +5 tests.
**Verified:** run 43526686657 ✅ (+docs 43527227407).

### 5.6 P1.1 / P1.1-A — strict staging validation (`P1_1_STRICT_STAGING_VALIDATION.md`, deleted)

**Failure mode.** Promotion only required "valid JSON", then the tolerant live-file reader silently skipped
what it could not use — so a document with no `series` object, a wrong-typed series, or a record under the
wrong series key could replace a good file and load as empty.
**Fix.** `validateStagingDocument()` proves structural completeness (version, series object, record identity,
`seriesId` match) **before** promotion; P1.1-A adds canonical `normalizedName` integrity (a record whose key is
not `NameForm.normalize(originalName)` is rejected). 5 files +451/−12, 6 files +206/−13; +11 and +4 tests.
**Verified:** 43531030306 ✅ → 43531550380 ✅ → 43561318751 ✅ (P1.1-A) → 43561668957 ✅.

### 5.7 P0/C2 — ML Kit English echo (`P0B_MLKIT_ENGLISH_ECHO.md`, deleted)

**Defect.** Four doors let untranslated English reach the screen as if it were Arabic: (1) page assembly
substituted the source when ML Kit answered nothing; (2) the progressive callback painted every finished block
before the page guard; (3) the quality guard was scoped to remote engines only, so ML Kit output was cached
and re-served; (4) the painter only refused blank text.
**Fix.** `MlKitPageAssembly.ordered()` (a missing answer is empty, never the source); the guard now runs for
`ml_kit_en_ar` in `CachingTranslator`; the progressive path is guarded; the painter refuses an echo at the
boundary. 6 files, +510/−8, +10 tests (`MlKitEnglishEchoTest`, new).
**Consequences kept:** progressive translation preserved; a rejected bubble stays **empty** (no source
substitution, no retry loop); pins are honoured before the guard.
**Verified:** 43563394305 ✅ (+docs 43563800508, 43564060458, 43564884208).

### 5.8 P2 audit (`P2_TRANSLATION_CONTEXT_AUDIT.md`, deleted)

Read-only audit of the translation path at `fe5aa6a`: data-flow trace, **4 confirmed bugs** (P2-01…P2-04),
**9 verified risks** (P2-05…P2-13), **3 improvements** (P2-14…P2-16), **5 investigated-correct** items
(P2-N1…N5), existing coverage and gaps, and a staged roadmap P2-A…P2-D. No code changed; no test run claimed.
**Verified:** run 43567398304 ✅ (docs) → recorded in 43567644013 ✅.

### 5.9 Documentation consolidation (this commit)

| File | Fate |
| --- | --- |
| `REPORTS.md` | **deleted** — its rule text and index are now §13 and §5/§6 here |
| `P0_MANHWALENS_AUDIT.md`, `P0A_GEMINI_NAME_SHEET.md`, `P0B_MLKIT_ENGLISH_ECHO.md` | **deleted** — folded into §5.1, §5.2, §5.7 and the risk register |
| `P1_CHARACTER_MEMORY.md`, `P1_SERIES_NAME_MEMORY.md`, `P1_REVIEW_CORRECTIONS.md`, `P1_PERSISTENCE_SAFETY_FIX.md`, `P1_1_STRICT_STAGING_VALIDATION.md` | **deleted** — folded into §5.3–§5.6 |
| `P2_TRANSLATION_CONTEXT_AUDIT.md` | **deleted** — folded into §6 (findings + per-issue status) and §7 (P2-A) |
| `PLAN.md` | **kept** — forward-looking roadmap (M0–M6) and definitions of done; not a report |
| `README.md`, `PRIVACY.md`, `CHANGELOG.md` | **kept** — product/version/privacy documents |
| `BLOCKERS.md`, `DECISIONS.md`, `MANUAL_TESTS.md`, `OPEN_SOURCE_RESEARCH.md`, `V096_PLAN.md` | **kept** — register, decision log, on-device checklist, licence research, earlier plan; roles are unambiguous non-report |
| Code comment in `names/JsonCharacterStore.kt:20` | pointed at the deleted `P1_CHARACTER_MEMORY.md` → now points here (comment text only; no code, no behaviour) |

---

## 6. P2 findings register and per-issue status

Priority labels are the ones the audit assigned (**not** re-labelled by P2-A). Evidence line numbers refer to
the audit baseline `fe5aa6a` / `533ae90`.

| ID | Type | Priority | Finding | Status |
| --- | --- | --- | --- | --- |
| **P2-01** | Bug | High | `Glossary` rewrote ordinary English words (`long`, `song`, `sun`, `pan`, `tan`, `ding`…) before translation | **FIXED** in `25b15c2` (§7.2) |
| **P2-02** | Bug | High | Remote chunks built from visual-priority order while the prompt claims consecutive page order | **FIXED** in `25b15c2` (§7.3) |
| **P2-03** | Bug | Medium | `jsonReply` never reaches the primary request; body is legacy while the instruction demands JSON (`GeminiTranslator.kt:232`) | **OPEN — deliberately not implemented by this task** |
| **P2-04** | Bug | Medium | Last-resort "one line per id" parse can shift translations onto the wrong bubbles (`GeminiWire.kt:321-328`) | **OPEN — deliberately not implemented by this task** |
| **P2-05** | Risk | High | Two name authorities on the Gemini path: page-local `applyPinned` can override the series/locked spelling | **OPEN** (needs a decision — roadmap P2-C) |
| **P2-06** | Risk | Medium | One failing long-block chunk aborts the whole ML Kit page | **OPEN** |
| **P2-07** | Risk | Medium | `pageNames` survives early returns → stale names can leak into the next page (audit C3) | **OPEN** |
| **P2-08** | Risk | Medium | LibreTranslate fails the page on one rejected bubble and gets no name sheet | **OPEN** |
| **P2-09** | Risk | Medium | Remote upgrade always wins on non-blank output — no comparison with the local draft | **OPEN** |
| **P2-10** | Risk | Medium | Cache keys are context-free; a reused line keeps its old pronoun/gender reading | **OPEN** |
| **P2-11** | Risk | Low | `PagePlan.prepare` is called without `preprocess`, contrary to its documented contract | **OPEN** (unchanged by P2-A: the tap path does pass it; Live does not) |
| **P2-12** | Risk | Low | SFX bubbles are dropped by the planner while the Gemini prompt instructs SFX transliteration | **OPEN** |
| **P2-13** | Risk | Low | `NameMemory.pageNameSpellings` indexes the page list with bubble ids (latent, no caller today) | **OPEN (latent)** |
| **P2-14** | Improvement | Low | Validator cannot detect a truncated short answer, nor a 1–2 word English remnant | **OPEN** |
| **P2-15** | Improvement | Low | Post-processing order differs between the engine and the decorator | **OPEN** |
| **P2-16** | Improvement | Low | A `MAX_TOKENS`-truncated reply is traced, but a bubble cut mid-sentence is kept | **OPEN** |
| **P2-N1…N5** | Not a problem | — | Idempotent `prepare`; per-bubble triage; pinned override vs stale cache; offline independence; names survive chunking | **verified correct** (re-verified in §7.5 for N1/N2/N4/N5 under the new rules) |

### 6.1 Open findings — evidence anchors and the minimal fix each needs

Carried over from the deleted audit so the work does not have to be re-derived. Line numbers are the audit
baseline (`fe5aa6a`); re-verify them before editing.

| ID | Anchor (baseline) | Minimal fix the audit proposed | Regression the fix must add |
| --- | --- | --- | --- |
| P2-03 | `GeminiTranslator.kt:232` (`jsonReply = false` hard-wired), KDoc `:47-52` | pass the field through (`jsonReply = jsonReply`) and document which body is primary | `jsonReply = true` → body contains `responseMimeType`; `false` → legacy body; must land **before** P2-16 |
| P2-04 | `GeminiWire.kt:316-330`, fallback `:321-328` | positional mapping only on an **exact** count match (`>=` → `==`) and drop a translator-label line first | equal-count reply maps by position; an extra leading label line maps nothing |
| P2-05 | `GeminiTranslator.kt:87-99` (sheet from series) vs `:128-136` (`applyPinned` from page plan) | one authority: give the engine the same series table the sheet used | a series table + Gemini post-processing test (none exists) |
| P2-06 | `MlKitTranslator.kt:154-181`, abort `:174-176` | omit the failed long block (empty) and continue the page | a failing chunk leaves one empty block, page still `Success` |
| P2-07 | `MlKitTranslator.kt:246` clears `pageNames`; early returns `:103-108` | `try/finally` around the body (or clear at entry) | two pages on one translator instance never share names |
| P2-08 | `LibreTranslateTranslator.kt:57-58`, `:80-100` | per-bubble triage like ML Kit; attach the sheet | one rejected bubble does not fail the page |
| P2-09 | `FloatingButtonService.kt:3705-3711` | do not replace a local patch with a worse remote one (needs a quality signal) | arbitration test on the relay path |
| P2-10 | `TranslationCache.kt:18-30`, `CachingTranslator.kt:224-232`, `TranslationMemo.kt` | document the choice; consider a context-bearing key only if evidence demands it | none today (documented trade-off) |
| P2-11 | `PagePlan.kt:59-68` + Live call site | pass `Glossary::prepare` where the contract says so | `Wei Lin` / `wei lin` dedupe to one item on the Live path |
| P2-12 | `MeaninglessText.kt:163-177`, `PagePlan.kt:74-84` | make the prompt and the planner agree about SFX | SFX kept (or the prompt claim removed) |
| P2-13 | `NameMemory.kt:259`, `:287` | index by id, not by position | latent — no caller passes `pageArabic` today |
| P2-14 | `ArabicValidator.kt:59-90`, `:144-166` | detect a truncated short answer and a 1–2 word English remnant | guard tests for both |
| P2-15 | `GeminiTranslator.kt:133-142` vs the decorator path | unify post-processing order | same text judged on both paths |
| P2-16 | `GeminiTranslator.kt:321-337`, `GeminiWire.kt:303` | treat `MAX_TOKENS` truncation (empty the cut bubble) instead of tracing it | a truncated reply leaves no half sentence |

Open P2 items are **not** claimed fixed anywhere in this repository. P2-B (P2-03/P2-04), P2-C (P2-05) and
P2-D (resilience) remain parked until this round is reviewed.

---

## 7. P2-A — source-text and context integrity (this round)

**Scope authorised:** P2-01 and P2-02 only. **Baseline:** `533ae90`. **Code commit:** `25b15c2`.
**Test fix:** `a7b276f`. **Out of scope and untouched:** prompts, model settings, OCR, persistence, UI,
dependencies, architecture, P2-03, P2-04.

### 7.1 What the baseline did (verified in the code at `533ae90`)

* `Glossary.applyCompiled` (`Glossary.kt:84-91` at baseline) replaced every whole-word table hit
  case-insensitively; the only protection was `ambiguousShort` — tokens of ≤3 letters that are in a small
  English stop-word list (`Glossary.kt:106`). `Long`, `Song`, `Sun`, `Pan`, `Tan`, `Ding`, `Tang`, `Fang`,
  `Ma`, `Mo`, `Ye`, `Li`, `Ren`, `Tao`, `Dong`, `Wang` are all table keys and all pass that filter, so
  "How long until the sun sets?" reached the engine as "How لونغ until the سون sets?".
* The tap path computed **one** list — `PagePlan.engineBlocks(dedupePlan).sortedBy { rankById[it.id] }`,
  i.e. short → biggest → most central (`FloatingButtonService.kt:2778-2779`) — and handed it to the relay
  (`:2813`), which passed it to `remote.translatePage(unique)` (`:3705`), which chunked it sequentially
  (`GeminiTranslator.kt:101`, `GeminiWire.chunkBlocks`). Meanwhile the prompt tells the model
  *"Consecutive blocks belong to the same page — use them to resolve context and pronouns"*
  (`GeminiWire.kt:128`). The claim was false for every relay request.

### 7.2 P2-01 — conservative glossary matching (`Glossary.kt`)

Replacement now has to earn itself (`Glossary.accepts`), in this order:

1. an entry the reader typed in Settings is an **explicit pin** → always replaced (this is the escape hatch);
2. a **multi-word or hyphenated** key (`Wei Wuxian`, `Song Lan`, `young master`, `Mobei-Jun`) is unambiguous →
   always replaced;
3. a **single-word genre term** (`TERMS`: sect, core, qi, cultivation, dantian, pill, demon, immortal,
   talisman, shidi/shifu/shijie/shimei/shixiong …) is the table's subject matter → replaced in any case,
   exactly as before;
4. any other single word must be written **capitalised** in the source and must not be ALL CAPS;
5. its lower-case form must not be an **ordinary English word** (`AMBIGUOUS_ENGLISH` = the existing stop-word
   list + the English words that collide with table keys: long, song, sun, pan, tan, ding, tang, fang, ma, mo,
   ye, tao, dong, wang, li, ren).

Sentence position is deliberately **not** used: "Long ago…" is the same word mid-sentence, so capitalisation
alone cannot separate a name from prose. The rule set is deterministic, table-driven and pure Kotlin.

**Trade-off (documented, not hidden).** A character actually named `Long`, `Song`, `Sun`, `Pan`, `Tan` or
`Ding` keeps the **Latin** spelling on the glossary path, because a one-word token gives no deterministic
signal and corrupting prose is the worse failure for every page that contains the word as prose. Every escape
hatch stays open: the reader's Settings line (explicit pin), a multi-word spelling (`Long Wei`), and the series
name memory (`SceneNames`/`NameMemory` + `NameForm.replaceLatin`), which runs *after* `prepare` and is
unaffected. The table itself is unchanged, so `Glossary.effective()` still offers every name to the memory
planner — the fix refuses the *replacement*, it does not forget the *name*.

**Rejected alternatives:** disabling all short/capitalised names (breaks `Wei`, `Lan`, `Mo Xuanyu`); a
capitalisation-only rule (still corrupts "Long ago", "Sun was in his eyes"); a sentence-position rule (same
corruption at the start of a line, which is exactly where names and prose both appear); a per-entry `pun` flag
(does not separate `long` the word from `Long` the surname — both are the same key); swapping the engine's
output afterwards (too late: the engine has already translated the Arabic-corrupted token).

### 7.3 P2-02 — one order per engine (`PagePlan.kt`, `FloatingButtonService.kt`)

`PagePlan` now names the two orders:

| Helper | Order | Who uses it |
| --- | --- | --- |
| `PagePlan.remoteOrder(plan)` | page / reading order (`engineBlocks`) | the remote relay, and every `TranslatorFactory.create(...)` call that can resolve to a remote engine |
| `PagePlan.progressiveOrder(plan, rankById)` | visual priority, unranked ids last (previous behaviour) | `createProgressive` (ML Kit draft), `createOffline`, the ML Kit fallback |

The Live pipeline (`FloatingButtonService.kt:3296-3304`) already used `engineBlocks` and is **unchanged**.
Chunk boundaries therefore now slice the page in reading order for remote requests; the local progressive
draft still paints the most useful bubbles first. No global policy swap: the two orders are separate named
helpers, so a future caller cannot pick "the" order by accident.

### 7.4 Files changed (5 files, +348/−13)

| File | Why |
| --- | --- |
| `app/src/main/java/com/manhwalens/app/translate/Glossary.kt` | `Rule`/`Compiled` carry the pin flag; `accepts` implements rules 1–5; `TERMS` + `AMBIGUOUS_ENGLISH`; documentation of the trade-off |
| `app/src/main/java/com/manhwalens/app/translate/PagePlan.kt` | `remoteOrder` and `progressiveOrder` with the contract documented |
| `app/src/main/java/com/manhwalens/app/overlay/FloatingButtonService.kt` | tap path computes both orders; relay and remote-capable `create(...)` calls take `remoteBlocks` |
| `app/src/test/java/com/manhwalens/app/translate/GlossaryTest.kt` | 8 new regressions (existing 9 untouched) |
| `app/src/test/java/com/manhwalens/app/translate/TranslationOrderingTest.kt` | **new**, 7 ordering tests |

### 7.5 Tests added (15 new `@Test`; 629 → 644 discovered)

`GlossaryTest` (each asserts the **resulting text**):

| Test | Property pinned |
| --- | --- |
| `ordinaryLongAndSunStayEnglish` | mid-sentence `long`/`sun` are not replaced (exact string) |
| `sentenceInitialLongAndSunAreStillOrdinaryWords` | sentence-initial `Long`/`Sun` are ordinary words, not names |
| `shortCollisionsStayEnglish` | `pan`, `tan`, `ding`, capitalised `Song` stay Latin; the term `sect` beside them still translates |
| `termsStillTranslateInAnyCase` | `sect`/`core`/`qi`/`shidi` still resolve (no weakening of the term dictionary) |
| `genuineNamesStillTranslate` | `Song Lan` and `Wei Wuxian` still resolve to `سونغ لان` / `وي ووشيان` |
| `aPinnedNameIsAlwaysHonoured` | a Settings line (`Long = لونغ`) forces the swap; unpinned `Song` is unaffected |
| `thePinIsScopedToTheCurrentSettings` | the pin disappears when the settings change (no leakage) |
| `theTableStillCarriesTheNameForTheMemory` | `effective()` still holds `Long`/`Song`, and `NameForm.replaceLatin` still converts a series-memory name the glossary now leaves alone |

`TranslationOrderingTest` (new):

| Test | Property pinned |
| --- | --- |
| `remoteOrderIsPageOrder` | 24 blocks reach a remote engine in ids 1…24 |
| `visualPriorityOrderIsPreservedForTheLocalPass` | reversed ranks paint 24…1, and the two orders differ |
| `unrankedBlocksGoLastInVisualPriorityOrder` | ranked ids first, unranked keep page order |
| `remoteChunksKeepThePageSequenceAcrossBoundaries` | 24 blocks, `maxChars = 200`, `maxBlocks = 8` → ≥3 chunks, flattened order = 1…24, every chunk ascending and duplicate-free |
| `visualPriorityOrderWouldScrambleTheChunks` | the old behaviour is reproducible (24…1) — the regression is pinned, not just removed |
| `dedupeDoesNotDisturbTheRemoteOrder` | 21 blocks with one repeat → 20 unique, ids ascending minus the duplicate, and both ids get the same Arabic |
| `theLivePathStillUsesPageOrder` | Live's `engineBlocks` is exactly `remoteOrder` and is page order |

Existing suites kept working, including `GlossaryTest.linesOfOneBubbleBecomeOneSentence` (exact strings),
`cacheKeyFollowsThePreparedText` (`وي ووشيان`), `preparingAHundredBlocksStaysCheap`, all 18 `names` isolation
tests (`series are isolated from each other`, `aliases are searchable and never replace the original`,
`an alias finds the record`) — untouched and green in the run below.

### 7.6 Verification actually performed

| Step | Command / source | Result |
| --- | --- | --- |
| Local build/tests | `java`, `javac`, `gradle`, `kotlinc`, `ANDROID_HOME` | **all absent** — no local execution possible, nothing claimed |
| CI run 1 | [43568984747](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43568984747) at `25b15c2` | ❌ **failure**: `GlossaryTest > shortCollisionsStayEnglish` — my expectation compared the sentence against itself while the term `sect` in it legitimately translates. `Unit tests` ❌, `Build debug APK` skipped |
| Fix | `a7b276f` | expectation corrected to the intended behaviour (`He sang a Song for the طائفة.`) |
| CI run 2 | [43569302435](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43569302435) at `a7b276f` | ✅ **success** — steps *Unit tests* ✅, *Build debug APK* ✅, APK and test-report artifacts uploaded, job 132369008530 |

The exact command CI runs (and the only one that has executed this code) is
`./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain` (workflow *Android build*,
`pull_request` event). **Executed / skipped test counts are UNVERIFIED**: green runs print no per-test lines,
and the `test-reports` artifact (101 KB) plus the job log live on `*.blob.core.windows.net`, outside this
sandbox's allowlist (`gh run view --log` → `EOF`). What *is* verified is that the task failed when one test
failed and succeeded when it was fixed, and that one failure name was reported — the 644 discovered `@Test`
methods compile and none failed in run 43569302435.

### 7.7 Explicitly not implemented (as instructed)

* **P2-03** (`jsonReply` propagation, `GeminiTranslator.kt:232`) — untouched.
* **P2-04** (positional reply parsing, `GeminiWire.kt:321-328`) — untouched.
* No prompt text, model setting, OCR, persistence or UI change; no dependency; no architecture redesign;
  no test deleted; no global order swap; Live path unmodified.

`git diff --name-only 533ae90..a7b276f` lists exactly the five files of §7.4.

---

## 8. Source and test map

| Area | Files | State at this head |
| --- | --- | --- |
| `overlay/FloatingButtonService.kt` | 4.4k-line orchestrator (tap, Live, relay, painter wiring) | touched by P0/C2 and P2-A (order selection only); structural risk (audit item 10) unchanged |
| `translate/` | engines, cache decorator, `PagePlan`, `BlockPriority`, `Glossary`, `NameMemory`, `SceneNames`, guards | touched by P0-A, P0/C2, P2-A |
| `translate/gemini/` | `GeminiTranslator`, `GeminiWire` | **not touched by P2-A** (P2-03/P2-04 live here) |
| `translate/mlkit/`, `translate/libre/` | on-device and Libre engines | untouched by P2-A |
| `names/` | record, form, resolver, JSON store, series catalogue | added by P1, hardened four times; JVM-tested |
| `data/` | prefs, caches, `CharacterMemory`/`NameSession` | P1 glue |
| `ocr/`, `render/`, `capture/` | OCR, patch painter, MediaProjection | untouched by this branch's phases |

Test suite layout (`app/src/test`, 70 files, 644 `@Test`): `translate` (largest group: glossary, page plan,
chunking, validators, cache, engine fakes), `live`, `names`, `ocr`, `render`, `util`, `data`, `overlay`,
`gemini`, `libre`.

---

## 9. Verification: what is real here

### 9.1 Environment

The authoring sandbox has **no JDK, no `javac`, no `gradle`, no `kotlinc` and no Android SDK**
(`command -v` → nothing; `ANDROID_HOME` unset), so Gradle cannot run locally. GitHub Actions is the only
executor. `lint` is unavailable offline, R8 is disabled on this box, and only `assembleDebug` is produced.

### 9.2 All runs on this branch (30 runs: 26 success, 4 failure)

| Run | Commit | Result | Note |
| --- | --- | --- | --- |
| [43569844274](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43569844274) | `94cf2f5` | ✅ | **consolidation verified** (unit tests + APK) |
| [43569302435](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43569302435) | `a7b276f` | ✅ | **P2-A verified** (unit tests + APK) |
| [43568984747](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43568984747) | `25b15c2` | ❌ | one wrong test expectation (§7.6), fixed in `a7b276f` |
| [43567644013](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43567644013) | `533ae90` | ✅ | P2 audit docs |
| [43567398304](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43567398304) | `01e12a6` | ✅ | P2 audit |
| [43566212238](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43566212238) | `fe5aa6a` | ✅ | audit baseline |
| [43565553808](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43565553808) | `05fa1ab` | ✅ | docs consistency audit |
| [43564884208](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43564884208) | `3674ebf` | ✅ | rule S6 |
| [43564060458](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43564060458) | `7b30804` | ✅ | |
| [43563800508](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43563800508) | `b82fb6f` | ✅ | |
| [43563394305](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43563394305) | `08004c4` | ✅ | P0/C2 code + docs |
| [43561668957](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43561668957) | `6eb8974` | ✅ | |
| [43561318751](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43561318751) | `e546470` | ✅ | P1.1-A verified here |
| [43558741498](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43558741498) | `feb20ef` | ✅ | |
| [43531550380](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43531550380) | `723f101` | ✅ | P1.1 |
| [43531030306](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43531030306) | `e6598a8` | ✅ | |
| [43530112769](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43530112769) | `8140a4b` | ✅ | |
| [43527227407](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43527227407) | `87830b7` | ✅ | persistence safety |
| [43526686657](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43526686657) | `345715d` | ✅ | |
| [43525048064](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43525048064) | `b960444` | ✅ | rules push |
| [43524129101](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43524129101) | `06b3d84` | ✅ | |
| [43523522645](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43523522645) | `e99dd93` | ✅ | P1 review |
| [43520783453](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43520783453) | `1c0c270` | ✅ | |
| [43518604612](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43518604612) | `8d5cc9e` | ✅ | P1 code |
| [43517986742](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43517986742) | `1686e72` | ❌ | 4 failed tests in P1 — analysed then, fixed in `8d5cc9e` |
| [43517478032](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43517478032) | `6a71f77` | ❌ | nullable smart-cast compile error |
| [43514608722](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43514608722) | `528d629` | ✅ | P0-A code |
| [43514068424](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43514068424) | `1940487` | ✅ | P0-A second attempt |
| [43513473811](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43513473811) | `0e6d5ff` | ❌ | 1 failed test (the new P0-A test), fixed in `1940487` |

Each of the four failures is a pre-fix state of a later-green commit; none is open.

### 9.3 Test counts — provenance (each row is the source count at that commit)

| Commit | Phase | `@Test` discovered | CI |
| --- | --- | --- | --- |
| `b8e37de` | v0.11.0 baseline | 509 | green (43504057987, tag push) |
| `528d629` | after P0-A (+8) | 517 | green |
| `8d5cc9e` | after P1 (+73) | 590 | green |
| `e99dd93` | after P1 review (+9) | 599 | green |
| `87830b7` | after persistence safety (+5) | 604 | green |
| `723f101` | after P1.1 (+11) | 615 | green |
| `fee9d9e` | after P1.1-A (+4) | 619 | green |
| `3674ebf` | after P0/C2 (+10) | 629 | green |
| `25b15c2` / `a7b276f` | after P2-A (+15) | **644** | green (43569302435) |

**Discovered vs executed:** discovered = 644 (verified by `grep`). Executed = UNVERIFIED as a number; the
*Unit tests* step succeeded, so failures among executed tests = 0. Skipped = UNVERIFIED. No document here
claims a per-test count for a green run.

---

## 10. Device validation status

**None.** No phase in this repository — including P2-A — has been validated on a device. Every claim about
overlay timing, scroll riding, Live mode, battery, the withheld-bubble behaviour of P0/C2, the series UI of P1
and the remote-order change of P2-02 is **build-and-JVM-verified only**. The on-device checklist lives in
[MANUAL_TESTS.md](MANUAL_TESTS.md) (Redmi 13C, Brave) and has not been executed by anyone with hardware.

---

## 11. Risks, limitations and pending work

1. **No device validation** (above) — the largest open risk, unchanged by this branch.
2. **P2-A trade-off** (§7.2): a one-word ambiguous name (`Long`, `Song`, `Sun`, `Pan`, `Tan`, `Ding`) is no
   longer auto-replaced; it relies on the reader's pin, a two-word spelling, or the series memory.
3. **P2-03 / P2-04 open**: the Gemini request/response format still has a legacy body and a positional parse
   that can misalign bubbles (§6).
4. **P2-05 open**: two name authorities on the Gemini path can disagree — needs a decision (P2-C).
5. **Audit items still open**: C3 (`pageNames` across pages), C4 (AI cleanup on the live thread), C5
   (`NameMemory.plan` duplication), RTL correctness, offline readiness step, render performance.
6. **Environment blockers** (also in [BLOCKERS.md](BLOCKERS.md)): no JDK/Android SDK locally, lint
   unavailable offline, R8 disabled, debug APK only, no real Gemini/Libre traffic, executed test counts
   unreadable.
7. **PR #2 has 0 reviews** — no human has signed off on any of these phases.

---

## 12. Next action

1. **Review P2-A** (this round): §7 is self-contained — root causes at `533ae90`, the rule set, the
   trade-off, the five files, the 15 tests and run 43569302435.
2. On approval, the next P2 step is **P2-B** (P2-03 + P2-04: `jsonReply` propagation and parsing that cannot
   misalign bubbles) — **not started, and not started by this task**.
3. P2-C (P2-05 name authority) needs a design decision before any code; P2-D stays opportunistic.
4. Nothing is merged; the branch stops at `a7b276f` (+ this documentation commit) for review.

---

## 13. Report and rules metadata

**Standing rules (replacing S1–S6 as previously written in the deleted `REPORTS.md`/`DECISIONS.md`):**

* **S1.** Every change ships in the **same push** as an update to **this file**. There are no per-change
  report files any more; a new work package adds a subsection to §5 (history) and, if it is a P2 finding, a
  row to §6.
* **S2.** Each work-package subsection records: scope, base commit, verified root cause with file:line, the fix
  and the rejected alternatives, files changed and why, tests added and the property each pins, the exact
  command and the CI run that executed it, and the remaining risks (including "not verified on device").
* **S3.** Evidence discipline: no test is reported as passing unless a run executed it; a command that cannot
  run here (no JDK/SDK) is reported as such, never simulated; a finding that contradicts an existing document
  is stated before the code changes.
* **S4.** Chat summaries, commit trailers and PR descriptions are conveniences, never the record. If a later
  change alters an earlier claim, this file is corrected in the same push.
* **S5.** This file is the repository-level snapshot **and** the authoritative per-change record (§1–§7).
  `PLAN.md` stays the forward-looking roadmap; `DECISIONS.md` the decision log; `BLOCKERS.md` the environment
  register; `MANUAL_TESTS.md` the device checklist.
* **S6.** A refresh follows **every** prompt, including doc-only ones — with an explicit statement that no code
  changed and no test run is claimed.

**Report history**

| Revision | Date | Head | What changed |
| --- | --- | --- | --- |
| **2.0** | 2026-11-14 (UTC) | `94cf2f5` (+ this doc-only refresh) | **Consolidation**: the nine per-change reports and `REPORTS.md` were folded into §5–§7 and deleted; added the phase table P0–P6 with the milestone/phase distinction (§3/§4), the P2 findings register with per-issue status (§6), the full P2-A record (§7), the open-finding anchors (§6.1) and the rewritten rules (§13). **No behaviour change**: Markdown plus a single documentation-pointer comment in `names/JsonCharacterStore.kt`; no test, Gradle or dependency change. |
| 1.x | 2026-11-13/14 | `01e12a6`, `05fa1ab`, `3674ebf`, `b82fb6f`, `e546470`, `723f101`, `8140a4b`, `a7b276f` | Incremental snapshots (branch/PR state, work packages, run tables, test-count boundaries, consistency audits). Superseded by this revision; their content is carried over in §5–§10. |
