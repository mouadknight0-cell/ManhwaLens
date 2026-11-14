# P1.1 — Strict staging validation (data-integrity hardening)

**Scope:** one persistence-safety correction inside `names/JsonCharacterStore.kt` (+ its tests).
No redesign of the character-memory architecture; `NameSession`, series selection, resolution
priority, cache rewriting, OCR/translation, Gemini/ML Kit integration, the settings UI and the
dependency set are untouched.
**Branch:** `arena/8a718184-manhwalens` · PR [#2](https://github.com/mouadknight0-cell/ManhwaLens/pull/2) (not merged)
**Base commit:** `8140a4b6e816f45972de4151e5439555a2844062`
**Files changed:** `names/JsonCharacterStore.kt`, `names/JsonCharacterStoreTest.kt`; docs: this file,
`DECISIONS.md`, `REPORTS.md`.

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
| **normalized names unique within a series** | `duplicate name 'Name' in 'x'` |

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

## 4. Tests added (11) and results

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

Kept and still passing: valid staging promotion, refused-promotion behaviour (5 tests), failed-write
handling and retry, no staging file after success, stray copy never shadowing a readable file,
corrupt/newer live handling, locked immunity, series isolation, restart persistence, and every
non-store test in the module. Test methods in the module: **615** (604 + 11).

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
