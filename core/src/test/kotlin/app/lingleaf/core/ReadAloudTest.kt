package app.lingleaf.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadAloudTest {
    private fun texts(paragraph: String) = ReadAloud.sentences(paragraph).map { paragraph.substring(it.start, it.end) }

    @Test
    fun `a paragraph is read sentence by sentence`() {
        assertEquals(listOf("Tom has a boat.", "It is red!", "Is it new?"), texts("Tom has a boat. It is red!  Is it new?"))
        assertEquals(listOf("“Come here,” she said.", "He came."), texts("“Come here,” she said. He came."))
        assertEquals(listOf("No end here"), texts("  No end here  "))
        assertEquals(emptyList(), texts("   "))
        assertEquals(emptyList(), texts(""))
    }

    @Test
    fun `a very long sentence comes in pieces without repeating or losing words`() {
        val long = List(400) { "word$it" }.joinToString(" ") + "."
        val pieces = ReadAloud.sentences(long)
        assertTrue(pieces.size > 1)
        assertTrue(pieces.all { it.end - it.start <= TextTools.MAX_SENTENCE })
        assertEquals(long.split(" "), pieces.flatMap { long.substring(it.start, it.end).split(" ") })
    }

    @Test
    fun `reading starts at the sentence of the offset and goes on to the end`() {
        val book = listOf("One. Two.", "", "Three.")
        val first = ReadAloud.start(book, 0)!!
        assertEquals(SentencePosition(0, WordRange(0, 4)), first)
        assertEquals(SentencePosition(0, WordRange(5, 9)), ReadAloud.start(book, 0, offset = 6), "the sentence that holds the offset")
        val second = ReadAloud.next(book, first)!!
        assertEquals("Two.", book[0].substring(second.range.start, second.range.end))
        val third = ReadAloud.next(book, second)!!
        assertEquals(SentencePosition(2, WordRange(0, 6)), third, "empty paragraphs are skipped")
        assertNull(ReadAloud.next(book, third), "the book's last sentence")
        assertNull(ReadAloud.start(book, 3))
        assertNull(ReadAloud.start(listOf("", " "), 0))
        assertEquals(first, ReadAloud.start(book, -1), "a negative paragraph starts at the first")
    }

    @Test
    fun `previous goes back one sentence, across empty paragraphs`() {
        val book = listOf("One. Two.", "", "Three.")
        val one = SentencePosition(0, WordRange(0, 4))
        val two = SentencePosition(0, WordRange(5, 9))
        val three = SentencePosition(2, WordRange(0, 6))
        assertEquals(two, ReadAloud.previous(book, three))
        assertEquals(one, ReadAloud.previous(book, two))
        assertNull(ReadAloud.previous(book, one), "the book's first sentence")
        assertEquals(three, ReadAloud.previous(book, SentencePosition(9, WordRange(0, 1))), "past the end: the last sentence")
    }

    @Test
    fun `a repeated stretch counts its sentences`() {
        val book = listOf("One. Two.", "", "Three.")
        val one = SentencePosition(0, WordRange(0, 4))
        val three = SentencePosition(2, WordRange(0, 6))
        assertEquals(1, ReadAloud.count(book, one, one))
        assertEquals(3, ReadAloud.count(book, one, three))
        assertEquals(0, ReadAloud.count(book, three, one), "the end before the start")
        assertEquals(0, ReadAloud.count(book, one, SentencePosition(0, WordRange(1, 2))), "an end that is no sentence")
    }
}
