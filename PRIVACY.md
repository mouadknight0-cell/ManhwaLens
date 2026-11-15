# ManhwaLens Privacy Policy

**Effective:** 2026-11-13 · **App version:** 0.10.0

ManhwaLens captures the visible frame only after Android's MediaProjection consent. OCR, bubble grouping, filtering, and overlay rendering happen on the device.

## On-device mode

- **PaddleOCR** performs OCR locally on-device. **ML Kit Translation** translates English→Arabic locally after its one-time model download. No frame or recognized text is sent to a network service in offline mode.
- The translation cache contains source and translated text only. Its memory and app-private disk entries can be cleared in Settings.
- When diagnostics are enabled, a recent capture may be kept in app-private cache for review; it is never sent automatically.

## Optional network engines

- **Gemini:** if you select Gemini and provide your own API key, recognized bubble text is sent to Google's Gemini API over HTTPS. Text translation does not send screenshots. The key is stored in app-private preferences and sent in the `x-goog-api-key` header.
- **AI cleanup:** if separately enabled, cropped bubble images (up to three) and their Arabic translations may be sent to Gemini for image editing. This feature is off unless the user enables it; it does not upload the full page.


The app does not log API keys or send analytics, ads, or telemetry. ManhwaLens has no user accounts and no translation server operated by the developer.

## Permissions

| Permission | Purpose |
|---|---|
| Internet | Optional Gemini translation and AI cleanup requests |
| System alert window | Floating lens and translation overlay |
| MediaProjection / foreground service | User-consented screen capture |
| Notifications | Capture-session status and Stop action |
| Accessibility service (optional) | Detect end of scrolling to trigger a capture; it does not read screen content |

## Your controls

- Choose ML Kit for on-device translation.
- Clear the Gemini key from Settings.
- Clear the translation cache or disable diagnostics from Settings.
- Revoke system permissions or uninstall at any time; uninstall removes app-private data.

---

# سياسة الخصوصية — ملخص عربي

**سارية من:** 2026-11-13 · **الإصدار:** 0.10.0

تُلتقط الشاشة بعد موافقة MediaProjection، ويُجرى OCR عبر PaddleOCR محليًا. تُترجم النصوص عبر ML Kit على الجهاز بعد تنزيل نموذج الترجمة مرة واحدة. اختيار Gemini يرسل نص الفقاعات إلى Google عبر HTTPS بمفتاحك؛ وخيار تنظيف الصور التجريبي قد يرسل صورًا مقتطعة لما يصل إلى ثلاث فقاعات. عند تفعيل فرض الترجمة دون إنترنت، تُمنع ميزات السحابة. لا تُرسل أي صور تلقائيًا لترجمة النص. قد يحتوي الكاش الخاص بالتطبيق على النص الأصلي والمترجم، ويمكن مسحه من الإعدادات. لا توجد تحليلات أو إعلانات أو بيانات قياس.
