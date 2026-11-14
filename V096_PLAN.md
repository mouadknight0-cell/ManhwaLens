# v0.9.6 implementation plan (focused, per-part, commit after each part)

> **Label note (added by the 2026-11-13 documentation audit):** the `P1`…`P8` numbers below are
> **task labels of this v0.9.6 plan**, not the engineering phases `P0`–`P6` defined in
> [`REPOSITORY_REPORT.md`](REPOSITORY_REPORT.md) §3 (P0 audit/foundations, P1 series name memory,
> P2 translation quality, P3 performance/caching, P4 OCR, P5 text cleanup/rendering, P6 release).
> The plan was never started and is kept for provenance only; it describes work that v0.11.0 later
> covered differently.

Goal: stop translating ourselves, stop ghost text, stop UI chrome, make one-shot as clean as Live.

Device evidence to fix:
- Own windows (lens caption "Watching/Scanning/مشاهدة", hint, debug panel, stale patches) OCR'd/translated; Arabic burned into frame read as Latin garbage ("NHRAIP GAI PNG").
- One-shot captured with patches/lens still on screen → incomplete translation.
- Patches drawn over ManhwaLens Settings + system clock → Live not effectively paused on our own screens.
- English visible behind Arabic (patch not fully opaque / not covering whole OCR box).
- UI chrome translated ("Episode 1" header, stray block near "650").
- ML Kit literal/garbled Arabic.

Parts:
- P1 OwnedWindows registry + hide-before-capture + block filtering (own rects + own strings + garbage classifier).
- P2 One-shot path parity with Live clean-capture (hide overlays, wait real frame, verify stash not burned) + retry on cut output.
- P3 Live pauses on own screens: clear patches immediately (reason "own app visible"), skip translation when foreground == com.manhwalens.app, self-heal + tests.
- P4 Opaque full-coverage patches: cover OCR box + 4-6px, alpha 255 center, ring-median fill; geometry + coverage tests.
- P5 Don't translate UI chrome: status/nav/browser bars via insets + ignore band; bubble-like min height + word count; tests w/ exact strings ("Episode1", "650", etc.).
- P6 Gemini default engine when key exists (ML Kit fallback/offline); refine system instruction to MSA Arabic comic dialogue.
- P7 Diagnostics: blocks dropped by reason + capture-hide wait time in self-check report.
- P8 Tests green, versionName 0.9.6 / versionCode 20, DECISIONS.md + MANUAL_TESTS.md 4 steps.

Status: NOT STARTED. Tracking in this file only; real record = DECISIONS.md + git commits.
