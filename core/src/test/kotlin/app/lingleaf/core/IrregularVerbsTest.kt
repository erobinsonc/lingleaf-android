package app.lingleaf.core

import kotlinx.serialization.SerializationException
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IrregularVerbsTest {
    private val bundled: ByteArray = javaClass.getResourceAsStream("/irregular-verbs.json")!!.use { it.readBytes() }
    private val table: JsonObject = Json.parseToJsonElement(bundled.toString(Charsets.UTF_8)).jsonObject
    private val verbs = IrregularVerbs.shared

    private fun JsonObject.with(
        key: String,
        value: JsonElement?,
    ) = JsonObject(if (value == null) this - key else this + (key to value))

    /** The bundled table with its first verb (or [base]) changed by [change]. */
    private fun withVerb(
        base: String = "put",
        change: (JsonObject) -> JsonObject,
    ): JsonObject {
        val list =
            table["verbs"]!!.jsonArray.map {
                if (it.jsonObject["base"]!!.jsonPrimitive.content == base) {
                    change(
                        it.jsonObject,
                    )
                } else {
                    it
                }
            }
        return table.with("verbs", JsonArray(list))
    }

    private fun withHomograph(
        form: String,
        change: (JsonObject) -> JsonObject,
    ): JsonObject {
        val list =
            table["homographs"]!!.jsonArray.map {
                if (it.jsonObject["form"]!!.jsonPrimitive.content == form) {
                    change(
                        it.jsonObject,
                    )
                } else {
                    it
                }
            }
        return table.with("homographs", JsonArray(list))
    }

    private fun strings(vararg s: String) = JsonArray(s.map(::JsonPrimitive))

    private fun refused(json: JsonObject) = refused(json.toString().toByteArray())

    private fun refused(bytes: ByteArray) {
        val error = runCatching { IrregularVerbs.load(bytes) }.exceptionOrNull()
        assertTrue(
            error is IrregularVerbsException || error is SerializationException || error is IllegalArgumentException,
            "accepted: $error",
        )
    }

    @Test
    fun `the bundled table loads`() {
        assertNull(IrregularVerbs.loadError)
        assertTrue(verbs.formToBase.size > 400)
        assertTrue(verbs.participles.size > 150)
        assertTrue(verbs.bases.size > 100)
        // Every level of the reviewed table is accepted, C1 and C2 included.
        IrregularVerbs.load(bundled)
    }

    @Test
    fun `forms lead to their base`() {
        val links =
            mapOf(
                "stolen" to "steal", "learnt" to "learn", "lying" to "lie", "lied" to "lie", "being" to "be", "hanged" to "hang",
                "became" to "become", "torn" to "tear", "knelt" to "kneel", "flung" to "fling", "am" to "be", "woken" to "wake",
            )
        for ((form, base) in links) assertEquals(base, verbs.formToBase[form], form)
        for (homograph in listOf("left", "saw", "stole", "lives", "leaves", "lay")) {
            assertNull(verbs.formToBase[homograph], "$homograph needs its sentence")
            assertTrue(verbs.isHomograph(homograph))
        }
        assertEquals("tear", verbs.sharedBase("tears"))
        assertNull(verbs.sharedBase("left"))
        assertNull(verbs.sharedBase("walked"))
    }

    @Test
    fun `participles and bases come from the table`() {
        assertFalse("got" in verbs.participles, "\"have got\" means \"have\"")
        assertTrue("had" in verbs.participles)
        assertTrue(verbs.baseLikeParticiples.containsAll(listOf("come", "read", "bet")))
        assertFalse("seen" in verbs.baseLikeParticiples)
        assertFalse(Verbs.isBase("light"), "mostly a noun: \"had better light\"")
        assertTrue(Verbs.isBase("strike"))
        assertTrue(Verbs.isParticiple("travelled"), "-ll: travel")
        assertTrue(Verbs.isParticiple("sunk"))
        // Everything the hand-written lists had is kept.
        val oldParticiples =
            """beaten become been begun bent bitten bought bound broken brought built burnt burst cast caught chosen come
            cost crept cut dealt done drawn dreamt driven drunk dug eaten fallen fed felt fled flown forgiven forgotten fought
            found frozen given gone gotten grown had heard held hidden hit hung hurt kept known laid lain learnt led left lent
            let lit lost made meant met mistaken overcome paid put quit read ridden risen run said sat seen sent set shaken
            shed shot shown shut slept sold sought spent spoken spread stolen stood struck stuck sung swept sworn swum taken
            taught thought thrown told torn understood wept woken won worn wound written"""
        val oldBaseLike = "become burst cast come cost cut hit hurt let overcome put quit read run set shed shut spread"
        val oldBases =
            """be beat become begin bite break bring build buy catch choose come cut do draw drink drive eat fall feed feel
            fight find fly forget forgive get give go grow hang have hear hide hit hold hurt keep know lead leave lend let lie
            lose make mean meet pay put read ride rise run say see seek sell send set shake shoot show shut sing sit sleep
            speak spend stand steal stick swear sweep swim take teach tear tell think throw understand wake wear win write"""

        fun words(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }
        assertEquals(emptyList(), words(oldParticiples) - verbs.participles)
        assertEquals(emptyList(), words(oldBaseLike) - verbs.baseLikeParticiples)
        assertEquals(emptyList(), words(oldBases) - verbs.bases)
    }

    @Test
    fun `size and depth are refused before parsing`() {
        refused(ByteArray(IrregularVerbs.MAX_BYTES + 1) { ' '.code.toByte() })
        refused(("[".repeat(IrregularVerbs.MAX_DEPTH + 1) + "]".repeat(IrregularVerbs.MAX_DEPTH + 1)).toByteArray())
        val big = java.io.ByteArrayInputStream(ByteArray(IrregularVerbs.MAX_BYTES * 2))
        assertEquals(IrregularVerbs.MAX_BYTES + 1, IrregularVerbs.readAtMost(big, IrregularVerbs.MAX_BYTES + 1).size, "read no further")
        assertEquals(3, IrregularVerbs.readAtMost(java.io.ByteArrayInputStream(ByteArray(3)), 10).size)
    }

    @Test
    fun `another schema, unknown or missing fields are refused`() {
        refused(table.with("schemaVersion", JsonPrimitive(2)))
        refused(table.with("extra", JsonPrimitive(true)))
        refused(table.with("context", null))
        refused(withVerb { it.with("ing", null) })
        refused(withVerb { it.with("tense", JsonPrimitive("x")) })
        refused(withVerb { it.with("past", JsonPrimitive("put")) })
        refused(withHomograph("found") { it.with("noun", null) })
        refused(withHomograph("left") { it.with("side", JsonPrimitive("x")) })
        refused(table.with("context", table["context"]!!.jsonObject.with("subjects", null)))
        refused(table.with("context", table["context"]!!.jsonObject.with("others", strings("x"))))
        refused("not json".toByteArray())
    }

    @Test
    fun `bad values are refused`() {
        refused(withVerb { it.with("past", strings("Put")) })
        refused(withVerb { it.with("ing", JsonPrimitive("put ting")) })
        refused(withVerb { it.with("s", JsonPrimitive("a".repeat(21))) })
        refused(withVerb { it.with("past", strings()) })
        refused(withVerb { it.with("past", strings("put", "putt", "putted")) })
        refused(withVerb { it.with("past", strings("put", "put")) })
        refused(withVerb { it.with("participle", strings()) })
        refused(withVerb { it.with("es", strings()) })
        refused(withVerb { it.with("es", strings(" ")) })
        refused(withVerb { it.with("es", strings("a", "b", "c")) })
        refused(withVerb { it.with("group", JsonPrimitive("nope")) })
        refused(withVerb { it.with("level", JsonPrimitive("D1")) })
        refused(withVerb { it.with("spokenAs", JsonObject(mapOf("future" to JsonPrimitive("x")))) })
        refused(withVerb { it.with("spokenAs", JsonObject(mapOf("past" to JsonPrimitive("Red!")))) })
        refused(withVerb("lie") { it.with("homonym", it["homonym"]!!.jsonObject.with("past", strings())) })
        refused(withVerb("lie") { it.with("homonym", it["homonym"]!!.jsonObject.with("es", strings())) })
        refused(withVerb("lie") { it.with("homonym", it["homonym"]!!.jsonObject.with("ing", JsonPrimitive("LYING"))) })
        refused(table.with("context", table["context"]!!.jsonObject.with("skip", strings("Very"))))
    }

    @Test
    fun `inconsistent tables are refused`() {
        // "sold" would lead to two bases.
        refused(withVerb { it.with("past", strings("sold")) })
        // A form that is another verb's base is a homograph or nothing ("lay").
        refused(withVerb { it.with("participle", strings("see")) })
        refused(withVerb("set") { it.with("base", JsonPrimitive("put")) })
        val groups = table["groups"]!!.jsonArray
        refused(table.with("groups", JsonArray(groups + groups.first())))
        val homographs = table["homographs"]!!.jsonArray
        refused(table.with("homographs", JsonArray(homographs + homographs.first())))
        refused(withHomograph("left") { it.with("rules", strings("guess")) })
        refused(withHomograph("left") { it.with("verb", JsonPrimitive("zzz")) })
        refused(withHomograph("left") { it.with("verb", JsonPrimitive("see")) })
        refused(withHomograph("left") { it.with("nounBefore", strings("Hand")) })
        refused(withHomograph("lead") { it.with("baseVerb", null) })
    }

    private fun read(
        sentence: String,
        word: String,
    ): Reading? = verbs.resolve(sentence, sentence.rangeOf(word))

    private fun assertReading(
        sentence: String,
        word: String,
        kind: Reading.Kind,
        base: String,
        step: Reading.Step,
    ) = assertEquals(Reading(kind, base, step), read(sentence, word), sentence)

    @Test
    fun `verb or noun, step by step`() {
        val noun = Reading.Kind.NOUN
        val verb = Reading.Kind.VERB
        assertNull(read("She walked home.", "walked"), "not a homograph")
        assertNull(verbs.resolve("She left.", WordRange(1, 3)), "not a word of the text")
        assertReading("We met Aunt Rose there.", "Rose", noun, "rose", Reading.Step.NAME)
        assertReading("He is left-handed.", "left", noun, "left", Reading.Step.NAME)
        assertReading("Turn left at the bank.", "left", noun, "left", Reading.Step.FIXED)
        assertReading("Raise your left hand.", "left", noun, "left", Reading.Step.FIXED)
        assertReading("They looked right and left before crossing.", "left", noun, "left", Reading.Step.FIXED)
        assertReading("The ship was bound for Spain.", "bound", noun, "bound", Reading.Step.FIXED)
        assertReading("It is a little bit cold.", "bit", noun, "bit", Reading.Step.NOUN)
        assertReading("The dead leaves fell.", "leaves", noun, "leaf", Reading.Step.NOUN)
        assertReading("Tom's bit was small.", "bit", noun, "bit", Reading.Step.NOUN)
        assertReading("The soldier's wound was deep.", "wound", Reading.Kind.UNSURE, "wound", Reading.Step.UNSURE)
        assertReading("You need to saw the plank.", "saw", verb, "saw", Reading.Step.BASE)
        assertReading("Please lead the way.", "lead", verb, "lead", Reading.Step.BASE)
        assertReading("She had already left.", "left", verb, "leave", Reading.Step.PARTICIPLE)
        assertReading("She left early.", "left", verb, "leave", Reading.Step.SUBJECT)
        assertReading("Tom quickly left.", "left", verb, "leave", Reading.Step.SUBJECT)
        assertReading("No one saw him.", "saw", verb, "see", Reading.Step.SUBJECT)
        assertReading("There rose a cry.", "rose", verb, "rise", Reading.Step.SUBJECT)
        assertReading("The sun rose slowly.", "rose", verb, "rise", Reading.Step.SUBJECT_NOUN)
        assertReading("When the old man spoke, we listened.", "spoke", verb, "speak", Reading.Step.SUBJECT_NOUN)
        assertReading("He stood up and spoke.", "spoke", verb, "speak", Reading.Step.CONJUNCTION)
        assertReading("She wore a fur stole.", "stole", Reading.Kind.UNSURE, "stole", Reading.Step.UNSURE)
        assertReading("With one bound he was free.", "bound", Reading.Kind.UNSURE, "bound", Reading.Step.UNSURE)
    }

    @Test
    fun `lay is lay before an object, else lie`() {
        val verb = Reading.Kind.VERB
        assertReading("She lay the book down.", "lay", verb, "lay", Reading.Step.SUBJECT)
        assertReading("She lay a long time awake.", "lay", verb, "lie", Reading.Step.SUBJECT)
        assertReading("He lay still.", "lay", verb, "lie", Reading.Step.SUBJECT)
        assertReading("They lay down on the grass.", "lay", verb, "lie", Reading.Step.SUBJECT)
        assertReading("They lay bricks.", "lay", Reading.Kind.UNSURE, "lay", Reading.Step.UNSURE)
        assertReading("There lay a sword.", "lay", verb, "lie", Reading.Step.SUBJECT)
        assertReading("Lay the table, please.", "Lay", verb, "lay", Reading.Step.BASE)
    }

    @Test
    fun `names and the table's base at a word`() {
        val s = "Uncle Drew drew a map. Drew it again."
        assertTrue(verbs.isName(s, s.rangeOf("Drew")))
        assertFalse(verbs.isName(s, WordRange(s.lastIndexOf("Drew"), s.lastIndexOf("Drew") + 4)), "opens a sentence")
        assertFalse(verbs.isName(s, s.rangeOf("drew")))
        assertFalse(verbs.isName("He saw DREW.", "He saw DREW.".rangeOf("DREW")), "all capitals")
        assertFalse(verbs.isName("We met Anna.", "We met Anna.".rangeOf("Anna")), "not a form")
        assertFalse(verbs.isName("Uncle Drew", WordRange(0, 3)), "not a word")
        assertEquals(null, verbs.baseAt(s, s.rangeOf("Drew")))
        assertEquals("draw", verbs.baseAt(s, s.rangeOf("drew")))
        assertEquals("leave", verbs.baseAt("She left.", "She left.".rangeOf("left")))
        assertNull(verbs.baseAt("Turn left.", "Turn left.".rangeOf("left")))
        assertNull(verbs.baseAt("She wore a fur stole.", "She wore a fur stole.".rangeOf("stole")))
        assertNull(verbs.baseAt("A dog.", "A dog.".rangeOf("dog")))
    }

    private fun spoken(
        sentence: String,
        word: String,
    ) = verbs.spokenAt(sentence, sentence.rangeOf(word)) ?: word

    @Test
    fun `a respelling only where the reading proves its slot`() {
        assertEquals("read", spoken("I like to read", "read"))
        assertEquals("red", spoken("She had read it", "read"))
        assertEquals("red", spoken("The letter was never read.", "read"))
        // No reading proves the past here (present "I read every day" looks the same): as written.
        assertEquals("read", spoken("Yesterday I read it", "read"))
        assertEquals("read", spoken("I'd read it", "read"), "had or would")
        assertEquals("wound", spoken("He tried to wound the bear.", "wound"), "the verb wound, not wind")
        assertEquals("wound", spoken("The path wound around the tree.", "wound"), "unsure: as written")
        assertEquals("wownd", spoken("it wound around the tree", "wound"))
        assertEquals("wownd", spoken("The rope was wound tight.", "wound"))
        assertEquals("wound", spoken("The wound was deep.", "wound"), "a noun")
        assertEquals("tare", spoken("Try not to tear it.", "tear"))
        assertEquals("tare", spoken("Tear the page out.", "Tear"))
        assertEquals("tear", spoken("A tear ran down.", "tear"))
        assertEquals("tares", spoken("She tears the paper.", "tears"))
        assertEquals("dohv", spoken("She dove into the lake.", "dove"))
        assertEquals("dove", spoken("A white dove flew.", "dove"))
        assertEquals("lay", spoken("She lay down.", "lay"), "lie has no respelling")
        assertEquals("wind", spoken("The wind blew.", "wind"))
        assertEquals("Read", spoken("We met Read today.", "Read"), "a name")
        assertEquals("read", spoken("read", "read"), "nothing before it")
        assertEquals("walked", spoken("She had walked.", "walked"))
        assertEquals("put", spoken("She had put it.", "put"), "no respelling")
        assertNull(verbs.spokenAt("She had read it", WordRange(0, 2)), "not a word of the text")
    }

    /** Reads every word of [text] the way the reader does: underline, card base, voice. */
    private fun readAll(text: String): Int {
        val words = TextTools.wordRanges(text)
        val marked = TextTools.marked(text, words, setOf("leave", "see", "rise"))
        for (index in words.indices) {
            verbs.baseAt(text, words, index)
            verbs.spokenAt(text, words, index)
        }
        return marked.size
    }

    private fun bestNanos(text: String): Long =
        (1..5).minOf {
            val start = System.nanoTime()
            readAll(text)
            System.nanoTime() - start
        }

    @Test
    fun `reading every word stays linear in the paragraph's length`() {
        // Homographs, capitalised forms and names everywhere: the worst case for the rule.
        val unit = "Left left Rose saw Drew leaves, the Lay wound; Tom Read read. She left. "
        val small = unit.repeat(TextTools.MAX_PARAGRAPH / unit.length)
        val large = unit.repeat(10 * TextTools.MAX_PARAGRAPH / unit.length)
        assertTrue(readAll(small) > 0)
        repeat(3) { readAll(large) } // warm up the JIT
        val tSmall = bestNanos(small)
        val tLarge = bestNanos(large)
        println("read every word: ${small.length} chars ${tSmall / 1000} µs, ${large.length} chars ${tLarge / 1000} µs")
        // 10× the text: about 10× the time when linear, 100× when quadratic.
        assertTrue(tLarge <= 30 * tSmall + 20_000_000, "10× the text took ${tLarge / tSmall.coerceAtLeast(1)}× the time")
        assertTrue(tLarge < 2_000_000_000, "20 000 characters took ${tLarge / 1_000_000} ms")
    }

    @Test
    fun `the word-list overloads agree with the range ones`() {
        val text = "Turn left. She left; Uncle Drew had read it, and the wound bled."
        val words = TextTools.wordRanges(text)
        for ((index, range) in words.withIndex()) {
            assertEquals(verbs.resolve(text, range), verbs.resolve(text, words, index))
            assertEquals(verbs.isName(text, range), verbs.isName(text, words, index))
            assertEquals(verbs.baseAt(text, range), verbs.baseAt(text, words, index))
            assertEquals(verbs.spokenAt(text, range), verbs.spokenAt(text, words, index))
            assertEquals(TextTools.matchKeys(text, range), TextTools.matchKeys(text, words, index))
        }
        val absent = WordRange(1, 3)
        assertNull(verbs.baseAt(text, absent))
        assertNull(verbs.spokenAt("I read.", WordRange(3, 5)), "not a word of the text")
        assertEquals(TextTools.matchKeys("ur"), TextTools.matchKeys(text, absent))
        assertEquals(TextTools.baseFormCandidates("ur"), TextTools.baseFormCandidates(text, absent))
        assertEquals(listOf("left"), TextTools.marked(text, words, setOf("leave")).map { text.substring(it.start, it.end) })
        assertEquals(emptyList(), TextTools.marked(text, words, emptySet()))
    }
}
