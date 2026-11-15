# ManhwaLens — release ProGuard/R8 rules.
# Verified on the 2026-11-11 release build (assembleRelease is green with these).
# If you bump ML Kit / Compose, re-run the release smoke test on device.

# --- ML Kit (OCR + on-device translation): JNI + reflection pipelines ---
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.android.gms.internal.vision.** { *; }
-keep class com.google.android.gms.tasks.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**
-dontwarn com.google.android.gms.internal.**

# --- Services referenced only from the manifest ---
-keep class com.manhwalens.app.overlay.FloatingButtonService { *; }
-keep class com.manhwalens.app.capture.ManhwaAutoCaptureService { *; }
-keep class com.manhwalens.app.capture.CaptureConsentActivity { *; }
-keep class com.manhwalens.app.ui.settings.SettingsActivity { *; }

# --- Compose --------------------------------------------------
# compose has consumer rules; nothing extra required for this app.
