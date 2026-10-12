package app.lingleaf.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A helper verb ("did" in "did not go") or a modal verb ("will" in "will go") found in a
 * sentence. Alone these words mean little or something misleading ("did" → "hizo"), so the
 * word sheet shows what they do in the sentence and translates the verb group instead.
 * Same rules as `FunctionWords` in lingleaf-ios.
 */
data class FunctionWord(
    val kind: Kind,
    /** The base form: "do" for "did" and "didn't", "will" for "won't". */
    val base: String,
    val use: Use,
    /**
     * The verb group in the sentence ("did not go", "should have known", "Can you help");
     * null when it can't be told reliably (a question whose subject is not a pronoun, or a
     * sentence that ends right after the word: "Yes, I can.").
     */
    val chunk: WordRange?,
) {
    @Serializable
    enum class Kind {
        /** do / does / did used to make a negative or a question. */
        @SerialName("helper")
        HELPER,

        /** can, could, will, would, shall, should, must, may, might. */
        @SerialName("modal")
        MODAL,
    }

    /** What the word does in this sentence; the sheet explains it with one short note. */
    sealed interface Use {
        data object PastNegative : Use

        data object PresentNegative : Use

        data object PastQuestion : Use

        data object PresentQuestion : Use

        /** "Don't touch that.": telling someone not to do something. */
        data object Prohibition : Use

        /** The modal's base form ("will" for "won't"). */
        data class Modal(val base: String) : Use
    }
}

object FunctionWords {
    private val doForms =
        mapOf(
            "do" to false,
            "does" to false,
            "did" to true,
            "don't" to false,
            "doesn't" to false,
            "didn't" to true,
        )

    private val modals =
        mapOf(
            "can" to "can",
            "cannot" to "can",
            "can't" to "can",
            "could" to "could",
            "couldn't" to "could",
            "will" to "will",
            "won't" to "will",
            "would" to "would",
            "wouldn't" to "would",
            "shall" to "shall",
            "shan't" to "shall",
            "should" to "should",
            "shouldn't" to "should",
            "must" to "must",
            "mustn't" to "must",
            "may" to "may",
            "might" to "might",
            "mightn't" to "might",
        )

    private val pronouns = setOf("i", "you", "he", "she", "it", "we", "they")
    private val questionWords = setOf("what", "where", "when", "why", "how", "who", "whom", "whose", "which")

    /** Skipped on the way to the main verb: "should have known", "will never be able". */
    private val inGroup =
        setOf(
            "have",
            "be",
            "been",
            "being",
            "never",
            "always",
            "ever",
            "really",
            "just",
            "also",
            "still",
            "even",
            "only",
            "already",
            "probably",
            "certainly",
            "surely",
            "often",
        )

    /** Before a modal these make it a noun: "a can of beans", "his will", "free will". */
    private val determiners =
        setOf(
            "a",
            "an",
            "the",
            "my",
            "your",
            "his",
            "her",
            "its",
            "our",
            "their",
            "free",
            "good",
            "ill",
        )

    /**
     * What the word at [range] of [sentence] does, or null for an ordinary word
     * (including "do" as a main verb: "I did my homework").
     */
    fun analyze(
        sentence: String,
        range: WordRange,
    ): FunctionWord? {
        val words = TextTools.wordRanges(sentence)
        val index = words.indexOf(range)
        if (index < 0) return null
        val surface = sentence.substring(range.start, range.end)
        val tokens = words.map { TextTools.normalize(sentence.substring(it.start, it.end)) }
        val token = tokens[index]
        val next = tokens.getOrNull(index + 1)
        val isQuestion = sentence.trimEnd(' ', '"', '”', '’', ')', ']').endsWith("?")
        val group = groupEnd(tokens, index, next, isQuestion)?.let { WordRange(range.start, words[it].end) }

        val past = doForms[token]
        if (past != null) {
            if (token.endsWith("n't") || next == "not") {
                // "Don't touch that." / "Do not touch": first word, no subject → a prohibition.
                if (index == 0 && !past && token != "doesn't" && token != "does" && !isQuestion) {
                    return FunctionWord(FunctionWord.Kind.HELPER, "do", FunctionWord.Use.Prohibition, group)
                }
                val use = if (past) FunctionWord.Use.PastNegative else FunctionWord.Use.PresentNegative
                return FunctionWord(FunctionWord.Kind.HELPER, "do", use, group)
            }
            val opensQuestion = index == 0 || tokens[index - 1] in questionWords
            if (!isQuestion || !opensQuestion) return null
            val use = if (past) FunctionWord.Use.PastQuestion else FunctionWord.Use.PresentQuestion
            return FunctionWord(FunctionWord.Kind.HELPER, "do", use, if (next in pronouns) group else null)
        }

        val base = modals[token] ?: return null
        if (index > 0 && tokens[index - 1] in determiners) return null
        // "May" and "Will" capitalized mid-sentence are a month and a name.
        if (index > 0 && (token == "may" || token == "will") && surface.first().isUpperCase()) return null
        return FunctionWord(FunctionWord.Kind.MODAL, base, FunctionWord.Use.Modal(base), group)
    }

    /**
     * The verb group's last word: the word, "not", a pronoun subject in a question
     * ("Can you help", "didn't you come"), have/be and adverbs ("should have known"),
     * then the main verb; up to the sentence's end if it comes first.
     */
    private fun groupEnd(
        tokens: List<String>,
        index: Int,
        next: String?,
        isQuestion: Boolean,
    ): Int? {
        var last = index
        if (next == "not") last += 1
        if (isQuestion && last + 1 < tokens.size && tokens[last + 1] in pronouns) last += 1
        var skipped = 0
        while (last + 1 < tokens.size && tokens[last + 1] in inGroup && skipped < 4) {
            last += 1
            skipped += 1
        }
        if (last + 1 < tokens.size) return last + 1
        return if (last > index) last else null
    }
}
