# P1.1 — Strict staging validation (data-integrity hardening)

**Scope:** one persistence-safety correction inside `names/JsonCharacterStore.kt` (+ its tests).
No redesign of the character-memory architecture; `NameSession`, series selection, resolution
priority, cache rewriting, OCR/translation, Gemini/ML Kit integration, the settings UI and the
dependency set are untouched.
**Branch:** `arena/8a718184-manhwalens` · PR [#2](https://github.com/mouadknight0-cell/ManhwaLens/pull/2) (not merged)
**Base commit:** `8140a4b6e816f45972de4151e5439555a2844062`
**Follow-up (P1.1-A):** base `feb20ef8a350d01c3826dd5f4a7f9a4836dc6007` — canonical normalized-name
integrity, see §8.
**Files changed:** `names/JsonCharacterStore.kt`, `names/JsonCharacterStoreTest.kt`; docs: this file,
`DECISIONS.md`, `REPORTS.md`, `REPOSITORY_REPORT.md`.

---

## 1. The failure mode addressed

A staging copy (`<file>.tmp`) is promoted into the live slot, so it must be **the complete document
the writer intended** — not merely valid JSON. The previous gate was `isReadableJson()`, which only
asked "does `JSONObject(raw)` parse?", and the load path then reused the **tolerant live-file
reader**, which silently skips anything it cannot use:

* a document with `version` but **no `series` object** was "usable" (it returned `true` and loaded
  nothing);
* a `series` value of the wrong JSON type was skipped;
* a record that `CharacterRecord.fromJson` rejects (blank id / series id / original name) was
  skipped;
* a record whose `seriesId` did not match its containing series key was skipped;
* duplicates that the model forbids were never noticed.

The dangerous part is the follow-up: the store then held a **reduced** record set, and the next
mutation serialised that reduced set over the staging copy and promoted it — permanently dropping
the reader's characters that had been silently ignored. Data loss with no error, no warning, and a
file that stays valid JSON the whole time.

---

## 2. Validation rules implemented

`validateStaging(raw)` is a **separate, strict** path. It never calls the tolerant reader and never
reads a single record out of a document it has not first accepted. All of it, or nothing:

| Rule | Rejection reason |
| --- | --- |
| Root parses as a JSON **object** | `not a JSON object` (classified *truncated* — see §3) |
| `version` present and numeric | `missing or non-numeric version` |
| `version` is an integer | `version is not an integer` |
| `version` supported (`1 … SCHEMA_VERSION`) | `unsupported version N` |
| `series` present | `missing series object` |
| `series` is a JSON **object** | `series is not an object` |
| every series value is a JSON **array** | `series 'x' is not an array` |
| every element is a JSON **object** | `series 'x' record #i is not an object` |
| every element is accepted by `CharacterRecord.fromJson` | `series 'x' record #i is not a valid record` |
| every record has non-blank **id, series id, original name** | `… has a blank id, series id or name` |
| record `seriesId` equals its containing series key | `series 'x' record #i belongs to 'y'` |
| record **ids unique within a series** | `duplicate record id 'id' in 'x'` |
| stored `normalizedName` equals `NameForm.normalize(originalName)` (§8) | `series 'x' record #i has a non-canonical normalized name 'y' (expected 'z')` |
| **canonical names unique within a series** — judged on the recomputed key, not the JSON value (§8) | `duplicate name 'Name' in 'x'` |

Why those two uniqueness invariants and no others: `upsert`/`find` resolve records by exactly these
two keys, so a duplicate makes resolution ambiguous and makes a later `upsert` silently merge two
different characters. Nothing beyond the current data model is asserted — no invented compatibility
rules, no schema change (`version` stays `1`, the writer is untouched).

Self-written documents always pass: the writer emits `version` as an int, `series` as an object,
arrays, `toJson()` records, and `upsert` guarantees per-series uniqueness of both keys. So the strict
gate cannot reject the store's own output.

---

## 3. Recovery behaviour, case by case

The live-file path is **unchanged** and stays tolerant: a reader who already has data keeps getting
whatever is readable from it.

| Case | Behaviour |
| --- | --- |
| Live file readable | Used as-is. Staging is not read, not validated, not moved, not deleted — the live bytes are untouched. |
| Live missing/empty/corrupt/newer **+ staging valid** | Records loaded from the validated document, then promoted with one atomic move. |
| Same, but the atomic move is refused | Records stay loaded in memory, the copy stays for the next attempt, nothing is written, one warning. (Unchanged from P1.) |
| Live unusable **+ staging is not JSON** (torn write) | Discarded — the existing cleanup policy for demonstrably truncated copies. |
| Live unusable **+ staging is JSON but structurally invalid** | **Nothing loaded, nothing promoted.** The copy is renamed to `<file>.tmp.rejected` (bytes preserved for diagnosis; a previous `.rejected` is replaced so the slot stays bounded), the live file is not created or modified, the in-memory store stays **empty**, and one warning carries the exact reason. |
| A save happens after a rejection | It serialises exactly the in-memory state (which is empty unless the reader acted) and promotes it — it never merges with a document that was refused. The `.rejected` copy survives for diagnosis. |

Two properties worth naming:

1. **No partial load.** A rejected document contributes zero records, so the in-memory state can
   never be a reduced version of it.
2. **The live file is still never opened for writing.** Recovery only ever renames *the staging
   file*; `git grep`-verified: no `writeText`, `copyTo`, `outputStream` or `FileOutputStream(file…)`
   on the live path anywhere in the store.

---

## 4. Tests added (15) and results

`grep -n "file\.writeText\|copyTo\|FileOutputStream(file" JsonCharacterStore.kt` → **no matches**.

| Test | Pins |
| --- | --- |
| `a staging document without a series object is rejected` | missing `series` → empty store, no live file, `.tmp.rejected` created, one warning |
| `a staging document without a version is rejected` | missing `version` |
| `a staging document from an unsupported newer schema is rejected` | `version: 2` |
| `a staging document whose series is not an object is rejected` | `"series": []` |
| `a staging document with a malformed record is rejected instead of reduced` | record rejected by the parser → whole document rejected, not silently reduced |
| `a staging record filed under another series is rejected` | `seriesId` ≠ containing key |
| `duplicate record ids in a staging document are rejected` | id uniqueness |
| `duplicate normalized names in a staging document are rejected` | identity uniqueness |
| `an invalid staging copy never touches a valid live file` | live bytes `assertArrayEquals`, staging bytes unchanged, live records served |
| `a rejected staging copy leaves no live file behind` | blank-id record → rejection, live path never created |
| `a save after a rejected staging copy persists the complete in-memory state` | post-rejection save writes 2 records, `.rejected` kept, staging consumed |
| `a non-canonical stored normalized name is rejected` (§8) | forged key ≠ `NameForm.normalize(originalName)` → whole document rejected |
| `forged normalized names cannot bypass duplicate identity detection` (§8) | two spellings of one character with different forged keys → rejected |
| `two spellings of one name are one identity and are rejected` (§8) | both keys canonical and both rows well-formed → only canonical duplicate detection can catch it |
| `a rejected staging copy leaves an existing live file byte-for-byte untouched` (§8) | present-but-unloadable live file: bytes unchanged, no promotion |

Kept and still passing: valid staging promotion, refused-promotion behaviour (5 tests), failed-write
handling and retry, no staging file after success, stray copy never shadowing a readable file,
corrupt/newer live handling, locked immunity, series isolation, restart persistence, and every
non-store test in the module. Test methods in the module: **619** (604 + 11 + 4).

Results: **CI run [43531030306](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43531030306)
— success** at `e6598a82f61d696335b7bfc075fa45b8dd905d43` (suite + `assembleDebug`, 3m56s). The
authoring sandbox has no JDK/Android SDK, so no local run is possible and none is claimed.

---

## 5. CI and commits

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain
```

Executed by GitHub Actions (`.github/workflows/android.yml`, triggered by the open pull request):
**run 43531030306 — success**, commit `e6598a8` (the code, tests and this report were pushed
together; the commit recording the run id is docs-only).

---

## 6. Remaining limitations

| Limitation | Note |
| --- | --- |
| Rejected copies are bounded to one | `<file>.tmp.rejected` is replaced on each rejection, so the very oldest diagnostics can be lost if several different corruptions occur. Acceptable: it is a diagnostic aid, not a history. |
| `.rejected` is never uploaded anywhere | It sits in app-private storage; support requires an explicit export (not implemented — would be UI work). |
| The live-file reader stays tolerant | By design: an existing reader must keep what is readable. A structurally invalid **live** file can therefore still load a reduced set and be rewritten on the next save. Changing that is a separate product decision (it would blank a reader's visible data), so it is deliberately out of scope here. |
| Strictness is per-document, not per-record repair | A single malformed row rejects the whole staging copy. That is the point (no partial trust), but it means one bad row costs the whole recovery attempt; the bytes are preserved so a future repair tool can use them. |
| No device validation | Not performed — no Android SDK/JDK in the authoring sandbox. |

---

## 7. Confirmation: live-file atomicity preserved

* The live file is **only** ever changed by `commitAtomically()` → `Files.move(ATOMIC_MOVE)` with a
  same-directory `renameTo` fallback. No copy, no stream, no truncate — verified by grep in §4.
* Recovery renames only the *staging* file (to `.rejected`) and never creates the live path.
* `writeDurably()` (staging only) still does `flush()` + `fd.sync()` before the rename.
* A refused promotion still leaves both files in place and reports through `onWarning`.
* The P1 guarantees — refused promotion writes nothing, a valid live file is byte-for-byte
  unchanged, a failed write keeps the previous document — are covered by tests that still pass.

---

## 8. P1.1-A — canonical normalized-name integrity

### 8.1 The gap

`validateStaging()` compared duplicate identity using the `normalizedName` **stored in the JSON**:

```kotlin
val keys = HashSet<String>(array.length())
...
if (!keys.add(record.normalizedName)) { /* duplicate */ }
```

The stored value is untrusted input. `CharacterRecord.fromJson` accepts any non-blank string for it
(it only computes the canonical value when the field is missing), so a document could carry
`{"originalName": "Han Ji-woo", "normalizedName": "a"}` and
`{"originalName": "Han Ji-woo", "normalizedName": "b"}` — two rows for one character, no duplicate
detected, both promoted. Identity is what `upsert()`/`find()` resolve on, so a wrong key is a wrong
record: a later save could merge or duplicate the reader's character.

### 8.2 The invariant added

For every staged record, in every series:

1. **`stored normalizedName == NameForm.normalize(originalName)`** — exact, case- and
   punctuation-sensitive, because the canonical form is letters-only lower case. A present value
   that differs rejects the document. (A missing/blank value needs no special case: the record
   parser already canonicalises it, so the comparison is still exact.)
2. **Duplicate identity is judged on the recomputed canonical key**, never on the JSON value.

Both are additions; no existing rule was relaxed.

### 8.3 Implementation and files

| File | Change |
| --- | --- |
| `names/JsonCharacterStore.kt` (+14/−2) | `validateStaging()` recomputes `val canonical = NameForm.normalize(record.originalName)`, rejects on mismatch, and uses `canonical` for the duplicate set; the function's KDoc lists both rules |
| `names/JsonCharacterStoreTest.kt` (+61) | the four tests listed in §4 |

`NameForm`, `CharacterRecord`, the schema (`version: 1`), the writer, the atomic promotion and the
recovery flow are unchanged. `NameSession`, series selection, resolution priority, cache rewriting,
OCR/translation, Gemini/ML Kit and the settings UI are untouched.

### 8.4 Tests added (4)

| Test | Pins |
| --- | --- |
| `a non-canonical stored normalized name is rejected` | single forged key → rejected, `.rejected` created, no live file, warning says `non-canonical` |
| `forged normalized names cannot bypass duplicate identity detection` | two spellings of one name with *different* forged keys → rejected (would have passed a JSON-value check) |
| `two spellings of one name are one identity and are rejected` | `Han Ji-woo` + `Han Jiwoo`, both keys canonical → caught by canonical duplicate detection |
| `a rejected staging copy leaves an existing live file byte-for-byte untouched` | present-but-unloadable live file: `assertArrayEquals` on its bytes, no promotion, staging consumed |

Valid staging and every existing recovery test (adoption, refused promotion, failed write, stray
copy, corrupt/newer live, locked immunity, series isolation) still pass unchanged — no duplication
was added for them.

### 8.5 Commands and results

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain
```

Executed by GitHub Actions (`.github/workflows/android.yml`, triggered by the open pull request) —
run URL and SHA for this follow-up are recorded in [REPORTS.md](REPORTS.md). The authoring sandbox
has no JDK/Android SDK, so no local run was performed and none is claimed. **615 → 619** test methods
in the module (+4).

### 8.6 Remaining limitations

| Limitation | Note |
| --- | --- |
| Canonicalisation is a fixed rule | If `NameForm.normalize` ever changes, documents written by an older build could be rejected by the new strict check. That is a schema-compatibility concern for a future migration (bump `SCHEMA_VERSION`), not something to guess at now. |
| No canonical-key check on the **live** file | Deliberate: the live reader stays tolerant (§6). A forged live file can still load a reduced set; only the staging path is strict. |
| `.rejected` keeps one copy | As before — a diagnostic aid, not a history. |
| No device validation | Not performed; no Android SDK/JDK in the authoring sandbox. |

### 8.7 Live-file atomicity — unchanged

Recovery still renames only the staging file; the live path is written only by `commitAtomically()`
(atomic move, else same-directory rename). `grep -n "file\.writeText\|copyTo\|FileOutputStream(file"
JsonCharacterStore.kt` → **no matches**. The P1 and P1.1 test suites that prove this still pass.
