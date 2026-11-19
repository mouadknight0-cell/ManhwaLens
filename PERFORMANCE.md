# Screen-to-Arabic performance contract

Primary metrics: T6−T0 (first complete dialogue group drawn), T7−T0 (all eligible dialogue groups drawn). Engine-only benchmark results in Settings are diagnostic, **not** evidence of screen-to-Arabic improvements.

## Implemented in this change

Manual capture emits privacy-safe `ScreenLatency` logcat events, one monotonic per-screen ID, with:

| Event | Boundary |
|---|---|
| T0 | captureFrame initiation, before capture-executor queue wait |
| T1 | clean frame acquired and converted to bitmap |
| T2 | downscaling, black-frame handling, bar cropping complete |
| T3 | OCR returns, before coordinate remapping/grouping |
| T4 | grouping and filtering complete |
| T5 | engine scheduling starts, after priority/deduplication preparation |
| T6 | first complete Arabic bubble patch drawn at full fade-in alpha |
| T7 | every eligible source bubble ID drawn at full alpha |

T6/T7 use the actual overlay Canvas callback, not translation completion, rasterization completion, UI-post time, or window attachment. They approximate display submission: **not** GPU/compositor presentation fences. Arabic quality still requires human review; a full bubble is the existing sentence reconstruction unit, not proof of grammatical correctness. T7 records the first validated usable translation for every bubble; optional remote quality upgrades can arrive later.

Counts distinguish eligible regions, accepted translated groups, and successfully drawn groups (one bubble may contain multiple grammatical sentences). Translation failure rate is missing accepted groups / eligible groups for finalized translation passes; engine retry errors and aborted passes are not silently folded into that rate. Fully failed or interrupted captures lack T7, remain in the started-screen count, and must be reported separately. No-dialogue screens do not count as instant translation successes. An unavailable window or failed rasterization cannot produce T6/T7.

Each event samples process CPU milliseconds since worker entry and Java heap usage. These include unrelated concurrent app work, exclude executor queue CPU, and are **not** CPU utilization, native/GPU memory, PSS, or true peak memory. Use Android Studio/Perfetto or `adb shell dumpsys meminfo com.manhwalens.app` alongside the benchmark for native/PSS data. Run IDs assume a single process session; do not concatenate separate app sessions with colliding monotonic IDs.

## Reproducible device baseline protocol

1. Freeze a **licensed, local screenshot corpus** and its SHA-256 manifest before comparing changes. Required cases: short/long dialogue, multiline bubbles, repeated text, dense page, empty page, black/secure capture, slow/failing network. Do not upload private screenshots. No verified image corpus or device measurements are supplied by this checkout yet.
2. Record corpus manifest, Git SHA, Android/device model, refresh rate, capture resolution, app settings, engine/model version, warm/cold model/cache state, network conditions, battery/thermal state and test ordering. Freeze those conditions between baseline/candidate. Separate manual from Live and warm from cold; never pool them.
3. For each screenshot, display the same content in the same reader at identical scale. Do a separate warm-up pass; then at least 30 measured scans per case. Include cache-hit, cache-miss and failure cohorts separately. Start log capture **before** scanning, stop only after all draws or a declared fixed timeout (e.g. 30 s).
4. Collect and summarize:

   ```sh
   adb logcat -c
   adb logcat -v monotonic ScreenLatency:I '*:S' > baseline.log
   # Perform scans, then interrupt logcat.
   python3 tools/screen_latency_report.py baseline.log > baseline.json
   python3 tools/screen_latency_report.py candidate.log --baseline baseline.json > candidate.json
   ```

   Reports include median, nearest-rank P95, worst full latency, per-stage distributions, counts, incomplete-screen visibility, and per-screen timestamps/resource samples. Preserve raw logs and metadata externally, not in Git. Report timeout fraction alongside successful-screen quantiles to avoid survivorship bias. Do not compare different sample cohorts merely because the script accepts two files.
5. Human-review Arabic meaning, punctuation, dialogue order, bubble boundaries, missing text, RTL readability and progressive stability against the same source. Report quality regressions and resource/network deltas, not only time.

## Audit and decision ledger (no speedup claimed)

Existing mechanisms retained: persistent capture session; bundled recognizer reuse/prewarm; bar crop and width cap; grouping before translation; per-page dedupe/cache; bounded ML Kit concurrency; concurrent optional remote upgrade; worker-thread rasterization; per-bubble progressive overlay.

| Change | First / full latency | Quality | Resources | Reliability / verification |
|---|---|---|---|---|
| Timestamp at full-alpha draw | Measurement correction, not an optimization | Unchanged | Small event log/callback overhead; quantify on device | Avoids optimistic pre-UI timing; per-capture closure prevents late callbacks contaminating another trace |
| Reconcile only missing progressive patches | Unknown / expected to recover partial pages | Existing accepted translations preserved | Retries missing patch builds once, no new OCR or translation request | Previously any existing patch could suppress recovery of other missing patches; Android recovery test still needed |

Next optimizations require a verified baseline. Rank **measured** stage contributions; do not select a new OCR engine, increase parallelism, change image resolution, or alter batching solely from engine microbenchmarks. For each candidate record T6/T7 median/P95, quality, CPU/memory/network costs and reliability, compared with the previous verified baseline.

## Explicit remaining gaps

- Live mode's capture initiation is in LiveLoopDriver, before liveTranslate receives a frame. It has reuse/band OCR and separate batch-swap display paths. This change does **not** instrument Live with misleading post-capture T0 values; wire capture-origin run contexts and reused/new IDs through the driver before claiming Live end-to-end coverage.
- Device dataset baseline, real display/presentation-fence timing, allocation/copy profiling, OCR quality measurements, detailed engine/format/rasterization subspans, native memory/GPU/CPU utilization and network throughput remain device work.
- Consent is outside captureFrame T0 here; report cold permission/startup separately. T1 includes bitmap conversion; some preprocessing/capture costs cannot yet be separated.
- Build/test success is not an end-to-end latency benchmark. Do not publish a performance percentage or promote a release based on synthetic tests alone.
