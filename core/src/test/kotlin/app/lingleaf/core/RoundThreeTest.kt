package app.lingleaf.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Round 3 review fixes (v4 part 1), with the ethical hacker's cases as tests. */
class RoundThreeTest {
    private val storage = MemoryStorage()
    private val clock = TestClock()

    private fun library() = Library(storage, clock).apply { load() }

    private fun word(
        word: String,
        context: String,
        created: String,
        box: Int = 0,
    ) = VocabWord(word = word, context = context, createdAt = created, nextReview = created, box = box, translation = "x")

    private fun save(words: List<VocabWord>) {
        storage.files["words.json"] = Json.encodeToString(ListSerializer(VocabWord.serializer()), words)
    }

    @Test
    fun `a word that has its meaning waits for nothing`() {
        save(listOf(word("leave", "Turn left at the bank.", "2026-03-01T00:00", box = 3)))
        val library = library().apply { correctWordsOnce() }
        assertTrue(library.state.value.word("left#noun")!!.waitsForMeaning)
        assertTrue(library.dueWords().isEmpty())
        // Saved again from the card, with its translation.
        library.saveWord("left", learned = false, translation = "izquierda", translationLanguage = "es", sense = Sense.NOUN)
        val saved = library.state.value.word("left#noun")!!
        assertFalse(saved.waitsForMeaning)
        assertEquals(listOf("left#noun"), library.dueWords().map { it.key })
        // A restored file can't keep a word with a meaning waiting.
        val restored = MemoryStorage()
        restored.files["corrected-homographs"] = "done"
        restored.files["words.json"] =
            """[{"word":"cat","translation":"gato","translationLanguage":"es","createdAt":"2026-01-01T00:00",""" +
            """"nextReview":"2026-01-01T00:00","waitsForMeaning":true}]"""
        val other = Library(restored, clock).apply { load() }
        assertFalse(other.state.value.words.single().waitsForMeaning)
        assertEquals(1, other.dueWords().size)
    }

    @Test
    fun `ties at a batch's end are read on the next run`() {
        val same = "2026-01-01T00:00"
        save((0 until Library.MAX_CORRECTED_PER_RUN).map { word("w$it", "A cat.", same) } + word("leave", "Turn left at the bank.", same))
        val library = library()
        library.correctWordsOnce()
        assertTrue(storage.files["corrected-homographs"]!!.startsWith("after:"))
        library.correctWordsOnce()
        assertEquals("done", storage.files["corrected-homographs"])
        assertNotNull(library.state.value.word("left#noun"), "the tied word was read")
        assertNull(library.state.value.word("leave"))
    }

    @Test
    fun `an unsorted file is read newest first, so nothing is skipped`() {
        save(
            (0 until Library.MAX_CORRECTED_PER_RUN).map {
                word("w$it", "A cat.", "2020-01-01T00:00")
            } + word("leave", "Turn left at the bank.", "2026-01-01T00:00"),
        )
        val library = library()
        repeat(3) { library.correctWordsOnce() }
        assertEquals("done", storage.files["corrected-homographs"])
        assertNotNull(library.state.value.word("left#noun"))
    }

    @Test
    fun `a hostile flag starts over`() {
        for (flag in listOf("after:", "after:zzzz", "after:" + "9".repeat(100_000), "after:2026-01-01T00:00\t", "garbage")) {
            storage.files.clear()
            storage.files["corrected-homographs"] = flag
            save(listOf(word("leave", "Turn left at the bank.", "2026-01-01T00:00")))
            val library = library()
            library.correctWordsOnce()
            assertEquals("done", storage.files["corrected-homographs"], flag.take(12))
            assertNotNull(library.state.value.word("left#noun"), flag.take(12))
        }
    }

    @Test
    fun `each run corrects at most what Undo can hold`() {
        val date = "2026-01-01T00:00"
        val record = """{"from":"a","to":"b","key":"b","merged":true,"original":{"word":"a","createdAt":"$date","nextReview":"$date"}}"""
        storage.files["corrections.json"] =
            (1 until Library.MAX_CORRECTIONS).joinToString(",", "[", "]") { record.replace("\"a\"", "\"a$it\"") }
        save(listOf(word("leave", "Turn left at the bank.", "2026-02-01T00:00"), word("feel", "The felt hat.", "2026-01-01T00:00")))
        val library = library()
        library.correctWordsOnce()
        assertEquals(Library.MAX_CORRECTIONS, library.state.value.corrections.size)
        assertNotNull(library.state.value.word("left#noun"))
        assertNotNull(library.state.value.word("feel"), "no room left to undo it: next run")
        assertTrue(storage.files["corrected-homographs"]!!.startsWith("after:"))
        library.closeCorrections()
        library.correctWordsOnce()
        assertNotNull(library.state.value.word("felt#noun"))
        assertEquals("done", storage.files["corrected-homographs"])
    }

    @Test
    fun `a word changed while corrections ran is left alone`() {
        save(listOf(word("leave", "Turn left at the bank.", "2026-01-01T00:00"), word("see", "She saw the saw.", "2025-01-01T00:00")))
        val library = library()
        library.beforeApply = { library.answerReview("leave", correct = true) }
        library.correctWordsOnce()
        assertNotNull(library.state.value.word("leave"), "reviewed meanwhile: not corrected")
        assertNull(library.state.value.word("left#noun"))
        assertEquals("done", storage.files["corrected-homographs"])
    }

    @Test
    fun `Undo doesn't bring back a word merged into one deleted since`() {
        save(
            listOf(
                word("leave", "Turn left.", "2026-01-01T00:00"),
                VocabWord(
                    "left",
                    sense = Sense.NOUN,
                    translation = "izquierda",
                    createdAt = "2026-02-01T00:00",
                    nextReview = "2026-02-01T00:00",
                ),
            ),
        )
        val library = library().apply { correctWordsOnce() }
        assertTrue(library.state.value.corrections.single().merged)
        library.deleteWord("left#noun")
        library.undoCorrections()
        assertNull(library.state.value.word("leave"))
        assertTrue(library.state.value.words.isEmpty())
    }

    private fun fixture(name: String) = javaClass.getResource("/dictionary/$name.json")!!.readText()

    @Test
    fun `a verb the table knows the sound of needs an entry with it`() =
        runTest {
            val reversed =
                DictionaryClient {
                        url ->
                    if (url.endsWith(
                            "/tear",
                        )
                    ) {
                        HttpResponse(200, fixture("tear-reversed").toByteArray())
                    } else {
                        HttpResponse(404, ByteArray(0))
                    }
                }
            val tear = reversed.lookup("tore", Reading(Reading.Kind.VERB, "tear", Reading.Step.TABLE))!!
            assertTrue(tear.definition!!.startsWith("To rend"), "whatever the entries' order")
            // An entry list without that pronunciation: no definition (nothing proves which it is).
            val wrong = fixture("tear").replace("/tɛɹ/", "/tɛə/")
            val noMatch = DictionaryClient { HttpResponse(200, wrong.toByteArray()) }
            assertNull(noMatch.lookup("tore", Reading(Reading.Kind.VERB, "tear", Reading.Step.TABLE)))
            assertEquals("tɛr", IrregularVerbs.shared.ipa("tear", "base"), "the card shows the table's IPA instead")
        }

    @Test
    fun `Review's prompt names two senses without their meaning`() {
        storage.files["settings.json"] = """{"targetLanguage": "es"}"""
        val library = library()
        library.saveWord("lie", learned = false, sense = Sense.VERB)
        library.saveWord("lie", learned = false, sense = Sense.HOMONYM)
        val state = library.state.value
        val twin = state.word("lie#homonym")!!
        assertEquals("to lie", state.displayName(twin, withMeaning = false))
        assertEquals("to lie (mentir)", state.displayName(twin))
    }

    @Test
    fun `Review and Words say a word saved unsure in each reading`() {
        val verbs = IrregularVerbs.shared
        val wound =
            VocabWord(
                "wound",
                createdAt = "2026-01-01T00:00",
                nextReview = "2026-01-01T00:00",
                meanings = listOf(SavedMeaning(Sense.NOUN, "wound"), SavedMeaning(Sense.VERB, "wind")),
            )
        assertEquals("wound, wownd", verbs.spokenSaved(wound))
        val left = wound.copy(word = "left", meanings = listOf(SavedMeaning(Sense.VERB, "leave"), SavedMeaning(Sense.NOUN, "left")))
        assertEquals("left", verbs.spokenSaved(left), "once when they sound the same")
        val lying = wound.copy(word = "lying", meanings = listOf(SavedMeaning(Sense.VERB, "lie"), SavedMeaning(Sense.HOMONYM, "lie")))
        assertEquals("lying", verbs.spokenSaved(lying))
        assertEquals("tare", verbs.spokenSaved(VocabWord("tear", sense = Sense.VERB, createdAt = "", nextReview = "")))
        assertEquals("tear", verbs.spokenSaved(VocabWord("tear", createdAt = "", nextReview = "")))
    }

    @Test
    fun `a spelling that is also another word is always named by its sense`() {
        val library = library()
        library.saveWord("left", learned = false, sense = Sense.NOUN)
        library.saveWord("lie", learned = false, sense = Sense.HOMONYM)
        library.saveWord("light", learned = false, sense = Sense.VERB)
        library.saveWord("leave", learned = false, sense = Sense.VERB)
        library.saveWord("wind", learned = false)
        val state = library.state.value
        assertEquals("the left", state.displayName(state.word("left#noun")!!), "a homograph")
        assertEquals("to lie", state.displayName(state.word("lie#homonym")!!), "a verb with a twin")
        assertEquals("to light", state.displayName(state.word("light#verb")!!), "mostly a noun")
        assertEquals("leave", state.displayName(state.word("leave#verb")!!), "only a verb: its spelling")
        assertEquals("wind", state.displayName(state.word("wind")!!), "saved without a sense: as before")
        assertTrue(IrregularVerbs.shared.namesItsSense("Wind"))
        assertFalse(IrregularVerbs.shared.namesItsSense("cat"))
    }
}
