# P2 — Translation Quality & Context Audit (read-only)

**Phase:** P2 audit (first P2 task). **Implementation: NOT STARTED — nothing in this task changes behaviour.**
**Branch:** `arena/8a718184-manhwalens` · **PR [#2](https://github.com/mouadknight0-cell/ManhwaLens/pull/2)** (OPEN, not merged)
**Audit baseline commit:** `fe5aa6ad6e32d790f2b4e4f815b33b8efee24623` (branch head at audit start; working tree clean)
**Base of the branch:** `main` @ `560705d` · 29 commits ahead at baseline · 629 `@Test` at baseline
**Files changed by this audit:** this report + `REPOSITORY_REPORT.md` + `REPORTS.md` only.
**No application source, test, Gradle or dependency file was modified**; no model name, prompt, request format or API
behaviour was changed.

> Evidence discipline: every finding below was read out of the source at the baseline commit; line numbers were
> re-checked before writing. No test was executed in this task (the sandbox has no JDK, `javac`, `gradle` or Android
> SDK — verified again: `which java javac gradle` returns nothing, `ANDROID_HOME` unset). Claims about test
> behaviour cite the existing test *source*, not a run; the only executed evidence is the historical CI runs listed
> in §10.

---

## 1. Scope

**In scope:** the translation pipeline from OCR block to painted Arabic — context delivery, name consistency,
Arabic output quality, Gemini request/response handling, ML Kit/offline consistency, caching and validation.

**Explicitly out of scope (and untouched):** P0/C4 (AI cleanup on the live thread), service extraction from
`FloatingButtonService`, OCR replacement, UI redesign, dependency or Gradle changes, cache redesign, replacing ML Kit
or Gemini, prompt rewriting. Series-scoped character memory is treated as fixed architecture (§9.2).

---

## 2. Current architecture and data flow

Two engines can run for one capture: the **on-device ML Kit draft** (always, progressive) and an optional
**remote upgrade** (Gemini or LibreTranslate) that replaces local patches when it returns valid Arabic.

```
OCR (ocr/, Latin model)
  └─ FloatingButtonService.captureAndTranslate
       pageBlocks = ocrBlocks → TranslationBlock(id, english)            FBS:2741-2743
       PagePlan.prepare(pageBlocks, ownStrings = UI_STRINGS)             FBS:2747-2750
         · drops MeaninglessText (punctuation/watermark/chapter/SFX/already-Arabic)
         · dedupes identical strings (key = lowercase, whitespace-collapsed)
       PagePlan.engineBlocks(plan) → unique blocks (first id per item)    FBS:2778
       BlockPriority.orderRects(...) → blocksToSend sorted by            FBS:2765-2779
         "short (<80) → biggest area → most central"                      (not reading order)
       │
       ├─ LOCAL (always, progressive)
       │    TranslatorFactory.createProgressive(ctx, painter::onBlockReady)   FBS:2826-2829
       │      → CachingTranslator(memo, disk, pinnedOverride = NameSession.rewrite)
       │        → MlKitTranslator(onBlockReady, nameTableProvider = session.tableFor)
       │            src(text) = SceneNames.apply(Glossary.prepare(text), pageNames)   MlKit:78
       │            pageNames = series table ∪ page detections (series wins)          MlKit:126-151
       │            strategy: per-block ≤2 in flight | joined (only with a proven separator)  MlKit:190-241
       │          → emitReady() → MlKitPageAssembly.progressivePayload (P0/C2 guard)    MlKit:282-295
       │        → decorator: postProcess (clean→polishArabic→tidyDialogue) + guard      CT:152-181
       │    → BubblePainter.paint(tb, remote=false) → PatchOverlay                     FBS:3841-3852
       │
       └─ REMOTE (only if configured; runs in parallel on remoteExecutor)   FBS:2807-2819, 3685-3711
            TranslatorFactory.createRemoteOnly(ctx) → CachingTranslator(...)
              → GeminiTranslator(sheetProvider = session.tableFor)    Gemini:87-99, 101-115
                   · sheet = series memory, else page-local NameMemory.sheet (limit 20)
                   · GeminiWire.chunkBlocks(≤18 blocks, ≤2600 chars) → ≤3 parallel HTTP requests
                   · primary body = LEGACY numbered-lines (+sheet); JSON body only on the 400 retry  Gemini:232,250
                   · parse: {"t":{…}} | translations | array | "[id] text" | bare lines in order  Wire:316-330
                   · clean → polishArabic → NameMemory.applyPinned(page-local table) → guard        Gemini:133-142
              or LibreTranslateTranslator: Glossary.prepare → ONE batch → index-mapped, any
                   rejection fails the page                                        Libre:57-100
            → PagePlan.expand(plan, blocks, englishById)                     FBS:3707-3710
            → painter.onRemote(tb) → paint(tb, remote=true) → remoteWins      FBS:3842-3852
```

**Cache layer (both paths):** `CachingTranslator` = memory LRU 1000 → disk `translation_cache.json` 2000 → fuzzy
memo (exact 1500 + fuzzy 600 for ≤40 chars). Key = `engine|style|Glossary.prepare(text)` (`TranslationCache.kt:18-30`,
`CachingTranslator.kt:224-232`). Cached text is re-judged by the guard for `gemini`, `libre_translate` and (since
P0/C2) `ml_kit_en_ar`; invalid entries are evicted from all three tiers and re-translated (`CachingTranslator.kt:89-124`).

**Name data path:** `NameSession.tableFor(texts)` (`CharacterMemory.kt:118-131`) → `CharacterResolver` →
`PageNameTable` (locked > user > built-in > trusted memory > page model > transliteration > Latin). The same session
feeds ML Kit (`nameTableProvider.table`), Gemini (`sheetProvider.sheet`) and the decorator
(`pinnedOverride = session::rewrite`). One `NameSession` is created per factory call, i.e. per capture
(`TranslatorFactory.kt:66,102-118,144-149`).

---

## 3. Findings — summary table

| ID | Class | Severity | Title |
| --- | --- | --- | --- |
| P2-01 | **CONFIRMED BUG** | **High** | Glossary surname table rewrites ordinary English words before translation |
| P2-02 | **CONFIRMED BUG** | **High** | Remote chunks are built from visual-priority order, not reading order, while the prompt claims page order |
| P2-03 | **CONFIRMED BUG** | Medium | `jsonReply` never reaches the primary request; the body is legacy while the instruction demands JSON |
| P2-04 | **CONFIRMED BUG** | Medium | Last-resort "one line per id" reply parsing can misalign translations onto the wrong bubbles |
| P2-05 | VERIFIED RISK | **High** | Two name authorities on the Gemini path: page-local `applyPinned` can override the series/locked spelling |
| P2-06 | VERIFIED RISK | Medium | One failing long-block chunk aborts the whole ML Kit page |
| P2-07 | VERIFIED RISK | Medium | `pageNames` survives early returns → stale names can leak into the next page (audit C3, still open) |
| P2-08 | VERIFIED RISK | Medium | LibreTranslate fails the whole page on a single rejected bubble and gets no name sheet |
| P2-09 | VERIFIED RISK | Medium | Remote upgrade always wins on non-blank output — no comparison with the local draft |
| P2-10 | VERIFIED RISK | Medium | Cache keys are context-free; a line reused in another context keeps the old pronoun/gender reading |
| P2-11 | VERIFIED RISK | Low | `PagePlan.prepare` is called without `preprocess`, contrary to its documented contract |
| P2-12 | VERIFIED RISK | Low | SFX bubbles are dropped by the planner while the Gemini prompt instructs SFX transliteration |
| P2-13 | VERIFIED RISK | Low | `NameMemory.pageNameSpellings` indexes the page list with bubble ids (latent; no caller today) |
| P2-14 | IMPROVEMENT | Low | Validator cannot detect a truncated short answer, nor a 1–2 word English remnant |
| P2-15 | IMPROVEMENT | Low | Post-processing order differs between the engine and the decorator |
| P2-16 | IMPROVEMENT | Low | A `MAX_TOKENS`-truncated reply is only traced; a bubble cut mid-sentence is kept |
| P2-N1…N5 | NOT A PROBLEM | — | Idempotent prepare; per-bubble triage; pinned override vs stale cache; offline independence; names survive chunking |

Totals: **4 confirmed bugs, 9 verified risks, 3 improvements, 5 investigated-and-correct items.**

---

## 4. Findings — evidence

### P2-01 — Glossary rewrites ordinary English words (CONFIRMED BUG, High)

* **Where:** `translate/Glossary.kt:39-41` (patterns built for every table key), `:84-92` (`applyCompiled`),
  `:106-107` (`ambiguousShort`), table at `:276-343`, word list at `:254-273`.
* **Path:** every engine call — ML Kit `src()` (`MlKitTranslator.kt:78`), Gemini `promptFor`
  (`GeminiWire.kt:152-160`), LibreTranslate (`LibreTranslateTranslator.kt:57`), plus cache keys
  (`CachingTranslator.kt:224`) and dedupe (`PagePlan`).
* **Current behaviour:** each table entry becomes `(?i)(?<![\p{L}])key(?![\p{L}])` and is replaced **regardless of
  case**. The only guard skips a match when it is ≤3 characters *and* present in `ENGLISH_WORDS`
  (`ambiguousShort`). The table contains 4-letter and 3-letter English words that are not in that list:
  `Long`, `Song`, `Sun`, `Pan`, `Tan`, `Ding`, `Ma`, `Mo`, `Su`, `Lu`, `Fu`, `Gu`, `Hu`, `Li`, `Ye`, `Shi`.
* **Why defective:** ordinary dialogue is corrupted *before* translation and the Arabic fragment then survives into
  the output. Deterministic: `Glossary.prepare("How long until the sun sets?")` →
  `"How لونغ until the سون sets?"`; `"I need a pan"` → `"I need a بان"`. Names are not being recognised — English
  words are being renamed.
* **Reproduction / existing test evidence:** `GlossaryTest.ordinaryEnglishWordsAreNotTouched`
  (`GlossaryTest.kt:32-37`) uses `"He will come with the army to the river and she knows it."` — a sentence that
  deliberately contains **none** of the colliding tokens, so the suite is green while the defect exists. There is no
  test for `long`, `song`, `sun`, `pan`, `tan`, `ding`.
* **Expected:** a table entry replaces text only when it is a plausible name occurrence (capitalised mid-sentence or
  multi-word), never a lowercase ordinary word.
* **Minimal fix:** in `applyCompiled`, skip a single-token key of ≤4 letters when the matched text is not capitalised
  in the input (case-sensitive check on the match) — multi-word keys and capitalised single tokens keep working.
  Alternative/cheaper: extend `ENGLISH_WORDS` with the collision set (fragile; the case rule is the general fix).
* **Required regressions:** ordinary sentence with `long`/`sun`/`song` unchanged; lowercase `pan`, `tan`, `ding`
  unchanged; `Wei Wuxian` / `Lan Wangji` still replaced; `Song Lan` (multi-word) still replaced; `Song` capitalised
  mid-sentence still replaced.
* **Dependencies:** none. Blocks P2-B/P2-C measurements of Arabic quality (it pollutes the input of every later test).

### P2-02 — Remote context is built from visual-priority order (CONFIRMED BUG, High)

* **Where:** `overlay/FloatingButtonService.kt:2765-2779` (`BlockPriority.orderRects` → `blocksToSend` sorted by
  rank), `:2811-2817` (`relayUnique = blocksToSend` handed to the remote relay), `:3554-3566`
  (`remote.translatePage(unique)`), `translate/gemini/GeminiTranslator.kt:101-105`
  (`chunkBlocks(translatable, 2600, maxBlocksPerRequest)`), `translate/gemini/GeminiWire.kt:112-135` (rule 7:
  "Consecutive blocks belong to the same page — use them to resolve context and pronouns").
* **Current behaviour:** the tap path orders blocks by *reading priority* — short bubbles first, then largest area,
  then most central — and sends that order to the remote engine, which chunks it in groups of ≤18. A page of 20
  bubbles therefore produces a chunk mixing e.g. bubble #1 and bubble #19, and the system instruction tells the model
  those blocks are consecutive page content.
* **Why defective:** the one context mechanism the remote engine actually has (adjacency in the prompt) is fed a
  non-adjacent set. Pronouns, speaker turns and conversational continuity are resolved against unrelated dialogue;
  the model complies with the instruction and confidently returns wrong referents.
* **Reproduction / test evidence:** the ordering is deterministic and visible in the code above; `BlockPriorityTest`
  covers the ranking itself, and nothing tests what order the remote engine receives. Live mode is **not** affected
  (`FloatingButtonService.kt:3288-3289` sends `PagePlan.engineBlocks(pagePlan)` in page order) — the tap path and
  Live therefore disagree, which is itself evidence of an unintended order.
* **Expected:** remote requests carry page order (id order); visual priority stays a *local progressive* concern.
* **Minimal fix:** build `relayUnique` from `PagePlan.engineBlocks(dedupePlan)` (page order) and keep the sorted list
  only for the ML Kit progressive translator.
* **Required regressions:** relay receives page order for a 20-block page; chunk boundaries follow id order; ML Kit
  progressive order is unchanged (priority); Live path unchanged.
* **Dependencies:** improves the value of P2-B/P2-C work; independent of P2-01.

### P2-03 — `jsonReply` never reaches the primary request (CONFIRMED BUG, Medium)

* **Where:** `GeminiTranslator.kt:232` — `GeminiWire.buildRequestBody(chunk, THINKING_LEVEL, sheet, jsonReply = false)`
  (hard-coded `false`), `:250` — the `jsonReply` field is used only for the HTTP-400 retry body; field KDoc at `:47-52`;
  `GeminiWire.kt:112-135` (rule 1: "Reply with JSON only") vs `:187-215` (legacy body: no `responseMimeType`).
* **Current behaviour:** the primary request is always the legacy numbered-lines body with `temperature 0.3` and
  `thinkingLevel = "low"`. The JSON-mode fast body (`responseMimeType: application/json`, `temperature 0.2`,
  `thinkingBudget 0`) is only ever sent as the retry of a 400.
* **Why defective:** (a) the constructor flag is inert for its stated purpose; (b) the instruction asks for JSON while
  the request does not declare a JSON MIME type, which is exactly the condition that produces prose replies — and
  prose replies are what the weakest parser path (P2-04) then handles; (c) the "no thinking tokens / low temperature"
  speed design does not apply to the call that matters.
* **Reproduction / existing test evidence:** `GeminiTranslatorTest.kt:273` asserts the first request does **not**
  contain `responseMimeType`, and `:290-296` asserts `jsonReply = true` changes only the retry — i.e. the current
  behaviour is pinned by tests, but the tests pin the *implementation*, not the documented intent in the field KDoc.
* **Expected:** one behaviour, documented once: either the flag selects the body, or the field and its KDoc are
  removed and the retry is described as "JSON fallback".
* **Minimal fix:** pass the field through at `:232` (`jsonReply = jsonReply`) and update `:47-52` to say the first
  attempt honours it. Default stays `false`, so production behaviour is unchanged unless a caller opts in — the bug is
  the lie, not the default.
* **Required regressions:** `jsonReply = true` → first body contains `responseMimeType`; `jsonReply = false` → legacy
  first body, JSON only on 400; sheet still present in both bodies (P0-A).
* **Dependencies:** must land before P2-16 (truncation handling), because the reply shape differs per body.

### P2-04 — Positional reply parsing can misalign bubbles (CONFIRMED BUG, Medium)

* **Where:** `GeminiWire.kt:316-330`, fallback at `:321-328`.
* **Current behaviour:** when the reply is neither JSON nor `[id] text`, the parser takes every non-empty line that
  contains an Arabic character and, **if `lines.size >= expectedIds.size`**, maps `lines[i]` to `expectedIds[i]`.
* **Why defective:** one extra Arabic line anywhere (a label such as `الترجمة:`, a note, a duplicated line, a line
  belonging to a merged bubble) shifts every following translation onto the wrong bubble. The quality guard cannot see
  a misalignment: each shifted line is valid Arabic and contains no echo of its new source, so it is accepted, cached
  and painted. Misassigned dialogue is worse than a missing bubble — a missing bubble falls back to the local draft.
* **Reproduction / test evidence:** pure-function behaviour, no test exists (`grep -rn parseJsonTranslations app/src/test`
  → no hits). Scenario: `expectedIds = [1,2,3]`, reply
  `"الترجمة:\nأنت متأخر\nانتظرني\nسأعود"` → id 1 gets `الترجمة:` (stripped to empty by `clean`), id 2 gets
  `أنت متأخر`, id 3 gets `انتظرني`, and `سأعود` is dropped — three bubbles are wrong or lost, silently.
* **Expected:** positional mapping only when the count matches exactly *and* nothing else could recover ids; otherwise
  report the missing ids to the retry pass.
* **Minimal fix:** change `>=` to `==` and drop a line that is only a translator label before counting
  (`ArabicValidator.clean` already knows how); keep the retry path for everything else.
* **Required regressions:** equal-count reply maps by position; extra leading label line → no positional mapping
  (retry instead); fewer lines than ids → no positional mapping; `[id]` lines unaffected.
* **Dependencies:** P2-03 (reply shape) first.

### P2-05 — Two name authorities on the Gemini path (VERIFIED RISK, High)

* **Where:** `GeminiTranslator.kt:87-99` (sheet from `sheetProvider`, i.e. series memory), `:128-136`
  (`pinned = NameMemory.plan(translatable.map { it.english }).table` — **page-local**, `known = Glossary.effective()`),
  `NameMemory.kt:425-438` (`applyPinned` rewrites Latin remnants only), `CharacterMemory.kt:141-154` +
  `names/NameForm.kt:65-90` (the decorator's repair covers Latin remnants and recorded `previousArabic` spellings only).
* **Current behaviour:** the model is told the series spelling, then the reply is post-processed with a *different*
  table: whatever `NameMemory.plan` produced from the built-in glossary and page detections. If a reader's locked
  spelling differs from the built-in entry (e.g. `Wei Wuxian` locked as `ويي ووشيان`), and the model leaves the name in
  Latin (which rule 5 explicitly invites), `applyPinned` converts it to the **built-in** `وي ووشيان`; the decorator's
  `rewrite` then only fixes it if that string is a recorded previous spelling — the built-in form usually is not.
* **Why risky:** the P1 guarantee ("a locked spelling reaches the model and wins") holds on the ML Kit path (series
  table is injected into the source, `MlKitTranslator.kt:126-142`) but is **not enforced** on the Gemini path.
* **Reproduction / test evidence:** no test combines a series table with the Gemini post-processing path;
  `NameMemoryTest` tests the page-local plan in isolation, `CachingNameOverrideTest` tests the decorator repair.
* **Expected:** one authority — the page plan should use the same series table the sheet used, or `applyPinned` should
  be skipped when a series sheet is in force and left to `session.rewrite`.
* **Minimal fix (proposed for P2-C, not this task):** pass the series table into the engine (replace
  `NameMemory.plan(...)` at `:129` by the provider's table when non-blank), keeping the page-local table as the
  unassigned fallback.

### P2-06 — One long-block chunk failure aborts the ML Kit page (VERIFIED RISK, Medium)

* **Where:** `MlKitTranslator.kt:154-181`, abort at `:174-176`.
* **Current behaviour:** blocks >400 chars are split by `TextChunker` (≤380-char chunks). If **any** chunk call throws,
  the method returns `PageTranslation.Failure` for the whole page — including the bubbles that already painted
  progressively and the normal blocks that were never attempted.
* **Why risky:** the ordinary path degrades per block (a failed block is retried sequentially, then fails the page only
  if the sequential pass fails); the long-block path is all-or-nothing on the first chunk error. A single 15 s timeout
  inside a novel-length bubble costs every other bubble of the page.
* **Reproduction / test evidence:** code path only; no test injects a failing chunk (`JoinedFallbackTest` covers
  separator choice, not chunk failure).
* **Minimal fix:** omit the failed long block (empty) and continue, so the page contract (empty block → local art
  stays) applies as everywhere else.

### P2-07 — `pageNames` leaks across pages on early returns (VERIFIED RISK, Medium)

* **Where:** `MlKitTranslator.kt:246` is the only place `pageNames` is cleared; early returns at `:103-108`
  (model download failure), `:115-121` (no translatable text) and `:174-176` (long-block failure) return before it.
* **Current behaviour:** after such a failure, a reused `MlKitTranslator` instance still holds the previous page's name
  table and applies it to the next page (`src()` at `:78`).
* **Why risky:** the table is derived from *another page's* proper nouns; a same-spelling collision substitutes a name
  that may not occur on the new page. Instances are created per capture today, which is why this is latent (audit C3).
* **Minimal fix:** `try/finally` around the body, or clear at entry.

### P2-08 — LibreTranslate: page-level rejection and no name sheet (VERIFIED RISK, Medium)

* **Where:** `libre/LibreTranslateTranslator.kt:57-58` (prepare, one batch), `:80-100` (any rejection → page Failure),
  `:102-110` (index mapping).
* **Current behaviour:** Libre gets glossary-substituted text but **no** series name sheet and **no** page context; and
  if the guard rejects a single bubble the entire page is reported as `PARSE_ERROR`, unlike Gemini and ML Kit which
  keep the readable bubbles.
* **Why risky:** the "one bad bubble never costs the page" property (documented in `CachingTranslator.kt:15-22`) does
  not hold for this engine; and name consistency depends entirely on the built-in/user glossary.

### P2-09 — Remote upgrade always wins on non-blank output (VERIFIED RISK, Medium)

* **Where:** `FloatingButtonService.kt:3705-3711` (`expanded.filter { arabic.isNotBlank() } → painter.onRemote`),
  `:3842-3852` (`paint(remote=true)` + `remoteWins`).
* **Current behaviour:** any remote block with non-blank Arabic that passed the guard replaces the local patch,
  regardless of relative quality, length or confidence. Failures keep the draft (`:3577-3581,3595-3598`) — the
  offline-first contract is intact — but the *upgrade* decision is unconditional.
* **Why risky:** Gemini chunked in priority order (P2-02) plus no quality comparison means a worse remote pass can
  systematically replace a better local one, and the user has no signal. This is a design decision to confirm, not an
  oversight: pinning it with a test would make it explicit.

### P2-10 — Cache keys carry no context (VERIFIED RISK, Medium)

* **Where:** `TranslationCache.kt:18-30`, `CachingTranslator.kt:224-232`, `TranslationMemo.kt` (fuzzy tier for ≤40
  chars).
* **Current behaviour:** a translation is keyed by `engine|style|prepared text` only. The same English line in a
  different scene reuses the previous Arabic verbatim, including pronoun/gender choices that depended on the earlier
  context; the fuzzy memo extends this to near-identical short strings.
* **Why risky:** it is the price of speed and is documented; the risk is invisible because no test asserts what should
  happen when a repeated line has a different addressee.

### P2-11 — `PagePlan.prepare` without `preprocess` (VERIFIED RISK, Low)

* **Where:** `PagePlan.kt:59-68` (documents `preprocess`, says "the pipeline passes `Glossary.prepare`"), call sites
  `FloatingButtonService.kt:2747-2750` and `:3273-3275` pass only `ownStrings`.
* **Current behaviour:** dedupe keys are raw OCR text, so `Wei Lin` and `wei lin` still become two items (and two cache
  entries, two engine slots) — the exact case the parameter exists for. `PagePlanWiringTest` passes no preprocess either.
* **Why risky:** documentation/behaviour mismatch and a missed dedupe, not a correctness bug.

### P2-12 — SFX are dropped while the prompt promises SFX transliteration (VERIFIED RISK, Low)

* **Where:** `ocr/MeaninglessText.kt:163-177` (`SOUND_EFFECT` verdict), `PagePlan.kt:74-84` (dropped before the
  engine), vs `GeminiWire.kt:123` (rule 4) and `:134-135` (rule 11).
* **Current behaviour:** on the tap/Live paths a bubble that is only `KRAK!`/`THUD THUD` never reaches any engine, so
  rules 4 and 11 are unreachable code paths for real pages.
* **Why risky:** either the prompt or the planner is wrong; today the user sees untranslated SFX art (acceptable) while
  the prompt promises Arabic transliteration (not delivered). Decide one and delete the other.

### P2-13 — `pageNameSpellings` pairs the wrong bubble with its Arabic (VERIFIED RISK, Low, latent)

* **Where:** `NameMemory.kt:259` (`clean` **filters** blank texts), `:287` (`pageNameSpellings(clean, pageArabic)`),
  `:341-347` (`texts.getOrNull(id)` — a block **id** used as a list index).
* **Current behaviour:** ids are not array indices (OCR ids are not guaranteed 0-based, and blanks shift the list), so
  the "model-decided spelling" votes would pair one bubble's English with another bubble's Arabic.
* **Why latent:** no caller passes `pageArabic` (`GeminiTranslator.kt:93,129`, `SceneNames.kt:29`,
  `CharacterResolver.kt:102` all use the default `emptyMap()`), so the path is currently dead. Whoever enables it must
  fix the indexing first.

### P2-14 — Validator blind spots (IMPROVEMENT, Low)

* **Where:** `ArabicValidator.kt:59-90` (any Arabic without an echo passes), `:144-166` (`hasLongEnglishTail` needs
  ≥3 consecutive source tokens).
* **Current behaviour:** `"أنت"` as the answer for a 30-word sentence passes; `"أنت go"` (a 1-word English remnant)
  passes. Length/coverage and 1–2-word remnants are not checked.
* **Why improvement, not bug:** no correct translation is rejected because of this, and the guard is deliberately
  conservative (false rejections cost a bubble). Adding a length heuristic risks exactly that.

### P2-15 — Post-processing order differs per path (IMPROVEMENT, Low)

* **Where:** engine path `GeminiTranslator.kt:133-142` = `clean → polishArabic → applyPinned → guard`; decorator path
  `CachingTranslator.kt:152-181` = `postProcess(clean → polishArabic → tidyDialogue) → guard → pinnedOverride`; cache
  hits re-run only the guard + override (`:99-114`).
* **Current behaviour:** `tidyDialogue` (ellipsis collapse, repeated-word collapse) is applied on the decorator path but
  not inside the engine; `pinnedOverride` runs after the guard in the decorator but before it in the engine.
* **Why improvement:** the guard judges slightly different text depending on the path; unifying the order removes a
  class of "passes here, fails there" surprises.

### P2-16 — Truncated replies are traced, not treated (IMPROVEMENT, Low)

* **Where:** `GeminiTranslator.kt:334-337` (trace only), `:321-333` (partial map accepted), `GeminiWire.kt:303`
  (`truncated` for `MAX_TOKENS`/`RECITATION`/`SAFETY`).
* **Current behaviour:** a reply cut mid-bubble keeps whatever text arrived for that id and paints a half sentence; the
  missing ids are retried, the truncated one is not.

### Investigated and found correct (NOT A PROBLEM)

| ID | Item | Why it is fine |
| --- | --- | --- |
| P2-N1 | `Glossary.prepare` applied twice / three times (PagePlan, engine, cache key) | Documented idempotent (`Glossary.kt:56-60`) and structurally so: the table matches Latin keys, so a second pass finds nothing to replace; `transliterateUnknown` only fires on capitalised pinyin, which no longer exists after the first pass. Verified by reading both passes, not by a test. |
| P2-N2 | One bad bubble vs the page | Gemini (`GeminiTranslator.kt:145-157`) and ML Kit (`CachingTranslator.kt:152-181`) keep readable bubbles and fail only when nothing is usable — pinned by `GeminiTranslatorTest.englishEchoIsRejectedSoTheOfflineDraftCanRemainVisible` and `MlKitEnglishEchoTest`. |
| P2-N3 | Pinned names beat stale cache entries | `CachingTranslator.kt:89-114` repairs before judging and re-writes the repaired value; `CachingNameOverrideTest` covers it. |
| P2-N4 | Offline first | `remoteUpgradeAvailable` returns false for `ML_KIT` (`TranslatorFactory.kt:219-227`); a remote failure keeps the ML Kit draft and opens the circuit (`FloatingButtonService.kt:3577-3581,3595-3603,3676-3683`); the remote never delays phase 1. |
| P2-N5 | Names survive Gemini chunking | The sheet is attached to **every** chunk body, both formats (`GeminiWire.kt:187-215,236-257`; P0-A), and `chunkBlocks` keeps ids. |

---

## 5. Confirmed bugs vs recommendations

* **Fix before any quality tuning (P2-A/P2-B):** P2-01 (corrupted source text), P2-02 (misused context), P2-03/P2-04
  (Gemini reply integrity). These are deterministic and do not require a model to observe.
* **Decisions to confirm with the product owner (P2-C/P2-D):** P2-05 (which name authority wins), P2-09 (does remote
  always win), P2-12 (SFX: translate or drop).
* **Quality preferences, not defects:** "literal vs natural Arabic", tone, dialect-vs-MSA, and the transliteration
  tables' wording. No finding in this audit asks for prompt text changes.

---

## 6. Existing test coverage and gaps

**Discovered:** 629 `@Test` in `app/src/test` (single `:app` module, `testDebugUnitTest`), 69 files. Translation-related
suites, by `@Test` count: `GeminiTranslatorTest` 22, `BatchSplitterTest` 21, `GeminiWireTest` 16, `NameMemoryTest` 14,
`CachingTranslatorTest` 11, `BatchPolicyTest` 11, `ArabicQualityGuardTest` 11, `MlKitEnglishEchoTest` 10,
`TranslationMemoTest` 9, `GlossaryTest` 9, `PageBatchTest` 7, `LibreTranslateWireTest` 6,
`LibreTranslateTranslatorTest` 6, `ArabicValidatorTest` 6, `PagePlanWiringTest` 5, `PageDedupeTest` 5,
`CachingNameOverrideTest` 5, `TextChunkerTest` 5, `JoinedFallbackTest` 3, `BubbleTextRulesTest` 3,
`ArabicPolishTest` 3 (`translate/` package total 231).

**Executed in this task: 0.** No JDK/`gradle` in the sandbox, no test command was run, and none is claimed.

**Coverage gaps this audit relies on (each is a required regression for the finding that names it):**

| Gap | Why it matters | Finding |
| --- | --- | --- |
| No test for table collisions (`long`, `song`, `sun`, `pan`, `tan`, `ding`) | The suite is green while ordinary words are renamed | P2-01 |
| No test on the order the remote engine receives | Context grouping is unspecified behaviour | P2-02 |
| `parseJsonTranslations` has **no direct test at all** | The weakest parser path is untested | P2-04 |
| No test combining a series name table with Gemini post-processing | The P1 guarantee is unverified on that path | P2-05 |
| No failing-chunk test for long blocks | All-or-nothing page loss is unpinned | P2-06 |
| No test for `pageNames` after an early return | C3 is still open in the audit | P2-07 |
| No `pageArabic` test | Latent wrong-index path | P2-13 |
| `GeminiTranslatorTest.kt:273` pins "first body has no `responseMimeType`" | The bug is pinned as behaviour — the test must change with the fix | P2-03 |

---

## 7. Prioritized P2 roadmap

Each task is one reviewable push with its own report, tests and CI run; nothing in P2 may touch OCR, prompts, model
ids, dependencies or the `FloatingButtonService` structure.

### P2-A — Source-text and context integrity (highest priority)

| | |
| --- | --- |
| Scope | P2-01, P2-02 |
| Files likely to change | `translate/Glossary.kt`, `overlay/FloatingButtonService.kt` (relay argument only), tests `GlossaryTest`, `PageBatchTest`/new `TranslationOrderTest` |
| Dependencies | none |
| Regression tests | collision set unchanged in `prepare`; names still replaced; relay order == page order; ML Kit progressive order unchanged |
| Acceptance | `Glossary.prepare` leaves lowercase ordinary words untouched and still replaces every built-in name; the remote engine receives blocks in page-id order on the tap path; both pinned by tests; CI green |
| Risks | tightening the case rule may stop replacing a genuinely lowercase name spelling (`wei lin`); mitigate by keeping multi-word keys unconditional and adding a test for the lowercase-name case |
| Must stay untouched | ML Kit strategies, cache keys' *format*, prompts, name-resolution priority |

### P2-B — Gemini reply integrity

| | |
| --- | --- |
| Scope | P2-03, P2-04, P2-16 |
| Files | `gemini/GeminiTranslator.kt`, `gemini/GeminiWire.kt`, tests `GeminiWireTest`, `GeminiTranslatorTest` |
| Dependencies | P2-A recommended first (chunk content), not required |
| Regression tests | `jsonReply` honoured on the first body; positional mapping only on exact count; label line ignored; truncated bubble withheld |
| Acceptance | the flag means what its KDoc says, or the KDoc is corrected; no reply shape can silently shift translations; truncated text never painted; CI green |
| Risks | changing the primary body could alter latency/cost; keep the default body and only honour the flag |
| Must stay untouched | prompt text, model ids, retry ladder timings |

### P2-C — Name authority and engine consistency (needs a decision first)

| | |
| --- | --- |
| Scope | P2-05, P2-11, P2-13 (+ P2-08 if Libre parity is approved) |
| Files | `gemini/GeminiTranslator.kt`, `translate/NameMemory.kt`, `overlay/FloatingButtonService.kt` (preprocess argument), `libre/LibreTranslateTranslator.kt` |
| Dependencies | decision on "series table is the single authority" |
| Regression tests | series spelling wins over the built-in form on the Gemini path; dedupe uses prepared text; `pageArabic` indexing correct or removed |
| Acceptance | one authority per page, proven by test; no change to resolution priority; CI green |
| Risks | touching name post-processing can regress P1 guarantees — the tests must come first |
| Must stay untouched | series-scoped memory architecture, JSON store, `NameSession` caching |

### P2-D — Resilience and validation polish (only if justified)

| | |
| --- | --- |
| Scope | P2-06, P2-07, P2-14, P2-15 (+ P2-10/P2-12 as decisions, no code) |
| Files | `mlkit/MlKitTranslator.kt`, `translate/CachingTranslator.kt`, `translate/ArabicValidator.kt` |
| Dependencies | P2-C (for post-processing order) |
| Regression tests | failing long-block chunk → page still paints other bubbles; `pageNames` empty after every early return; polishing order identical for engine and cache paths |
| Acceptance | no single block can cost a page; no stale name table; guard verdicts independent of path |
| Risks | loosening failure semantics can hide engine outages — keep the all-failed case a `Failure` |
| Must stay untouched | validator thresholds that already have regressions, cache tiers, renderer |

---

## 8. Acceptance criteria for every proposed task (common)

1. Its own report at the repository root, indexed in `REPORTS.md`, in the same push.
2. Every listed regression test exists and is executed by CI on the final commit; a run id is recorded.
3. No application behaviour changes outside the finding's scope; no prompt, model, dependency or Gradle change.
4. `629` test count only ever moves upward, and the new total is stated with its scope.
5. Remaining risks (device validation, real API traffic) are restated, not dropped.
6. The audit rows for the finding are updated to "fixed in P2-x" only after CI verifies it.

---

## 9. Explicit non-goals and architectural constraints

1. **Offline first stays mandatory.** ML Kit remains the always-on draft; the remote engine stays an optional upgrade
   that can never delay or suppress it (`P2-N4`).
2. **Series-scoped memory stays.** No package-based/global memory (the audit's item 7 was deliberately rejected, D1 in
   `P1_SERIES_NAME_MEMORY.md`); no change to resolution priority, store schema, staging validation or cache rewriting.
3. **No prompt/model changes in P2-A…P2-D** beyond what a finding explicitly requires; no new engine; no dependency
   additions (no Robolectric, no new libraries).
4. **Do not redesign the cache.** P2-10 is a decision to record, not a redesign task.
5. **Do not touch P0/C4, service extraction, OCR, rendering/RTL or UI.**
6. **No merging.** PR #2 remains for architectural approval.

---

## 10. Verification status and unresolved questions

**What this audit verified directly (source at `fe5aa6a`)** — the four confirmed bugs were each reduced to a
deterministic code path with line references; the nine risks each have a concrete path and a named gap in the tests.

**Not executable here:** the sandbox has no JDK, `javac`, `gradle` or Android SDK (`which java javac gradle` → nothing,
`ANDROID_HOME` unset), so **no test was run in this task**; the counts in §6 are source-level `@Test` counts.

**Executed evidence used (historical CI, verified through the GitHub API, all ancestors of the baseline):**
run 43565553808 (`05fa1ab`), 43565915093 (`c528ca2`), 43566212238 (`fe5aa6a`) — *Android build*: success, steps
*Unit tests* and *Build debug APK* both success. These runs executed the suite **at the audit baseline**, i.e. the
same code this report describes.

**Documentation-only CI for this audit:** commit `01e12a6` (this report + `REPORTS.md` + `REPOSITORY_REPORT.md`)
→ run [43567398304](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43567398304) — **success**,
steps *Unit tests* ✅ and *Build debug APK* ✅. The run executed the unchanged application code; it is not
evidence that any finding is fixed, only that the repository still builds and the suite still passes with the
defects present. These runs prove the suite passes **with** the defects above, which is
the point: the gaps are untested behaviour, not failing tests.

**Unresolved questions (need a decision or a device/API test):**

1. Does Gemini actually return legacy prose often enough for P2-04's path to be the norm? (Needs a real key and a
   logged sample; the wire format is only covered by fakes.)
2. Which name authority should win when a series spelling exists but the model returns Latin (P2-05)?
3. Should a remote result replace a local draft unconditionally (P2-09)?
4. SFX: translate or keep the art (P2-12)?
5. Device behaviour of every finding (withheld bubbles, chunk context, name rendering) is **unverified** — see
   `MANUAL_TESTS.md` M0.4b for the P0/C2 check and §7 for the checklist this audit would add.
