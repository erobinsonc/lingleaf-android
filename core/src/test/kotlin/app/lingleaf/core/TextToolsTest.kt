package app.lingleaf.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextToolsTest {
    @Test
    fun `a long run of marks is matched in linear time`() {
        val hostile = "!".repeat(50_000) + "x"
        val started = System.nanoTime()
        assertEquals(1, TextTools.sentenceRanges(hostile).size, "no sentence end: one sentence")
        assertTrue((System.nanoTime() - started) / 1e9 < 2, "not one scan of the run per mark")
        assertEquals(3, TextTools.sentenceRanges("Wait!!! He said “no.” Then?").size, "runs and closing quotes still end sentences")
    }

    @Test
    fun `a paragraph the size of a book is cut at sentence ends or spaces`() {
        val noStops = "word ".repeat(5_000)
        val pieces = TextTools.splitParagraphs(noStops)
        assertTrue(pieces.size > 1)
        assertTrue(pieces.all { it.length <= TextTools.MAX_PARAGRAPH })
        assertEquals(noStops.trim(), pieces.joinToString(" "), "no word lost or cut")
        val sentences = TextTools.splitParagraphs("A short sentence here. ".repeat(300))
        assertTrue(sentences.all { it.endsWith(".") && it.length <= TextTools.MAX_PARAGRAPH })
        assertEquals(listOf(2_000, 2_000, 500), TextTools.splitParagraphs("a".repeat(4_500)).map { it.length })
        // A lone high surrogate before a space can't stall the cut.
        assertTrue(TextTools.splitParagraphs("\uD83D " + "b".repeat(3_000)).isNotEmpty())
    }

    @Test
    fun `a 10 MB text without blank lines splits in linear time`() {
        val book = "word ".repeat(2_000_000)
        val started = System.nanoTime()
        val pieces = TextTools.splitParagraphs(book)
        val seconds = (System.nanoTime() - started) / 1e9
        assertEquals(5_000, pieces.size, "2,000-unit pieces")
        assertTrue(seconds < 3, "took $seconds s")
    }

    @Test
    fun `a long sentence is capped around the tapped word`() {
        val paragraph = "word ".repeat(500)
        val range = TextTools.sentenceRangeAt(paragraph, 1_200)
        assertTrue(range.end - range.start <= TextTools.MAX_SENTENCE)
        assertTrue(1_200 in range)
        assertEquals('w', paragraph[range.start], "starts at a word")
        assertEquals("Short one.", TextTools.sentenceAt("Short one. Here.", 2), "ordinary sentences are unchanged")
    }

    @Test
    fun `labels lose invisible characters and stay short`() {
        assertEquals("evil title x", TextTools.cleanLabel("\u202Eevil\u200B title\n  x"))
        assertEquals(TextTools.MAX_LABEL, TextTools.cleanLabel("t".repeat(300)).length)
        assertEquals("Alice’s Adventures", TextTools.cleanLabel("Alice’s Adventures"))
        assertEquals("a\u0301\u0301\u0301\u0301", TextTools.cleanLabel("a" + "\u0301".repeat(100_000)), "at most 4 accents on a letter")
        assertEquals("Fake", TextTools.cleanLabel("\u3164\u115F\u2800Fake"), "blank fillers")
        assertEquals("Alice x", TextTools.cleanLabel("\u3000\u00A0Alice\u2003 x"), "Unicode spaces")
        assertEquals("Café", TextTools.cleanLabel("Cafe\u0301").let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFC) })
    }

    @Test
    fun `occurrences find the saved word or group in a sentence`() {
        val sentence = "All animals are equal, but some animal is more equal."
        assertEquals(listOf("animals", "animal"), TextTools.occurrences("animal", sentence).map { sentence.substring(it.start, it.end) })
        val group = "He did NOT go home."
        assertEquals(listOf("did NOT go"), TextTools.occurrences("did not go", group).map { group.substring(it.start, it.end) })
        assertTrue(TextTools.occurrences("can we fix", group).isEmpty())
    }

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
    fun `-ll stems give the single l when the word has two vowel groups`() {
        for ((form, base) in listOf(
            "travelled" to "travel",
            "travelling" to "travel",
            "cancelled" to "cancel",
            "quarrelling" to "quarrel",
            "controlled" to "control",
            "compelled" to "compel",
            "patrolling" to "patrol",
        )) {
            assertEquals(base, TextTools.baseFormCandidates(form).first(), form)
        }
        assertEquals(listOf("call", "calle"), TextTools.baseFormCandidates("called"), "one vowel group: as before")
        assertEquals(listOf("fill", "fille"), TextTools.baseFormCandidates("filled"))
        assertFalse("fuel" in TextTools.baseFormCandidates("fuelled"), "missed on purpose: one vowel group before the l")
    }

    @Test
    fun `irregular forms come from the table`() {
        for ((form, base) in listOf(
            "stolen" to "steal", "learnt" to "learn", "lying" to "lie", "lied" to "lie", "being" to "be", "became" to "become",
            "shook" to "shake", "drew" to "draw", "torn" to "tear", "blew" to "blow", "sold" to "sell", "spilt" to "spill",
        )) {
            assertEquals(base, TextTools.baseFormCandidates(form).firstOrNull(), form)
        }
        assertEquals("wife", TextTools.baseFormCandidates("wives").first(), "irregular nouns stay")
        assertEquals(listOf("good"), TextTools.baseFormCandidates("better"))
    }

    @Test
    fun `homographs need their sentence`() {
        for (word in listOf("left", "saw", "felt", "found", "lives", "leaves", "lay")) {
            assertEquals(emptyList(), TextTools.baseFormCandidates(word), "$word without a sentence")
        }
        assertEquals(listOf("tear"), TextTools.baseFormCandidates("tears"), "both readings share the base")
        val she = "She left early."
        assertEquals(listOf("leave"), TextTools.baseFormCandidates(she, she.rangeOf("left")))
        assertEquals(setOf("leave"), TextTools.matchKeys(she, she.rangeOf("left")), "not \"left\" the side")
        val turn = "Turn left here."
        assertEquals(setOf("left"), TextTools.matchKeys(turn, turn.rangeOf("left")))
        val leaves = "The leaves fell. She leaves today."
        assertEquals(setOf("leaf"), TextTools.matchKeys(leaves, leaves.rangeOf("leaves")))
        val unsure = "She wore a fur stole."
        assertEquals(setOf("stole"), TextTools.matchKeys(unsure, unsure.rangeOf("stole")))
        val name = "We met Uncle Drew."
        assertEquals(emptyList(), TextTools.baseFormCandidates(name, name.rangeOf("Drew")), "a name: no irregular link")
        val drew = "She drew a map."
        assertEquals(listOf("draw"), TextTools.baseFormCandidates(drew, drew.rangeOf("drew")))
        assertEquals(setOf("animals", "animal"), TextTools.matchKeys("Big animals.", "Big animals.".rangeOf("animals")))
    }

    @Test
    fun `occurrences read homographs in their sentence`() {
        fun marked(
            word: String,
            sentence: String,
        ) = TextTools.occurrences(word, sentence).map { sentence.substring(it.start, it.end) }
        assertEquals(listOf("left"), marked("leave", "Turn left, then she left."))
        assertEquals(listOf(5), TextTools.occurrences("left", "Turn left, then she left.").map { it.start })
        assertEquals(listOf("leaves"), marked("leaf", "The leaves fell. She leaves today."))
        assertEquals(listOf("stole", "stolen"), marked("steal", "He stole it; it was stolen."))
        assertEquals(listOf("travelled"), marked("travel", "We travelled far."))
    }

    @Test
    fun `matchKeys links inflected forms to the base word`() {
        assertTrue(TextTools.matchKeys("Animals").containsAll(listOf("animals", "animal")))
        assertTrue("be" in TextTools.matchKeys("was"))
        assertTrue(5 in WordRange(4, 11))
    }
}
