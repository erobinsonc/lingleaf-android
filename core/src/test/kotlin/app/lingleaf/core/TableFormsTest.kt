package app.lingleaf.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** v3: the table's forms that are not homographs, the twin's sense, Listen, look-ups and corrections. */
class TableFormsTest {
    private val verbs = IrregularVerbs.shared
    private val storage = MemoryStorage()
    private val clock = TestClock()

    private fun library() = Library(storage, clock).apply { load() }

    private fun read(
        sentence: String,
        word: String,
    ) = verbs.resolve(sentence, sentence.rangeOf(word))

    private fun reading(
        kind: Reading.Kind,
        base: String,
        step: Reading.Step,
    ) = Reading(kind, base, step)

    @Test
    fun `a past or participle of the table is its verb, the twin's own forms the twin`() {
        assertEquals(reading(Reading.Kind.VERB, "tear", Reading.Step.TABLE), read("She tore the page.", "tore"))
        assertEquals(reading(Reading.Kind.VERB, "tear", Reading.Step.TABLE), read("The page was torn.", "torn"))
        assertEquals(reading(Reading.Kind.VERB, "light", Reading.Step.TABLE), read("A lit candle.", "lit"))
        assertEquals(reading(Reading.Kind.HOMONYM, "lie", Reading.Step.TABLE), read("He lied to us.", "lied"))
        assertEquals(reading(Reading.Kind.HOMONYM, "hang", Reading.Step.TABLE), read("They hanged him.", "hanged"))
        assertEquals(reading(Reading.Kind.NOUN, "drew", Reading.Step.NAME), read("We met Uncle Drew.", "Drew"), "a name")
        assertNull(read("A red cat.", "cat"))
        assertEquals(listOf("lie"), TextTools.baseFormCandidates("He lied.", "He lied.".rangeOf("lied")))
        assertEquals(Sense.HOMONYM, Sense.of(read("He lied to us.", "lied")))
        assertNull(Sense.of(read("We met Uncle Drew.", "Drew")), "a name has no sense")
        assertEquals(IrregularVerbs.shared.meanings("lie", homonym = true).first(), Meanings.fromTable(read("He lied.", "lied")!!, "es"))
        assertEquals("to tell a lie", Meanings.query(read("He lied.", "lied")!!), "the twin's own English query")
    }

    @Test
    fun `an -s or -ing form is a verb only where a step proves it`() {
        val verb = Reading.Kind.VERB
        assertEquals(reading(verb, "light", Reading.Step.S_SUBJECT), read("She lights the fire.", "lights"))
        assertEquals(reading(Reading.Kind.WORD, "lights", Reading.Step.ORDINARY), read("The lights went out.", "lights"))
        assertEquals(reading(verb, "say", Reading.Step.QUOTE), read("\"Look!\" says Tom.", "says"))
        assertEquals(reading(verb, "meet", Reading.Step.ING), read("She is meeting him.", "meeting"))
        assertEquals(reading(Reading.Kind.WORD, "meeting", Reading.Step.ORDINARY), read("A meeting began.", "meeting"))
        assertEquals(reading(verb, "meet", Reading.Step.ING), read("Meeting the teacher, she smiled.", "Meeting"))
        assertEquals(listOf("light"), TextTools.baseFormCandidates("The lights went out.", "The lights went out.".rangeOf("lights")))
        assertEquals(emptyList(), TextTools.baseFormCandidates("A meeting began.", "A meeting began.".rangeOf("meeting")))
        assertNull(Sense.of(read("The lights went out.", "lights")), "an ordinary word has no sense")
        // An ordinary word has no sense: it marks no word saved with one, verb or noun.
        val library = library()
        library.saveWord("light", learned = false, sense = Sense.VERB)
        assertFalse(library.state.value.isLearning("The lights went out.", "The lights went out.".rangeOf("lights")))
        library.saveWord("light", learned = false, sense = Sense.NOUN)
        assertFalse(library.state.value.isLearning("The lights went out.", "The lights went out.".rangeOf("lights")), "nor a noun")
        library.saveWord("light", learned = false)
        assertTrue(
            library.state.value.isLearning("The lights went out.", "The lights went out.".rangeOf("lights")),
            "a word without a sense: as before",
        )
    }

    @Test
    fun `lying and hanging take their sense from the next word, else both`() {
        assertEquals(reading(Reading.Kind.HOMONYM, "lie", Reading.Step.SENSE), read("He is lying to me.", "lying"))
        assertEquals(reading(Reading.Kind.VERB, "lie", Reading.Step.SENSE), read("He is lying on the bed.", "lying"))
        assertEquals(reading(Reading.Kind.HOMONYM, "hang", Reading.Step.SENSE), read("He is hanging himself.", "hanging"))
        assertEquals(Reading.Kind.UNSURE, read("He was lying.", "lying")?.kind)
        val text = "He was lying."
        val words = TextTools.wordRanges(text)
        val choices = verbs.choices(text, words, words.indexOf(text.rangeOf("lying")))
        assertEquals(listOf(Reading.Kind.VERB to "lie", Reading.Kind.HOMONYM to "lie"), choices.map { it.kind to it.base })
        assertEquals(listOf("to lie", "to lie"), choices.map { SavedMeaning.of(it).label })
        assertEquals(listOf(Sense.VERB, Sense.HOMONYM), choices.map { SavedMeaning.of(it).kind })
        assertEquals(emptyList(), verbs.choices("He is lying to me.", TextTools.wordRanges("He is lying to me."), 2), "certain")
        assertEquals(
            "mentir",
            Meanings.fromTable(Reading(Reading.Kind.HOMONYM, "lie", Reading.Step.CHOSEN), "es", first = true),
        )
    }

    @Test
    fun `the bundled stories leave nothing unsure and the lights are lights`() {
        val books = File("../app/src/main/assets/books").listFiles { f -> f.name.endsWith(".txt") }.orEmpty()
        assertEquals(3, books.size, "the bundled stories")
        val unsure = mutableListOf<String>()
        for (book in books) {
            for (paragraph in TextTools.splitParagraphs(book.readText())) {
                val words = TextTools.wordRanges(paragraph)
                for (index in words.indices) {
                    val reading = verbs.resolve(paragraph, words, index) ?: continue
                    val w = words[index]
                    if (reading.kind == Reading.Kind.UNSURE) unsure += "${book.name}: ${paragraph.substring(w.start, w.end)}"
                    if (paragraph.substring(w.start, w.end) == "lights") {
                        assertEquals(
                            Reading.Kind.WORD,
                            reading.kind,
                            "the lights that guide",
                        )
                    }
                }
            }
        }
        assertEquals(emptyList(), unsure)
    }

    @Test
    fun `Listen says the headword then the form, each in its slot`() {
        fun listen(
            headword: String,
            word: String,
            text: String,
            chosen: Reading? = null,
        ) = verbs.listenText(headword, text, text.rangeOf(word), chosen)
        assertEquals("wined, wownd", listen("wind", "wound", "It wound around the tree."))
        assertEquals("wound", listen("wound", "wound", "The wound healed."))
        assertEquals("dive, dohv", listen("dive", "dove", "She dove into the lake."))
        assertEquals("steal, stolen", listen("steal", "stolen", "It was stolen."))
        assertEquals("tare, tore", listen("tear", "tore", "She tore the page."))
        assertEquals("red", listen("read", "read", "She had read it."))
        assertEquals("animal, animals", listen("animal", "animals", "All animals are equal."))
        assertEquals("Wound, wownd", listen("Wound", "Wound", "Wound is a word."), "unsure: each reading, rows' order")
        assertEquals("Left", listen("Left", "Left", "Left is the answer."), "once when they sound the same")
        val text = "Wound is a word."
        val verb = verbs.choices(text, TextTools.wordRanges(text), 0)[1]
        assertEquals("wownd", listen("Wound", "Wound", text, verb), "only the chosen one")
        assertEquals("x", verbs.listenText("x", "cat", WordRange(0, 9)))
        assertEquals("x, ca", verbs.listenText("x", "cat", WordRange(0, 2)), "not a word of the text: as written")
    }

    @Test
    fun `Review and Words say a verb with its base's spoken form, a noun as written`() {
        assertEquals("wined", verbs.spokenHeadword("wind", Sense.VERB))
        assertEquals("wind", verbs.spokenHeadword("wind", Sense.NOUN))
        assertEquals("tare", verbs.spokenHeadword("tear", Sense.VERB))
        assertEquals("tear", verbs.spokenHeadword("tear", null))
        assertEquals("so", verbs.spokenHeadword("sow", Sense.VERB))
        assertEquals("lie", verbs.spokenHeadword("lie", Sense.HOMONYM))
    }

    private fun fixture(name: String) = javaClass.getResource("/dictionary/$name.json")!!.readText()

    private val dictionary =
        DictionaryClient { url ->
            val word = url.substringAfterLast('/')
            if (word in setOf("wind", "tear", "lead", "lie", "bear")) {
                HttpResponse(200, fixture(word).toByteArray())
            } else {
                HttpResponse(404, ByteArray(0))
            }
        }

    @Test
    fun `a reading takes the entry whose pronunciation is the table's, and its part of speech`() =
        runTest {
            val wind = dictionary.lookup("wound", Reading(Reading.Kind.VERB, "wind", Reading.Step.SUBJECT))!!
            assertTrue(wind.definition!!.startsWith("To turn coils"), "/waɪnd/, not /wɪnd/")
            assertEquals("/waɪnd/", wind.phonetic)
            assertTrue(
                dictionary.lookup(
                    "wind",
                    Reading(Reading.Kind.NOUN, "wind", Reading.Step.NOUN),
                    nounIpa = "wɪnd",
                )!!.definition!!.startsWith("Real or perceived"),
            )
            val tear = dictionary.lookup("tore", Reading(Reading.Kind.VERB, "tear", Reading.Step.TABLE))!!
            assertTrue(tear.definition!!.startsWith("To rend"), "/tɛɹ/ is the table's tɛr")
            assertTrue(
                dictionary.lookup("led", Reading(Reading.Kind.VERB, "lead", Reading.Step.TABLE))!!.definition!!.startsWith("To guide"),
            )
            val lie = dictionary.lookup("lay", Reading(Reading.Kind.VERB, "lie", Reading.Step.SUBJECT))!!
            assertTrue(lie.definition!!.startsWith("To rest"))
            assertNull(dictionary.lookup("lied", Reading(Reading.Kind.HOMONYM, "lie", Reading.Step.TABLE)), "the twin: none")
            assertTrue(
                dictionary.lookup("bore", Reading(Reading.Kind.VERB, "bear", Reading.Step.SUBJECT))!!.definition!!.startsWith("To carry"),
            )
            assertTrue(
                dictionary.lookup(
                    "bear",
                    Reading(Reading.Kind.NOUN, "bear", Reading.Step.NOUN),
                )!!.definition!!.startsWith("A large mammal"),
            )
            assertTrue(DictionaryEntry.sameIpa("/ˈwaɪnd/", "waɪnd"))
            assertFalse(DictionaryEntry.sameIpa("", ""))
            // An ordinary word or a name is looked up as tapped.
            assertEquals("bear", dictionary.lookup("bear", Reading(Reading.Kind.WORD, "bear", Reading.Step.ORDINARY))?.word)
        }

    private fun save(words: List<VocabWord>) {
        storage.files["words.json"] = Json.encodeToString(ListSerializer(VocabWord.serializer()), words)
    }

    private fun word(
        word: String,
        context: String,
        created: String,
        box: Int = 0,
        learned: Boolean = false,
    ) = VocabWord(
        word = word,
        context = context,
        createdAt = created,
        nextReview = created,
        box = box,
        learned = learned,
        translation = "x",
    )

    @Test
    fun `Undo puts back both words whatever the order (ethical hacker's case)`() {
        for (contextA in listOf("Nothing left.", "Left.", "What is left?", "Is any left?", "Some left.", "Those left.")) {
            storage.files.clear()
            val b = word("left", "She left early.", "2026-02-01T00:00")
            val a = word("leave", contextA, "2026-01-01T00:00")
            save(listOf(b, a))
            val library = library()
            library.correctWordsOnce()
            library.undoCorrections()
            val keys = library.state.value.words.map { it.key }.toSet()
            assertEquals(setOf("left", "leave"), keys, contextA)
            assertEquals(listOf(b, a).toSet(), library.state.value.words.toSet(), "each as it was: $contextA")
        }
    }

    @Test
    fun `Undo leaves what changed since and doesn't bring back what was deleted`() {
        save(
            listOf(
                word("leave", "Turn left at the bank.", "2026-03-01T00:00", box = 3),
                word("see", "She saw the saw.", "2026-02-01T00:00"),
                word("feel", "The felt hat.", "2026-01-01T00:00"),
                word("cat", "A cat sat.", "2025-12-01T00:00"),
            ),
        )
        val library = library()
        library.correctWordsOnce()
        val keys = library.state.value.words.map { it.key }
        assertTrue("left#noun" in keys && "felt#noun" in keys, "$keys")
        library.answerReview("left#noun", correct = true) // changed after the correction
        library.deleteWord("felt#noun") // deleted since
        library.answerReview("cat", correct = true) // an uncorrected word
        val cat = library.state.value.word("cat")
        library.undoCorrections()
        val after = library.state.value.words.associateBy { it.key }
        assertNotNull(after["left#noun"], "reviewed since: stays")
        assertNull(after["leave"], "its original isn't forced back beside it")
        assertNull(after["feel"], "deleted since: not brought back")
        assertEquals(cat, after["cat"], "review progress on other words survives")
    }

    @Test
    fun `a corrected word starts its review again once it has a meaning`() {
        save(
            listOf(
                word("leave", "Turn left at the bank.", "2026-03-01T00:00", box = 3).copy(nextReview = "2026-12-01T04:00", source = "Book"),
                word("leaf", "The wind leaves.", "2026-02-01T00:00", learned = true),
            ),
        )
        val library = library()
        library.correctWordsOnce()
        val left = library.state.value.word("left#noun")!!
        assertEquals(0, left.box)
        assertEquals("2026-10-09T09:00", left.nextReview)
        assertEquals("2026-03-01T00:00", left.createdAt)
        assertEquals("Book", left.source)
        assertTrue(left.waitsForMeaning)
        assertTrue(library.dueWords().isEmpty(), "no meaning yet: not due")
        clock.instant = clock.instant.plusSeconds(60)
        library.setTranslation("left#noun", "izquierda", "es")
        val ready = library.state.value.word("left#noun")!!
        assertFalse(ready.waitsForMeaning)
        assertEquals("2026-10-09T09:00", ready.nextReview, "due since the correction")
        assertEquals(listOf("left#noun"), library.dueWords().map { it.key })
        library.undoCorrections()
        assertEquals(3, library.state.value.word("leave")?.box, "a translation arriving doesn't block Undo")
        assertNull(library.state.value.word("left#noun"))
    }

    @Test
    fun `Undo right after the correction restores the original box`() {
        save(listOf(word("leave", "Turn left at the bank.", "2026-03-01T00:00", box = 3)))
        val library = library()
        library.correctWordsOnce()
        assertEquals(0, library.state.value.word("left#noun")!!.box)
        library.undoCorrections()
        assertEquals(3, library.state.value.word("leave")!!.box)
        assertNull(library.state.value.word("left#noun"))
    }

    @Test
    fun `in Spanish a corrected verb has the table's meaning and IPA at once`() {
        storage.files["settings.json"] = """{"targetLanguage": "es"}"""
        save(listOf(word("left", "They wind the clock and it wound down.", "2026-03-01T00:00").copy(word = "wound")))
        val library = library()
        library.correctWordsOnce()
        val wind = library.state.value.words.single()
        assertEquals("wind#verb", wind.key)
        assertEquals(IrregularVerbs.shared.meanings("wind").joinToString(" · "), wind.translation)
        assertEquals("/waɪnd/", wind.phonetic)
        assertFalse(wind.waitsForMeaning)
        assertEquals(listOf("wind#verb"), library.dueWords().map { it.key })
    }

    @Test
    fun `the base both readings share stays, as on iOS`() {
        save(listOf(word("tear", "Tears fell.", "2026-03-01T00:00")))
        val library = library()
        library.correctWordsOnce()
        assertEquals(listOf("tear"), library.state.value.words.map { it.key })
        assertTrue(library.state.value.corrections.isEmpty())
    }

    @Test
    fun `corrections need the table and run in batches`() {
        save(listOf(word("leave", "Turn left.", "2026-03-01T00:00")))
        val refused = library().apply { tableReady = { false } }
        refused.correctWordsOnce()
        assertNull(storage.files["corrected-homographs"], "no table: not marked done")
        assertEquals(listOf("leave"), refused.state.value.words.map { it.key })
        // Over the batch size: the rest on the next run.
        val many =
            (0 until Library.MAX_CORRECTED_PER_RUN + 1).map {
                word(
                    "w$it",
                    "A cat.",
                    "2026-01-01T00:00:${it.toString().padStart(5, '0')}",
                )
            }
        save(many + word("leave", "Turn left.", "2025-01-01T00:00"))
        val library = library()
        library.correctWordsOnce()
        assertTrue(storage.files["corrected-homographs"]!!.startsWith("after:"))
        assertNotNull(library.state.value.word("leave"), "not reached yet")
        library.correctWordsOnce()
        assertEquals("done", storage.files["corrected-homographs"])
        assertNotNull(library.state.value.word("left#noun"))
        library.correctWordsOnce()
    }

    @Test
    fun `a hostile corrections file is bounded on load`() {
        storage.files["corrected-homographs"] = "done"
        storage.files["words.json"] = "[]"
        val big = "x".repeat(100_000)
        storage.files["corrections.json"] =
            """[{"from":"‮evil","to":"$big","key":"$big","merged":false,"original":{"word":"Zz‮","context":"$big",""" +
            """"createdAt":"nope","nextReview":"nope","sense":"bogus","formStart":-5,"formEnd":99999999,"form":"x","meanings":""" +
            """[{"kind":"verb","base":"‮$big"},{"kind":"verb","base":"a"},{"kind":"noun","base":"b"},{"kind":"verb","base":"c"}]},""" +
            """"result":{"word":"y","createdAt":"nope","nextReview":"nope"}}]"""
        val library = library()
        val item = library.state.value.corrections.single()
        assertFalse('‮' in item.from)
        assertTrue(item.to.length <= Correction.MAX_NAME)
        assertTrue(item.key.length <= 100)
        assertTrue(item.original.context.length <= TextTools.MAX_SENTENCE)
        assertNull(item.original.sense)
        assertNull(item.original.formRange)
        assertEquals(listOf("a", "b"), item.original.meanings!!.map { it.base })
        library.dueWords()
        library.undoCorrections()
        library.dueWords()
        val words = library.state.value.words
        assertTrue(words.all { it.context.length <= TextTools.MAX_SENTENCE })
        assertTrue(words.isEmpty(), "a record that can't be checked restores nothing")
        // At most MAX_CORRECTIONS records are read.
        val date = "2026-01-01T00:00"
        val record = """{"from":"a","to":"b","key":"b","merged":true,"original":{"word":"a","createdAt":"$date","nextReview":"$date"}}"""
        val records = (1..(Library.MAX_CORRECTIONS + 5)).map { record.replace("\"a\"", "\"a$it\"") }
        storage.files["corrections.json"] = records.joinToString(",", "[", "]")
        assertEquals(Library.MAX_CORRECTIONS, library().state.value.corrections.size)
    }

    @Test
    fun `a word saved before senses becomes the sense it is saved as`() {
        val library = library()
        library.saveWord("leave", learned = false, translation = "irse")
        assertEquals("leave", library.state.value.saved("leave", Sense.VERB)?.key, "found as the same word")
        assertNull(library.state.value.saved("leave", null)?.sense)
        assertNull(library.state.value.saved("zzz", Sense.VERB))
        val previous = library.saveWord("leave", learned = true, sense = Sense.VERB)
        assertEquals(listOf("leave#verb"), library.state.value.words.map { it.key })
        assertEquals("irse", library.state.value.words.single().translation)
        library.restoreWord("leave#verb", previous)
        assertEquals(listOf("leave"), library.state.value.words.map { it.key }, "Undo puts it back as it was")
    }

    @Test
    fun `two verb senses are named by their first meaning`() {
        storage.files["settings.json"] = """{"targetLanguage": "es"}"""
        val library = library()
        library.saveWord("lie", learned = false, sense = Sense.VERB)
        library.saveWord("lie", learned = false, sense = Sense.HOMONYM)
        val state = library.state.value
        assertEquals("to lie (${verbs.meanings("lie").first()})", state.displayName(state.word("lie#verb")!!))
        assertEquals("to lie (mentir)", state.displayName(state.word("lie#homonym")!!))
        library.updateSettings { it.copy(targetLanguage = "fr") }
        library.setTranslation("lie#homonym", "mentir · tromper", "fr")
        val fr = library.state.value
        assertEquals("to lie (mentir)", fr.displayName(fr.word("lie#homonym")!!), "the translated meaning's first group")
        assertEquals("to lie", fr.displayName(fr.word("lie#verb")!!), "no meaning yet")
    }

    @Test
    fun `stored forms and meanings keep to the shared limits`() {
        val now = "2026-10-09T09:00"
        val long = "a".repeat(41)
        val ok = "a".repeat(40)
        val w = VocabWord("a", createdAt = now, nextReview = now, context = "$long $ok", form = long, formStart = 0, formEnd = 41)
        assertNull(w.bounded().form, "over 40 units")
        assertEquals(ok, w.copy(form = ok, formStart = 42, formEnd = 82).bounded().form)
        val twin = SavedMeaning.bounded(listOf(SavedMeaning(Sense.HOMONYM, "lie", "mentir")))!!
        assertEquals("to lie", twin.single().label)
    }

    @Test
    fun `the v3 fields of the table are checked`() {
        val bundled = javaClass.getResourceAsStream("/irregular-verbs.json")!!.use { it.readBytes() }.toString(Charsets.UTF_8)
        val table = Json.parseToJsonElement(bundled).jsonObject

        fun refused(json: JsonObject) = assertTrue(runCatching { IrregularVerbs.load(json.toString().toByteArray()) }.isFailure)

        fun withLie(change: (JsonObject) -> JsonObject): JsonObject {
            val list =
                table["verbs"]!!.jsonArray.map {
                    val v = it.jsonObject
                    if (v["base"]!!.jsonPrimitive.content == "lie") JsonObject(v + ("homonym" to change(v["homonym"]!!.jsonObject))) else v
                }
            return JsonObject(table + ("verbs" to JsonArray(list)))
        }
        refused(withLie { JsonObject(it + ("homonymBefore" to JsonArray(listOf(JsonPrimitive("To"))))) })
        refused(withLie { JsonObject(it + ("verbBefore" to JsonArray(listOf(JsonPrimitive("on the"))))) })
        refused(withLie { JsonObject(it + ("sense" to JsonPrimitive("x"))) })
        refused(JsonObject(table + ("context" to JsonObject(table["context"]!!.jsonObject - "sSubjects"))))
        refused(JsonObject(table + ("context" to JsonObject(table["context"]!!.jsonObject - "beForms"))))
        refused(JsonObject(table + ("context" to JsonObject(table["context"]!!.jsonObject - "ingAfter"))))
        IrregularVerbs.load(bundled.toByteArray())
    }
}
