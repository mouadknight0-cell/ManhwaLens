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

**`GeminiTranslatorTest` (primary path, via the existing `FakeGeminiApi`):**

| Test | Proves |
|---|---|
| `primaryLegacyRequestCarriesThePageNameSheet` | default translator, **exactly one** captured request (so it is the primary request, not the 400 retry), every sheet line present, `thinkingLevel: low` kept, no `responseMimeType` |
| `primaryJsonRequestCarriesThePageNameSheet` | same guarantee with `jsonReply = true` |
| `pageWithoutNamesSendsNoMalformedPrompt` | a page with no proper nouns sends no `null`, no empty text part, at most one glossary header |

The expected sheet is computed with the same `NameMemory.plan` + `sheet(limit = 20)` calls the
translator uses, so the tests cannot drift from production.

---

## 4. Verification status — read this before review

The test suite has **not** been executed on a real toolchain yet. The development sandbox had no
JDK, no Android SDK and no reachable Gradle/Maven hosts, so the targeted task could not start.

Run this before sign-off (single invocation, no parallel tasks):

```
./gradlew :app:testDebugUnitTest --tests "com.manhwalens.app.translate.gemini.*" --no-daemon --console=plain
```

Expected: 8 new tests pass; the 17 pre-existing Gemini tests are unaffected (their assertions are
substrings that this change does not alter — the sheet is added only when one exists, and a blank
sheet is a no-op).

Verified in the sandbox instead:

- Static review of overload resolution (the 3-argument builder is the only exactly-3-parameter
  candidate), nullability (`thinkingLevel: String?`, handled by `isNullOrBlank()`), imports, and
  every call site — only the 1-argument form in existing tests, one 4-argument call in production,
  no Java sources.
- A faithful Python mirror of `promptFor`, both body builders and the router, running the same
  assertions: **26/26 checks passed**, including proof that the pre-fix router really did drop the
  sheet. This validates prompt assembly only, not Kotlin compilation.

---

## 5. Remaining risks

1. **Compilation/test execution unverified** (see §4) — the highest risk. If a test fails, the
   likely cause is a test assumption (`Glossary.prepare` touching `"Hello!"`, or the Arabic reply
   being rejected by `ArabicValidator`), not the fix; both are asserted explicitly so they fail
   loudly rather than silently.
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
