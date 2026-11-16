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
    @Test fun stayBehindMeIsTranslatedAsNaturalArabicDialogue() {
        assertEquals(
            "ابقَ خلفي ولا تنطق بكلمة.",
            ShortDialogueOverrides.apply(
                "Stay behind me and don't say a word.",
                "البقاء ورائي ولا تقل كلمة."
            )
        )
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


    @Test fun soloLevelingChapterFourObservedLinesGetSourceExactRepairs() {
        assertEquals("ما رتبة الشخص الذي مات للتو؟", ShortDialogueOverrides.apply(
            "WHAT Was RANK OF THE THE ONE WHO DIED JUST NOW?",
            "ما كان رتبة الشخص الذي مات الآن؟"
        ))
        assertEquals("حتى لو كان ضعيفًا، لظل أقوى مني، فأنا من الرتبة E.", ShortDialogueOverrides.apply(
            "IF EVEN HE Was Bad, HE STILL WOULO'VE BEEN STRONGER THAN AN E-RANK LIKE ME.",
            "إذا كان حتى أنه كان سيئا، فسيظل Woulo've أقوى من المرتبة الإلكترونية مثلي."
        ))
        assertEquals("ما رتبة الشخص الذي مات للتو؟", ShortDialogueOverrides.apply(
            "WHAT WaS THE RANK OF THE ONE WHO DIED JUST NOW?", "ما هو رتبة الشخص الذي مات الآن؟"
        ))
        assertEquals("الرتبة D", ShortDialogueOverrides.apply("D-RANK?", "د المرتبة؟"))
        assertEquals("حتى لو كان ضعيفًا، لظل أقوى مني، فأنا من الرتبة E.", ShortDialogueOverrides.apply(
            "IF EVEN He Was BaD, HE STILL WOULD'VE BEEN STRONGER THAN AN E-RANK LIKE ME.",
            "إذا كان الأمر كذلك، فسيظل سيكون أقوى من رتبة E مثلي."
        ))
        assertEquals("هذا الدهليز لا تتجاوز رتبته D.", ShortDialogueOverrides.apply(
            "THIS DUNGEON IS A D-RANK AT MOST.", "هذا المحصنة هو رتبة D على الأكثر."
        ))
        assertEquals("في أول غارة لي، انفصلت عن فريقي بالخطأ وضللت الطريق.", ShortDialogueOverrides.apply(
            "DURING MY FIRST RAID, I AcCidEnTALLY BROKE AWAY FROM MY PARTY AND GOT LOST",
            "خلال غارة الأولى، اندلعت بطريق الخطأ بعيدا عن حزبي وتضيع"
        ))
        assertEquals("يستبدل الآخرون نوىهم السحرية بأسلحة أفضل.", ShortDialogueOverrides.apply(
            "OTHERS TRADE THEIR MaGical CORES FOR BETTER WEAPONS",
            "تجارة الآخرين من نوىهم السحرية للحصول على أسلحة أفضل"
        ))
        assertEquals("فهم مضطرون إلى بذل ضعف الجهد.", ShortDialogueOverrides.apply(
            "THEY HAVE TO WORK TWICE as HaRD.", "لديهم للعمل مرتين بجد."
        ))
        assertEquals("لكنا أُبيدنا جميعًا.", ShortDialogueOverrides.apply(
            "WE COULD'VE BEEN WIPED OUT.", "يمكن أن تم القضاء عليها."
        ))

        assertEquals("لم أسمع قط بوجود وحوش بهذه القوة في دهاليز من الرتبة D!", ShortDialogueOverrides.apply(
            "I'VE NEVER HEARD OF MON-\nSTERS AS STRONG AS THEM IN THE LIKES OF D-RANK DUNGEONS!",
            "لم أسمع أبدا عن الوحوش قوية مثلهم في أمثال D-Rank الأبراج المحصنة!"
        ))
        assertEquals("هذه ليست المرة الأولى التي أواجه فيها تجربة كدت أموت خلالها.", ShortDialogueOverrides.apply(
            "THIS ISN't THE FIRST TIME I ENCOUNTERED A NEAR-DEATH EXPERIENCE,",
            "هذه ليست المرة الأولى التي واجهت فيها تجربة شبه الموت،"
        ))
        assertEquals("لا أستطيع فعل ذلك.", ShortDialogueOverrides.apply("I CAN'T DO THAT", "لا أستطيع فعل هذا"))
        assertEquals("يُلقّب هذه الأيام بأضعف صياد في العالم.", ShortDialogueOverrides.apply(
            "THESE DAYS, HE'S CALLED THE WORLD'S WEAKEST HUNTER.",
            "في هذه الأيام، يطلق عليه أضعف هنتر في العالم."
        ))
        assertEquals("أي إنه، رغم كونه صيادًا، عديم الفائدة.", ShortDialogueOverrides.apply(
            "MEANING EVEN THOUGH HE'S A HUNTER, HE'S USELESS.",
            "وهذا يعني رغم أنه صياد، فهو عديم الفائدة."
        ))
        assertEquals("كدت أعجز عن تفاديها. لولا أن سونغ صاح محذرًا،", ShortDialogueOverrides.apply(
            "I BARELY DODGED IT. IF SUNG DIDN'T YELL OUT,",
            "بالكاد تهرب منه. إذا كان سونغ لم يصيح،"
        ))
    }

    @Test fun soloLevelingChapterFiveObservedLinesGetSourceExactRepairs() {
        val cases = listOf(
            "THE TUNNEL THAT CONNECTS THE OTHER WORLD TO OURS." to "النفق الذي يربط العالم الآخر بعالمنا.",
            "THE TUNNEL THAT OTHER CONNECTS THE WORLD TO OURS." to "النفق الذي يربط العالم الآخر بعالمنا.",
            "AFTER GATE THE APPEARED, WORLD THE EXPERIENCED MANY BIZARRE EVENTS." to "بعد ظهور البوابة، شهد العالم أحداثًا غريبة كثيرة.",
            "THE GATE AFTER APPEARED, WORLD THE EXPERIENCED MANY BIZARRE EVENTS." to "بعد ظهور البوابة، شهد العالم أحداثًا غريبة كثيرة.",
            "ONE EXAMPLE IS THE HUNTERS." to "ومن أمثلة ذلك الصيادون.",
            "THEIR STRENGTH STAYS THE SAME FROM WHEN THEY WERE AWAKENED - IT NEVER CHANGES." to "تظل قوتهم كما كانت منذ استيقاظهم؛ فهي لا تتغير أبدًا.",
            "I TOO, BECAME A HUNTER, BUT MY STRENGTH WAS ONLY ENOUGH FOR AN E-RANK." to "وأنا أيضًا أصبحت صيادًا، لكن قوتي لم تؤهلني إلا للرتبة E.",
            "INSANE MONSTER THAT SPRINGS OUT OF NOWHERE." to "وحش مرعب يظهر من العدم.",
            "THERE'S NOTHING WE CAN DO NOW, IS THERE?" to "لا يسعنا فعل شيء الآن، أليس كذلك؟",
            "I BROUGHT ONLY THREE HEALERS BECAUSE I THOUGHT THIS WOULD BE OVER QUICKLY.." to "أحضرت ثلاثة معالجين فقط لأنني ظننت أن الأمر سينتهي بسرعة.",
            "ONE GOT ATTACKED SO BRUTALLY THAT NOT EVEN HIS CORPSE REMAINED," to "تعرض أحدهم لهجوم وحشي حتى لم يبقَ من جثته شيء.",
            "WHEN THE SITUATION CLEARS A BIT, LET'S TRY RUNNING THEN." to "عندما تهدأ الأمور قليلًا، لنحاول الركض حينها.",
            "EVEN IF WE SOMEHOW BYPASS HIM, WE STILL HAVE TO GO THROUGH THE GUARD STATLE." to "حتى لو تجاوزناه بطريقة ما، فلا يزال علينا اجتياز تمثال الحارس.",
            "HE PROBABLY NEVER INTENDED FOR US TO LEAVE!" to "ربما لم يكن ينوي السماح لنا بالمغادرة أبدًا.",
            "SO WHY AREN'T THEY?" to "فلماذا لا يفعلون ذلك؟",
            "DUNNO.." to "لا أدري.",
            "IT'S DIFFERENT FROM THE MONSTERS WHO BLINDLY ATTACK HUNTERS ON SIGHT." to "إنه يختلف عن الوحوش التي تهاجم الصيادين فور رؤيتهم.",
            "THEY ATTACK IN A PATTERN!" to "إنها تهاجم وفق نمط محدد!",
            "IT CAN'T BE.." to "مستحيل...",
            "MR. SONG CHI-YUL! WHAT WAS THE FIRST COMMANDMENT??" to "سيد سونغ تشي يول! ما الوصية الأولى؟",
            "COMMAND-MENT?" to "الوصية؟",
            "COMMANDMENT?" to "الوصية؟",
            "THE FIRST WOULD BE...." to "الأولى هي...",
            "SIR, THERE'S A RULE IN THIS DUNGEON." to "سيدي، هناك قاعدة في هذا الدهليز.",
            "IF I'M CORRECT...." to "إن لم أكن مخطئًا..."
        )
        cases.forEach { (source, expected) ->
            assertEquals(expected, ShortDialogueOverrides.apply(source, "ترجمة آلية ضعيفة"))
        }
    }

    @Test fun longerSentencesAreNeverOverridden() {
        val output = "شغّل البرنامج ثم انتظر"
        assertEquals(output, ShortDialogueOverrides.apply("Run the program and wait.", output))
    }
    @Test fun unrelatedShortTextIsUntouched() {
        assertEquals("نعم.", ShortDialogueOverrides.apply("Yes.", "نعم."))
    }
}
