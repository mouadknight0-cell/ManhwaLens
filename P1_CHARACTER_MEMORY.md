# P1 — Series character memory

Status: implemented on `arena/8a718184-manhwalens` (PR #2). Supersedes the scene-only name
pinning of v0.11.0, which forgot every name when the page changed.

## 1. The defect

v0.11.0 pins names **per page** (`NameMemory.plan`). A reader therefore met the same character
spelled differently in the next bubble group, the next page, and after every restart:

| page | spelling produced |
| --- | --- |
| 1 | `هان جيو` |
| 2 | `هان جي وو` |
| 3 | `هان جيوو` |

The glossary could fix one name at a time, but a chapter introduces dozens of characters, and a
glossary entry is **global**: the same key would be applied to every story the reader opens.

## 2. Scope: a series, not a package

Memory is keyed by an **explicit series id the reader creates/selects** (`SeriesCatalog`), never
by the reader package name. Two chapters of the same webtoon shipped in two packages are one
series; two different stories inside one reader package are two series. Nothing is persisted
while the session is unassigned — an empty selection resolves names exactly like v0.11.0 and
writes nothing, so a mapping can never leak into a story the reader did not choose.

## 3. Storage

`JsonCharacterStore` — one JSON file at `filesDir/character_memory.json`, `version = 1`:

```json
{ "version": 1,
  "series": { "<seriesId>": [ { "id": "…", "originalName": "Han Ji-woo",
                                "arabicName": "هان جيو", "aliases": ["Jiwoo"],
                                "confidence": 0.92, "source": "HEURISTIC",
                                "locked": false, "previousArabic": ["هان جي وو"] } ] } }
```

* **Why not Room.** A series holds tens of records (a few kB) and is read once per page. Room
  would add an annotation processor, schema export and migration plumbing — and a new Gradle
  dependency, which this phase forbids — to query a list that fits in memory. Everything Room
  would buy here is covered by the tolerant reader in `CharacterRecord.fromJson`, the
  `SCHEMA_VERSION` field, and the atomic write below.
* **Durability.** Writes go to `character_memory.json.tmp` and are renamed over the original: a
  kill mid-write can never leave half a file.
* **Corruption.** A file that does not parse is renamed to `character_memory.json.corrupt` and
  kept for support; the store then starts empty. A file written by a newer build is set aside as
  `.newer` instead of being guessed at. Neither case crashes the pipeline.
* **Threading.** One in-memory index per file, guarded by a single monitor; the live worker, the
  tap worker and the settings screen can all write.
* **Locked is final.** `upsert` returns the existing record untouched when it is locked and the
  incoming write is not — an automatic detection can never replace a reader's decision.

## 4. Resolution ladder

`CharacterResolver.decide` is the single place the priority lives:

1. **locked** user record — wins over everything, forever;
2. **user-defined** spelling (their series record, then their glossary entry);
3. **built-in** curated table;
4. **previously confirmed series memory** — only records that are `isTrusted`;
5. **high-confidence page detection** (`PAGE_AI`);
6. **deterministic transliteration** — usable on the page, never "confirmed";
7. **the original Latin spelling** when nothing above is trustworthy.

Two rules fall out of this and are pinned by tests:

* a record below `CharacterRecord.TRUST_MIN` (0.6) is never treated as memory — a machine
  transliteration stays a guess, so it cannot entrench a wrong spelling;
* a bare surname (`Wang`, `Kim`, `Lee`, `Lin`, `Han`) is **not** evidence of a script. The
  classifier returns `UNKNOWN` with low confidence, so the name stays Latin until the reader
  confirms it instead of receiving an invented Arabic form.

## 5. One table, both engines

`NameSession.tableFor(texts)` resolves the page once and returns a `PageNameTable`:

* `.table` — Latin → Arabic, longest first → ML Kit inline substitution;
* `.sheet` — `Name = عربي; …` (≤ 20) → the Gemini name sheet, i.e. the P0-A path.

Both engines are wired from `TranslatorFactory`, so they cannot disagree by construction.

## 6. Corrections beat the cache

A correction is useless if a cached translation keeps painting the old spelling. So
`CachingTranslator` takes `pinnedOverride = session::rewrite` and applies it:

* to a **cache hit** (memory, disk and fuzzy) *before* `ArabicValidator.issue` judges it, so the
  guard sees what the reader will actually see;
* to **fresh engine output** before it is cached and persisted.

`rewrite` does two repairs: a remaining Latin spelling becomes the canonical Arabic, and a
superseded Arabic spelling (`previousArabic`) becomes the current one. Repairs are whole-word
only, and an Arabic fragment shorter than three letters is never rewritten — two-letter strings
occur inside ordinary words.

## 7. Settings UI

*Settings → Character names* (`CharacterNamesScreen`):

* create / select / clear a series (the selection is what makes memory series-scoped);
* per character: edit the Arabic spelling (saves **and locks**), add an alternative Latin
  spelling, toggle the lock, delete the record.

Every read and write runs on `Dispatchers.IO` against the same store instance the pipeline uses,
so the next capture sees a correction without a restart.

## 8. Tests

`./gradlew :app:testDebugUnitTest --tests "com.manhwalens.app.names.*" --no-daemon --console=plain`

| test | what it protects |
| --- | --- |
| `NameFormTest` | whole-word Latin/Arabic repair, alias awareness, ambiguous words |
| `CharacterRecordTest` | JSON round trip, tolerant reader, trust floor, correction history |
| `PageNameTableTest` | longest-first table, sheet format and limit, Latin-only names excluded |
| `CharacterResolverTest` | locked wins, untrusted memory never confirmed, no writes when unassigned, writer never overwrites |
| `JsonCharacterStoreTest` | restart persistence, series isolation, locked immunity, corrupt/newer file handling |
| `SeriesCatalogTest` | creation, dedupe, selection, rename, delete |

## 9. Known limits

* Names are only remembered for a **selected** series — by design, but it means the readme has to
  tell the reader to pick one.
* `CharacterMemory.store` is a process-wide singleton keyed to `filesDir`; a second profile is not
  supported (out of scope for v0.11.0).
* Two-letter Arabic fragments are never repaired (false-positive guard).
* The settings screen is English-first; Arabic strings fall back to English until translated.
* Live mode uses the same session wiring, but a lock applied mid-session is picked up on the next
  page, not mid-page.
