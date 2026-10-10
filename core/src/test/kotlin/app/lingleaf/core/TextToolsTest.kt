package app.lingleaf.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextToolsTest {
    @Test
    fun `paragraphs join hard-wrapped lines and handle CRLF`() {
        assertEquals(
            listOf("Line one line two.", "Second paragraph."),
            TextTools.splitParagraphs("Line one\r\nline two.\r\n\r\n   \nSecond paragraph.\n"),
        )
    }

    @Test
    fun `word ranges keep contractions and skip numbers`() {
        val text = "Don’t stop, it's 10 o'clock!"
        assertEquals(listOf("Don’t", "stop", "it's", "o'clock"), TextTools.wordRanges(text).map { text.substring(it.start, it.end) })
    }

    @Test
    fun `wordAt finds the word including its trailing edge`() {
        val text = "All animals are equal."
        assertEquals(WordRange(4, 11), TextTools.wordAt(text, 4))
        assertEquals(WordRange(4, 11), TextTools.wordAt(text, 11))
        assertEquals(WordRange(16, 21), TextTools.wordAt(text, 21))
        assertNull(TextTools.wordAt("  ...  ", 3))
    }

    @Test
    fun `sentenceAt returns the containing sentence`() {
        val p = "All animals are equal. Some are more equal than others! The end"
        assertEquals("Some are more equal than others!", TextTools.sentenceAt(p, p.indexOf("more")))
        assertEquals("All animals are equal.", TextTools.sentenceAt(p, 0))
        assertEquals("The end", TextTools.sentenceAt(p, p.indexOf("end")))
        assertEquals("Just one.", TextTools.sentenceAt("Just one.", 20))
    }

    @Test
    fun `base form candidates`() {
        assertTrue("animal" in TextTools.baseFormCandidates("animals"))
        assertTrue("house" in TextTools.baseFormCandidates("houses"))
        assertEquals(listOf("story"), TextTools.baseFormCandidates("stories"))
        assertEquals(listOf("try"), TextTools.baseFormCandidates("tried"))
        assertTrue("walk" in TextTools.baseFormCandidates("walked"))
        assertEquals("stop", TextTools.baseFormCandidates("stopped").first())
        assertEquals("run", TextTools.baseFormCandidates("running").first())
        assertTrue("make" in TextTools.baseFormCandidates("making"))
        assertEquals(listOf("stand"), TextTools.baseFormCandidates("stood"))
        assertEquals(listOf("child"), TextTools.baseFormCandidates("Children"))
        assertEquals(listOf("cat"), TextTools.baseFormCandidates("cat's"))
        assertTrue(TextTools.baseFormCandidates("thing").isEmpty())
        assertTrue(TextTools.baseFormCandidates("glass").isEmpty())
    }

    @Test
    fun `matchKeys links inflected forms to the base word`() {
        assertTrue(TextTools.matchKeys("Animals").containsAll(listOf("animals", "animal")))
        assertTrue("be" in TextTools.matchKeys("was"))
        assertTrue(5 in WordRange(4, 11))
    }
}
