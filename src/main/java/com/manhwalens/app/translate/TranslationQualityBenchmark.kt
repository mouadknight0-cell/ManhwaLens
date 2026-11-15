package com.manhwalens.app.translate

import java.text.Normalizer

/**
 * Small, deterministic EN→AR reference set used by Settings → Diagnostics.
 *
 * Token overlap is a lexical diagnostic, NOT a semantic-accuracy score. References are
 * hand-authored baselines; valid alternative Arabic phrasings can score lower. The shared
 * ArabicValidator is reported separately so lexical similarity never overrides safety.
 */
object TranslationQualityBenchmark {
    data class Case(
        val id: Int,
        val category: String,
        val source: String,
        val referenceArabic: String
    )

    data class CaseResult(
        val id: Int,
        val category: String,
        val accepted: Boolean,
        val issue: ArabicValidator.Issue?,
        val exactMatch: Boolean,
        val tokenF1: Double
    )

    data class Report(
        val total: Int,
        val completed: Int,
        val accepted: Int,
        val rejected: Int,
        val exactMatches: Int,
        val meanTokenF1: Double,
        val elapsedMs: Long,
        val cases: List<CaseResult>
    ) {
        fun toText(): String = buildString {
            appendLine("Offline translation quality check (EN→AR)")
            appendLine("Reference cases: $total · completed: $completed · accepted by Arabic guard: $accepted · rejected/missing: $rejected")
            appendLine("Exact normalized matches: $exactMatches/$total · mean Arabic token F1: ${"%.1f".format(java.util.Locale.ROOT, meanTokenF1 * 100)}%")
            appendLine("Elapsed: ${elapsedMs}ms")
            appendLine("NOTE: token F1 is lexical overlap only, NOT semantic accuracy; alternate valid wording may score lower.")
            val weak = cases.sortedBy { it.tokenF1 }.take(5)
            if (weak.isNotEmpty()) {
                appendLine("Lowest lexical-overlap cases (inspect source/reference/output in translation-pair log):")
                weak.forEach { appendLine("  #${it.id} ${it.category}: F1 ${"%.2f".format(java.util.Locale.ROOT, it.tokenF1)} · ${it.issue ?: if (it.accepted) "accepted" else "missing"}") }
            }
        }.trim()
    }

    /** 20 curated baseline bubbles covering dialogue, narration, names, short replies and SFX. */
    val CASES: List<Case> = listOf(
        Case(1, "dialogue", "ALRIGHT, I'LL HAVE A GLASS.", "حسنًا، سأشرب كأسًا."),
        Case(2, "dialogue", "Wait… you made it! I wasn't sure you'd come.", "انتظر... لقد أتيت! لم أكن متأكدًا من أنك ستأتي."),
        Case(3, "sfx", "KRAK", "طَق"),
        Case(4, "dialogue", "Where are you going? Come back here.", "إلى أين تذهب؟ عُد إلى هنا."),
        Case(5, "narration", "The gate opens at dawn. We leave before sunrise.", "تُفتح البوابة عند الفجر. سنغادر قبل شروق الشمس."),
        Case(6, "dialogue", "Stay behind me and don't say a word.", "ابقَ خلفي ولا تنطق بكلمة."),
        Case(7, "dialogue", "Don't be stupid. This is suicide.", "لا تكن أحمق. هذا انتحار."),
        Case(8, "narration", "I've been waiting for three years for this meeting.", "لقد انتظرت هذا اللقاء منذ ثلاث سنوات."),
        Case(9, "dialogue", "Again? You always do this to me.", "مجدّدًا؟ أنت تفعل هذا بي دائمًا."),
        Case(10, "names", "Wei Wuxian, hand over the Stygian Tiger Seal or Lan Wangji dies here.", "وي ووشيان، سلّم ختم النمر الشيطاني وإلا سيموت لان وانغجي هنا."),
        Case(11, "dialogue", "Elder Wei, the Jiang clan will not forgive this insult!", "أيها الشيخ وي، لن تسامح عشيرة جيانغ هذه الإهانة!"),
        Case(12, "short", "No.", "لا."),
        Case(13, "short", "What did you just say?", "ماذا قلت للتو؟"),
        Case(14, "narration", "My lord, the northern army has crossed the river.", "سيدي، لقد عبر الجيش الشمالي النهر."),
        Case(15, "short", "I don't believe you.", "أنا لا أصدقك."),
        Case(16, "dialogue", "Then prove it. Show me your sword.", "إذًا أثبت ذلك. أرني سيفك."),
        Case(17, "dialogue", "It hurts… but I can still stand.", "هذا مؤلم... لكنني ما زلت قادرًا على الوقوف."),
        Case(18, "short", "Run!", "اهرب!"),
        Case(19, "dialogue", "Who gave you permission to enter this room?", "من سمح لك بدخول هذه الغرفة؟"),
        Case(20, "dialogue", "From now on, you are my student.", "من الآن فصاعدًا، أنت تلميذي.")
    )

    fun run(
        translateOne: (String) -> String,
        cases: List<Case> = CASES,
        clock: () -> Long = { System.nanoTime() / 1_000_000L }
    ): Report {
        val start = clock()
        val results = cases.map { case ->
            val output = runCatching { translateOne(Glossary.prepare(case.source)) }.getOrDefault("")
            val issue = ArabicValidator.issue(case.source, output)
            val accepted = issue == null
            val reference = tokens(case.referenceArabic)
            val actual = tokens(output)
            val overlap = reference.groupingBy { it }.eachCount().toMutableMap()
            var common = 0
            actual.forEach { token ->
                val count = overlap[token] ?: 0
                if (count > 0) {
                    common++
                    overlap[token] = count - 1
                }
            }
            val precision = if (actual.isEmpty()) 0.0 else common.toDouble() / actual.size
            val recall = if (reference.isEmpty()) 0.0 else common.toDouble() / reference.size
            val f1 = if (precision + recall == 0.0) 0.0 else 2.0 * precision * recall / (precision + recall)
            CaseResult(
                id = case.id,
                category = case.category,
                accepted = accepted,
                issue = issue,
                exactMatch = normalize(output) == normalize(case.referenceArabic),
                tokenF1 = f1
            )
        }
        return Report(
            total = cases.size,
            completed = results.size,
            accepted = results.count { it.accepted },
            rejected = results.count { !it.accepted },
            exactMatches = results.count { it.exactMatch },
            meanTokenF1 = if (results.isEmpty()) 0.0 else results.map { it.tokenF1 }.average(),
            elapsedMs = (clock() - start).coerceAtLeast(0),
            cases = results
        )
    }

    private fun tokens(value: String): List<String> =
        normalize(value).split(Regex("\\s+")).filter { it.isNotBlank() }

    private fun normalize(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace(Regex("[\\u064B-\\u065F\\u0670\\u0640]"), "")
            .replace(Regex("[أإآٱ]"), "ا")
            .replace('ى', 'ي')
        return normalized.lowercase(java.util.Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }
}
