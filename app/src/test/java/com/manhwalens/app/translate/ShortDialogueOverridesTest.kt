package com.manhwalens.app.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortDialogueOverridesTest {
    @Test fun noIsNotRenderedAsNumber() {
        assertEquals("لا.", ShortDialogueOverrides.apply("No.", "رقم"))
    }
    @Test fun imperativeRunIsTranslatedAsDialogue() {
        assertEquals("اهرب!", ShortDialogueOverrides.apply("Run!", "تشغيل!"))
    }
    @Test fun commonSoundEffectGetsArabicOnomatopoeia() {
        assertEquals("طَق!", ShortDialogueOverrides.apply("KRAK", "krak"))
    }
    @Test fun donePhraseIsNotTranslatedAsVerbToSeize() {
        assertEquals("يبدو أن الأمر انتهى!", ShortDialogueOverrides.apply("Looks like it's done!", "يبدو أنه انتهز!"))
    }
    @Test fun pieceOfCakeIdiomIsTranslatedByMeaning() {
        assertEquals("ها، هذا سهل للغاية.", ShortDialogueOverrides.apply("Ha, this is a piece of cake.", "ها، هذه قطعة من الكعكة."))
    }
    @Test fun magicalCoreUsesFantasyTerminologyBeforeGlossaryCanRewriteTheSource() {
        // "core" is a glossary term (الجوهرة), but this exact phrase needs its own
        // fantasy-specific rendering and must be matched against the untouched OCR source.
        assertEquals("النواة السحرية.", ShortDialogueOverrides.apply("The magical core", "The magical الجوهرة"))
    }
    @Test fun chapterThreeObservedOcrVariantsReceiveExactMeaningRepairs() {
        assertEquals("المكان آمن! هيا بنا!", ShortDialogueOverrides.apply("ALL CLEAR! LET'S GO!", "كلها واضحة! دعنا نذهب!"))
        assertEquals("بماذا تفكر؟", ShortDialogueOverrides.apply("WHAT'RE YOL THINKING ABOUT?", "ما هو التفكير YOL؟"))
        assertEquals("حوالي أربعين دقيقة.", ShortDialogueOverrides.apply("ABOUT 40 MINLTES.", "حوالي 40 دقيقة."))
        assertEquals(
            "لن تُغلق البوابة ما دام الزعيم حياً.",
            ShortDialogueOverrides.apply(
                "THE GATE DOES NOT CLOSE IF THE B055 15 STILL ALIVE.",
                "البوابة لا تغلق إذا كان B055 15 لا يزال حيا."
            )
        )
        assertEquals("كم استغرقنا في المشي؟", ShortDialogueOverrides.apply("HOW LONG DID WE WALK?", "كم من الوقت نسير؟"))
        assertEquals("بشأن ماذا؟", ShortDialogueOverrides.apply("ABOUT WHAT.", "حول ما."))
        assertEquals(
            "أنا بخير، فلا داعي للقلق عليّ.",
            ShortDialogueOverrides.apply("I'M FINE SO YOU DON'T NEED TO WORRY ABOUT ME.", "أنا بخير حتى لا تقلق بشأنني.")
        )
        assertEquals(
            "بصراحة... لا، لست بخير إطلاقاً.",
            ShortDialogueOverrides.apply("TO BE HONEST... NO. I AM DEFINITELY NOT OKAY.", "أن نكون صادقين... لا. أنا بالتأكيد ليست بخير.")
        )
        assertEquals(
            "إذا كنت تشعر بالأسف إلى هذا الحد، فما رأيك أن تعزمني على الطعام؟",
            ShortDialogueOverrides.apply(
                "IF YOU'RE THAT SORRY... HOW ABOUT TREATING ME TO SOME FOOD?",
                "إذا كنت آسف... ماذا عن علاج لي بعض الطعام؟"
            )
        )
        assertEquals(
            "ما الذي دفعك إلى الموافقة أصلاً؟ هل أصبت رأسك أيضاً؟",
            ShortDialogueOverrides.apply(
                "WHAT EVEN MADE YOU SAY YES, HUH?? DID YOU ALSO HURT YOUR HEAD?!",
                "ما الذي جعلك تقول نعم، هاه؟ هل اذنب رأسك ايضا"
            )
        )
    }

    @Test fun longerSentencesAreNeverOverridden() {
        val output = "شغّل البرنامج ثم انتظر"
        assertEquals(output, ShortDialogueOverrides.apply("Run the program and wait.", output))
    }
    @Test fun unrelatedShortTextIsUntouched() {
        assertEquals("نعم.", ShortDialogueOverrides.apply("Yes.", "نعم."))
    }
}
