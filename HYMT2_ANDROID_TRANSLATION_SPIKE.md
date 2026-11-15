# HY-MT2 Android Translation Spike

Status: **Research decision approved; implementation and device performance not yet verified.**
Date: 2026-11-15

## Product constraints
- Translation must run entirely offline after model/runtime assets are present.
- Primary pair: English to Modern Standard Arabic (EN→AR).
- Primary optimization target: minimum end-to-end latency on Android, not desktop throughput.
- Translation quality must remain readable and semantically faithful for manhwa dialogue.
- OCR timing and quality must be measured separately from translation.

## Candidate selected for first implementation spike
**Tencent Hy-MT2 1.8B GGUF**, using the official GGUF distribution and llama.cpp-compatible runtime.

Reasons:
- The model card lists Arabic and English among its supported languages.
- The family publishes a 1.8B model and GGUF variants intended for local inference.
- Tencent documents an extreme 1.25-bit quantized on-device variant (claimed 440 MB and 1.5x inference speed improvement). These are vendor/model-card claims, not measured ManhwaLens Android results.
- The model card lists Apache-2.0 for the base Hy-MT2 model. Verify the exact license and notices for every selected GGUF/quantization artifact before redistribution.

References:
- https://huggingface.co/tencent/Hy-MT2-1.8B
- https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF
- https://huggingface.co/tencent/Hy-MT2-1.8B-1.25Bit-GGUF
- https://github.com/ggerganov/llama.cpp

## Quantization test order
1. Official 1.25-bit GGUF: speed/footprint candidate; quality loss is a risk and must be measured.
2. Official 2-bit GGUF: quality/speed control against extreme quantization.
3. A higher-bit GGUF only if memory and latency on the target device make it practical.

Do not select a quantization from advertised file size or desktop benchmark alone.

## Android integration spike
- First prove the model can be loaded and run with a pinned llama.cpp revision on ARM64 Android.
- Keep the native runtime behind the existing translation-engine interface; do not couple UI code to llama.cpp.
- Add explicit model/runtime version metadata, checksum validation, cancellation, bounded context, bounded output tokens, and deterministic native resource cleanup.
- Bundle neither a multi-gigabyte source checkpoint nor unverified assets in the base APK. Prefer a separately downloadable model package with checksum and clear offline-ready state; network is used only for the user's initial explicit model download, never for translation requests.
- Once downloaded, translation must work with airplane mode enabled. No remote APIs, telemetry-dependent inference, or cloud fallback.
- Preserve the current runtime engine until the candidate passes tests; no automatic promotion.

## Benchmark protocol
Use the existing fixed EN→AR 20-case corpus initially, then expand to at least 100 reviewed manhwa dialogue cases before a default-engine decision.

For every model/quantization:
- Same source text, ordering, preprocessing, output cap and prompt.
- Cold model load and first translation reported separately from warm inference.
- Warm runs: at least 5; report median, P90/P95 and worst latency.
- Measure both per-bubble latency and full-page batch wall time.
- Record tokens/sec, peak native RSS/PSS where available, model file size, load time, thermal/battery state, and crashes/OOM.
- Quality review per case: meaning, omissions, hallucinations, English echo, names/terminology, tone, and natural MSA. Lexical token overlap is supplementary only, not semantic accuracy.
- Run on the actual target phone with network disabled after model installation. CI can validate compilation and deterministic JVM tests, but cannot establish device speed, RAM or semantic quality.

## Acceptance gates
- Strictly offline inference after model installation.
- No crash/OOM on a complete representative page.
- Meaningful latency improvement over current offline baseline on the target phone.
- No unacceptable regression in human-reviewed Arabic quality versus the current baseline.
- Model download, checksum, cancellation, storage and update behavior verified.
- Only after all gates pass may the engine become the default. Otherwise keep it optional or reject it.

## Current project baseline
At v0.12.8, ML Kit is the default offline translator and Bergamot is optional/experimental. Bergamot has an existing isolated 20-case benchmark. Neither engine has been device-verified for JNI inference, latency, RAM and semantic quality in this handoff. Keep these baselines intact during the HY-MT2 spike.
