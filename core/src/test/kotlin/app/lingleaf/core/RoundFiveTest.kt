package app.lingleaf.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** v5: the review round's last fixes, with the ethical hacker's cases as tests. */
class RoundFiveTest {
    private val storage = MemoryStorage()

    private fun library() = Library(storage, TestClock()).apply { load() }

    private fun word(
        word: String,
        context: String,
        created: String,
    ) = VocabWord(word = word, context = context, createdAt = created, nextReview = created, translation = "x")

    private fun save(words: List<VocabWord>) {
        storage.files["words.json"] = Json.encodeToString(ListSerializer(VocabWord.serializer()), words)
    }

    private val table: JsonObject =
        Json.parseToJsonElement(javaClass.getResource("/irregular-verbs.json")!!.readText()).jsonObject

    private fun JsonObject.with(
        key: String,
        value: JsonElement,
    ) = JsonObject(this + (key to value))

    /** The table with the verb "lie" changed by [change]. */
    private fun withLie(change: (JsonObject) -> JsonObject) =
        table.with(
            "verbs",
            JsonArray(
                table["verbs"]!!.jsonArray.map {
                    if (it.jsonObject["base"]!!.jsonPrimitive.content == "lie") {
                        change(
                            it.jsonObject,
                        )
                    } else {
                        it
                    }
                },
            ),
        )

    /** The table with the first expression of "bound" given the Spanish [es]. */
    private fun withBoundExpression(es: String) =
        table.with(
            "homographs",
            JsonArray(
                table["homographs"]!!.jsonArray.map { h ->
                    if (h.jsonObject["form"]!!.jsonPrimitive.content != "bound") {
                        h
                    } else {
                        val expressions = h.jsonObject["expressions"]!!.jsonArray
                        val first = expressions[0].jsonObject.with("es", JsonArray(listOf(JsonPrimitive(es))))
                        h.jsonObject.with("expressions", JsonArray(listOf(first) + expressions.drop(1)))
                    }
                },
            ),
        )

    private fun loads(json: JsonObject) = runCatching { IrregularVerbs.load(json.toString().toByteArray()) }.isSuccess

    @Test
    fun `the table's Spanish meanings refuse invisible and control characters`() {
        assertTrue(loads(table), "the bundled table loads")
        val hostile =
            mapOf(
                "right-to-left override" to "estar‮evil",
                "zero-width space" to "est​ar",
                "newline" to "estar\nPAGA AQUI",
                "NUL" to "est\u0000ar",
                "bidi isolate" to "a⁧b",
            )
        for ((name, bad) in hostile) {
            assertFalse(loads(withLie { it.with("sEs", JsonPrimitive(bad)) }), "sEs with a $name")
            assertFalse(
                loads(withLie { it.with("homonym", it["homonym"]!!.jsonObject.with("nounEs", JsonArray(listOf(JsonPrimitive(bad))))) }),
                "nounEs with a $name",
            )
            assertFalse(loads(withBoundExpression(bad)), "an expression's es with a $name")
        }
        // Length in code points: 100 letters outside the BMP are one hundred characters, not two hundred.
        val wide = "𝐀".repeat(100)
        assertTrue(loads(withLie { it.with("sEs", JsonPrimitive(wide)) }), "100 code points")
        assertFalse(loads(withLie { it.with("sEs", JsonPrimitive(wide + "a")) }), "101 code points")
    }

    @Test
    fun `a long saved word can't stall the corrections cursor`() {
        // A full run ends on a word with a 250-character key: the next run must read past it.
        val filler = (0 until Library.MAX_CORRECTED_PER_RUN - 1).map { word("w$it", "A cat.", "2026-01-01T00:00") }
        val long = word("z".repeat(250), "A cat.", "2025-06-01T00:00")
        val target = word("leave", "Turn left at the bank.", "2020-01-01T00:00")
        save(filler + long + target)
        val library = library()
        library.correctWordsOnce()
        assertTrue(storage.files["corrected-homographs"]!!.startsWith("after:"))
        library.correctWordsOnce()
        assertNotNull(library.state.value.word("left#noun"), "the second run reads the rest")
        assertEquals("done", storage.files["corrected-homographs"])
    }

    @Test
    fun `saved words are bounded on save and on load, so every key fits the cursor`() {
        val library = library()
        library.saveWord("a".repeat(VocabWord.MAX_WORD + 100), learned = false)
        assertEquals(VocabWord.MAX_WORD, library.state.value.words.single().word.length)
        save(listOf(word("b".repeat(VocabWord.MAX_WORD + 100), "", "2026-01-01T00:00")))
        val loaded = library().state.value.words.single()
        assertEquals(VocabWord.MAX_WORD, loaded.word.length)
        assertTrue(VocabWord.keyOf(loaded.word, Sense.HOMONYM).length <= VocabWord.MAX_KEY)
    }

    @Test
    fun `a later translation of an unsure word reads its rows as the card did`() =
        runTest {
            val library = library()
            val rows =
                listOf(
                    SavedMeaning(kind = Sense.NOUN, base = "lie"),
                    SavedMeaning(kind = Sense.VERB, base = "lie"),
                )
            library.saveWord("lies", learned = false, context = "The lies were everywhere.", meanings = rows)
            val saved = library.state.value.word("lies")!!
            val spanish = Meanings.unsureMeanings(saved) { Meanings.fromTable(it, "es", first = true) }
            // "lies" is the verb's -s form: its own meaning, not the base's first group.
            assertEquals(listOf("mentira", "estar, encontrarse"), spanish)
            library.setMeanings(saved.key, spanish!!, "es")
            assertEquals(listOf("mentira", "estar, encontrarse"), library.state.value.word("lies")!!.meanings!!.map { it.meaning })
            // One row that fails: nothing is written.
            assertNull(Meanings.unsureMeanings(saved) { if (it.kind == Reading.Kind.NOUN) null else "x" })
            assertNull(Meanings.unsureMeanings(word("cat", "A cat.", "2026-01-01T00:00")) { "x" }, "not saved unsure")
        }

    @Test
    fun `a corrected noun of tear gets its sentence's sound`() {
        save(listOf(word("tears", "Her eyes filled with tears.", "2026-01-01T00:00")))
        val library = library().apply { correctWordsOnce() }
        assertEquals("/tɪr/", library.state.value.word("tear#noun")!!.phonetic)
    }

    @Test
    fun `lights with an object after it is the verb`() {
        val text = "The sun lights the room."
        val words = TextTools.wordRanges(text)
        assertEquals(Reading.Kind.VERB, IrregularVerbs.shared.resolve(text, words, 2)?.kind)
    }

    @Test
    fun `a saved expression marks the expression in another book`() {
        val saved =
            VocabWord(
                "bound for",
                context = "The ship was bound for Spain.",
                createdAt = "2026-01-01T00:00",
                nextReview = "2026-01-01T00:00",
            )
        val text = "The train was bound for Paris. He was bound to win, and the books were bound."
        val words = TextTools.wordRanges(text)
        val marked = TextTools.marked(text, words, LearningIndex.of(listOf(saved))).map { text.substring(it.start, it.end) }
        assertEquals(listOf("bound"), marked, "only the bound of bound for")
    }

    @Test
    fun `a dictionary entry without a word is skipped, and parts of speech match in any case`() =
        runTest {
            val answer =
                """[{"meanings": [{"partOfSpeech": "noun", "definitions": [{"definition": "Hostile."}]}]},
                   {"word": "left", "meanings": [{"partOfSpeech": "Noun", "definitions": [{"definition": "The left side."}]}]}]"""
            val client = DictionaryClient { HttpResponse(200, answer.toByteArray()) }
            val entry = client.lookup("left", Reading(Reading.Kind.NOUN, "left", Reading.Step.CHOSEN))
            assertEquals("The left side.", entry?.definition)
        }

    private fun listen(
        text: String,
        word: String,
        headword: String = word,
    ): String {
        val words = TextTools.wordRanges(text)
        val range = words.first { text.substring(it.start, it.end) == word }
        return IrregularVerbs.shared.listenText(headword, text, range)
    }

    @Test
    fun `a noun is spoken as its sentence proves when the voice would say it wrong`() {
        val verbs = IrregularVerbs.shared
        assertEquals("led", listen("It was made of lead.", "lead"))
        assertEquals("led", listen("The lead pipes burst.", "lead"))
        assertEquals("lead", listen("She was the lead singer.", "lead"), "liːd: the voice says it right")
        assertEquals("tare", listen("There is a tear in my shirt.", "tear"))
        assertEquals("tear", listen("A tear rolled down her cheek.", "tear"), "tɪr: as written")
        // A plural says the plural; the headword the singular's spoken form for the same sound.
        assertEquals("led, leds", listen("His leads were made of metal.", "leads", headword = "lead"))
        // Unsure: the tapped form in each reading, the noun's as its sentence proves.
        assertEquals("leads, leds", listen("We need lead pipes and old roof leads.", "leads", headword = "lead"))
        assertEquals("tares", listen("There were tears in the sail.", "tears", headword = "tear"), "unsure: both readings sound alike")
        assertEquals("tare, tares", listen("She mended two tears in her coat.", "tears", headword = "tear"))
        assertEquals("tear, tears", listen("Her tears fell.", "tears", headword = "tear"), "tɪr: as written")
        // Next-word rules before previous-word ones: "with lead vocals" is the singer's lead.
        val text = "He sang with lead vocals."
        val words = TextTools.wordRanges(text)
        assertEquals("liːd", verbs.nounIpa(text, words, 3))
        assertNull(verbs.nounSpoken(text, words, 3))

        // Review and Words: from the saved IPA, no new stored field.
        fun saved(
            word: String,
            ipa: String,
        ) = VocabWord(word, sense = Sense.NOUN, phonetic = ipa, createdAt = "2026-01-01T00:00", nextReview = "2026-01-01T00:00")
        assertEquals("led", verbs.spokenSaved(saved("lead", "/lɛd/")))
        assertEquals("lead", verbs.spokenSaved(saved("lead", "/liːd/")))
        assertEquals("tare", verbs.spokenSaved(saved("tear", "/tɛɹ/")), "the dictionary's ɹ is r")
        assertEquals("tear", verbs.spokenSaved(saved("tear", "")))
        assertEquals("tare", verbs.spokenSaved(saved("tear", "").copy(sense = Sense.VERB)), "the verb: the table's respelling")
    }

    @Test
    fun `the table's noun pronunciation rules are checked`() {
        fun withNounIpa(
            form: String,
            change: (JsonArray) -> JsonArray,
        ) = table.with(
            "homographs",
            JsonArray(
                table["homographs"]!!.jsonArray.map { h ->
                    if (h.jsonObject["form"]!!.jsonPrimitive.content != form) {
                        h
                    } else {
                        h.jsonObject.with(
                            "nounIpa",
                            change(h.jsonObject["nounIpa"]!!.jsonArray),
                        )
                    }
                },
            ),
        )

        fun rule(vararg fields: Pair<String, JsonElement>) = JsonObject(fields.toMap())
        // "leads" has /lɛd/ "leds" and /liːd/: the singular keeps both sounds, /lɛd/ said "led".
        val ipa = "ipa" to JsonPrimitive("lɛd")
        val led = "spokenAs" to JsonPrimitive("led")
        val leed = "ipa" to JsonPrimitive("liːd")
        val before = "before" to JsonArray(listOf(JsonPrimitive("pipes")))
        val after = "after" to JsonArray(listOf(JsonPrimitive("of")))
        assertTrue(loads(withNounIpa("lead") { JsonArray(listOf(rule(ipa, before, led), rule(ipa, after, led), rule(leed))) }))
        assertFalse(
            loads(
                withNounIpa("lead") {
                    JsonArray(listOf(rule(ipa, after, led), rule(ipa, before, led), rule(leed)))
                },
            ),
            "after before before",
        )
        for (bad in listOf("Led", "le d", "l\u00e9d", "a".repeat(21), "")) {
            val spoken = "spokenAs" to JsonPrimitive(bad)
            assertFalse(loads(withNounIpa("lead") { JsonArray(listOf(rule(ipa, before, spoken), rule(leed))) }), "spokenAs \"$bad\"")
        }
    }

    @Test
    fun `a plural's spoken forms are its singular's plus s`() {
        fun withRules(
            form: String,
            rules: List<JsonObject>,
        ) = table.with(
            "homographs",
            JsonArray(
                table["homographs"]!!.jsonArray.map {
                        h ->
                    if (h.jsonObject["form"]!!.jsonPrimitive.content != form) h else h.jsonObject.with("nounIpa", JsonArray(rules))
                },
            ),
        )

        fun rule(
            ipa: String,
            spoken: String?,
        ) = JsonObject(listOfNotNull("ipa" to JsonPrimitive(ipa), spoken?.let { "spokenAs" to JsonPrimitive(it) }).toMap())
        val near = "near" to JsonArray(listOf(JsonPrimitive("metal")))

        fun ruleNear(
            ipa: String,
            spoken: String?,
        ) = JsonObject(rule(ipa, spoken) + near)
        assertTrue(loads(withRules("leads", listOf(ruleNear("lɛd", "leds"), rule("liːd", null)))))
        assertFalse(loads(withRules("leads", listOf(ruleNear("lɛd", "led"), rule("liːd", null)))), "the singular's form")
        assertFalse(loads(withRules("leads", listOf(ruleNear("lɛd", null), rule("liːd", null)))), "one has it, the other not")
        assertFalse(loads(withRules("leads", listOf(ruleNear("lɛd", "leds"), rule("liːd", "leeds")))), "the singular has none for liːd")
        assertFalse(loads(withRules("leads", listOf(ruleNear("lɛd", "leds"), rule("lɪd", null)))), "a sound the singular doesn't have")
    }

    @Test
    fun `Review shows the sentence when the label alone doesn't tell the sense`() {
        val verbs = IrregularVerbs.shared
        val date = "2026-01-01T00:00"
        val lie = VocabWord("lie", sense = Sense.VERB, context = "The cat likes to lie in the sun.", createdAt = date, nextReview = date)
        assertTrue(verbs.reviewShowsSentence(lie, listOf(lie)), "a verb with a twin, one sense saved")
        assertTrue(verbs.reviewShowsSentence(lie.copy(sense = Sense.HOMONYM), listOf(lie)), "the twin")
        assertFalse(verbs.reviewShowsSentence(lie.copy(context = ""), listOf(lie)), "no sentence")
        assertFalse(verbs.reviewShowsSentence(lie.copy(context = "Lie"), listOf(lie)), "the sentence is the word")
        val wind = VocabWord("wind", sense = Sense.NOUN, context = "The wind blew.", createdAt = date, nextReview = date)
        assertFalse(verbs.reviewShowsSentence(wind, listOf(wind)), "the wind: the label tells")
        val windVerb = wind.copy(sense = Sense.VERB, context = "Wind the clock.")
        assertTrue(verbs.reviewShowsSentence(wind, listOf(wind, windVerb)), "two senses saved")
        val ring = VocabWord("ring", sense = Sense.NOUN, context = "The bell rings.", createdAt = date, nextReview = date)
        assertFalse(verbs.reviewShowsSentence(ring, listOf(ring)), "the ring: the label tells")
    }

    @Test
    fun `the noun of a mostly-noun -s is its lemma, saved as the ring`() {
        val text = "The bell rings."
        val words = TextTools.wordRanges(text)
        val noun = IrregularVerbs.shared.choices(text, words, 2).single { it.kind == Reading.Kind.NOUN }
        assertEquals("ring", noun.base)
        assertNull(Meanings.fromTable(noun, "es"), "the noun is translated")
        assertEquals("the ring", Meanings.query(noun))
        val library = library()
        library.saveWord(noun.base, learned = false, sense = Sense.of(noun), context = text)
        val saved = library.state.value.word("ring#noun")!!
        assertEquals("the ring", library.state.value.displayName(saved))
    }
}
