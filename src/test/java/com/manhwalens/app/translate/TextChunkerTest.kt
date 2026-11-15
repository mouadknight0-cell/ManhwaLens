package com.manhwalens.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerTest {

    @Test
    fun shortText_notChunked() {
        assertEquals(listOf("hello world"), TextChunker.chunk("hello world"))
        assertEquals(false, TextChunker.needsChunk("x".repeat(400)))
        assertEquals(true, TextChunker.needsChunk("x".repeat(401)))
    }

    @Test
    fun sentenceBoundariesKeepSentencesWhole() {
        val s = "First sentence here. Second sentence follows! Third one asks? Yes it does."
        val sentences = TextChunker.sentences(s)
        assertEquals(4, sentences.size)
        assertTrue(sentences[0].startsWith("First"))
        assertTrue(sentences[3].startsWith("Yes"))
    }

    @Test
    fun longText_splitsIntoBoundedChunks() {
        val sentence = "This is a reasonably long sentence for the manhwa dialogue test case. "
        val long = sentence.repeat(30) // ~2100 chars
        val chunks = TextChunker.chunk(long)
        assertTrue("must split", chunks.size > 3)
        for (c in chunks) assertTrue("chunk too big: ${c.length}", c.length <= TextChunker.CHUNK_TARGET)
        // Reassembly keeps every sentence
        assertEquals(sentence.repeat(30).trim(), chunks.joinToString(" ").trim())
    }

    @Test
    fun overlongSingleSentence_hardSplit() {
        val s = "x".repeat(900)
        val chunks = TextChunker.chunk(s)
        assertEquals(3, chunks.size)
        assertTrue(chunks.all { it.length <= TextChunker.CHUNK_TARGET })
    }

    @Test
    fun arabicQuestionMarkBoundary() {
        val s = "إلى أين تذهب؟ لا أعرف. ابق هنا!"
        val parts = TextChunker.sentences(s)
        assertEquals(3, parts.size)
    }
}
