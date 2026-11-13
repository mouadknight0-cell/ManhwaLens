# P0-A — Gemini Name Sheet Fix

**Phase:** P0-A (first fix from the [P0 architecture audit](P0_MANHWALENS_AUDIT.md))
**Baseline:** v0.11.0 (`b8e37de`)
**Scope:** `translate/gemini/` only — no other engine, service, offline or persistence change.

---

## 1. Root cause (confirmed)

The page-local name sheet never reached the primary Gemini request.

The production default is `jsonReply = false` (`GeminiTranslator.kt:55`; `TranslatorFactory.kt:82`
builds the translator without overriding it), so `translateChunk` sent the legacy body:

```
GeminiTranslator.kt:216  buildRequestBody(chunk, THINKING_LEVEL, sheet, jsonReply = false)
  → GeminiWire router (:204-215)
      jsonReply == false  →  buildRequestBody(blocks, thinkingLevel)   ← no nameSheet parameter
          → promptFor(blocks)                                          ← sheet absent
              → Glossary.promptHints()                                 (up to 40 global names)
```

The sheet survived only on the HTTP-400 retry, via `buildFastBody(chunk, sheet, …)`
(`GeminiTranslator.kt:234`). Net effect: page-local names were never enforced on the normal
path, and `NameMemory.applyPinned` could only repair the Latin-remnant case afterwards.

No test covered the sheet in a request body: `GeminiWireTest` only ever called the 1-argument
form and `GeminiTranslatorTest` only asserted `thinkingLevel`, model selection and HTTP status.

---

## 2. Fix

Three coordinated edits, all inside `GeminiWire`:

1. **The legacy builder now takes the sheet.** `buildRequestBody(blocks, thinkingLevel)` was
   split into a 2-argument convenience form that delegates to a new
   `buildRequestBody(blocks, thinkingLevel, nameSheet)`, which passes the sheet to
   `promptFor(blocks, nameSheet)`.
2. **The router forwards the sheet to both branches** —
   `if (jsonReply) buildFastBody(...) else buildRequestBody(blocks, thinkingLevel, nameSheet)`.
   This is the one-line root-cause fix.
3. **`nameSheet` deliberately has no default value.** A default would leave 3-argument calls
   depending on Kotlin's "fewer default arguments wins" rule; two explicit arities keep every
   call site unambiguous.

No duplication: `promptFor` already implements "sheet if present, else the reader's glossary"
as an `if`/`else` on `hints`, so a sheet and the global glossary can never appear together.
`SYSTEM_INSTRUCTION`, temperatures, `thinkingConfig`, `responseMimeType` and both reply formats
are byte-identical to before.

Behaviour preservation: `promptFor(blocks)` and `promptFor(blocks, "")` were already equivalent,
so every existing call path produces exactly the same output as before — except the one being
fixed. `GeminiTranslator` needed no logic change (it was already passing `sheet`); only a
clarifying comment was added at the call site.

---

## 3. Tests added (8)

JUnit 4 + `org.json` only — no new dependencies.

**`GeminiWireTest` (wire level):**

| Test | Proves |
|---|---|
| `legacyRequestBodyCarriesEveryNameSheetLine` | every `Name = عربي` line is in the `jsonReply = false` body; the glossary block appears **exactly once** |
| `jsonRequestBodyCarriesEveryNameSheetLine` | same for the JSON body, plus `responseMimeType` still set |
| `bothBodyFormatsAgreeOnTheNamesTheySend` | the prompt is compared line-by-line on both paths, so the formats can never silently diverge (line-by-line because JSON escapes newlines) |
| `blankNameSheetFallsBackWithoutMalformedPromptContent` | a blank sheet produces a body **identical** to the old 1-arg body; no `null`, no empty `"text":""`, at most one glossary header |
| `nullThinkingLevelOmitsThinkingConfigButKeepsTheSheet` | `thinkingLevel = null` emits no `thinkingConfig`, no `null`, sheet still present |

**`GeminiTranslatorTest` (primary and retry paths, via `FakeGeminiApi`):**

| Test | Proves |
|---|---|
| `primaryLegacyRequestCarriesThePageNameSheet` | default translator, **exactly one** captured request (so it is the primary request, not the 400 retry), every sheet line present, `thinkingLevel: low` kept, no `responseMimeType` |
| `jsonRetryBodyCarriesThePageNameSheetToo` | a queued HTTP 400 then 200 proves **both** the primary body and the JSON retry body carry the sheet |
| `pageWithoutNamesSendsNoMalformedPrompt` | a page with no proper nouns sends no `null`, no empty text part, at most one glossary header |

The expected sheet is computed with the same `NameMemory.plan` + `sheet(limit = 20)` calls the
translator uses, so the tests cannot drift from production. `FakeGeminiApi` gained an optional
response `queue` (consumed before `result`) so the 400-retry path can be scripted; existing tests
are unaffected.

---

## 4. Verification status — verified by CI

The suite does not run in the development sandbox (no JDK, no Android SDK, no reachable
Gradle/Maven hosts), so it was verified on GitHub Actions, which runs on every pull request:

```
./gradlew :app:testDebugUnitTest        # CI, and the same command locally
```

**Run 1** (`43513473811`): 517 tests completed, **1 failed** — `primaryJsonRequestCarriesThePageNameSheet`.

The failure was in the new test, not in the fix, and it exposed a real asymmetry:
`translateChunk` hardcodes `jsonReply = false` for the **primary** request
(`GeminiTranslator.kt:216`), so the constructor flag `jsonReply` only changes the HTTP-400
**retry** body (`buildFastBody`, `:234`). The test asserted `responseMimeType` on the first
request, which is only ever a legacy body. Replaced by `jsonRetryBodyCarriesThePageNameSheetToo`,
which scripts a 400 then a 200 and asserts the sheet on **both** bodies.

**Run 2** (`43514068424`): **passed** — `:app:testDebugUnitTest` green (517 tests) and
`:app:assembleDebug` produced the debug APK.

> Note (out of scope, tracked for later): the `jsonReply` constructor flag does not make the
> primary request JSON — its KDoc implies it does. It only affects the retry body. Fixing that
> would change production request behaviour, so it is not part of P0-A; the name sheet is now
> carried by both formats either way.

## 5. Remaining risks

1. **`jsonReply` is retry-only** (see the note in §4): the constructor flag never makes the
   primary request JSON. Harmless for names now that both bodies carry the sheet, but the flag's
   KDoc overstates what it does — fix separately if the JSON format is ever wanted first.
2. The 3-argument legacy builder and the 4-argument router both accept three positional
   arguments. Kotlin picks the legacy one, and both now carry the sheet, so a mis-resolution could
   only change the *reply format* — never lose names. Renaming the router would remove even that;
   not done, to keep the diff minimal.
3. The prompt grows by the sheet (≤20 pairs, ~800 chars) on the legacy path. Chunking is based on
   block text, so this is a small constant per request, not a new chunk — the JSON path already
   behaved this way.
4. The sheet is still page-scoped and still computed twice per page (`GeminiTranslator.kt:78` and
   `:113`) — tracked as C5 in the audit, out of P0-A scope.
5. The related ML Kit echo defect (**C2**) is untouched by design: `MlKitTranslator.kt:220` can
   still paint English as Arabic, and `CachingTranslator`'s quality guard still excludes ML Kit.
6. An empty sheet still falls back to the 40-name global glossary — pre-existing behaviour,
   unchanged by design.
