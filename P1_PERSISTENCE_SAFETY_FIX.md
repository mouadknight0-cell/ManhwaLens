# P1 — Persistence safety fix (final)

**Scope:** one data-integrity defect in the character-memory store. No redesign of the
character-memory system, no change to `NameSession` caching, series selection/UI, translation,
OCR, Gemini or ML Kit. No P2 work.
**Base commit:** `b960444e70c0161194557486f0400af43f5171bb` (PR #2 head; the reviewed store code is
`e99dd93`, untouched by the two documentation commits after it)
**Files changed:** `names/JsonCharacterStore.kt`, `data/CharacterMemory.kt` (diagnostic wiring),
`names/JsonCharacterStoreTest.kt`. Docs: this file, `DECISIONS.md`, `REPORTS.md`.
**Verified by:** the `build` check of GitHub Actions on the final commit — see
[REPORTS.md](REPORTS.md) for the recorded run id (docs-only follow-up commits are covered by the
run at HEAD).

---

## 1. The defect

`recoverInterruptedWrite()` promoted a complete `<file>.tmp` with `commitAtomically`, and when that
failed it fell back to:

```kotlin
if (!commitAtomically(staging)) {
    runCatching { staging.copyTo(file, overwrite = true) }   // ← removed
}
```

`File.copyTo` opens the destination for writing and streams into it, so the live file is truncated
and rewritten in place. A kill, an OOM or a full disk in that window leaves a partial live file —
precisely the failure the atomic path exists to prevent, reintroduced on the recovery path (a
recovery usually runs at start-up, when the process is most likely to be killed again).

---

## 2. What recovery does now

| Situation at load | Behaviour |
| --- | --- |
| Live file readable | Used as-is. A stray `<file>.tmp` is **not** read, not promoted, not deleted; the live bytes are untouched. |
| Live missing/empty, complete copy present, promotion succeeds | Records are read from the copy, then it is committed with one atomic move. |
| Live missing/empty, complete copy present, promotion **refused** | Records are read **from the copy in place**; the copy stays on disk; the live path is never created; the failure is reported through the diagnostic sink. Nothing is written anywhere. |
| Live corrupt | Set aside as `.corrupt` (unchanged rule), then the copy is considered as above. |
| Live from a newer build | Set aside as `.newer` (unchanged rule), then the copy is considered as above. |
| Copy present but truncated/unparsable | Discarded — never promoted, never parsed as data. |

Three consequences worth stating explicitly:

1. **No code path writes into the live character-memory file.** It only ever changes by rename, so
   it is always a complete document — the previous one or the new one.
2. **A refused promotion loses nothing.** Because the copy is read in place, the in-memory index is
   complete; the next successful save serialises the *whole* state into the copy and promotes it.
   (Without that, a mutation on a store that failed to adopt would have overwritten the only
   surviving copy with an empty document.)
3. **The failure is visible, not thrown.** `onWarning` is a constructor seam; the Android layer
   (`CharacterMemory.store`) wires it to `PipelineTracer.span("character memory", …)`, i.e. the
   debug-panel trace that already carries the per-page "which series" line. The last-error file
   stays reserved for crashes. The store itself stays Android-free and JVM-testable.

Schema (`version: 1`), the tolerant reader, `.corrupt`/`.newer` handling and the locked-record rules
are unchanged.

### Test seam

```kotlin
class JsonCharacterStore(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    /** commit seam: move staging over the live file (default = atomic move, else rename) */
    private val atomicMove: (File, File) -> Boolean = ::atomicMoveOrRename,
    /** diagnostic sink for a deferred recovery; never thrown */
    private val onWarning: (String) -> Unit = {}
)
```

Both default to production behaviour, so every existing call site is unchanged. The seam exists
because "the filesystem refuses an atomic move" cannot be induced portably (permissions do not
block a rename, and a cross-device move cannot be built in a unit test).

---

## 3. Tests added (5, all in `JsonCharacterStoreTest`)

| Test | Property pinned |
| --- | --- |
| `a refused promotion never writes into the live file` | live path stays absent, the copy's bytes are identical, records are still readable, exactly one warning |
| `a refused promotion keeps the complete copy for a later attempt` | the copy survives; a later default-seam load restores **all** records and consumes the copy |
| `a refused promotion leaves a valid live file byte-for-byte unchanged` | `assertArrayEquals` on the live file with a stray copy present |
| `a save after a refused promotion keeps the full state in the copy` | no empty-overwrite: 2 records loaded from the copy + 1 mutation → a later load sees 3 |
| `a truncated copy is never promoted even when the live file is missing` | half-writes are discarded, nothing is created |

Kept intact (and still passing): promotion on success, failure of `startWrite`, retry after a
failed save, no staging file after success, stray copy never shadowing a readable file, corrupt and
newer-schema handling, locked immunity, series isolation, restart persistence.

---

## 4. Verification

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain
```

Executed by GitHub Actions on this exact commit (the authoring sandbox has no JDK/Android SDK, so no
local run is possible or claimed — `BLOCKERS.md`). Test methods in the module: 604 (599 + 5).

---

## 5. Diff

`git diff --stat b960444..<final>` — 3 code/test files (+205 / −53) plus documentation. Compare:
<https://github.com/mouadknight0-cell/ManhwaLens/compare/b960444...arena/8a718184-manhwalens>

---

## 6. Remaining risks

| Risk | Status |
| --- | --- |
| Directory fsync | Java offers no portable directory fsync; a power cut right after the rename can lose the rename itself, leaving the previous complete file — never a partial one. |
| A permanently unpromotable copy | Records keep loading from the copy and every save rewrites it from the full in-memory state; the warning fires on each load. A user-visible banner would need UI work (out of scope). |
| Non-atomic *save* fallback | Deliberately removed: if the atomic move is refused during a save, the save is dropped and the previous document remains. Losing one save is recoverable; a torn file is not. |
| Device validation | Not performed — no Android SDK/JDK in the authoring sandbox; CI is the only executable verification. |
