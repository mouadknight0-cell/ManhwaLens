# ManhwaLens Performance — End-to-End Translation Latency

**The #1 product metric is End-to-End Translation Latency: the time from screen
scanning initiation to the final Arabic sentence on screen.** Component speed
(OCR alone, engine alone) is never optimized at the expense of the whole
pipeline. Every optimization in v0.13.0 was chosen by one rule:

1. Does it reduce the time from scan initiation to the first complete translated sentence?
2. Does it reduce the time to translate the entire screen?
3. Does it preserve or improve Arabic translation quality?
4. Does it increase CPU, GPU, memory, or network consumption?
5. Does it introduce reliability or consistency problems?

## T0–T7 instrumentation (v0.13.0)

One scan = one "run". `EndToEndLatency` (pure Kotlin, JVM-tested) records:

| Mark | Event | Where it is marked |
|---|---|---|
| **T0** | Screen scanning initiated | `FloatingButtonService.captureFrame()` |
| **T1** | Screen capture completed (frame in a Bitmap) | `doCapture()` after frame acquire + cap |
| **T2** | Image preprocessing completed (crop for OCR) | `CaptureOcrProcessor` after `cropForOcr` |
| **T3** | OCR completed (text back from the recognizer) | `CaptureOcrProcessor` after `recognize` |
| **T4** | Grouping + filtering (sentence-reconstruction input) complete | `CaptureOcrProcessor` after filter |
| **T5** | Translation execution started | `doCapture()` before the first `translatePage` |
| **T6** | First complete Arabic sentence displayed | `ProgressiveBubblePainter` on first attach |
| **T7** | All detected dialogue translated + display queued | `doCapture()` after reconcile |

Derived metrics (kept in a bounded ring of the last 100 runs):

- **First Complete Sentence Latency = T6 − T0**
- **Full Translation Latency = T7 − T0**
- Median + P95 + worst-case of both, per-stage medians (T1−T0 … T7−T6)
- Dialogue volume: regions detected, sentences translated, translation failure rate

The key-free block is appended to the pipeline report
(`PipelineTracer.report()` → Settings → Last error / bug reports). It contains
**no page text** — only timings and counts.

## Fixed benchmark dataset

- **20-bubble sample page**: `BenchmarkRunner.SAMPLE` (mixed lengths, SFX,
  questions, numbers — the same page the in-app benchmark uses).
- **40 OCR lines** derived deterministically from it (2 lines/bubble).
- `OrchestrationBenchmarkTest` (pure JVM, runs in CI) exercises the real
  orchestration — `BubbleGrouper` → `PagePlan` → `CachingTranslator` — with a
  scripted-latency engine, and prints `ORCH-BENCH …` lines to the test report:
  grouping, page planning, cold vs warm translate, dedupe fanout, and
  sequential vs parallel dispatch. Correctness invariants are asserted, so a
  faster-but-wrong pipeline fails the build.

## Honest limits — read this before quoting numbers

- **No device-measured latency numbers exist yet.** The sandbox has no Android
  device, no SDK, and no JDK; CI runs JVM unit tests only. Any T0→T7 figure
  quoted from CI is an **orchestration estimate** (grouping/planning/cache
  dispatch), never a screen-to-Arabic measurement.
- Real T0→T7 numbers require a physical device running the debug APK with the
  pipeline report captured (Settings → Last error). Issue #8 tracks that.
- The v0.13.0 optimizations are **provably output-identical** (binary-search
  auto-fit, single filter pass, key reuse, async disk write, working-resolution
  mirror) — the claim is "same pixels/text, less work", verified by unit tests,
  not by device timing. Device before/after numbers must be collected before
  claiming a specific speedup in milliseconds.

## v0.13.0 optimization log

| Change | Stage | Why it helps E2E |
|---|---|---|
| Working-resolution mirror (≤720 px) + branch-light RGBA swizzle | Capture (T0→T1) | No full-res copy, alloc, or downscale per capture |
| Single filter pass (was duplicated) | Filter (T3→T4) | Removes a pure-duplicate CPU pass on the critical path |
| `Glossary.prepare` memo + cache-key reuse | Translation prep (T4→T5) | One regex pass per block instead of up to 3 |
| Disk cache write off the pipeline thread | Translation (T5→T7) | `translatePage` no longer serializes the JSON cache |
| Auto-fit binary search (~6 vs ~58 layouts) | Display (T6/T7) | Same font, ~10× fewer StaticLayouts per patch |
| Painter single-flight per area | Display (T6/T7) | No discarded raster builds for sibling bubbles |
| O(n²) → O(n) id lookup in cache-hit path | Translation (T5→T7) | One map instead of a scan per cached block |

## Progressive display contract

Independent sentence groups are translated and painted as they complete;
reading order and already-displayed patches are never disturbed; a block is
only painted when its Arabic is complete and passes the quality guard —
**incomplete, corrupted, or fragmented output is never shown as final**.
