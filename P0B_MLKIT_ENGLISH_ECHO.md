# P0/C2 — Eliminating the English echo in ML Kit translation

**Scope:** the four doors through which on-device (ML Kit) output could present **untranslated English
as Arabic**, and nothing else. No translation-architecture redesign, no engine replacement, no OCR
change, no Gemini-prompt or LibreTranslate-networking change, no Arabic-rendering/RTL change, no
removal of progressive translation, no new dependency, no retry loop, no added latency. Series name
memory, pinned-name overrides and the remote-upgrade/local-fallback arbitration are untouched.
**Branch:** `arena/8a718184-manhwalens` · PR [#2](https://github.com/mouadknight0-cell/ManhwaLens/pull/2) (not merged)
**Base commit:** `6eb8974be92a26b319e23b85fec36a17a1499ec7`
**Files changed:** `translate/MlKitPageAssembly.kt` (new), `translate/mlkit/MlKitTranslator.kt`,
`translate/CachingTranslator.kt`, `overlay/FloatingButtonService.kt`,
`translate/MlKitEnglishEchoTest.kt` (new); docs: this file, `P0_MANHWALENS_AUDIT.md`, `REPORTS.md`,
`REPOSITORY_REPORT.md`.

---

## 1. The defect, as verified in the base commit

The quality guard (`ArabicValidator`) already existed and already rejected echoes — but it was reachable
from only two of the four paths that put text on the overlay. All four doors were read in the code at the
base commit:

| # | Door | Evidence (base commit `6eb8974`) | Consequence |
|---|------|----------------------------------|-------------|
| 1 | Page assembly substitutes the source | `mlkit/MlKitTranslator.kt:246` — `TranslatedBlock(b.id, b.english, arabicById[b.id] ?: if (b.english.isBlank()) "" else b.english)` | A block ML Kit answered nothing for became the **English original**, and the page still returned `PageTranslation.Success` — a silently "successful" untranslated page. |
| 2 | Progressive callback is unguarded | `mlkit/MlKitTranslator.kt:276-279` — `if (tb.arabic.isNotBlank()) runCatching { l(tb) }` | Every finished block was painted straight onto the art, before the page-level guard could run. |
| 3 | Guard scoped to remote engines only | `CachingTranslator.kt:51-52` — `engineId == "gemini" \|\| engineId == "libre_translate"` | ML Kit output was written to the memory LRU, the disk cache and the fuzzy memo **and served back** without ever being judged. |
| 4 | Painter only refuses blank text | `overlay/FloatingButtonService.kt:3844-3846` — `if (tb.arabic.isBlank()) return` | An echo was laid out with Cairo Bold, right-aligned inside an RTL paragraph — visibly broken, and exactly what the guard exists to prevent. |

The four doors compound: door 1 manufactures the echo, doors 2 and 4 display it, door 3 makes it
permanent for the rest of the chapter.

## 2. The fix

### (A) A missing answer is empty — never the source

`MlKitPageAssembly.ordered(blocks, arabicById)` replaces the inline `map` at the former line 246:

```kotlin
fun ordered(blocks: List<TranslationBlock>, arabicById: Map<Int, String>): List<TranslatedBlock> =
    blocks.map { b -> TranslatedBlock(id = b.id, english = b.english, arabic = arabicById[b.id].orEmpty()) }
```

Page order and ids are preserved exactly; the English source is still carried on the block (the painter
and the fallback need it) but is never the output. A block with no answer is **empty**, which is the
existing "nothing to paint" state: the art stays untouched and the caller's fallback/reconciliation
applies. A page ML Kit answers nothing for is therefore no longer reported as translated content.

*Rejected:* returning `PageTranslation.Failure` for any single missing block. That would discard the
blocks the engine *did* translate and change the page contract for a per-bubble condition; the contract
already expresses "this bubble has no usable text" as an empty block.

### (B) The shared guard now covers ML Kit — fresh and cached

`CachingTranslator.requiresArabicGuard` gains `|| engineId == "ml_kit_en_ar"`. This single predicate
already gates all three tiers, so ML Kit inherits the complete existing behaviour:

* fresh output is judged per bubble (`ArabicValidator.issue`) — a rejected bubble is returned **empty**,
  its siblings are kept, and an all-rejected reply keeps the existing failure contract;
* a rejected bubble is never written to the memory LRU, the disk cache or the fuzzy memo
  (the write-back sits behind the same verdict);
* a stale cached ML Kit entry that fails the guard is **evicted from all three tiers** and treated as a
  cache miss, so the block is re-translated instead of re-served.

The verdicts themselves are unchanged (`EMPTY_OUTPUT / NO_ARABIC_SCRIPT / UNTRANSLATED_ECHO /
MALFORMED_OUTPUT / PARTIAL_TRANSLATION / MEANINGLESS_OUTPUT`), which is what keeps the legitimate cases
alive: Arabic dialogue containing a Latin character name still passes, and a single preserved unfamiliar
name (`Hanwoo`) is still accepted rather than "corrected" into nothing.

*Rejected:* a second, ML-Kit-specific validator. Two guards drift; the P0 audit's whole point is that one
guard judges every engine.

### (C) The progressive path is filtered, not disabled

`MlKitTranslator.emitReady()` now asks `MlKitPageAssembly.progressivePayload(tb)`, which returns the block
unchanged when it is non-blank and passes `ArabicValidator.issue`, and `null` otherwise (with a
`PipelineTracer` span naming the withheld block). Consequences:

* a good Arabic bubble reaches the painter **immediately and unchanged** — progressive display keeps its
  latency and its per-block behaviour;
* an echo, a blank, or a half-translated sentence is simply **not painted now**; the final page result
  (validated again at B) decides what it becomes;
* `BubblePainter.paint()` gained a matching last-line-of-defence check on `block.text` vs `tb.arabic`, so
  *any* path — progressive callback, remote upgrade, final reconciliation, cache — cannot draw
  non-Arabic into an RTL patch. The same validator object decides, so a preserved Latin name still draws.

Remote-vs-local arbitration (`remoteWins`) is untouched: the guard only decides whether a patch is drawn,
never which source wins.

*Rejected:* dropping the progressive callback for ML Kit. That would hide the echo (and the user's
"rejected" list explicitly keeps progressive translation) at the cost of the perceived responsiveness the
progressive path exists for.

### (D) Rejected blocks stay empty; no retries, no latency

A rejected or missing block is `""` in the page result, which is the state the existing fallback,
reconciliation and area-merge code already handles. Nothing retries a rejected block, nothing waits
longer, and the guard's cost is one regex-based check per block.

## 3. Files changed

| File | Change | Why |
|------|--------|-----|
| `app/src/main/java/com/manhwalens/app/translate/MlKitPageAssembly.kt` | **new** (46 L) | The pure half of ML Kit assembly: `ordered()` (no source substitution) + `progressivePayload()` (progressive gateway). Pure Kotlin, so the rules are JVM-testable without Robolectric — `MlKitTranslator` itself is Android-bound (`android.util.Log`, ML Kit `Tasks`). |
| `app/src/main/java/com/manhwalens/app/translate/mlkit/MlKitTranslator.kt` | +21/−7 | Assembly delegates to `ordered()`; `emitReady()` filters through the gateway and traces withheld blocks. Batching, timeouts, strategies, chunking and name pinning untouched. |
| `app/src/main/java/com/manhwalens/app/translate/CachingTranslator.kt` | +8/−2 | `requiresArabicGuard` extended to `ml_kit_en_ar` (one predicate covers read, write and eviction paths). |
| `app/src/main/java/com/manhwalens/app/overlay/FloatingButtonService.kt` | +9 | Painter-boundary defence before any sampling or patch planning; logs the refusal reason instead of drawing. |
| `app/src/test/java/com/manhwalens/app/translate/MlKitEnglishEchoTest.kt` | **new** (10 tests) | Regressions for every door and for the cases that must keep working. |

## 4. Tests added (10) and the property each pins

Pure JVM, existing JUnit 4, no new dependency, no Robolectric.

| Test | Property pinned |
|------|-----------------|
| `englishEchoIsRejectedByTheSharedGuard` | Plain English in → `UNTRANSLATED_ECHO`. |
| `outputWithoutArabicIsRejectedUnlessItIsALegitimateStandaloneName` | Latin-only dialogue is rejected; a single preserved name (`Hanwoo`) is not. |
| `arabicDialogueWithALatinCharacterNameIsAccepted` | Arabic dialogue keeping a Latin character name stays usable (no over-rejection). |
| `aPartiallyTranslatedSentenceWithAnEnglishTailIsRejected` | Arabic + an untranslated English sentence → `PARTIAL_TRANSLATION`. |
| `aBlockWithNoMlKitAnswerStaysEmptyAndKeepsPageOrder` | `ordered()`: missing answer → `""` (never the English), ids and page order intact, source still carried. |
| `rejectedMlKitOutputIsNotWrittenToMemoryDiskOrFuzzyCaches` | A rejected ML Kit block is absent from all three tiers, its valid sibling is cached, and a repeat call returns to the engine (nothing was memoised). |
| `invalidCachedMlKitOutputIsDiscardedAndRetranslated` | A pre-guard cached echo is treated as a miss: evicted, re-translated once, and the cache then holds the Arabic. |
| `theProgressiveGatewayNeverDeliversRejectedOutput` | Echo, blank and half-translated blocks never reach the progressive listener. |
| `validArabicStillReachesThePainterImmediately` | Valid Arabic and a preserved name are returned unchanged and delivered immediately (progressive display intact). |
| `geminiAndLibreTranslateGuardBehaviourIsUnchanged` | For `gemini` and `libre_translate`: echo blanked, valid sibling kept, caches reflect exactly that — the pre-existing remote behaviour is unchanged. |

Existing suites that must keep passing (and do, in the run below): `ArabicValidatorTest`,
`ArabicQualityGuardTest`, `CachingTranslatorTest`, `TranslationMemoTest`, plus the rest of the module.

Module test count: **619 → 629** (`@Test` annotations across `app/src/test`).

## 5. Verification

The sandbox has no JDK and no Android SDK, so Gradle cannot run locally; GitHub Actions is the only
execution path. The committed command is the repository's:

```
./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain
```

**Status:** the workflow run for this commit is listed under "CI and commits" below with its result.
No local test execution is claimed.

## 6. CI and commits

* Code + tests + this report: commit `c008f62` (25th on the branch, on top of base `6eb8974`).
* CI run: **pending when this file was committed** — the workflow run id and its result are recorded in
  the follow-up documentation commit, together with the audit status flip (no result is claimed here).
* Follow-up documentation commit: records the run id, flips C2 to *fixed* in
  [P0_MANHWALENS_AUDIT.md](P0_MANHWALENS_AUDIT.md), and refreshes the snapshot in
  [REPOSITORY_REPORT.md](REPOSITORY_REPORT.md).

## 7. Remaining risks

* **Device validation is outstanding.** All of this is verified by unit tests and CI; no device or
  emulator run was performed here, so the visual result (a withheld bubble keeping the original art) is
  reasoned, not observed. `MANUAL_TESTS.md` remains the checklist.
* **ML Kit's echo rate is not measured.** The fix makes an echo invisible and uncacheable; it does not
  make the model translate. A page ML Kit answers nothing for now yields empty blocks, so a chapter can
  show more untranslated art than before *(previously it showed wrong Arabic instead)* — the honest
  trade, and the reason C5/offline-quality work stays open.
* **Other engine ids remain outside the guard** (any future engine must opt in via
  `requiresArabicGuard`). ML Kit was the one in scope for C2.
* **Painter guard scope.** It judges `block.text` against `tb.arabic`; a bubble whose Arabic legitimately
  embeds three or more Latin tokens that also appear verbatim in the source line is withheld. That is the
  shared guard's existing rule (already applied to Gemini/LibreTranslate), not a new one, but it is now
  enforced at the last boundary too.
* **Unrelated open audit items** (C4 live-thread cleanup, RTL text direction, C3/C5, `FloatingButtonService`
  seams) are untouched by this phase.

## 8. Invariants preserved

* Progressive translation still paints valid blocks as they finish; nothing was made batched or blocking.
* Rejected blocks are empty — the fallback/reconciliation contract, area merging and `remoteWins`
  arbitration are unchanged.
* Pinned-name overrides still rewrite cached text *before* the guard judges it (so a locked spelling is
  not mistaken for an echo).
* Series-scoped character memory, the JSON data model and its staging pipeline are untouched.
* No new dependency; no Gemini prompt, LibreTranslate networking, OCR or RTL change.

---

## ملخص بالعربية

**المشكلة:** نص إنجليزي غير مترجَم كان يمكن أن يُعرَض ويُخزَّن كأنه عربي، من أربعة أبواب: استبدال
النتيجة المفقودة بالنص الأصلي في `MlKitTranslator.kt:246`، ثم تمرير كل بلوك مباشرة إلى الرسّام دون فحص
(`:276-279`)، ثم استثناء `ml_kit_en_ar` من الحارس في `CachingTranslator.kt:51-52`، ثم فحص الفراغ فقط في
`FloatingButtonService.kt:3844-3846`.

**الحل:** (أ) البلوك بلا نتيجة يبقى فارغًا ولا يُستبدل بالإنجليزية؛ (ب) الحارس المشترك `ArabicValidator`
أصبح يغطي ML Kit للنتائج الجديدة وللمخزَّنة، ولا يُخزَّن مرفوض ويُحذف القديم الفاسد؛ (ج) المسار التدرّجي
يُرشَّح لا يُلغى، مع حماية أخيرة داخل الرسّام؛ (د) البلوك المرفوض يبقى فارغًا دون إعادة محاولة أو تأخير.

**الاختبارات:** 10 اختبارات JVM جديدة (المجموع 629)، والتحقق النهائي عبر GitHub Actions.
