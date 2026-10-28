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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** v4 part 2: expressions, the twin's noun, mostly-noun -s forms, the noun's pronunciation, per-sense queries. */
class RoundFourTest {
    private val verbs = IrregularVerbs.shared

    private fun read(
        sentence: String,
        word: String,
    ) = verbs.resolve(sentence, sentence.rangeOf(word))

    private fun choices(
        sentence: String,
        word: String,
    ): List<Reading> {
        val words = TextTools.wordRanges(sentence)
        return verbs.choices(sentence, words, words.indexOf(sentence.rangeOf(word)))
    }

    private fun nounIpa(
        sentence: String,
        word: String,
    ): String? {
        val words = TextTools.wordRanges(sentence)
        return verbs.nounIpa(sentence, words, words.indexOf(sentence.rangeOf(word)))
    }

    @Test
    fun `bound for and bound to are expressions`() {
        assertEquals(Reading(Reading.Kind.EXPRESSION, "bound for", Reading.Step.EXPRESSION), read("The ship was bound for Spain.", "bound"))
        assertEquals(Reading(Reading.Kind.EXPRESSION, "bound to", Reading.Step.EXPRESSION), read("It is bound to rain.", "bound"))
        assertEquals(
            Reading(Reading.Kind.EXPRESSION, "bound for", Reading.Step.EXPRESSION),
            read("Bound for London, they sailed.", "Bound"),
        )
        assertEquals(Reading.Kind.VERB, read("They were bound to the mast.", "bound")?.kind, "to + no verb: the participle")
        val expression = read("It is bound to rain.", "bound")!!
        assertEquals(emptyList(), TextTools.baseFormCandidates("It is bound to rain.", "It is bound to rain.".rangeOf("bound")))
        assertNull(Sense.of(expression))
        assertEquals("seguro que · obligado a", Meanings.fromTable(expression, "es"))
        assertEquals("certain to", Meanings.query(expression))
        assertEquals("sure or obliged to", verbs.gloss(expression))
        assertEquals("bound to", verbs.baseAt("It is bound to rain.", "It is bound to rain.".rangeOf("bound")))
        assertEquals(
            "bound to",
            verbs.listenText("bound to", "It is bound to rain.", "It is bound to rain.".rangeOf("bound")),
            "said as the unit",
        )
    }

    @Test
    fun `lies after a determiner is the twin's noun, unproven it is unsure`() {
        assertEquals(Reading(Reading.Kind.NOUN, "lie", Reading.Step.TWIN_NOUN), read("His lies hurt.", "lies"))
        assertEquals(listOf("lie"), TextTools.baseFormCandidates("His lies hurt.", "His lies hurt.".rangeOf("lies")))
        assertEquals(Reading(Reading.Kind.UNSURE, "lie", Reading.Step.UNSURE_NOUN), read("She tells lies.", "lies"))
        val rows = choices("She tells lies.", "lies")
        assertEquals(listOf(Reading.Kind.NOUN to "lie", Reading.Kind.VERB to "lie"), rows.map { it.kind to it.base })
        assertEquals(listOf("the lie", "to lie"), rows.map { SavedMeaning.of(it).label })
        assertEquals("mentira", Meanings.fromTable(rows[0], "es", first = true))
        assertEquals("estar, encontrarse", Meanings.fromTable(rows[1], "es", first = true), "the -s form's meaning")
        assertEquals(listOf("mentira"), verbs.twinNounEs("lie"))
        assertNull(verbs.twinNounEs("cat"))
    }

    @Test
    fun `a mostly-noun -s form after a noun subject with no object is unsure`() {
        assertEquals(Reading(Reading.Kind.UNSURE, "ring", Reading.Step.UNSURE_NOUN_FIRST), read("The bell rings.", "rings"))
        val rows = choices("The bell rings.", "rings")
        assertEquals(listOf(Reading.Kind.VERB to "ring", Reading.Kind.NOUN to "rings"), rows.map { it.kind to it.base })
        assertEquals(listOf("to ring", "the rings"), rows.map { SavedMeaning.of(it).label })
        assertEquals("sonar", Meanings.fromTable(rows[0], "es", first = true))
        assertNull(Meanings.fromTable(rows[1], "es"), "the noun is translated")
    }

    @Test
    fun `the noun's pronunciation comes from its sentence`() {
        assertEquals("tɪr", nounIpa("Tears in her eyes.", "Tears"))
        assertEquals("tɛr", nounIpa("There is a tear in my shirt.", "tear"))
        assertEquals("lɛd", nounIpa("It was made of lead.", "lead"))
        assertEquals("liːd", nounIpa("He took the lead.", "lead"))
        assertEquals("wɪnd", nounIpa("The wind blew.", "wind"))
        assertNull(nounIpa("The tear was big.", "tear"), "nothing proves it")
        assertNull(nounIpa("A cat.", "cat"))
        assertNull(verbs.nounIpa("A cat.", TextTools.wordRanges("A cat."), 9))
        assertNull(nounIpa("She left.", "left"), "no rules for left")
        assertTrue(verbs.hasNounIpa("Tears"))
        assertTrue(!verbs.hasNounIpa("left"))
    }

    @Test
    fun `each sense has its own English query, and a gloss when two rows match`() {
        val lie = Reading(Reading.Kind.VERB, "lie", Reading.Step.CHOSEN)
        val twin = Reading(Reading.Kind.HOMONYM, "lie", Reading.Step.CHOSEN)
        assertEquals("to lie down", Meanings.query(lie))
        assertEquals("to tell a lie", Meanings.query(twin))
        assertEquals("to leave", Meanings.query(Reading(Reading.Kind.VERB, "leave", Reading.Step.CHOSEN)))
        assertEquals("the left", Meanings.query(Reading(Reading.Kind.NOUN, "left", Reading.Step.CHOSEN)))
        assertEquals("to hang up", Meanings.query(Reading(Reading.Kind.VERB, "hang", Reading.Step.CHOSEN)))
        assertEquals("zz", Meanings.query(Reading(Reading.Kind.EXPRESSION, "zz", Reading.Step.EXPRESSION)))
        assertEquals("to zzz", Meanings.query(Reading(Reading.Kind.HOMONYM, "zzz", Reading.Step.CHOSEN)))
        assertEquals(
            listOf("mentir (recline)", "Mentir (say what isn't true)"),
            Meanings.distinguished(listOf(lie, twin), listOf("mentir", "Mentir")),
        )
        assertEquals(listOf("a", "b"), Meanings.distinguished(listOf(lie, twin), listOf("a", "b")))
        assertEquals(listOf("a", null), Meanings.distinguished(listOf(lie, twin), listOf("a", null)))
        assertEquals(listOf("a"), Meanings.distinguished(listOf(lie), listOf("a")))
        val noun = Reading(Reading.Kind.NOUN, "lie", Reading.Step.CHOSEN)
        assertEquals(listOf("x", "x (recline)"), Meanings.distinguished(listOf(noun, lie), listOf("x", "x")), "a noun has no gloss")
        assertNull(verbs.gloss(noun))
    }

    private fun fixture(name: String) = javaClass.getResource("/dictionary/$name.json")!!.readText()

    private val dictionary =
        DictionaryClient { url ->
            val word = url.substringAfterLast('/')
            if (word in setOf("tear", "lead", "lie")) HttpResponse(200, fixture(word).toByteArray()) else HttpResponse(404, ByteArray(0))
        }

    @Test
    fun `a noun of tear or lead is the entry its sentence's sound proves, else none`() =
        runTest {
            val drop = dictionary.lookup("tears", Reading(Reading.Kind.NOUN, "tear", Reading.Step.NOUN), nounIpa = "tɪr")!!
            assertTrue(drop.definition!!.startsWith("A drop"))
            val hole = dictionary.lookup("tear", Reading(Reading.Kind.NOUN, "tear", Reading.Step.NOUN), nounIpa = "tɛr")!!
            assertTrue(hole.definition!!.startsWith("A hole"))
            assertNull(dictionary.lookup("tear", Reading(Reading.Kind.NOUN, "tear", Reading.Step.NOUN)), "nothing proves it")
            val metal = dictionary.lookup("lead", Reading(Reading.Kind.NOUN, "lead", Reading.Step.NOUN), nounIpa = "lɛd")!!
            assertTrue(metal.definition!!.startsWith("A heavy"))
            assertNull(dictionary.lookup("lies", Reading(Reading.Kind.NOUN, "lie", Reading.Step.TWIN_NOUN)), "the twin's noun: none")
            assertNull(
                dictionary.lookup("bound", Reading(Reading.Kind.EXPRESSION, "bound for", Reading.Step.EXPRESSION)),
                "no adjective entry",
            )
            val bound =
                DictionaryClient {
                    HttpResponse(
                        200,
                        """[{"word": "bound", "meanings": [{"partOfSpeech": "verb", "definitions": [{"definition": "To leap."}]},
                            {"partOfSpeech": "adjective", "definitions": [{"definition": "Heading for a place."}]}]}]""".toByteArray(),
                    )
                }
            assertEquals(
                "Heading for a place.",
                bound.lookup("bound", Reading(Reading.Kind.EXPRESSION, "bound for", Reading.Step.EXPRESSION))!!.definition,
            )
            assertEquals("laɪ", verbs.ipa("lie", "base"), "the twin shows the base's IPA")
            assertEquals("hæŋ", verbs.ipa("hang", "base"))
        }

    @Test
    fun `a corrected expression becomes the unit`() {
        val storage = MemoryStorage()
        storage.files["settings.json"] = """{"targetLanguage": "es"}"""
        val date = "2026-01-01T00:00"
        val words =
            listOf(VocabWord("bound", context = "The ship was bound for Spain.", createdAt = date, nextReview = date, translation = "x"))
        storage.files["words.json"] = Json.encodeToString(ListSerializer(VocabWord.serializer()), words)
        val library = Library(storage, TestClock()).apply { load() }
        library.correctWordsOnce()
        val unit = library.state.value.word("bound for")!!
        assertNull(unit.sense)
        assertEquals("rumbo a, con destino a", unit.translation)
        assertEquals(listOf("bound" to "bound for"), library.state.value.corrections.map { it.from to it.to })
    }

    @Test
    fun `the v4 fields of the table are checked`() {
        val bundled = javaClass.getResourceAsStream("/irregular-verbs.json")!!.use { it.readBytes() }.toString(Charsets.UTF_8)
        val table = Json.parseToJsonElement(bundled).jsonObject

        fun refused(json: JsonObject) = assertTrue(runCatching { IrregularVerbs.load(json.toString().toByteArray()) }.isFailure)

        fun withVerb(
            base: String,
            change: (JsonObject) -> JsonObject,
        ) = JsonObject(
            table +
                (
                    "verbs" to
                        JsonArray(
                            table["verbs"]!!.jsonArray.map {
                                if (it.jsonObject["base"]!!.jsonPrimitive.content == base) change(it.jsonObject) else it
                            },
                        )
                ),
        )

        fun withHomograph(
            form: String,
            change: (JsonObject) -> JsonObject,
        ) = JsonObject(
            table +
                (
                    "homographs" to
                        JsonArray(
                            table["homographs"]!!.jsonArray.map {
                                if (it.jsonObject["form"]!!.jsonPrimitive.content == form) change(it.jsonObject) else it
                            },
                        )
                ),
        )

        fun JsonObject.with(
            key: String,
            value: Any?,
        ) = JsonObject(
            if (value == null) {
                this - key
            } else {
                this + (key to (value as? kotlinx.serialization.json.JsonElement ?: JsonPrimitive(value.toString())))
            },
        )

        fun strings(vararg s: String) = JsonArray(s.map(::JsonPrimitive))
        refused(withVerb("lie") { it.with("query", "To lie") })
        refused(withVerb("lie") { it.with("gloss", "x") })
        refused(withVerb("lie") { it.with("sEs", " estar") })
        refused(withVerb("lie") { it.with("homonym", it["homonym"]!!.jsonObject.with("nounEs", null)) })
        refused(withVerb("lie") { it.with("homonym", it["homonym"]!!.jsonObject.with("noun", "Lie")) })
        refused(withVerb("lie") { it.with("homonym", it["homonym"]!!.jsonObject.with("nounEs", strings())) })
        refused(withVerb("lie") { it.with("homonym", it["homonym"]!!.jsonObject.with("query", "tell a lie!")) })
        refused(withVerb("lie") { it.with("ipa", JsonObject(mapOf("base" to JsonPrimitive("lcy")))) })
        val plain = JsonObject(mapOf("ipa" to JsonPrimitive("tɪr")))
        refused(withHomograph("tear") { it.with("nounIpa", JsonArray(listOf(plain, plain))) })
        refused(
            withHomograph("tear") {
                it.with("nounIpa", JsonArray(listOf(JsonObject(mapOf("ipa" to JsonPrimitive("tɪr"), "near" to strings("Eye"))))))
            },
        )
        refused(
            withHomograph("tear") {
                it.with("nounIpa", JsonArray(listOf(JsonObject(mapOf("ipa" to JsonPrimitive("tɪr"), "far" to strings("eye"))))))
            },
        )
        val expression =
            table["homographs"]!!.jsonArray.first {
                it.jsonObject["form"]!!.jsonPrimitive.content == "bound"
            }.jsonObject["expressions"]!!.jsonArray[0].jsonObject
        refused(withHomograph("bound") { it.with("expressions", JsonArray(listOf(expression.with("next", "For")))) })
        refused(withHomograph("bound") { it.with("expressions", JsonArray(listOf(expression.with("es", strings())))) })
        refused(withHomograph("bound") { it.with("expressions", JsonArray(listOf(expression.with("query", "Heading")))) })
        refused(withHomograph("bound") { it.with("expressions", JsonArray(listOf(expression.with("gloss", "")))) })
        refused(withHomograph("bound") { it.with("expressions", JsonArray(listOf(expression.with("extra", "x")))) })
        refused(JsonObject(table + ("context" to JsonObject(table["context"]!!.jsonObject + ("boundAdjective" to strings("for"))))))
        IrregularVerbs.load(bundled.toByteArray())
    }
}
