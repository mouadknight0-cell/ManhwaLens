package com.manhwalens.app.data

import com.manhwalens.app.names.CharacterRecord
import com.manhwalens.app.names.JsonCharacterStore
import com.manhwalens.app.names.NameForm
import com.manhwalens.app.names.NameSource
import com.manhwalens.app.translate.NameScript
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * P1 — the per-page session the pipeline actually talks to.
 *
 * [NameSession] is what makes the three engines agree: it resolves the page once, hands the same
 * answer to ML Kit and Gemini, and rewrites cached Arabic with confirmed spellings. It has no
 * Android dependency, so it is tested here against the real file store.
 */
class NameSessionTest {

    private lateinit var dir: File
    private lateinit var store: JsonCharacterStore

    @Before
    fun setUp() {
        dir = File.createTempFile("name-session", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        store = JsonCharacterStore(File(dir, "character_memory.json")) { 100L }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `an unassigned session resolves names but persists nothing`() {
        val session = NameSession(seriesId = null, seriesTitle = null, store = null)

        assertFalse(session.isAssigned)
        val table = session.tableFor(listOf("Wei Wuxian drew his sword."))

        assertEquals(null, table.seriesId)
        assertTrue(table.names.isNotEmpty())
        assertTrue(store.seriesIds().isEmpty())
    }

    @Test
    fun `a session uses the series memory and stores new names`() {
        store.upsert(locked("Han Ji-woo", "هان جيو"))
        val session = NameSession(seriesId = "s1", seriesTitle = "Solo Leveling", store = store)

        val table = session.tableFor(
            listOf("Han Ji-woo left.", "Lan Wangji waited near the gate.")
        )

        assertTrue(session.isAssigned)
        assertEquals(
            "هان جيو",
            table.names.first { NameForm.normalize(it.original) == "hanjiwoo" }.arabic
        )
        // Whatever else the page detected is now remembered for THIS series only.
        assertTrue(store.records("s1").size >= 1)
        assertTrue(store.records("s2").isEmpty())
    }

    @Test
    fun `the page table is computed once for the same page`() {
        val session = NameSession(seriesId = "s1", seriesTitle = "Solo", store = store)
        val texts = listOf("Han Ji-woo left.")

        val first = session.tableFor(texts)
        val second = session.tableFor(texts)

        assertSame(first, second)
    }

    @Test
    fun `an equal text list with a different instance reuses the table`() {
        val session = NameSession(seriesId = "s1", seriesTitle = "Solo", store = store)
        val first = session.tableFor(listOf("Han Ji-woo left."))
        val second = session.tableFor(listOf("Han Ji-woo left."))

        assertSame(first, second) // content equality, not identity
    }

    @Test
    fun `two page texts sharing a hash never share a name table`() {
        store.upsert(locked("Aa", "آ"))
        val session = NameSession(seriesId = "s1", seriesTitle = "Solo", store = store)

        val pageA = listOf("Aa")
        val pageB = listOf("BB")
        // Premise of the regression: "Aa" and "BB" collide as strings, so these two pages have
        // the same List.hashCode() — which is exactly what the old cache key compared.
        assertEquals(pageA.hashCode(), pageB.hashCode())

        val tableA = session.tableFor(pageA)
        val tableB = session.tableFor(pageB)

        assertNotSame(tableA, tableB)
        assertTrue(tableA.names.any { it.original == "Aa" })
        assertTrue(tableB.names.none { it.original == "Aa" }) // page A's names must not leak into B
    }

    @Test
    fun `a locked correction rewrites already-translated arabic`() {
        // The real flow: the pipeline stored a spelling, the reader corrected it. The old
        // spelling lives on in previousArabic, which is the only reason the repair is possible.
        correct("Han Ji-woo", from = "هان جي وو", to = "هان جيو")
        val session = NameSession(seriesId = "s1", seriesTitle = "Solo", store = store)

        assertEquals(
            "هان جيو غادر الغرفة",
            session.rewrite("Han Ji-woo left the room.", "هان جي وو غادر الغرفة")
        )
    }

    @Test
    fun `a latin remnant in cached text becomes the confirmed spelling`() {
        correct("Han Ji-woo", from = "هان جي وو", to = "هان جيو")
        val session = NameSession(seriesId = "s1", seriesTitle = "Solo", store = store)

        assertEquals(
            "غادر هان جيو",
            session.rewrite("Han Ji-woo left.", "غادر Han Ji-woo")
        )
    }

    @Test
    fun `an untrusted spelling never rewrites cached text`() {
        store.upsert(
            CharacterRecord(
                id = "s1-guest",
                seriesId = "s1",
                originalName = "Han Ji-woo",
                normalizedName = NameForm.normalize("Han Ji-woo"),
                arabicName = "هان جي وو",
                script = NameScript.KOREAN,
                confidence = 0.3f,
                source = NameSource.TRANSLITERATION
            )
        )
        val session = NameSession(seriesId = "s1", seriesTitle = "Solo", store = store)

        val cached = "هان جيو غادر"
        assertEquals(cached, session.rewrite("Han Ji-woo left.", cached))
    }

    @Test
    fun `unrelated text is never touched`() {
        store.upsert(locked("Han Ji-woo", "هان جيو"))
        val session = NameSession(seriesId = "s1", seriesTitle = "Solo", store = store)

        assertEquals("انتظر هنا", session.rewrite("Wait here.", "انتظر هنا"))
    }

    /** Store a spelling the way the pipeline does, then lock the reader's correction over it. */
    private fun correct(original: String, from: String, to: String) {
        val record = store.upsert(
            CharacterRecord(
                id = "",
                seriesId = "s1",
                originalName = original,
                normalizedName = NameForm.normalize(original),
                arabicName = from,
                script = NameScript.KOREAN,
                confidence = 0.5f,
                source = NameSource.HEURISTIC
            )
        )
        store.lock(record.id, to, 200L)
    }

    private fun locked(original: String, arabic: String) = CharacterRecord(
        id = "s1-${NameForm.normalize(original)}",
        seriesId = "s1",
        originalName = original,
        normalizedName = NameForm.normalize(original),
        arabicName = arabic,
        script = NameScript.KOREAN,
        confidence = 1f,
        source = NameSource.USER,
        locked = true
    )
}
