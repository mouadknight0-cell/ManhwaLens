package com.manhwalens.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationOutputSanitizerTest {

    @Test
    fun progressiveOutputStripsLeadingTranslationLabelAndPolishesArabic() {
        val raw = TranslatedBlock(
            id = 4,
            english = "Are you okay?",
            arabic = "الترجمة: هل أنت بخير ?"
        )

        val safe = TranslationOutputSanitizer.prepare(raw)

        assertEquals("هل أنت بخير؟", safe?.arabic)
    }

    @Test
    fun progressiveOutputUsesTheSameAuthoritativeNameOverride() {
        val raw = TranslatedBlock(7, "Eren is here.", "إيرين هنا.")

        val safe = TranslationOutputSanitizer.prepare(raw) { _, arabic ->
            arabic.replace("إيرين", "إرن")
        }

        assertEquals("إرن هنا.", safe?.arabic)
    }

    @Test
    fun sourceAwareDialogueRepairRunsAfterPinnedNameOverride() {
        val raw = TranslatedBlock(8, "No.", "رقم")

        val safe = TranslationOutputSanitizer.prepare(raw) { _, arabic ->
            "$arabic شخصية"
        }

        assertEquals("لا.", safe?.arabic)
    }

    @Test
    fun progressiveOutputRejectsEnglishEchoesAndBlankResults() {
        assertNull(
            TranslationOutputSanitizer.prepare(
                TranslatedBlock(1, "Where are you going?", "Where are you going?")
            )
        )
        assertNull(
            TranslationOutputSanitizer.prepare(
                TranslatedBlock(2, "Hello", "   ")
            )
        )
    }

    @Test
    fun sanitationIsIdempotentForAlreadyCleanArabic() {
        val first = TranslationOutputSanitizer.clean("هل أنت بخير ? نعم , أنا بخير.")
        val second = TranslationOutputSanitizer.clean(first)

        assertEquals(first, second)
        assertEquals("هل أنت بخير؟ نعم، أنا بخير.", first)
    }
}
