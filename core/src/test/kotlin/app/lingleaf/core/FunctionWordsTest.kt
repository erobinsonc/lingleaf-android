package app.lingleaf.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FunctionWordsTest {
    /** Analyzes the first occurrence of [word] in [sentence]; returns the result and the chunk's text. */
    private fun analyze(
        sentence: String,
        word: String,
    ): Pair<FunctionWord?, String?> {
        val range = TextTools.wordAt(sentence, sentence.indexOf(word))!!
        val result = FunctionWords.analyze(sentence, range)
        return result to result?.chunk?.let { sentence.substring(it.start, it.end) }
    }

    @Test
    fun `did not makes the past negative`() {
        val (result, chunk) = analyze("He did not go to the beach.", "did")
        assertEquals(FunctionWord.Kind.HELPER, result?.kind)
        assertEquals("do", result?.base)
        assertEquals(FunctionWord.Use.PastNegative, result?.use)
        assertEquals("did not go", chunk)
    }

    @Test
    fun `contractions, present negative and prohibitions`() {
        assertEquals("doesn’t like", analyze("She doesn’t like it.", "doesn").second)
        assertEquals(FunctionWord.Use.PresentNegative, analyze("She doesn’t like it.", "doesn").first?.use)
        assertEquals("Do not touch", analyze("Do not touch that.", "Do").second)
        assertEquals(FunctionWord.Use.Prohibition, analyze("Do not touch that.", "Do").first?.use)
        assertEquals(FunctionWord.Use.Prohibition, analyze("Don’t go!", "Don").first?.use)
        assertEquals(FunctionWord.Use.PresentNegative, analyze("Don't you know?", "Don").first?.use, "a question is not a prohibition")
        assertEquals("did not", analyze("I did not.", "did").second, "the sentence ends: the group stops there")
        val (result, chunk) = analyze("Why didn't you come?", "didn")
        assertEquals(FunctionWord.Use.PastNegative, result?.use)
        assertEquals("didn't you come", chunk)
        // Nothing after it: still a helper, without a verb group.
        assertNull(analyze("No, I didn't.", "didn").first?.chunk)
    }

    @Test
    fun questions() {
        val (did, didChunk) = analyze("Did you see the ship?", "Did")
        assertEquals(FunctionWord.Use.PastQuestion, did?.use)
        assertEquals("Did you see", didChunk)
        assertEquals(FunctionWord.Use.PresentQuestion, analyze("What do they want?", "do").first?.use)
        assertEquals("do they want", analyze("What do they want?", "do").second)
        // A noun subject: still a question, but no verb group.
        val (noun, nounChunk) = analyze("Does the dog bark?", "Does")
        assertEquals(FunctionWord.Use.PresentQuestion, noun?.use)
        assertNull(nounChunk)
    }

    @Test
    fun `do as a main verb is an ordinary word`() {
        assertNull(analyze("I did my homework.", "did").first)
        assertNull(analyze("What did he say, and what did he do?", "do").first)
        assertNull(analyze("They do it every day.", "do").first)
    }

    @Test
    fun modals() {
        val (will, willChunk) = analyze("We will go home.", "will")
        assertEquals(FunctionWord.Kind.MODAL, will?.kind)
        assertEquals(FunctionWord.Use.Modal("will"), will?.use)
        assertEquals("will go", willChunk)
        assertEquals("Will you help", analyze("Will you help me?", "Will").second)
        assertEquals("must not run", analyze("You must not run.", "must").second)
        assertEquals("will", analyze("I won't stay.", "won").first?.base)
        assertEquals("can", analyze("I cannot see.", "cannot").first?.base)
        assertEquals("can", analyze("I can't see.", "can").first?.base)
        assertNull(analyze("Yes, I can.", "can").first?.chunk)
        assertEquals("can cut", analyze("A tool that can cut wood.", "can").second, "after a relative pronoun it's still a modal")
        assertEquals("should have known", analyze("You should have known.", "should").second, "have/be lead to the main verb")
        assertEquals("will never be able", analyze("We will never be able to go.", "will").second)
        assertEquals("did not really like", analyze("She did not really like it.", "did").second)
    }

    @Test
    fun `modal look-alikes are ordinary words`() {
        assertNull(analyze("He opened a can of beans.", "can").first)
        assertNull(analyze("It was his will.", "will").first)
        assertNull(analyze("We met in May last year.", "May").first)
        assertNull(analyze("Then Will smiled.", "Will").first)
        assertNull(analyze("The cat sat.", "cat").first)
        assertNull(FunctionWords.analyze("The cat sat.", WordRange(0, 2)))
    }

    @Test
    fun `sentenceRangeAt matches sentenceAt`() {
        val p = "  First one.  Did you go?  The end "
        val offset = p.indexOf("you")
        val range = TextTools.sentenceRangeAt(p, offset)
        assertEquals("Did you go?", p.substring(range.start, range.end))
        assertEquals("Did you go?", TextTools.sentenceAt(p, offset))
        assertEquals("The end", TextTools.sentenceAt(p, p.indexOf("end")))
    }
}
