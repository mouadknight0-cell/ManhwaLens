# Open-source project review for ManhwaLens v0.10.0

**Reviewed:** 2026-11-13. The app is an Android overlay; the current hot path uses on-device ML Kit OCR/translation, page glossary preprocessing, progressive painting, a persistent cache, and an optional Gemini upgrade. The main extension added here is a user-hosted, open-source translation backend that can run beside the local draft.

## Projects reviewed

| GitHub project | License / focus | Decision for this Android app |
|---|---|---|
| [LibreTranslate/LibreTranslate](https://github.com/LibreTranslate/LibreTranslate) | AGPL-3.0 server; self-hosted translation API. Its `/translate` endpoint accepts `q` as a string array and returns `translatedText` as a matching array. | Implemented a small HTTPS-only API client. No LibreTranslate server source is copied or bundled. The user's server handles inference; the app sends a full bubble batch in one request. |
| [argosopentech/argos-translate](https://github.com/argosopentech/argos-translate) | MIT-licensed offline Python translation library; commonly used behind LibreTranslate. | Recommended as the server-side model runtime. The app does not bundle Python or Argos models; the server operator installs an English→Arabic package. Model availability and quality depend on that installation. |
| [PaddlePaddle/PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR) | Apache-2.0 OCR toolkit with broad language support and a documented Android/ONNX deployment path. | Strong candidate for a future OCR backend, especially for stylized or low-resolution text. Not bundled in v0.10.0: Android deployment adds ONNX Runtime/OpenCV and separate detector/recognizer model assets. It needs a target-phone benchmark and model/license review before increasing APK size and memory use. |
| [kha-white/manga-ocr](https://github.com/kha-white/manga-ocr) | Apache-2.0, focused on Japanese manga text recognition. | Not selected for the current English→Arabic manhwa path. It addresses Japanese OCR rather than the current English OCR/Arabic translation bottleneck. |
| [zyddnys/manga-image-translator](https://github.com/zyddnys/manga-image-translator) | GPL-3.0 project covering OCR, translation, inpainting, and typesetting. | Reviewed, but not embedded. It is a separate, heavier image-translation pipeline, and its GPL-3.0 terms need a deliberate compatibility decision before incorporating code into this app. The overlay already renders Arabic patches without full-page image rewriting. |

## Implemented design

- Settings exposes **LibreTranslate / Argos** as an optional third engine. The default stays ML Kit.
- A configured LibreTranslate endpoint must be HTTPS. URLs with user-info, query parameters, fragments, invalid ports, or cleartext HTTP are rejected before network access. Redirects are not followed.
- The request is one JSON POST to `/translate`, with `q: [ ... ]`, `source: "en"`, `target: "ar"`, and `format: "text"`. The optional API key is in the JSON body (not URL/log/cache key).
- `Glossary.prepare` is applied before the request. The shared cache stays namespaced by engine, style, and a digest of the normalized endpoint, so switching servers does not reuse the previous server's translations.
- The server result must be a complete ordered array and pass a conservative Arabic-script/echo guard. On failure, the already-painted ML Kit draft stays visible. For Live, remote output replaces the draft in place only after validation.
- The quality guard is mechanical, not a semantic judge. It catches empty/malformed output and ordinary English echoes; it cannot determine whether an Arabic sentence is a good literary translation.

## Security and privacy notes

LibreTranslate/Argos is self-hosted, not automatically private: a remote server sees OCR text and may log requests. The user is responsible for selecting a trusted HTTPS endpoint and reviewing its retention policy. ManhwaLens sends no screenshot to LibreTranslate and does not operate the server. API keys are stored in app-private preferences, sent only to the configured HTTPS service, omitted from logs, and not included in cache namespaces.

The existing Gemini key is also user-supplied. The optional AI cleanup path is separate and may send cropped bubble images to Gemini; see `PRIVACY.md`.

## Next validation step: OCR

Before replacing ML Kit OCR, evaluate the PaddleOCR Android demo against a small, permission-safe manhwa sample set on the target phone. Report character error rate (CER), bubble detection recall, warm/cold latency (median and P95), model download/APK size, and peak memory. Do not ship bundled model weights until their individual license and redistribution terms are checked. Keep ML Kit as fallback until the OCR benchmark demonstrates a measurable gain.

## References

- LibreTranslate API implementation and schema: [server route](https://github.com/LibreTranslate/LibreTranslate/blob/main/libretranslate/app.py)
- PaddleOCR Android deployment guide: [Android deployment](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/inference_deployment/cross_platform/android_deployment.en.md)
- Manga OCR scope: [manga-ocr README](https://github.com/kha-white/manga-ocr)
- Manga image translator license/pipeline: [repository](https://github.com/zyddnys/manga-image-translator)
