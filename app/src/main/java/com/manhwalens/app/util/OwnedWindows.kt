package com.manhwalens.app.util

import com.manhwalens.app.ocr.geometry.Rect2D

/**
 * Registry of every window ManhwaLens draws on top of other apps: the lens button
 * (+ its live caption), the lens menu, the status pill, the debug trace panel.
 *
 * Before ANY capture used for OCR (Live and one-shot) these windows are hidden and a
 * REAL new frame is awaited, so our own UI can never be OCR'd or translated.
 * After OCR, blocks that intersect our rects (current or removed within [GRACE_MS])
 * or that match our own UI strings are dropped (defense in depth).
 *
 * Pure JVM — no Android types — so the registry, rect math and string matching are
 * unit-testable on the host.
 */
class OwnedWindows(private val nowMs: () -> Long) {

    /** Blocks overlapping a rect removed within this window are still treated as ours. */
    companion object {
        const val GRACE_MS = 1_000L

        /** Own UI strings (EN + AR) that must never be translated. Lowercase, trimmed. */
        val UI_STRINGS = setOf(
            // Live status / caption (EN)
            "watching", "scanning", "translating", "paused", "same page",
            "tap to start", "no frames yet", "live error",
            "live on", "live off", "live mode", "translate now",
            "arabic", "original", "clear translation", "review last capture",
            "stop manhwalens", "settings",
            // Capture UI (EN)
            "screen capture is ready", "capturing", "model download",
            "ai cleanup", "capture failed", "capture permission denied",
            // Live status / caption (AR)
            "مشاهدة", // watching
            "مسح", "ترجمة", "متوقف", "نفس الصفحة", "ابدأ", "لا توجد إطارات",
        )
    }

    /** Windows currently on screen (frame px). */
    private val current = linkedMapOf<String, Rect2D>()

    /** Windows hidden within the grace window — stale Arabic may still linger there. */
    private val removed = mutableListOf<RemovedRect>()

    /** Register (or refresh) a window we own, in CAPTURED-FRAME pixels. */
    fun add(id: String, rect: Rect2D) {
        current[id] = rect
        removed.removeAll { it.id == id }
    }

    /** A window we own has been hidden / removed — start its grace window. */
    fun remove(id: String) {
        val rect = current.remove(id) ?: return
        removed += RemovedRect(id, rect, nowMs())
    }

    /** Current on-screen rects (frame px). */
    fun currentRects(): List<Rect2D> = current.values.toList()

    /** Rects removed within the grace window — stale Arabic may still linger there. */
    fun recentlyRemovedRects(): List<Rect2D> {
        val now = nowMs()
        purgeRemoved(now)
        return removed.map { it.rect }
    }

    /** All rects that count as "our windows" right now (current + grace-removed). */
    fun allRects(): List<Rect2D> = currentRects() + recentlyRemovedRects()

    /** True when [text] is one of our own UI strings (case-insensitive, trimmed). */
    fun isOwnString(text: String): Boolean =
        UI_STRINGS.contains(text.trim().lowercase())

    fun clear() {
        current.clear()
        removed.clear()
    }

    /** Drop rects whose grace window has expired. Caller must pass [now]. */
    private fun purgeRemoved(now: Long) {
        removed.removeAll { now - it.removedAtMs > GRACE_MS }
    }

    private data class RemovedRect(
        val id: String,
        val rect: Rect2D,
        val removedAtMs: Long
    )
}
