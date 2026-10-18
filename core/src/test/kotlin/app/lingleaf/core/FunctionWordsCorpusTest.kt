package app.lingleaf.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Precision of each rule on the shared annotated corpus (same file and thresholds as
 * lingleaf-ios): a rule must be right in at least 95% of the cases where it fires, and the
 * corpus must expect it at least 30 times. Every mismatch is printed.
 */
class FunctionWordsCorpusTest {
    @Serializable
    private data class Case(
        val source: String,
        val sentence: String,
        val word: String,
        val use: String?,
        val base: String? = null,
        val chunk: String? = null,
        val note: String? = null,
    )

    @Serializable
    private data class Corpus(val about: String, val cases: List<Case>)

    private val measured = setOf("presentPerfect", "pastPerfect", "hadBetter", "contraction", "modal")

    private fun name(use: FunctionWord.Use): String =
        when (use) {
            FunctionWord.Use.PastNegative -> "pastNegative"
            FunctionWord.Use.PresentNegative -> "presentNegative"
            FunctionWord.Use.PastQuestion -> "pastQuestion"
            FunctionWord.Use.PresentQuestion -> "presentQuestion"
            FunctionWord.Use.Prohibition -> "prohibition"
            FunctionWord.Use.QuestionTag -> "questionTag"
            is FunctionWord.Use.Modal -> "modal"
            FunctionWord.Use.PresentPerfect -> "presentPerfect"
            FunctionWord.Use.PastPerfect -> "pastPerfect"
            FunctionWord.Use.HadBetter -> "hadBetter"
            FunctionWord.Use.Contraction -> "contraction"
        }

    @Test
    fun `each rule is precise on the shared corpus`() {
        val text = javaClass.getResource("/function-words-corpus.json")?.readText() ?: fail("corpus missing")
        val corpus = Json.decodeFromString<Corpus>(text)
        val fired = mutableMapOf<String, Int>()
        val right = mutableMapOf<String, Int>()
        val expected = mutableMapOf<String, Int>()
        val mismatches = mutableListOf<String>()
        for (case in corpus.cases) {
            val whole = Regex("(?<![A-Za-z'’])${Regex.escape(case.word)}(?![A-Za-z'’])")
            val offset = whole.find(case.sentence)?.range?.first ?: fail("'${case.word}' not in '${case.sentence}'")
            val range = TextTools.wordAt(case.sentence, offset) ?: fail("no word at '${case.word}'")
            val result = FunctionWords.analyze(case.sentence, range)
            val got = result?.let { name(it.use) }
            val chunk = result?.chunk?.let { case.sentence.substring(it.start, it.end) }
            case.use?.let { expected[it] = (expected[it] ?: 0) + 1 }
            val correct = got == case.use && (got == null || (result.base == case.base && chunk == case.chunk))
            if (got != null) {
                fired[got] = (fired[got] ?: 0) + 1
                if (correct) right[got] = (right[got] ?: 0) + 1
            }
            if (!correct) {
                mismatches += "${case.sentence} [${case.word}]: expected ${case.use} ${case.base} '${case.chunk}', " +
                    "got $got ${result?.base} '$chunk'"
            }
        }
        mismatches.forEach { println("corpus mismatch: $it") }
        for (rule in measured) {
            val count = expected[rule] ?: 0
            assertTrue(count >= 30, "$rule: only $count cases expect it (need 30)")
            val precision = (right[rule] ?: 0).toDouble() / (fired[rule] ?: 1)
            println("corpus $rule: ${right[rule] ?: 0}/${fired[rule] ?: 0} right, $count expected")
            assertTrue(precision >= 0.95, "$rule precision ${"%.3f".format(precision)} < 0.95")
        }
    }
}
