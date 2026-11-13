# ManhwaLens Privacy Policy

**Effective:** 2026-11-13 · **App version:** 0.10.0

ManhwaLens captures the visible frame only after Android's MediaProjection consent. OCR, bubble grouping, filtering, and overlay rendering happen on the device.

## On-device mode

- With **ML Kit** selected, OCR and English→Arabic translation run on-device. No frame or recognized text is sent to a network service for translation.
- The translation cache contains source and translated text only. Its memory and app-private disk entries can be cleared in Settings.
- When diagnostics are enabled, a recent capture may be kept in app-private cache for review; it is never sent automatically.

## Optional network engines

- **Gemini:** if you select Gemini and provide your own API key, recognized bubble text is sent to Google's Gemini API over HTTPS. Text translation does not send screenshots. The key is stored in app-private preferences and sent in the `x-goog-api-key` header.
- **AI cleanup:** if separately enabled, cropped bubble images (up to three) and their Arabic translations may be sent to Gemini for image editing. This feature is off unless the user enables it; it does not upload the full page.
- **LibreTranslate / Argos:** if selected, recognized text is sent as one batch over HTTPS to the LibreTranslate URL you configure. An optional server API key is sent in the HTTPS JSON request body. ManhwaLens does not operate or control that server; its operator's privacy and retention policies apply. Use an endpoint you trust.

The app does not log API keys or send analytics, ads, or telemetry. ManhwaLens has no user accounts and no translation server operated by the developer.

## Permissions

| Permission | Purpose |
|---|---|
| Internet | Optional Gemini and user-configured HTTPS LibreTranslate requests |
| System alert window | Floating lens and translation overlay |
| MediaProjection / foreground service | User-consented screen capture |
| Notifications | Capture-session status and Stop action |
| Accessibility service (optional) | Detect end of scrolling to trigger a capture; it does not read screen content |

## Your controls

- Choose ML Kit for on-device translation.
- Clear Gemini and LibreTranslate settings, including their keys, from Settings.
- Clear the translation cache or disable diagnostics from Settings.
- Revoke system permissions or uninstall at any time; uninstall removes app-private data.

---

# سياسة الخصوصية — ملخص عربي

**سارية من:** 2026-11-13 · **الإصدار:** 0.10.0

تُلتقط الشاشة بعد موافقة MediaProjection، ثم يُجرى OCR وتجميع الفقاعات وعرض الترجمة على الجهاز. مع ML Kit تبقى الترجمة على الجهاز. اختيار Gemini يرسل نص الفقاعات إلى Google عبر HTTPS بمفتاحك؛ وخيار تنظيف الصور التجريبي قد يرسل صورًا مقتطعة لما يصل إلى ثلاث فقاعات. اختيار LibreTranslate يرسل النص دفعة واحدة عبر HTTPS إلى عنوان الخادم الذي أدخلته، مع مفتاح اختياري في جسم JSON. لا يشغّل المطوّر أي خادم؛ تنطبق سياسة مشغّل الخادم الذي تختاره. لا تُرسل أي صور تلقائيًا لترجمة النص. قد يحتوي الكاش الخاص بالتطبيق على النص الأصلي والمترجم، ويمكن مسحه من الإعدادات. لا توجد تحليلات أو إعلانات أو بيانات قياس.
