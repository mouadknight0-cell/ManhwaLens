# Changelog

## 0.10.0 — 2026-11-13

- Restored the Android project source from the Dropbox v0.9.14 archive into the GitHub working tree (without the archive's embedded `.git`, APK, or signing material).
- Added an opt-in LibreTranslate/Argos engine for user-hosted HTTPS servers. A page is sent as one batch; the on-device ML Kit draft continues to paint progressively and remains the fallback.
- Added an Arabic output guard for cloud/self-hosted replies to reject empty, malformed, and ordinary English-echo translations before they are cached or replace a local draft; older remote memory/disk cache entries are rechecked and purged if invalid.
- Added endpoint validation, API-key redaction, endpoint-specific cache namespaces, and a bounded response size.
- Declared Android's `INTERNET` permission, which the existing Gemini HTTP engine also requires.
- Updated English/Arabic settings labels, privacy disclosures, build version, and the manual test checklist.
- Added `OPEN_SOURCE_RESEARCH.md` to record GitHub project/licensing decisions and the next OCR benchmark step.

### Verification status

Unit tests were added for request batching, response parsing, endpoint validation, output validation, and fallbacks. This sandbox has no Java/JDK or Android SDK installed, so Gradle tests and APK builds could not be run here. Physical-device timing and real-server tests remain outstanding.
