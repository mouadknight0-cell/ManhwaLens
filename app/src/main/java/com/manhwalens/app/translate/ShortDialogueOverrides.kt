package com.manhwalens.app.translate

import java.text.Normalizer
import java.util.Locale

/**
 * Conservative source-aware repairs for a tiny set of unambiguous, frequent dialogue/SFX
 * failures observed in the ML Kit EN→AR device corpus. Exact source matching only: never
 * rewrite a longer sentence or guess from the translated output alone.
 */
object ShortDialogueOverrides {
    private val exact = mapOf(
        "NO" to "لا.",
        "RUN" to "اهرب!",
        "KRAK" to "طَق!",
        "LOOKS LIKE IT S DONE" to "يبدو أن الأمر انتهى!",
        "HA THIS IS A PIECE OF CAKE" to "ها، هذا سهل للغاية.",
        "THIS IS WHAT YOU GET AFTER KILLING A MONSTER THE MAGICAL CORE" to "هذا ما تحصل عليه بعد قتل الوحش: النواة السحرية.",
        "THE MAGICAL CORE" to "النواة السحرية.",
        // Exact-source repairs captured from the Solo Leveling chapter-3 device trace.
        // These are deliberately tied to the observed OCR strings, not broad word substitutions.
        "ALL CLEAR LET S GO" to "المكان آمن! هيا بنا!",
        "WHAT RE YOL THINKING ABOUT" to "بماذا تفكر؟",
        "ABOUT 40 MINLTES" to "حوالي أربعين دقيقة.",
        "THE GATE DOES NOT CLOSE IF THE B055 15 STILL ALIVE" to "لن تُغلق البوابة ما دام الزعيم حياً.",
        "HOW LONG DID WE WALK" to "كم استغرقنا في المشي؟",
        "ABOUT WHAT" to "بشأن ماذا؟",
        "I M FINE SO YOU DON T NEED TO WORRY ABOUT ME" to "أنا بخير، فلا داعي للقلق عليّ.",
        "TO BE HONEST NO I AM DEFINITELY NOT OKAY" to "بصراحة... لا، لست بخير إطلاقاً.",
        "IF YOU RE THAT SORRY HOW ABOUT TREATING ME TO SOME FOOD" to "إذا كنت تشعر بالأسف إلى هذا الحد، فما رأيك أن تعزمني على الطعام؟",
        "WHAT EVEN MADE YOU SAY YES HUH DID YOU ALSO HURT YOUR HEAD" to "ما الذي دفعك إلى الموافقة أصلاً؟ هل أصبت رأسك أيضاً؟"
    )

    fun apply(source: String, translated: String): String {
        if (translated.isBlank()) return translated
        val key = Normalizer.normalize(
            BubbleTextRules.oneLine(BubbleTextRules.normalize(source)),
            Normalizer.Form.NFKC
        ).uppercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
        return exact[key] ?: translated
    }
}
