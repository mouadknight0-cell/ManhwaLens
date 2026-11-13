# P1 — Targeted review corrections

**Scope:** corrective review of the P1 series character memory. No redesign, no new persistence
framework, no P2 work, no change to translation quality or OCR.
**Base commit:** `1c0c2703f97aa5bd33689fb13af9e1b840e0e262` (PR [#2](https://github.com/mouadknight0-cell/ManhwaLens/pull/2) head)
**Fix commit:** `e99dd93` — **CI run [43523522645](https://github.com/mouadknight0-cell/ManhwaLens/actions/runs/43523522645): success**
**Diff of this review:** <https://github.com/mouadknight0-cell/ManhwaLens/compare/1c0c270...e99dd93> (7 files, +269 / −16)

---

## 1. Atomic JSON persistence (`names/JsonCharacterStore.kt`)

**Verified defect.** `saveLocked` wrote `<file>.tmp` and renamed it, but when `renameTo` returned
`false` it fell back to `file.writeText(...)` — an in-place truncate-and-write of the live file.
A kill, a full disk or a power cut inside that window left empty or half-written JSON, which the
next start would set aside as `.corrupt`: the reader's whole character memory, lost. The tmp write
was also never flushed to disk, so even the "atomic" path could lose its bytes on a power cut.

**Fix (AtomicFile contract, implemented locally).**

| Step | Implementation |
| --- | --- |
| `startWrite` | `writeDurably()`: `FileOutputStream` on `<file>.tmp`, write UTF-8, `flush()`, `fd.sync()`, close. Returns `false` on any failure. |
| `finishWrite` | `commitAtomically()`: `Files.move(tmp → file, ATOMIC_MOVE, REPLACE_EXISTING)`; fallback `tmp.renameTo(file)` (same-directory rename is atomic on every Android filesystem we support). |
| `failWrite` | `discard()`: delete the staged copy; the live file was never opened for writing. |

Guarantees, in order of importance:

1. **The live file is never opened for writing.** It only ever changes by rename, so it is always
   a complete previous version or a complete new version.
2. **A failed write preserves existing data** — `writeDurably` returns `false`, the staged copy is
   deleted, and nothing else is touched. The in-memory index stays authoritative, so the next
   successful save persists the full state (no partial merge, no lost records).
3. **An incomplete write cannot replace valid data.** At load, a leftover `<file>.tmp` is adopted
   only when it parses as JSON **and** the live file is missing/empty/unparsable; a truncated copy
   is discarded, and a stray copy never shadows a readable file.
4. **Same schema, same reader.** `version: 1`, `CharacterRecord.fromJson`, `.corrupt`/`.newer`
   set-aside semantics are untouched.

**Why not `android.util.AtomicFile` itself.** It lives in `android.jar`, whose methods are stubs in
JVM unit tests (`unitTests.isReturnDefaultValues = true`); `startWrite()` would return `null`, so
the persistence path — the one thing that must never corrupt a reader's data — would become
untestable, and Robolectric is not available offline (`BLOCKERS.md`). The identical contract is
implemented with `FileOutputStream` + `fd.sync()` + `Files.move(ATOMIC_MOVE)`, which the JVM tests
exercise for real, on a real filesystem.

**Regression tests added (6, `JsonCharacterStoreTest`):**

| Test | Proves |
| --- | --- |
| `a successful save leaves no staging file behind` | finishWrite consumed the copy |
| `a failed write leaves the stored records readable` | startWrite failure → previous records intact and parseable |
| `a successful save after a failed one persists the full state` | recovery: the next save writes everything from the in-memory index |
| `a complete copy left by an interrupted write is adopted` | crash between startWrite and finishWrite recovers |
| `a truncated copy left by an interrupted write is never adopted` | a half-write is never promoted to the live file |
| `a leftover copy never shadows a readable file` | a valid file always wins over a stray copy |

---

## 2. `NameSession` cache key (`data/CharacterMemory.kt`)

**Verified defect.** `tableFor` memoised on `texts.hashCode()`:

```kotlin
private var cachedKey: Int = 0
fun tableFor(texts: List<String>): PageNameTable {
    val key = texts.hashCode()
    cachedTable?.let { if (cachedKey == key) return it }   // ← hash equality, not content
```

Two different pages with colliding hashes would reuse the first page's table — one page's names
applied to another page's bubbles. `List.hashCode()` is easy to collide (`listOf("Aa")` and
`listOf("BB")` are both `2143`).

**Fix.** The memo keeps the actual immutable list and compares by content equality:

```kotlin
private var cachedTexts: List<String> = emptyList()
...
cachedTable?.let { if (cachedTexts == texts) return it }
...
cachedTexts = texts.toList()   // defensive copy: a caller mutating its list cannot alias the key
```

Behaviour and performance are unchanged: still one entry per page, still zero extra allocation for
the common case (a page is translated once and then reused by the second engine), and no
invalidation is weakened.

**Regression test:** `two page texts sharing a hash never share a name table` — asserts the premise
(`listOf("Aa").hashCode() == listOf("BB").hashCode()`), that the second call returns a different
table, and that page A's locked name does not appear in page B's table. Plus
`an equal text list with a different instance reuses the table` for the positive case.

---

## 3. Series-selection audit

Reviewed: `SeriesCatalog`, `SeriesPrefs`, `CharacterMemory.session`, `NameSession`,
`CharacterWriter.commit`, `TranslatorFactory`, `CharacterNamesScreen`, `SettingsScreen`.

| Question | Finding |
| --- | --- |
| Can a series be inferred from the reader's app or package name? | **No.** Nothing in `names/` or `CharacterMemory` reads a package name; the id comes only from `SeriesCatalog` (`active_series_v1`, default `""`). |
| Is there a default series? | **No.** A fresh install has an empty catalogue and an unassigned session (`SeriesCatalogTest.a fresh install…`). |
| Does creating a series select it? | **No** — `create()` only appends to the catalogue; selection is a separate explicit call (new test `creating a series never selects it`). |
| Can an ambiguous/unselected session store records? | **No.** `NameSession.isAssigned` is false → `CharacterWriter.commit` returns 0, and `CharacterResolver` never reads the store (`nothing is read or written when no series is selected`). |
| Can records land under a **previously** selected series? | **Yes, by design** — the selection persists, so a page is always filed under the series that was active when it was captured. This is the one real hazard, and it is a manual-selection limitation, not a bug: see below. |
| Is the active series visible while managing names? | Partially: it was shown inside the editor, but not on the Settings card or during translation. **Fixed** (see below). |

**Minimal UX fixes made (no new behaviour):**

1. The Settings → *Character names* card now shows **`Series: <title>`**, or a red
   *"No series selected — names are not saved. Pick one to enable memory."*; it refreshes when the
   reader returns from the editor.
2. Each page prints one trace line (`character memory`) with the series and its record count — or
   `unassigned (names are session-only)` — so the debug panel answers "where is this name being
   filed?" without opening Settings.

**Documented intentional behaviour.** Selection is manual and sticky by design: no inference from
app/package names, no title detection, no automatic assignment, and nothing is written while
unassigned. The limitation that follows is that a reader who forgets to switch series files the new
story's names under the previous one; the card + trace line make the active scope visible, but they
do not prevent it. Automatic series identification is explicitly out of scope for this phase.

---

## 4. Verification

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain
```

Run on GitHub Actions (the authoring sandbox has no JDK/Android SDK, see `BLOCKERS.md`), against the
exact fix commit `e99dd93abf169d5b8b911a6b4ae810ff3fd6f59a`: **success**.

| | Before review | After review |
| --- | ---: | ---: |
| Test methods in the module (`@Test` count) | 590 | 599 (+9) |
| `assembleDebug` | green | green |

No command was executed locally; no local result is claimed.

---

## 5. Remaining risks

| Risk | Status |
| --- | --- |
| Atomicity across a power cut | `fd.sync()` before the rename covers the data; the rename is atomic. No fsync of the *directory* (Java offers no portable way), so a power cut immediately after the rename can, on some filesystems, lose the rename — the previous complete file then remains, never a corrupt one. |
| A permanently unwritable store (disk full, revoked storage) | Saves fail silently by design; the in-memory index keeps working and the next successful save persists everything. No user-facing warning yet (would need a UI source; out of scope here). |
| Sticky manual selection | The reader can still file a story under the previously selected series; visible now on the card and in the trace, not prevented. |
| `character_memory.json.tmp` left on disk after a failed commit | Harmless: it is replaced by the next save, and only adopted when the live file is unusable. |
| Not validated on a device | No Android SDK/JDK in the authoring sandbox; CI is the only executable verification. |
