package app.lingleaf.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Precision of each step of the verb-or-noun rule on the shared annotated corpus (same file
 * and thresholds as lingleaf-ios, SPEC.md): every step must claim at least 15 cases and be right
 * (base and verb flag) in at least 95% of them; each family has at least 30 cases, 150 in all.
 * The v3 forms of the table that are not homographs (a past or participle; an -s or -ing form a
 * step proves, else an ordinary word; the twin's sense of "lying", "lies") are measured the same
 * way, with the twin's sense checked where the corpus gives it; an -s/-ing verb no step proves
 * counts as a miss, like unsure.
 * The v2 steps (phrase, opening participle, name/noun + -s form, quantifier) are held to the same
 * thresholds, "bit" after a noun subject on its own too, and every homograph of the bundled
 * stories must be read right: never wrong, unsure or unproven (a learner never meets a doubtful reading there).
 * Every miss is printed.
 *
 * Known residuals, not tuned away: a present-tense verb after a plural or "you" subject can be
 * read as the past of another verb ("they smelt iron ore", "deer bound across the road", "first
 * you saw the board in half", "two men saw logs"); a noun subject's object clause ("the boring
 * bit the teacher skipped") and an opening noun with an aside ("Thought in old China, as…").
 */
class HomographCorpusTest {
    @Serializable
    private data class Case(
        val source: String,
        val sentence: String,
        val word: String,
        val expectedBase: String,
        val expectedVerb: Boolean,
        /** For a verb with a regular twin: "verb" or "homonym" (the twin's sense: "lying to"). */
        val expectedSense: String? = null,
        /** The noun's pronunciation in this sentence, or JSON null when nothing proves it (v4). */
        val expectedNounIpa: JsonElement = NOT_GIVEN,
    )

    private companion object {
        /** No expectedNounIpa in the case (JSON null means "nothing proves it"). */
        val NOT_GIVEN: JsonElement = JsonPrimitive("not given")
    }

    @Serializable
    private data class Corpus(val about: String, val cases: List<Case>)

    private val families =
        mapOf(
            "plural" to listOf("lives", "leaves", "tears", "winds", "leads"),
            "pastOrNoun" to
                listOf(
                    "left", "saw", "felt", "rose", "fell", "ground", "wound", "spoke", "bit", "shot", "dove", "thought",
                    "stole", "slew", "spat", "spelt", "smelt",
                ),
            "twoVerbs" to listOf("found", "lay", "bore", "bound"),
            "sameBase" to listOf("lead", "tear", "wind"),
        )

    private val sources =
        setOf("constructed", "heldout1", "heldout2", "heldout3", "v2", "v2heldout", "v3", "v3heldout", "v4", "v4heldout", "v5", "stories")

    /** The steps added in v2 must each claim at least 15 cases too. */
    private val v2Steps =
        setOf(Reading.Step.PHRASE, Reading.Step.PARTICIPLE_OPEN, Reading.Step.NAME_S, Reading.Step.NOUN_S, Reading.Step.QUANTIFIER)

    @Test
    fun `each step is precise on the shared corpus`() {
        val text = javaClass.getResource("/homograph-corpus.json")?.readText() ?: fail("corpus missing")
        val corpus = Json.decodeFromString<Corpus>(text)
        val familyOf = families.flatMap { (family, forms) -> forms.map { it to family } }.toMap()
        val fired = mutableMapOf<Reading.Step, Int>()
        val right = mutableMapOf<Reading.Step, Int>()
        val perFamily = mutableMapOf<String, Int>()
        var unsure = 0
        var unproven = 0
        var ipaFired = 0
        var ipaRight = 0
        var bitFired = 0
        var bitRight = 0
        val storyErrors = mutableListOf<String>()
        for (case in corpus.cases) {
            assertTrue(case.source in sources, "unknown source ${case.source}")
            val whole = Regex("(?<![A-Za-z'’])${Regex.escape(case.word)}(?![A-Za-z'’])")
            val match = whole.find(case.sentence) ?: fail("'${case.word}' not in '${case.sentence}'")
            val range = WordRange(match.range.first, match.range.last + 1)
            // Homographs by family; v3 also has the table's other forms ("tore", "lights", "lying").
            familyOf[TextTools.normalize(case.word)]?.let { perFamily[it] = (perFamily[it] ?: 0) + 1 }
            val reading = assertNotNull(IrregularVerbs.shared.resolve(case.sentence, range), "${case.word} is a form of the table")
            val words = TextTools.wordRanges(case.sentence)
            val index = words.indexOf(range)
            // The noun's pronunciation, measured on its own (v4).
            case.expectedNounIpa.takeUnless { it === NOT_GIVEN }?.let { expected ->
                val want = (expected as? JsonPrimitive)?.contentOrNull
                val got = IrregularVerbs.shared.nounIpa(case.sentence, words, index)
                if (got != null || want == null) {
                    ipaFired++
                    if (got == want) ipaRight++ else println("corpus nounIpa miss: [${case.word}] ${case.sentence} -> $got, expected $want")
                }
            }
            // Unsure between two rows (v4: "She tells lies", "The bell rings"): right when the sentence's reading is a row.
            if (reading.step == Reading.Step.UNSURE_NOUN || reading.step == Reading.Step.UNSURE_NOUN_FIRST) {
                val rows = IrregularVerbs.shared.choices(case.sentence, words, index).map { it.base to (it.kind != Reading.Kind.NOUN) }
                fired[reading.step] = (fired[reading.step] ?: 0) + 1
                val ok = (case.expectedBase to case.expectedVerb) in rows && (case.expectedSense ?: Sense.VERB) == Sense.VERB
                if (ok) {
                    right[reading.step] = (right[reading.step] ?: 0) + 1
                } else {
                    println(
                        "corpus miss ${reading.step}: [${case.word}] ${case.sentence} -> $rows",
                    )
                }
                if (case.source == "stories") storyErrors += "${case.sentence} [${case.word}]: unsure"
                continue
            }
            val verb = reading.kind == Reading.Kind.VERB || reading.kind == Reading.Kind.HOMONYM
            // On the stories, unsure, or a verb no step proved (an ordinary word), is an error too.
            val missed = reading.kind == Reading.Kind.UNSURE || (reading.kind == Reading.Kind.WORD && case.expectedVerb)
            if (case.source == "stories" && missed) storyErrors += "${case.sentence} [${case.word}]: ${reading.kind}"
            if (missed) {
                // Unsure, or an -s/-ing verb no step proved: a miss (no table meaning), not a wrong one.
                if (reading.kind == Reading.Kind.UNSURE) unsure++ else unproven++
                continue
            }
            fired[reading.step] = (fired[reading.step] ?: 0) + 1
            val sense = case.expectedSense?.let { (reading.kind == Reading.Kind.HOMONYM) == (it == Sense.HOMONYM) } ?: true
            val ok = reading.base == case.expectedBase && verb == case.expectedVerb && sense
            if (case.word == "bit" && reading.step == Reading.Step.SUBJECT_NOUN) {
                bitFired++
                if (ok) bitRight++
            }
            if (ok) {
                right[reading.step] = (right[reading.step] ?: 0) + 1
            } else {
                println("corpus miss ${reading.step}: [${case.word}] ${case.sentence} -> ${reading.base}/${reading.kind}")
                if (case.source == "stories") storyErrors += case.sentence
            }
        }
        assertTrue(corpus.cases.size >= 150, "only ${corpus.cases.size} cases")
        for (family in families.keys) assertTrue((perFamily[family] ?: 0) >= 30, "$family: ${perFamily[family]} cases (need 30)")
        assertEquals(emptyList(), storyErrors, "a wrong reading in the bundled stories")
        assertTrue(corpus.cases.any { it.source == "stories" }, "the stories' homographs are in the corpus")
        println("corpus bit after a noun subject: $bitRight/$bitFired right")
        assertTrue(bitFired >= 15 && bitRight.toDouble() / bitFired >= 0.95, "bit subjectNoun: $bitRight/$bitFired")
        println("corpus noun IPA: $ipaRight/$ipaFired right")
        assertTrue(ipaFired >= 15 && ipaRight.toDouble() / ipaFired >= 0.95, "noun IPA: $ipaRight/$ipaFired")
        // The twin's noun is measured as one: after a determiner, and unsure with the verb.
        val twin = (fired[Reading.Step.TWIN_NOUN] ?: 0) + (fired[Reading.Step.UNSURE_NOUN] ?: 0)
        assertTrue(twin >= 15, "the twin's noun claims only $twin cases (need 15)")
        val steps = Reading.Step.entries - Reading.Step.UNSURE - Reading.Step.CHOSEN
        for (step in v2Steps) assertTrue(step in steps)
        for (step in steps) {
            val count = fired[step] ?: 0
            println("corpus $step: ${right[step] ?: 0}/$count right")
            // A quote before an -s form is rare (5 cases); the twin's noun steps count together (above).
            val least =
                when (step) {
                    Reading.Step.QUOTE -> 5
                    Reading.Step.TWIN_NOUN, Reading.Step.UNSURE_NOUN -> 1
                    else -> 15
                }
            assertTrue(count >= least, "$step claims only $count cases (need $least)")
            val precision = (right[step] ?: 0).toDouble() / count
            assertTrue(precision >= 0.95, "$step precision ${"%.3f".format(precision)} < 0.95")
        }
        println("corpus unsure: $unsure, unproven -s/-ing verbs: $unproven, of ${corpus.cases.size}")
        assertEquals(corpus.cases.size, fired.values.sum() + unsure + unproven)
    }
}
