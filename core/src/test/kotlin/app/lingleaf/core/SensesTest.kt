package app.lingleaf.core

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SensesTest {
    private val storage = MemoryStorage()
    private val clock = TestClock()

    private fun library() = Library(storage, clock).apply { load() }

    private fun marked(
        text: String,
        state: LibraryState,
    ) = state.learning(text, TextTools.wordRanges(text)).map { text.substring(it.start, it.end) }

    private val verb = Reading(Reading.Kind.VERB, "leave", Reading.Step.CHOSEN)
    private val noun = Reading(Reading.Kind.NOUN, "left", Reading.Step.CHOSEN)

    @Test
    fun `a word with a sense marks only forms read in that sense`() {
        val library = library()
        library.saveWord("wind", learned = false, sense = Sense.NOUN)
        val state = library.state.value
        assertEquals(listOf("wind"), marked("The wind was cold. She wound the clock.", state), "the wind doesn't mark wound")
        assertEquals(emptyList(), marked("Wind is strong.", state), "unsure: no sensed word")
        library.saveWord("wind", learned = false, sense = Sense.VERB)
        assertEquals(listOf("wind", "wound"), marked("The wind was cold. She wound the clock.", library.state.value))
        assertEquals(2, library.state.value.words.size, "one entry per sense")
        assertEquals(listOf("winding"), marked("She is winding the clock.", library.state.value), "an -ing form a step proves")
        assertEquals(emptyList(), marked("A winding road.", library.state.value), "after a determiner: an ordinary word")
    }

    @Test
    fun `a word saved unsure marks its exact form everywhere`() {
        val library = library()
        library.saveWord(
            "Left",
            learned = false,
            meanings = listOf(SavedMeaning.of(verb), SavedMeaning.of(noun)),
            translationLanguage = "es",
        )
        val saved = library.state.value.word("left")!!
        assertTrue(saved.isUnsure)
        assertEquals("", saved.translation)
        assertEquals(listOf("left", "left"), marked("Turn left; she left.", library.state.value))
        assertFalse(library.state.value.isLearning("leave", WordRange(0, 5)))
        assertTrue(library.state.value.isLearning("She left.", WordRange(4, 8)))
        assertFalse(library.state.value.isLearning("She left.", WordRange(4, 20)))
        assertEquals(listOf("to leave", "the left"), saved.meanings!!.map { it.label })
    }

    @Test
    fun `old words without a sense mark any reading of their forms, as before`() {
        val library = library()
        library.saveWord("leave", learned = false)
        assertEquals(listOf("left", "leaving"), marked("She left, leaving the room.", library.state.value))
        assertEquals(listOf("left"), marked("She left, leaving.", library.state.value), "an -ing form nothing proves")
        assertEquals(emptyList(), marked("Turn left.", library.state.value))
    }

    @Test
    fun `irregular links lead to the verb, a twin's forms to the twin`() {
        val library = library()
        library.saveWord("lie", learned = false, sense = Sense.HOMONYM)
        assertEquals(listOf("lied", "lying"), marked("He lied, lying to us; she lay down.", library.state.value))
        assertEquals(listOf("lied"), marked("He lied, lying again.", library.state.value), "unsure: no sensed word")
        library.saveWord("lie", learned = false, sense = Sense.VERB)
        assertEquals(listOf("lied", "lying", "lay"), marked("He lied, lying to us; she lay down.", library.state.value))
        assertEquals(listOf("lying"), library().state.value.let { marked("She was lying on the bed.", library.state.value) })
        assertEquals(
            listOf("drew"),
            marked(
                "Uncle Drew drew.",
                library().apply {
                    saveWord("draw", learned = false, sense = Sense.VERB)
                }.state.value,
            ),
        )
        val names = library()
        names.saveWord("rose", learned = false, sense = Sense.NOUN)
        assertEquals(listOf("rose"), marked("Aunt Rose cut a red rose.", names.state.value), "a name isn't the flower")
    }

    @Test
    fun `two senses of one spelling are named apart`() {
        storage.files["settings.json"] = """{"targetLanguage": "es"}"""
        val library = library()
        library.saveWord("wind", learned = false, sense = Sense.NOUN)
        val state1 = library.state.value
        assertEquals("wind", state1.displayName(state1.words.single()))
        library.saveWord("wind", learned = false, sense = Sense.VERB)
        val state = library.state.value
        assertEquals("the wind", state.displayName(state.word("wind#noun")!!))
        assertEquals("to wind", state.displayName(state.word("wind#verb")!!))
        library.saveWord("lie", learned = false, sense = Sense.VERB)
        library.saveWord("lie", learned = false, sense = Sense.HOMONYM)
        val lies = library.state.value
        assertEquals("to lie (mentir)", lies.displayName(lies.word("lie#homonym")!!))
        assertTrue(lies.displayName(lies.word("lie#verb")!!).startsWith("to lie ("))
        library.saveWord("plain", learned = false)
        assertEquals("plain", library.state.value.displayName(library.state.value.word("plain")!!))
    }

    @Test
    fun `choosing a reading of a form saved unsure replaces it and keeps its progress`() {
        val library = library()
        library.saveWord("left", learned = false, meanings = listOf(SavedMeaning.of(verb), SavedMeaning.of(noun)), context = "She left.")
        library.answerReview("left", correct = true)
        val unsure = library.state.value.word("left")!!
        val previous = library.saveWord("leave", learned = false, sense = Sense.VERB, translation = "irse", replacing = "left")
        assertNull(previous)
        assertNull(library.state.value.word("left"))
        val chosen = library.state.value.word("leave#verb")!!
        assertEquals(unsure.box, chosen.box)
        assertEquals(unsure.nextReview, chosen.nextReview)
        assertEquals(unsure.createdAt, chosen.createdAt)
        assertNull(chosen.meanings)
        // Undo: both back.
        library.restoreWord("leave#verb", previous)
        library.restoreWord("left", unsure)
        assertEquals(listOf("left"), library.state.value.words.map { it.key })
        // Replacing a key that doesn't exist, or the same key, changes nothing else.
        library.saveWord("other", learned = true, replacing = "missing")
        library.saveWord("other", learned = true, replacing = "other")
        assertNotNull(library.state.value.word("other"))
    }

    @Test
    fun `the tapped occurrence is kept and checked against its sentence`() {
        val library = library()
        val context = "She left early and left again."
        library.saveWord("leave", learned = false, sense = Sense.VERB, context = context, form = "left", formRange = WordRange(19, 23))
        val saved = library.state.value.word("leave#verb")!!
        assertEquals(WordRange(19, 23), saved.formRange)
        assertNull(saved.copy(formStart = 0, formEnd = 3).formRange, "not the form")
        assertNull(saved.copy(formEnd = 99).formRange, "outside the sentence")
        assertNull(saved.copy(formStart = null).formRange)
        assertNull(saved.copy(formEnd = null).formRange)
        assertNull(saved.copy(formStart = 5, formEnd = 5).formRange)
        assertNull(saved.copy(form = null).formRange)
        assertEquals(listOf(4, 19), TextTools.occurrences(saved, context).map { it.start })
        assertEquals(
            listOf(0),
            TextTools.occurrences(VocabWord("did not go", createdAt = "", nextReview = ""), "Did not go.").map { it.start },
        )
        // Saving again from another sentence forgets the old occurrence.
        library.saveWord("leave", learned = false, sense = Sense.VERB, context = "They left.")
        assertNull(library.state.value.word("leave#verb")!!.formRange)
        library.saveWord("leave", learned = false, sense = Sense.VERB)
        assertEquals("They left.", library.state.value.word("leave#verb")!!.context)
    }

    @Test
    fun `stored senses, forms and meanings are bounded on load`() {
        val now = "2026-10-09T09:00"
        val bad =
            listOf(
                VocabWord("a", createdAt = now, nextReview = now, sense = "adverb"),
                VocabWord("b", createdAt = now, nextReview = now, context = "Hi b.", form = "b<script>", formStart = 3, formEnd = 4),
                VocabWord("c", createdAt = now, nextReview = now, context = "Hi c.", form = "c", formStart = 3, formEnd = 4),
                VocabWord(
                    "d",
                    createdAt = now,
                    nextReview = now,
                    meanings =
                        listOf(
                            SavedMeaning("verb", "dive", "bucear‮"),
                            SavedMeaning("adverb", "x"),
                            SavedMeaning("noun", "Dove!"),
                            SavedMeaning("noun", "dove", "x".repeat(500)),
                            SavedMeaning("noun", "dove"),
                        ),
                ),
                VocabWord("e", createdAt = now, nextReview = now, sense = Sense.NOUN, meanings = listOf(SavedMeaning("noun", "e"))),
                VocabWord("f", createdAt = now, nextReview = now, meanings = listOf(SavedMeaning("x", "f"))),
                VocabWord("g", createdAt = now, nextReview = now, sense = Sense.NOUN),
                VocabWord("g", createdAt = now, nextReview = now, sense = Sense.NOUN, translation = "duplicate"),
            )
        storage.files["words.json"] = Json.encodeToString(ListSerializer(VocabWord.serializer()), bad)
        val words = library().state.value.words.associateBy { it.key }
        assertNull(words.getValue("a").sense, "unknown sense")
        assertNull(words.getValue("b").form, "not a word")
        assertEquals(WordRange(3, 4), words.getValue("c").formRange)
        val d = words.getValue("d").meanings!!
        assertEquals(listOf("verb", "noun"), d.map { it.kind }, "kinds known, at most two")
        assertEquals("bucear", d[0].meaning, "no invisible formatting")
        assertNull(words.getValue("e#noun").meanings, "a sense has no unsure meanings")
        assertNull(words.getValue("f").meanings)
        assertEquals("", words.getValue("g#noun").translation, "one entry per key")
        assertEquals(
            SavedMeaning.MAX_MEANING,
            SavedMeaning.bounded(listOf(SavedMeaning("noun", "dove", "x".repeat(500))))!![0].meaning.length,
        )
    }

    @Test
    fun `meanings of a word saved unsure are translated in order`() {
        val library = library()
        library.saveWord(
            "left",
            learned = false,
            meanings = listOf(SavedMeaning.of(verb), SavedMeaning.of(noun)),
            translationLanguage = "es",
        )
        assertTrue(library.state.value.word("left")!!.needsTranslation("es"))
        assertEquals(listOf("left"), library.wordsNeedingTranslation("es").map { it.key })
        library.setMeanings("left", listOf("irse, salir", ""), "es")
        library.setMeanings("left", listOf("irse, salir"), "es")
        library.setMeanings("missing", listOf("a", "b"), "es")
        library.saveWord("plain", learned = false)
        library.setMeanings("plain", listOf("a", "b"), "es")
        assertTrue(library.state.value.word("left")!!.needsTranslation("es"))
        library.setMeanings("left", listOf("irse, salir", "izquierda"), "es")
        val saved = library.state.value.word("left")!!
        assertEquals(listOf("irse, salir", "izquierda"), saved.meanings!!.map { it.meaning })
        assertFalse(saved.needsTranslation("es"))
        assertTrue(saved.needsTranslation("fr"))
        library.setTranslation("left", "x", "fr")
        assertEquals("", library.state.value.word("left")!!.translation, "an unsure word has meanings, not a translation")
    }

    @Test
    fun `meanings - queries, the table Spanish and cleaning`() {
        assertEquals("to leave", Meanings.query(verb))
        assertEquals("the left", Meanings.query(noun))
        assertEquals("the left", Meanings.query(SavedMeaning.of(noun)))
        assertEquals(IrregularVerbs.shared.meanings("leave").joinToString(" · "), Meanings.fromTable(verb, "es"))
        assertEquals(IrregularVerbs.shared.meanings("leave").first(), Meanings.fromTable(verb, "es-MX", first = true))
        assertEquals("mentir", Meanings.fromTable(Reading(Reading.Kind.VERB, "lie", Reading.Step.CHOSEN), "es", homonym = true))
        assertNull(Meanings.fromTable(verb, "fr"))
        assertNull(Meanings.fromTable(verb, null))
        assertNull(Meanings.fromTable(noun, "es"))
        assertNull(Meanings.fromTable(Reading(Reading.Kind.VERB, "zzz", Reading.Step.CHOSEN), "es"))
        assertEquals("izquierda", Meanings.clean("la izquierda.", noun = true, language = "es"))
        assertEquals("arbre", Meanings.clean("l'arbre", noun = true, language = "fr"))
        assertEquals("lesson", Meanings.clean("lesson", noun = true, language = "fr"), "not an article")
        assertEquals("verlassen", Meanings.clean("zu verlassen", noun = false, language = "de"))
        assertEquals("partir", Meanings.clean("to partir", noun = false, language = "pt"))
        assertEquals("左", Meanings.clean("the 左", noun = true, language = "zh"))
        assertEquals("la", Meanings.clean("la", noun = true, language = "es"), "never empty")
        assertEquals("the", Meanings.clean("the ", noun = true, language = null))
    }

    @Test
    fun `corrected words follow their sentence, once, with Undo`() {
        val now = "2026-10-01T09:00"
        val old =
            listOf(
                VocabWord(
                    "leave",
                    translation = "irse",
                    context = "Turn left at the bank.",
                    box = 3,
                    createdAt = now,
                    nextReview = "2026-12-01T04:00",
                ),
                VocabWord("see", translation = "ver", context = "Wind is strong, I saw.", createdAt = now, nextReview = now),
                VocabWord(
                    "leaf",
                    translation = "hoja",
                    context = "Double leaves in fiction are common.",
                    createdAt = now,
                    nextReview = now,
                    learned = true,
                ),
                VocabWord("rise", context = "Aunt Rose came.", createdAt = now, nextReview = now),
                VocabWord("live", context = "Tom lives in a house.", createdAt = now, nextReview = now),
                VocabWord("left", context = "Turn left.", createdAt = now, nextReview = now),
                VocabWord("leave", sense = Sense.NOUN, context = "Turn left.", createdAt = now, nextReview = now),
                VocabWord("feel", context = "I feel it; she felt it.", createdAt = now, nextReview = now),
                VocabWord("did not go", context = "He did not go.", createdAt = now, nextReview = now, kind = FunctionWord.Kind.HELPER),
                VocabWord("find", context = "", createdAt = now, nextReview = now),
                VocabWord("find", context = "Found it.", createdAt = now, nextReview = now, sense = null, translation = "x"),
            )
        storage.files["words.json"] = Json.encodeToString(ListSerializer(VocabWord.serializer()), old.distinctBy { it.key })
        val library = library()
        library.correctWordsOnce()
        val state = library.state.value
        val byKey = state.words.associateBy { it.key }
        // "leave" from "Turn left" → "left", the noun; due now, translated again.
        val left = byKey.getValue("left#noun")
        assertEquals("", left.translation)
        assertEquals("2026-10-09T09:00", left.nextReview)
        assertEquals(0, left.box, "from the first box again")
        assertEquals(WordRange(5, 9), left.formRange)
        // Unsure, its base from the old table → the tapped form with both readings; known stays known.
        val leaves = byKey.getValue("leaves")
        assertTrue(leaves.isUnsure)
        assertTrue(leaves.learned)
        assertEquals(listOf("to leave", "the leaf"), leaves.meanings!!.map { it.label })
        assertEquals("rose", byKey.getValue("rose").word, "a name: the word itself")
        assertNull(byKey["rise"])
        assertNotNull(byKey["live"], "still read as live")
        assertNotNull(byKey["left"], "saved as the form itself: unchanged")
        assertNotNull(byKey["feel"], "another word still leads to it")
        assertNotNull(byKey["did not go"])
        assertNotNull(byKey["see"], "\"saw\" after I is still see")
        val names = state.corrections.map { it.from to it.to }
        assertTrue(("leave" to "left") in names)
        assertTrue(("leaf" to "leaves") in names)
        // Once only.
        storage.files["words.json"] = Json.encodeToString(ListSerializer(VocabWord.serializer()), old.take(1))
        assertEquals(1, library().apply { correctWordsOnce() }.state.value.words.size, "not corrected again")
        // The card survives a restart until closed; Undo puts all back.
        storage.files["words.json"] = Json.encodeToString(ListSerializer(VocabWord.serializer()), state.words)
        val again = library()
        assertEquals(state.corrections.size, again.state.value.corrections.size)
        again.undoCorrections()
        val restored = again.state.value.words.map { it.key }.toSet()
        assertTrue("leave" in restored && "leaf" in restored && "rise" in restored)
        assertFalse("leaves" in restored)
        assertTrue(again.state.value.corrections.isEmpty())
        assertNull(storage.files["corrections.json"])
        again.undoCorrections()
        again.closeCorrections()
    }

    @Test
    fun `a correction that matches a saved entry is merged into it`() {
        val now = "2026-10-01T09:00"
        val old =
            listOf(
                VocabWord("leave", context = "Turn left.", createdAt = now, nextReview = now),
                VocabWord("left", sense = Sense.NOUN, translation = "izquierda", createdAt = now, nextReview = now),
            )
        storage.files["words.json"] = Json.encodeToString(ListSerializer(VocabWord.serializer()), old)
        val library = library()
        library.correctWordsOnce()
        assertEquals(listOf("left#noun"), library.state.value.words.map { it.key })
        assertEquals("izquierda", library.state.value.words.single().translation, "the saved entry stays")
        assertTrue(library.state.value.corrections.single().merged)
        library.closeCorrections()
        assertTrue(library.state.value.corrections.isEmpty())
        assertEquals(1, library().state.value.words.size)
        // Undo after a merge brings the original back and keeps the entry.
        storage.files.remove("corrected-homographs")
        storage.files["words.json"] = Json.encodeToString(ListSerializer(VocabWord.serializer()), old)
        val again = library()
        again.correctWordsOnce()
        again.undoCorrections()
        assertEquals(setOf("leave", "left#noun"), again.state.value.words.map { it.key }.toSet())
    }

    @Test
    fun `nothing to correct leaves no card`() {
        val cat = VocabWord("cat", context = "A cat.", createdAt = "2026-10-01T09:00", nextReview = "2026-10-01T09:00")
        storage.files["words.json"] = Json.encodeToString(ListSerializer(VocabWord.serializer()), listOf(cat))
        val library = library()
        library.correctWordsOnce()
        assertTrue(library.state.value.corrections.isEmpty())
        assertNull(storage.files["corrections.json"])
        assertEquals("done", storage.files["corrected-homographs"])
    }

    @Test
    fun `old links of homographs`() {
        assertTrue("leave" in TextTools.oldCandidates("left"))
        assertTrue("live" in TextTools.oldCandidates("lives"))
        assertTrue("life" in TextTools.oldCandidates("lives"))
        assertEquals(emptySet(), TextTools.oldCandidates("cat"))
    }
}
