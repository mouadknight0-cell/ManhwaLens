package com.manhwalens.app.util

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One named sub-step inside a tap run (includes waiting time, unlike stages). */
data class SpanTrace(
    val name: String,
    val durationMs: Long,
    val detail: String
) {
    fun line(): String = "     ├ " + name.padEnd(28) + " ${durationMs.toString().padStart(6)} ms  " + detail.take(180)
}

/** One pipeline stage outcome for a single tap. */
data class StageTrace(
    val stage: String,
    val status: Status,
    val durationMs: Long,
    val detail: String
) {
    enum class Status { OK, FAILED, SKIPPED }

    fun line(): String {
        val icon = when (status) {
            Status.OK -> "OK "
            Status.FAILED -> "FAIL"
            Status.SKIPPED -> "SKIP"
        }
        return "$icon  $stage — ${durationMs} ms — ${detail.take(400)}"
    }
}

/**
 * Single trace recorder for the tap → overlay pipeline. Stages are recorded
 * from the floating-button tap handler, the projection/capture code, OCR,
 * the filter, the translator and the overlay window, so a screenshot of the
 * debug panel shows exactly where a run stopped.
 *
 * Never contains the Gemini API key — every detail is scrubbed in [record].
 */
object PipelineTracer {

    enum class Stage(val wire: String) {
        TAP("TAP"),
        CONSENT("CONSENT"),
        CAPTURE("CAPTURE"),
        OCR("OCR"),
        FILTER("FILTER"),
        TRANSLATE("TRANSLATE"),
        OVERLAY("OVERLAY")
    }

    private val lock = Any()
    private var stages = ArrayList<StageTrace>()
    private var spans = ArrayList<SpanTrace>()
    private var tapAtMs = 0L

    /** Injectable clock (unit tests). Milliseconds. */
    var clock: () -> Long = { System.currentTimeMillis() }

    fun reset() {
        synchronized(lock) {
            stages = ArrayList()
            spans = ArrayList()
            tapAtMs = clock()
        }
    }

    fun record(stage: Stage, status: StageTrace.Status, durationMs: Long, detail: String) {
        synchronized(lock) {
            stages.add(StageTrace(stage.wire, status, durationMs.coerceAtLeast(0), sanitize(detail).take(600)))
        }
    }

    private const val MAX_SPANS = 40

    /** Record a named sub-step (e.g. gemini attempt, queue wait, overlay attach). */
    fun span(name: String, durationMs: Long, detail: String = "") {
        synchronized(lock) {
            spans.add(SpanTrace(name.take(40), durationMs.coerceAtLeast(0), sanitize(detail).take(300)))
            // Live mode emits spans several times per second — never grow without bound.
            if (spans.size > MAX_SPANS) spans = ArrayList(spans.takeLast(MAX_SPANS))
        }
    }

    fun snapshot(): List<StageTrace> = synchronized(lock) { stages.toList() }

    fun spanSnapshot(): List<SpanTrace> = synchronized(lock) { spans.toList() }

    fun totalMs(): Long = if (tapAtMs > 0) clock() - tapAtMs else -1L

    /** Multi-line, key-free text report (safe to paste into a bug report). */
    fun report(): String {
        val sb = StringBuilder()
        sb.append("ManhwaLens pipeline trace — ")
            .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            .append('\n')
        sb.append("tap→trace end: ${totalMs()} ms\n")
        for (s in snapshot()) sb.append(s.line()).append('\n')
        val sp = spanSnapshot()
        if (sp.isNotEmpty()) {
            sb.append("— waiting / sub-steps —\n")
            for (x in sp) sb.append(x.line()).append('\n')
        }
        return sb.toString().trim()
    }

    /** Persist the trace into the same file Settings → Last error reads. */
    fun persistToErrorLog(context: Context) {
        ErrorLog.writeRaw(context, "pipeline", report())
    }

    /** Scrub API keys and anything key-ish before it reaches logs or UI. */
    private fun sanitize(text: String): String =
        text.replace(Regex("AIza[0-9A-Za-z_-]{10,}"), "[redacted]")
            .replace(Regex("(?i)(api[-_ ]?key|key)\\s*[=:]\\s*\\S+"), "api_key=[redacted]")
}
