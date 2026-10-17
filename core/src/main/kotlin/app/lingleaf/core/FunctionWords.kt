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
    /**
     * A negative modal: "mustn't", "cannot", "will not". Its note says so, since the
     * meaning can flip ("must" = necessary, "mustn't" = not allowed).
     */
    val negative: Boolean = false,
    /**
     * For a pronoun contraction, what it stands for ("it is" for "it's", "I will" for
     * "I'll"); also its base form. Null for other words.
     */
    val expansion: String? = null,
) {
    @Serializable
    enum class Kind {
        /** do / does / did used to make a negative or a question. */
        @SerialName("helper")
        HELPER,

        /** can, could, will, would, shall, should, must, may, might. */
        @SerialName("modal")
        MODAL,

        /** A pronoun and a short verb: "it's" (= "it is"), "let's" (= "let us"). */
        @SerialName("contraction")
        CONTRACTION,
    }

    /** What the word does in this sentence; the sheet explains it with one short note. */
    sealed interface Use {
        data object PastNegative : Use

        data object PresentNegative : Use

        data object PastQuestion : Use

        data object PresentQuestion : Use

        /** "Don't touch that.": telling someone not to do something. */
        data object Prohibition : Use

        /** "You went, didn't you?": asks the listener to agree, like "right?". */
        data object QuestionTag : Use

        /** The modal's base form ("will" for "won't"). */
        data class Modal(val base: String) : Use

        /** have/has + past participle: "has seen". */
        data object PresentPerfect : Use

        /** had + past participle: "had left". */
        data object PastPerfect : Use

        /** "had better" / "'d better" + verb: strong advice. */
        data object HadBetter : Use

        /** A pronoun contraction that is not a helper here: "it's cold" (= "it is"). */
        data object Contraction : Use
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

    private val haveForms =
        mapOf(
            "have" to false,
            "has" to false,
            "had" to true,
            "haven't" to false,
            "hasn't" to false,
            "hadn't" to true,
        )

    /** Pronoun contractions and their expansions; "'s" and "'d" have two (see [analyze]). */
    private val contractions: Map<String, Pair<String, String>> =
        buildMap {
            fun add(
                pronouns: List<String>,
                suffix: String,
                verb: String,
            ) {
                for (pronoun in pronouns) put(pronoun + suffix, (if (pronoun == "i") "I" else pronoun) to verb)
            }
            add(listOf("i"), "'m", "am")
            add(listOf("you", "we", "they", "what", "who"), "'re", "are")
            add(listOf("he", "she", "it", "that", "there", "here", "what", "who", "where", "how"), "'s", "is")
            add(listOf("i", "you", "we", "they", "who"), "'ve", "have")
            add(listOf("i", "you", "he", "she", "it", "we", "they", "that", "there", "who"), "'ll", "will")
            add(listOf("i", "you", "he", "she", "it", "we", "they", "that", "there", "who"), "'d", "would")
            put("let's", "let" to "us")
        }

    /** Adverbs between a helper and its participle: "has never seen". */
    private val adverbs =
        setOf(
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
            "not",
            "recently",
            "finally",
            "almost",
            "nearly",
            "before",
            "once",
            "yet",
        )

    /** Before a noun subject in a question or a condition: "Has the post arrived?". */
    private val subjectDeterminers =
        setOf("the", "a", "an", "my", "your", "his", "her", "our", "their", "this", "that", "these", "those")

    private val pronouns = setOf("i", "you", "he", "she", "it", "we", "they")
    private val questionWords = setOf("what", "where", "when", "why", "how", "who", "whom", "whose", "which")

    /** Words that may come before a prohibition: "Please don't go", "And don't forget". */
    private val leadIns = setOf("please", "oh", "now", "so", "and", "but", "then", "well", "no", "yes", "just")

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

        // A question tag: ", didn't you?" / ", can't she?" closing the sentence.
        val before = if (index > 0) sentence.substring(words[index - 1].end, range.start) else ""
        if (isQuestion && index > 0 && ',' in before && index == tokens.size - 2 && tokens[index + 1] in pronouns) {
            if (token in doForms) return FunctionWord(FunctionWord.Kind.HELPER, "do", FunctionWord.Use.QuestionTag, null)
            if (token in haveForms) return FunctionWord(FunctionWord.Kind.HELPER, "have", FunctionWord.Use.QuestionTag, null)
            modals[token]?.let { return FunctionWord(FunctionWord.Kind.MODAL, it, FunctionWord.Use.QuestionTag, null) }
        }

        /*
         * The index of the past participle after the helper ("has never seen"), past a
         * pronoun subject in a question ("Have you seen"), adverbs ("had evidently heard"); one
         * more word after "been" when it is -ing or a participle ("has been reading"), so
         * "has been here" is "has been". [baseLike] as in [Verbs].
         */
        fun participle(
            subject: Boolean,
            baseLike: Boolean = true,
        ): Int? {
            var at = index + 1
            // "Have you seen…?", "Has the post arrived?", and "Had they known, …" (a condition).
            if (subject && (isQuestion || index == 0) && at < tokens.size) {
                if (tokens[at] in pronouns) {
                    at += 1
                } else if (tokens[at] in subjectDeterminers && at + 1 < tokens.size) {
                    at += 2
                }
            }
            var skipped = 0
            while (at < tokens.size && (tokens[at] in adverbs || tokens[at].endsWith("ly")) && skipped < 4) {
                at += 1
                skipped += 1
            }

            // Part of a hyphenated word ("read-only") is not a verb form.
            fun verbForm(at: Int) = sentence.getOrNull(words[at].end) != '-'
            if (at >= tokens.size || !Verbs.isParticiple(tokens[at], baseLike) || !verbForm(at)) return null
            if (tokens[at] == "been" && at + 1 < tokens.size) {
                val after = tokens[at + 1]
                if ((after.endsWith("ing") || Verbs.isParticiple(after)) && verbForm(at + 1)) return at + 1
            }
            return at
        }

        // "had better go" / "had better not": the index of the group's last word, or null.
        val betterVerb =
            when {
                next != "better" -> null
                tokens.getOrNull(index + 2) == "not" ->
                    if (tokens.getOrNull(index + 3)?.let(Verbs::isBase) == true) index + 3 else index + 2
                tokens.getOrNull(index + 2)?.let(Verbs::isBase) == true -> index + 2
                else -> null
            }

        fun through(end: Int) = WordRange(range.start, words[end].end)

        val contraction = contractions[token]
        if (contraction != null) return contracted(contraction, range, next, group, betterVerb, ::participle, ::through, index, tokens)

        val havePast = haveForms[token]
        if (havePast != null) {
            if (havePast && token == "had" && betterVerb != null) {
                return FunctionWord(FunctionWord.Kind.HELPER, "have", FunctionWord.Use.HadBetter, through(betterVerb))
            }
            val end = participle(subject = true) ?: return null
            val negative = token.endsWith("n't") || next == "not"
            val use = if (havePast) FunctionWord.Use.PastPerfect else FunctionWord.Use.PresentPerfect
            return FunctionWord(FunctionWord.Kind.HELPER, "have", use, through(end), negative)
        }

        val past = doForms[token]
        if (past != null) {
            if (token.endsWith("n't") || next == "not") {
                // "Don't touch that." / "Do not touch": first word, no subject → a prohibition.
                if (tokens.take(index).all { it in leadIns } && !past && token != "doesn't" && token != "does" && !isQuestion) {
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
        val negative = token.endsWith("n't") || token == "cannot" || next == "not"
        return FunctionWord(FunctionWord.Kind.MODAL, base, FunctionWord.Use.Modal(base), group, negative)
    }

    /** A pronoun contraction: what it stands for and, when it is a helper here, what it does. */
    @Suppress("LongParameterList")
    private fun contracted(
        contraction: Pair<String, String>,
        range: WordRange,
        next: String?,
        group: WordRange?,
        betterVerb: Int?,
        participle: (Boolean, Boolean) -> Int?,
        through: (Int) -> WordRange,
        index: Int,
        tokens: List<String>,
    ): FunctionWord {
        val (pronoun, verb) = contraction

        fun word(
            kind: FunctionWord.Kind,
            verb: String,
            use: FunctionWord.Use,
            chunk: WordRange?,
            negative: Boolean = false,
            expansion: String = "$pronoun $verb",
        ) = FunctionWord(kind, expansion, use, chunk, negative, expansion)
        return when {
            verb == "is" && next in setOf("been", "gone", "had") ->
                word(FunctionWord.Kind.HELPER, "has", FunctionWord.Use.PresentPerfect, through(participle(false, true) ?: index + 1))
            verb == "is" && next == "got" -> word(FunctionWord.Kind.CONTRACTION, "has", FunctionWord.Use.Contraction, range)
            verb == "have" && participle(false, true) != null ->
                word(FunctionWord.Kind.HELPER, "have", FunctionWord.Use.PresentPerfect, through(participle(false, true)!!))
            verb == "would" && betterVerb != null -> word(FunctionWord.Kind.HELPER, "had", FunctionWord.Use.HadBetter, through(betterVerb))
            verb == "would" && participle(false, false) != null ->
                word(FunctionWord.Kind.HELPER, "had", FunctionWord.Use.PastPerfect, through(participle(false, false)!!))
            // "He'd put", "she'd run": had or would, only the context tells; the card says both.
            verb == "would" && participle(false, true) != null ->
                word(
                    FunctionWord.Kind.CONTRACTION,
                    verb,
                    FunctionWord.Use.Contraction,
                    through(participle(false, true)!!),
                    expansion = "$pronoun had / $pronoun would",
                )
            // Up to the sentence's end if it comes first: "…, she'll."
            verb == "would" || verb == "will" ->
                word(FunctionWord.Kind.MODAL, verb, FunctionWord.Use.Modal(verb), group ?: range, negative = next == "not")
            // "Let's go": the suggestion with its verb.
            verb == "us" ->
                word(
                    FunctionWord.Kind.CONTRACTION,
                    verb,
                    FunctionWord.Use.Contraction,
                    if (index + 1 < tokens.size) through(index + 1) else range,
                )
            else -> word(FunctionWord.Kind.CONTRACTION, verb, FunctionWord.Use.Contraction, range)
        }
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
