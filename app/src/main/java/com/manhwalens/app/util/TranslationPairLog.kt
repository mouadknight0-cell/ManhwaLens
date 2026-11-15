package com.manhwalens.app.util

import android.content.Context
import android.util.Base64
import com.manhwalens.app.translate.TranslationPairTrace
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bounded, app-private translation history. Captures text only while Debug mode is enabled. */
object TranslationPairLog {
    private const val FILE_NAME = "manhwalens-translation-pairs.log"
    private const val MAX_ENTRIES = 200
    private const val MAX_FIELD_CHARS = 700
    private const val MAX_FILE_BYTES = 900_000
    const val MAX_REPORT_CHARS = 160_000

    fun record(context: Context, trace: TranslationPairTrace) {
        try {
            val app = context.applicationContext
            val file = File(app.filesDir, FILE_NAME)
            val line = listOf(
                timestamp(), trace.engineId.take(60), trace.blockId.toString(),
                trace.status.take(40), trace.detail.take(120),
                trace.elapsedMs.coerceAtLeast(0).toString(),
                trace.sourceEnglish.take(MAX_FIELD_CHARS),
                trace.candidateArabic.take(MAX_FIELD_CHARS),
                trace.finalArabic.take(MAX_FIELD_CHARS)
            ).joinToString("\t") { encode(it) }
            synchronized(this) {
                file.appendText(line + "\n")
                if (file.length() > MAX_FILE_BYTES || file.readLines().size > MAX_ENTRIES) {
                    val recent = file.readLines().filter { it.isNotBlank() }.takeLast(MAX_ENTRIES)
                    file.writeText(recent.joinToString("\n", postfix = "\n"))
                }
            }
        } catch (_: Throwable) {
            // Diagnostics must never interrupt translation.
        }
    }

    fun count(context: Context): Int = try { readLines(context).size } catch (_: Throwable) { 0 }

    fun clear(context: Context) {
        runCatching { synchronized(this) { File(context.applicationContext.filesDir, FILE_NAME).delete() } }
    }

    fun readReport(context: Context): String? = try {
        val lines = readLines(context)
        if (lines.isEmpty()) null else buildString {
            appendLine("ManhwaLens translation pair diagnostics")
            appendLine("entries=${lines.size} · local only · Debug mode capture")
            appendLine("EN = original OCR source · CANDIDATE AR = raw engine/cache value before current processing · FINAL AR = accepted value returned for display (blank if rejected)")
            appendLine()
            lines.forEachIndexed { index, line ->
                val fields = line.split('\t').map(::decode)
                if (fields.size >= 9) {
                    appendLine("PAIR ${index + 1} · ${fields[0]} · ${fields[1]} · block #${fields[2]}")
                    appendLine("status=${fields[3]} · detail=${fields[4]} · elapsedMs=${fields[5]}")
                    appendLine("EN: ${display(fields[6])}")
                    if (fields[7] != fields[8]) appendLine("CANDIDATE AR: ${display(fields[7])}")
                    appendLine("FINAL AR: ${display(fields[8])}")
                    appendLine()
                }
            }
        }.take(MAX_REPORT_CHARS)
    } catch (_: Throwable) { null }

    private fun readLines(context: Context): List<String> = synchronized(this) {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        if (file.exists()) file.readLines().filter { it.isNotBlank() }.takeLast(MAX_ENTRIES) else emptyList()
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    private fun encode(value: String): String =
        Base64.encodeToString(value.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)

    private fun decode(value: String): String =
        runCatching { String(Base64.decode(value, Base64.NO_WRAP), StandardCharsets.UTF_8) }.getOrDefault("")

    private fun display(value: String): String =
        value.replace("\r", " ").replace("\n", " ↵ ").take(MAX_FIELD_CHARS)
}
