package com.manhwalens.app.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class ArabicPolishTest {

    @Test
    fun questionAndCommaBecomeArabic() {
        assertEquals("هل أنت بخير؟ نعم، أنا بخير.", BubbleTextRules.polishArabic("هل أنت بخير? نعم, أنا بخير."))
    }

    @Test
    fun latinTextIsLeftAlone() {
        assertEquals("Hello, how are you?", BubbleTextRules.polishArabic("Hello, how are you?"))
        assertEquals("", BubbleTextRules.polishArabic("  "))
    }

    @Test
    fun spacesAroundArabicPunctuationCollapse() {
        assertEquals("قلت لك، لا.", BubbleTextRules.polishArabic("قلت لك ،  لا."))
    }
}
