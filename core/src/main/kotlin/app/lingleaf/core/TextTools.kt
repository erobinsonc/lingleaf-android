package app.lingleaf.core

/** A word inside a paragraph as UTF-16 offsets, the units Android text layout uses. */
data class WordRange(val start: Int, val end: Int) {
    operator fun contains(offset: Int): Boolean = offset in start until end
}

private val wordPattern = Regex("[A-Za-z]+(?:['’][A-Za-z]+)*")
private val sentenceEnd = Regex("[.!?]+[\"”’)\\]]*(?=\\s|$)")

object TextTools {
    /**
     * Splits text on blank lines and joins hard-wrapped lines
     * (Project Gutenberg wraps at ~70 columns).
     */
    fun splitParagraphs(text: String): List<String> {
        val paragraphs = mutableListOf<String>()
        val current = mutableListOf<String>()

        fun flush() {
            if (current.isNotEmpty()) {
                paragraphs += current.joinToString(" ")
                current.clear()
            }
        }
        for (line in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) flush() else current += trimmed
        }
        flush()
        return paragraphs
    }

    /** Every English word; contractions such as "don't" stay whole. */
    fun wordRanges(text: String): List<WordRange> = wordPattern.findAll(text).map { WordRange(it.range.first, it.range.last + 1) }.toList()

    /**
     * The word under [offset]. An offset right after the last letter still
     * counts, which matches how hit-testing reports taps on a word's right half.
     */
    fun wordAt(
        text: String,
        offset: Int,
    ): WordRange? {
        var trailing: WordRange? = null
        for (range in wordRanges(text)) {
            if (offset in range) return range
            if (range.end == offset) trailing = range
            if (range.start > offset) break
        }
        return trailing
    }

    fun normalize(word: String): String = word.replace('’', '\'').lowercase()

    /** The sentence of [paragraph] that contains [offset]. */
    fun sentenceAt(
        paragraph: String,
        offset: Int,
    ): String = sentenceRangeAt(paragraph, offset).let { paragraph.substring(it.start, it.end) }

    /**
     * Where [sentenceAt] is in [paragraph], without surrounding whitespace, so a
     * word's offset in the paragraph can be turned into its offset in the sentence.
     */
    fun sentenceRangeAt(
        paragraph: String,
        offset: Int,
    ): WordRange {
        var start = 0
        var end = paragraph.length
        for (match in sentenceEnd.findAll(paragraph)) {
            val matchEnd = match.range.last + 1
            if (matchEnd > offset) {
                end = matchEnd
                break
            }
            start = matchEnd
        }
        val sentence = trimmed(paragraph, start, end)
        return if (sentence.start == sentence.end) trimmed(paragraph, 0, paragraph.length) else sentence
    }

    private fun trimmed(
        text: String,
        from: Int,
        to: Int,
    ): WordRange {
        var start = from
        var end = to
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        return WordRange(start, end)
    }

    /**
     * Likely dictionary forms of [word], most likely first, never the word
     * itself. Used to look words up and to recognise inflected forms
     * ("animals", "walked") of a word being learned.
     */
    fun baseFormCandidates(word: String): List<String> {
        val w = normalize(word)
        val out = mutableListOf<String>()

        fun add(candidate: String) {
            if (candidate.length >= 2 && candidate != w && candidate !in out) out += candidate
        }

        IRREGULAR[w]?.let(::add)

        when {
            w.endsWith("'s") -> add(w.dropLast(2))
            w.endsWith("ies") && w.length > 4 -> add(w.dropLast(3) + "y")
            w.endsWith("es") && w.length > 3 -> {
                add(w.dropLast(1))
                add(w.dropLast(2))
            }
            w.endsWith("s") && !w.endsWith("ss") && w.length > 3 -> add(w.dropLast(1))
        }

        if (w.endsWith("ied") && w.length > 4) {
            add(w.dropLast(3) + "y")
        } else if (w.endsWith("ed") && w.length > 4) {
            val stem = w.dropLast(2)
            if (hasVowel(stem)) {
                if (isDoubled(stem)) add(stem.dropLast(1))
                add(stem)
                add(stem + "e")
            }
        }

        if (w.endsWith("ing") && w.length > 5) {
            val stem = w.dropLast(3)
            if (stem.length >= 3 && hasVowel(stem)) {
                if (isDoubled(stem)) add(stem.dropLast(1))
                add(stem)
                add(stem + "e")
            }
        }
        return out
    }

    /**
     * Where a saved word appears in [sentence], to mark it: its forms ("animals" for
     * "animal"), or, for a saved group of words ("did not go"), that group (ignoring case).
     */
    fun occurrences(
        word: String,
        sentence: String,
    ): List<WordRange> {
        val key = normalize(word)
        if (' ' in key) {
            val start = normalize(sentence).indexOf(key)
            return if (start < 0) emptyList() else listOf(WordRange(start, start + key.length))
        }
        return wordRanges(sentence).filter { key in matchKeys(sentence.substring(it.start, it.end)) }
    }

    /** The word plus its likely base forms: what to match against saved words. */
    fun matchKeys(word: String): Set<String> = setOf(normalize(word)) + baseFormCandidates(word)

    private fun hasVowel(s: String) = s.any { it in "aeiouy" }

    private fun isDoubled(stem: String) = stem.length >= 3 && stem.last() == stem[stem.length - 2] && stem.last() !in "aeiouslz"

    private val IRREGULAR =
        mapOf(
            "am" to "be", "is" to "be", "are" to "be", "was" to "be", "were" to "be", "been" to "be",
            "has" to "have", "had" to "have", "does" to "do", "did" to "do", "done" to "do",
            "went" to "go", "gone" to "go", "goes" to "go", "saw" to "see", "seen" to "see",
            "said" to "say", "made" to "make", "took" to "take", "taken" to "take",
            "came" to "come", "knew" to "know", "known" to "know", "thought" to "think",
            "got" to "get", "gotten" to "get", "gave" to "give", "given" to "give",
            "found" to "find", "told" to "tell", "felt" to "feel", "left" to "leave",
            "brought" to "bring", "began" to "begin", "begun" to "begin", "kept" to "keep",
            "stood" to "stand", "heard" to "hear", "ran" to "run", "sat" to "sit",
            "spoke" to "speak", "spoken" to "speak", "wrote" to "write", "written" to "write",
            "bought" to "buy", "caught" to "catch", "taught" to "teach", "fought" to "fight",
            "held" to "hold", "met" to "meet", "paid" to "pay", "sent" to "send", "spent" to "spend",
            "built" to "build", "lost" to "lose", "meant" to "mean", "slept" to "sleep",
            "won" to "win", "ate" to "eat", "eaten" to "eat", "drank" to "drink", "drunk" to "drink",
            "drove" to "drive", "driven" to "drive", "flew" to "fly", "flown" to "fly",
            "grew" to "grow", "grown" to "grow", "threw" to "throw", "thrown" to "throw",
            "fell" to "fall", "fallen" to "fall", "forgot" to "forget", "forgotten" to "forget",
            "broke" to "break", "broken" to "break", "chose" to "choose", "chosen" to "choose",
            "rode" to "ride", "ridden" to "ride", "rose" to "rise", "risen" to "rise",
            "sang" to "sing", "sung" to "sing", "swam" to "swim", "woke" to "wake",
            "wore" to "wear", "worn" to "wear", "understood" to "understand", "led" to "lead",
            "children" to "child", "men" to "man", "women" to "woman", "feet" to "foot",
            "teeth" to "tooth", "mice" to "mouse", "geese" to "goose", "lives" to "life",
            "wives" to "wife", "knives" to "knife", "leaves" to "leaf", "wolves" to "wolf",
            "better" to "good", "best" to "good", "worse" to "bad", "worst" to "bad",
        )
}
