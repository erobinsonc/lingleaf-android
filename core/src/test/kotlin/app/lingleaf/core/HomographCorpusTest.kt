package app.lingleaf.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Precision of each step of the verb-or-noun rule on the shared annotated corpus (same file
 * and thresholds as lingleaf-ios, SPEC.md): every step must claim at least 15 cases and be right
 * (base and verb flag) in at least 95% of them; each family has at least 30 cases, 150 in all.
 * Every miss is printed.
 *
 * Known residual, not tuned away: a present-tense verb after a plural or "you" subject can be
 * read as the past of another verb ("they smelt iron ore", "deer bound across the road", "first
 * you saw the board in half").
 */
class HomographCorpusTest {
    @Serializable
    private data class Case(
        val source: String,
        val sentence: String,
        val word: String,
        val expectedBase: String,
        val expectedVerb: Boolean,
    )

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

    private val sources = setOf("constructed", "heldout1", "heldout2", "heldout3")

    @Test
    fun `each step is precise on the shared corpus`() {
        val text = javaClass.getResource("/homograph-corpus.json")?.readText() ?: fail("corpus missing")
        val corpus = Json.decodeFromString<Corpus>(text)
        val familyOf = families.flatMap { (family, forms) -> forms.map { it to family } }.toMap()
        val fired = mutableMapOf<Reading.Step, Int>()
        val right = mutableMapOf<Reading.Step, Int>()
        val perFamily = mutableMapOf<String, Int>()
        var unsure = 0
        for (case in corpus.cases) {
            assertTrue(case.source in sources, "unknown source ${case.source}")
            val whole = Regex("(?<![A-Za-z'’])${Regex.escape(case.word)}(?![A-Za-z'’])")
            val match = whole.find(case.sentence) ?: fail("'${case.word}' not in '${case.sentence}'")
            val range = WordRange(match.range.first, match.range.last + 1)
            val family = familyOf[TextTools.normalize(case.word)] ?: fail("'${case.word}' is in no family")
            perFamily[family] = (perFamily[family] ?: 0) + 1
            val reading = assertNotNull(IrregularVerbs.shared.resolve(case.sentence, range), "${case.word} is a homograph")
            if (reading.kind == Reading.Kind.UNSURE) {
                unsure++
                continue
            }
            fired[reading.step] = (fired[reading.step] ?: 0) + 1
            if (reading.base == case.expectedBase && (reading.kind == Reading.Kind.VERB) == case.expectedVerb) {
                right[reading.step] = (right[reading.step] ?: 0) + 1
            } else {
                println("corpus miss ${reading.step}: [${case.word}] ${case.sentence} -> ${reading.base}/${reading.kind}")
            }
        }
        assertTrue(corpus.cases.size >= 150, "only ${corpus.cases.size} cases")
        for (family in families.keys) assertTrue((perFamily[family] ?: 0) >= 30, "$family: ${perFamily[family]} cases (need 30)")
        val steps = Reading.Step.entries - Reading.Step.UNSURE
        for (step in steps) {
            val count = fired[step] ?: 0
            println("corpus $step: ${right[step] ?: 0}/$count right")
            assertTrue(count >= 15, "$step claims only $count cases (need 15)")
            val precision = (right[step] ?: 0).toDouble() / count
            assertTrue(precision >= 0.95, "$step precision ${"%.3f".format(precision)} < 0.95")
        }
        println("corpus unsure: $unsure of ${corpus.cases.size}")
        assertEquals(corpus.cases.size, fired.values.sum() + unsure)
    }
}
