# P1 — Series Character Memory

**Phase:** P1 (second item of the [P0 architecture audit](P0_MANHWALENS_AUDIT.md), C1/C5 follow-up)
**Baseline:** v0.11.0 (`b8e37de`), on top of P0-A (`528d629`)
**Branch:** `arena/8a718184-manhwalens` → PR [#2](https://github.com/mouadknight0-cell/ManhwaLens/pull/2)
**Commits:** `6a71f77` (feature) → `1686e72` (compile fix) → `8d5cc9e` (two test-backed fixes)
**Verified by:** CI run [43518604612](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43518604612) — `build` **success**, 3m14s
**Design reference:** [P1_CHARACTER_MEMORY.md](P1_CHARACTER_MEMORY.md) (format, storage, ladder)
**New Gradle dependencies:** none. **Network use on the naming path:** none.

---

## 1. Problem

v0.11.0 pins names **per page** (`NameMemory.plan`): it scans one page, chooses one Arabic
spelling per name, tells the engines, and forgets everything. The same character therefore drifts
between bubbles, pages, chapters and restarts:

| page | ML Kit produced |
| --- | --- |
| 1 | `هان جيو` |
| 2 | `هان جي وو` |
| 3 | `هان جيوو` |

The glossary can fix a name, but it is **global** — one entry applies to every story the reader
opens — and it does not survive a cache hit: a chapter translated before the correction keeps
painting the old spelling.

What was missing was not a better guess. It was **memory**: a place where a confirmed spelling
lives beyond the page, scoped to the story it belongs to, and that outranks any fresh detection
or stale cache entry.

---

## 2. Decisions

| # | Decision | Why |
| --- | --- | --- |
| D1 | Memory is scoped to an **explicitly selected series**, never to the reader package | A package is a transport detail: two chapters can ship in two packages (same story) and two stories can live in one package (different stories). Binding to `com.example.reader` would mix them. |
| D2 | Nothing is read or written while **no series is selected** | An unassigned session must not attribute a mapping to a story the reader never chose. Behaviour is then byte-identical to v0.11.0. |
| D3 | **JSON file store**, not Room | "No new dependencies" is a standing rule. A series holds tens of records (a few kB), read once per page; Room would add an annotation processor, schema export and migrations to query a list that fits in memory. The app already persists JSON atomically for the translation cache. |
| D4 | Lookup key = **letters only, lower case** (no fuzzy matching) | `Han Ji-woo`, `Han Jiwoo` and `han ji woo` are one person. Fuzzy matching would eventually merge two different people — the one failure mode memory must not have. |
| D5 | `TRUST_MIN = 0.6`: a machine transliteration is **never** "confirmed" | A guess that is stored as fact entrenches itself. Only locked, user, built-in or ≥0.6-confidence spellings may rewrite text or be treated as memory. |
| D6 | Ambiguous English words (`Hope`, `May`, `Rose`, `Will`…) are never auto-confirmed, but **can** be locked | "Hope" is dialogue as often as it is a character. The reader is the only authority there. |
| D7 | A bare surname is **not evidence of a script** | `Wang`, `Kim`, `Lee`, `Lin`, `Han` are English words, Chinese surnames, Korean surnames and Japanese readings at once. |
| D8 | Nothing trustworthy → **keep the Latin spelling** | Better an untranslated name than an invented Arabic one. |
| D9 | Corrections must beat the **cache**, not just future pages | A reader who fixes a spelling expects the next capture to show it, including for a chapter translated last week. |
| D10 | One page table feeds **both** engines | ML Kit gets `.table` (inline substitution, its only steering wheel); Gemini gets `.sheet` (the P0-A path). Same object → consistency by construction, not by prompt wording. |

---

## 3. Storage

`filesDir/character_memory.json`, `version: 1`:

```json
{ "version": 1,
  "series": { "<seriesId>": [
      { "id": "…", "seriesId": "…", "originalName": "Han Ji-woo",
        "normalizedName": "hanjiwoo", "arabicName": "هان جيو",
        "script": "KOREAN", "aliases": ["Jiwoo"], "confidence": 1.0,
        "source": "USER", "locked": true,
        "previousArabic": ["هان جي وو"], "occurrences": 0 }
  ] } }
```

Safety properties implemented in `JsonCharacterStore`:

| Property | Mechanism |
| --- | --- |
| No half-written file | complete copy in `character_memory.json.tmp` + `fd.sync()`, committed with one atomic move; the live file is never opened for writing (reviewed and hardened — see [P1_REVIEW_CORRECTIONS.md](P1_REVIEW_CORRECTIONS.md) §1) |
| Corrupt file never crashes the pipeline | `character_memory.json.corrupt` is set aside (kept for support), store starts empty |
| A newer schema is not guessed at | `version > SCHEMA_VERSION` → set aside as `.newer` |
| A reader's decision is final | `upsert` returns the existing record untouched when it is locked and the write is not |
| No full scan per OCR block | one in-memory index per file, loaded once, guarded by a single monitor |
| Tolerant reads | `CharacterRecord.fromJson` returns `null` for unusable rows instead of throwing |

The catalogue (which series exists, which one is selected) lives in a **separate** prefs file
(`manhwalens_series`) behind `SeriesPrefs`, so a corrupt catalogue can be wiped without touching
the reader's glossary, engine choice or API key.

---

## 4. Resolution ladder

`CharacterResolver.decide` is the only place priority lives:

1. **locked** user record — never displaced by anything automatic;
2. **user-defined** spelling (their series record, then their glossary entry);
3. **built-in** curated table;
4. **previously confirmed series memory** — only `isTrusted` records;
5. **high-confidence page detection** (`PAGE_AI`, 0.8);
6. **deterministic transliteration** — usable on the page, never persisted unless confident *and* seen ≥2×;
7. **the original Latin spelling** when nothing above applies.

Two consequences worth stating plainly:

* a stored transliteration (confidence 0.3) can never impersonate memory — `isTrusted` gates it;
* a record the page scanner deliberately drops (an ambiguous English word) still reaches the
  page table when it is locked, because the resolver walks remembered names that appear in the
  page text independently of the scanner.

---

## 5. Wiring (one session, three consumers)

`TranslatorFactory` builds **one `NameSession` per translator instance** (i.e. per captured page)
and injects it:

| Consumer | Injected | Effect |
| --- | --- | --- |
| `MlKitTranslator` | `{ texts -> session.tableFor(texts).table }` | series spellings merged over the page guess, longest name first |
| `GeminiTranslator` | `{ texts -> session.tableFor(texts).sheet }` | series sheet preferred; the page-local sheet is the fallback (P0-A path preserved) |
| `CachingTranslator` | `session::rewrite` | cache hits and fresh engine replies are repaired before display |

All three read the **same memoised** table (`NameSession.tableFor` caches per text set), so the
on-device engine and the remote relay cannot disagree about a name — and the session's writes go
through one `CharacterWriter` that never overwrites an existing record.

`nameSession()` is wrapped so that a failing name database degrades to an **unassigned** session
(logged via `ErrorLog`) instead of breaking translation.

---

## 6. Corrections vs. the cache

`CachingTranslator.pinnedOverride` is applied in two places:

1. **A cache hit** (memory, disk or fuzzy) is repaired *before* `ArabicValidator.issue` judges it,
   so the guard evaluates the text the reader will actually see; the repair is written back so the
   next capture is already correct.
2. **A fresh engine reply** is repaired *before* it is cached and persisted, so a model that
   spells a locked name differently cannot reach the screen or the cache.

`NameSession.rewrite` performs two repairs, both whole-word only: a Latin remnant becomes the
canonical Arabic, and a superseded Arabic spelling (`previousArabic`, last 4) becomes the current
one. An Arabic fragment shorter than three letters is never rewritten — two-letter strings occur
inside ordinary words.

---

## 7. UI

`Settings → Character names` (`CharacterNamesScreen`):

* create / select / clear a series (this selection is what makes memory series-scoped);
* per character: the Latin original, its script/source/lock state, an editable Arabic spelling
  (**Save = save + lock**), an alternative Latin spelling, a lock switch, delete;
* every read and write runs on `Dispatchers.IO` against the same store instance the pipeline uses,
  so the next capture sees a correction without a restart.

20 new string resources (`character_names_*`, `settings_characters_button`).

---

## 8. Files

**New — naming core (pure Kotlin, no Android):**

| File | Contents |
| --- | --- |
| `names/CharacterRecord.kt` | `NameSource` (+ `priority`), the record, `TRUST_MIN`, tolerant `fromJson`, `previousArabic` cap |
| `names/NameForm.kt` | letters-only key, `appearsIn`, `replaceLatin`, `replaceArabic`, ambiguous-word list |
| `names/CharacterStore.kt` | persistence interface (9 operations) |
| `names/JsonCharacterStore.kt` | the file store (atomic, corrupt/newer handling, locked immunity) |
| `names/CharacterResolver.kt` | `ResolvedName`, `PageNameTable` (`.table`, `.sheet`), the ladder, `CharacterWriter` |
| `names/SeriesCatalog.kt` | `KeyValueStore` seam, series catalogue + selection, slug+checksum ids |

**New — Android glue / UI:** `data/SeriesPrefs.kt`, `data/CharacterMemory.kt`
(`CharacterMemory` + `NameSession`), `ui/settings/CharacterNamesScreen.kt`.

**Modified:** `translate/TranslatorFactory.kt` (session wiring), `translate/CachingTranslator.kt`
(`pinnedOverride`, hit repair + write-back), `translate/NameScript.kt` (`classify(): Evidence`,
pinyin inventory, bare-surname guard), `translate/NameMemory.kt` (`Pinned.count`),
`translate/mlkit/MlKitTranslator.kt` (table merge, longest-first), `translate/gemini/GeminiTranslator.kt`
(`sheetProvider`), `ui/settings/SettingsScreen.kt` (entry point + screen switch),
`res/values/strings.xml`.

**New — tests (9 files, 73 tests):** see §9. **Docs:** this file + `P1_CHARACTER_MEMORY.md`.

---

## 9. Tests added (73)

| File | Tests | Protects |
| --- | ---: | --- |
| `names/SeriesCatalogTest` | 12 | create/dedupe/select/rename/delete, dangling selection, stable ids |
| `names/JsonCharacterStoreTest` | 11 | restart persistence, series isolation, locked immunity, alias lookup, corrupt + newer files, delete/deleteSeries |
| `names/NameFormTest` | 11 | whole-word Latin and Arabic repair, alias awareness, ambiguous words, sub-3-letter refusal |
| `names/CharacterResolverTest` | 9 | locked wins, locked survives the scanner, alias lookup, untrusted memory never confirmed, **nothing read or written when unassigned**, writer never overwrites, Latin kept → not written |
| `names/CharacterRecordTest` | 7 | JSON round-trip, tolerant reader, trust floor, correction history (capped at 4) |
| `data/NameSessionTest` | 7 | unassigned persists nothing, table memoised, correction rewrites cached Arabic, Latin remnant, untrusted does not, unrelated text untouched |
| `translate/NameScriptClassifyTest` | 6 | bare surnames → `UNKNOWN` with confidence < 0.6, hyphenated Korean, pinyin, odd input never throws |
| `names/PageNameTableTest` | 5 | longest-first order, Latin names excluded, sheet format and 20-name limit, describe/empty |
| `translate/CachingNameOverrideTest` | 5 | hit repaired before the guard, repair written back, fresh output repaired before caching, no override → unchanged, throwing override harmless |

---

## 10. Verification (executed, not assumed)

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain
```

| Run | Commit | Result |
| --- | --- | --- |
| 43517478032 | `6a71f77` | ❌ `:app:compileDebugKotlin` — 4× "only safe or non-null asserted calls are allowed on a nullable receiver of type `CharacterStore?`" |
| 43517986742 | `1686e72` | ❌ compiles, **590 tests, 4 failed** |
| **43518604612** | **`8d5cc9e`** | ✅ **success** (3m14s) — no failing test reported |

Both failures were real, and each was fixed at its cause rather than by weakening an assertion:

1. **Nullable property captured in a lambda** (`CharacterResolver.resolve/commit`,
   `NameSession.records`): Kotlin cannot smart-cast a nullable *property* inside a lambda; the
   value is now snapshotted into a local `val` first.
2. **A repaired plain memory hit was never written back.** Disk and fuzzy hits were promoted; the
   memory tier was not, so every capture re-repaired the same stale entry. Fixed in
   `CachingTranslator`, and the test that caught it stays.
3. **Test-only defects:** `replaceArabic` is whole-word, so a name glued to a preceding letter
   (`لهان جي وو`) is *not* rewritten — the test asserted the opposite; and three tests looked names
   up by page spelling (`table["Han Ji-woo"]`) when the pipeline canonicalises it — they now match
   on the normalized key.

PR state after the green run: **OPEN / MERGEABLE / CLEAN**, 11 commits.

---

## 11. Diff

`git diff --stat 528d629..8d5cc9e` → **27 files, +3307 / −52** (8 tracked files modified, 19 added).
Two representative hunks:

```diff
-        val records = if (seriesId.isNullOrBlank() || store == null) emptyList()
-        else runCatching { store.records(seriesId) }.getOrDefault(emptyList())
+        val source = store          // smart casts do not survive into a lambda
+        val records = if (seriesId.isNullOrBlank() || source == null) emptyList()
+        else runCatching { source.records(seriesId) }.getOrDefault(emptyList())
```

```diff
-                    fromFuzzy && hit != null -> cache.put(engineId, b.english, hit, style)
+                    fromFuzzy && repairedHit != null -> cache.put(engineId, b.english, repairedHit, style)
                 }
+                // A repaired plain memory hit must be written back too.
+                if (repairedHit != rawHit) cache.put(engineId, b.english, repairedHit, style)
```

Full compare: <https://github.com/mouadknight0-cell/ManhwaLens/compare/528d629...8d5cc9e>

---

## 12. Manual review script

1. Settings → **Character names** → create `Solo Leveling` → select it.
2. Translate a page containing a name; open the screen again — the detected names are listed with
   their script and source.
3. Edit one spelling and press **Save** (it locks). Translate a page that was translated *before*
   the edit: the corrected spelling must appear (cache repair).
4. Clear the selection, translate a page: names still resolve, but nothing new appears in the list.
5. Settings → Last error should stay empty through all of the above.

---

## 13. Remaining risks / follow-ups

**Post-review corrections (2026-11-13):** [P1_REVIEW_CORRECTIONS.md](P1_REVIEW_CORRECTIONS.md)
— atomic persistence, `NameSession` cache identity (`texts.hashCode()` → content equality), and the
series-selection audit with its minimal UX fixes. Base `1c0c270`, fix `e99dd93`, CI run 43523522645.

**Persistence hardening that followed:** [P1_PERSISTENCE_SAFETY_FIX.md](P1_PERSISTENCE_SAFETY_FIX.md)
(recovery may never write the live file — `b960444` → `87830b7`) and
[P1_1_STRICT_STAGING_VALIDATION.md](P1_1_STRICT_STAGING_VALIDATION.md) (a staging copy is promoted
only after strict structural validation — `8140a4b` → `723f101`). The design described in this
document is unchanged by both; they harden the write/recovery edges of §3.

| Risk | Status |
| --- | --- |
| Names only accumulate for a **selected** series | By design (D2); the empty state tells the reader to pick one. Could be automated later via a title-detection heuristic — needs its own phase. |
| `CharacterMemory.store` is a process-wide singleton keyed to `filesDir` | No multi-profile support; out of scope for v0.11.0. |
| Arabic repair skips fragments < 3 letters, and whole-word only | Deliberate false-positive guard; a name glued to a preposition is left alone. |
| Settings screen is English-first | Arabic strings fall back to English until translated. |
| A lock applied during a live session | Takes effect on the next page, not mid-page (the session memoises per page). |
| One monitor guards the whole store | Fine at tens of records per series; not designed for thousands. |
| C5 (sheet computed twice per page) | Partially obsolete: with a series selected, Gemini consumes the series sheet, so the page-local plan usually does not run. |
| Not validated on a device | No Android SDK/JDK in the authoring sandbox — CI is the only executable verification; the manual script above is outstanding. |
