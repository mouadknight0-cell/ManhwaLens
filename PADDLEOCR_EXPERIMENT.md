# PaddleOCR experiment (ManhwaLens)

## Purpose

PaddleOCR is an optional, on-device OCR backend for controlled comparison against the existing ML Kit Latin recognizer. ML Kit remains the default and fallback; this experiment does not alter translation or Live Mode scheduling.

## Runtime models

- Detection: PaddlePaddle PP-OCRv5 mobile detection ONNX.
- Recognition: PaddlePaddle Arabic PP-OCRv5 mobile recognition ONNX. Its supported scripts include Arabic and English.
- Runtime: official PaddleOCR Android SDK implementation, ONNX Runtime Android 1.21.1, OpenCV Android 4.5.3.0.
- Inference is local. Model files are packaged in the experimental APK by CI, so no runtime model download or network permission is needed beyond the app's existing services.
- Arabic CTC visual-order output is converted to logical order in the decoder while Latin/numeric runs are retained.

## Build inputs

CI downloads the ONNX files from the PaddlePaddle Hugging Face repositories and validates their SHA-256 digests before packaging:
- PP-OCRv5 mobile detector: `a431985659dc921974177a95adcfbb90fd9e51989a5e04d70d0b75f597b6e61d`
- Arabic PP-OCRv5 mobile recognizer: `799113ebf267fbe742deb99eb36e8d42c9ddc5291ceacf92add41b4d52a59110`

The PaddleOCR Android Kotlin implementation is derived from PaddlePaddle/PaddleOCR under Apache-2.0. Model repositories declare Apache-2.0. ONNX Runtime is MIT; OpenCV Android package is Apache-2.0.

## How to switch

Settings → OCR engine:
- ML Kit: default baseline.
- PaddleOCR: experimental detector + Arabic/English recognizer.

The engine is selected at the next OCR call. First PaddleOCR initialization loads the local ONNX models and OpenCV; expect a longer first call and additional RAM use.

## Benchmark protocol

Use identical screenshots and capture bounds for both engines. Record:
1. detected line count and bubble count;
2. OCR latency after one discarded warm-up;
3. exact source-text correctness per bubble (not only mapping status);
4. false positives, missed text, merged/split bubbles;
5. peak memory / stability across repeated captures.

Do not change translation batching or rendering while comparing OCR engines. Save the ML Kit and PaddleOCR source text side by side, then compare the same translated bubbles. Keep ML Kit selected if PaddleOCR is slower or less accurate on the actual manhwa samples.

## Known limitations

- The Arabic recognizer is a general Arabic/English line recognizer, not a comic-specific model. Decorative fonts, vertical text, SFX, curved bubble text and very small captions need device-side evaluation.
- RTL line reading order and mixed Arabic/Latin strings need explicit visual review.
- This experiment is not a claim of improved OCR quality until tested on the user's pages.
