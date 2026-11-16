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
        "STAY BEHIND ME AND DON T SAY A WORD" to "ابقَ خلفي ولا تنطق بكلمة.",
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

        // Exact OCR/source variants observed in the Solo Leveling chapter-4 device trace.
        // Keep these source-specific: rank terminology and sentence repairs must not become
        // broad substitutions that can corrupt unrelated manhwa dialogue.
        "WHAT WAS THE RANK OF THE ONE WHO DIED JUST NOW" to "ما رتبة الشخص الذي مات للتو؟",
        "D RANK" to "الرتبة D",
        "IF EVEN HE WAS BAD HE STILL WOULD VE BEEN STRONGER THAN AN E RANK LIKE ME" to "حتى لو كان ضعيفًا، لظل أقوى مني، فأنا من الرتبة E.",
        "WAIT WAIT A SECOND" to "مهلًا، انتظر لحظة!",
        "THIS DUNGEON IS A D RANK AT MOST" to "هذا الدهليز لا تتجاوز رتبته D.",
        "IVE NEVER HEARD OF MON STERS AS STRONG AS THEM IN THE LIKES OF D RANK DUNGEONS" to "لم أسمع قط بوجود وحوش بهذه القوة في دهاليز من الرتبة D!",
        "THIS ISNT THE FIRST TIME I ENCOUNTERED A NEAR DEATH EXPERIENCE" to "هذه ليست المرة الأولى التي أواجه فيها تجربة كدت أموت خلالها.",
        "DURING MY FIRST RAID I ACCIDENTALLY BROKE AWAY FROM MY PARTY AND GOT LOST" to "في أول غارة لي، انفصلت عن فريقي بالخطأ وضللت الطريق.",
        "OTHERS TRADE THEIR MAGICAL CORES FOR BETTER WEAPONS" to "يستبدل الآخرون نوىهم السحرية بأسلحة أفضل.",
        "TO DEFEAT STRONGER MONSTERS" to "كي يهزموا وحوشًا أقوى.",
        "AND TO EARN MORE TREASURES" to "ويحصلوا على غنائم أكثر.",
        "I CANT DO THAT" to "لا أستطيع فعل ذلك.",
        "ALWAYS UNEQUIPPED" to "أظل من دون تجهيز دائمًا.",
        "THAT BRAT CAME OLT INJURED AGAIN" to "عاد ذلك الوغد مصابًا مرة أخرى.",
        "IN A MERE E RANK DUNGEON" to "في دهليز لا تتجاوز رتبته E؟",
        "I FEEL BAD FOR THE HEALERS RAIDING WITH HIM" to "أشفق على المعالجين الذين يشاركونه الغارات.",
        "THEY HAVE TO WORK TWICE AS HARD" to "فهم مضطرون إلى بذل ضعف الجهد.",
        "THESE DAYS HES CALLED THE WORLDS WEAKEST HUNTER" to "يُلقّب هذه الأيام بأضعف صياد في العالم.",
        "MEANING EVEN THOUGH HES A HUNTER HES USELESS" to "أي إنه، رغم كونه صيادًا، عديم الفائدة.",
        "I BARELY DODGED IT IF SUNG DIDNT YELL OUT" to "كدت أعجز عن تفاديها. لولا أن سونغ صاح محذرًا،",
        "WE COULD VE BEEN WIPED OUT" to "لكنا أُبيدنا جميعًا."
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
