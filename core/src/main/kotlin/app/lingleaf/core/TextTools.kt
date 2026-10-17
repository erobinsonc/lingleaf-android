package app.lingleaf.core

/** A word inside a paragraph as UTF-16 offsets, the units Android text layout uses. */
data class WordRange(val start: Int, val end: Int) {
    operator fun contains(offset: Int): Boolean = offset in start until end
}

private val wordPattern = Regex("[A-Za-z]+(?:['’][A-Za-z]+)*")
private val sentenceEnd = Regex("[.!?]+[\"”’)\\]]*(?=\\s|$)")

object TextTools {
    /**
     * Longest paragraph kept whole, in UTF-16 units: a text without blank lines can't
     * become one paragraph the size of a book.
     */
    const val MAX_PARAGRAPH = 2000

    /** Longest sentence kept as a word's context, in UTF-16 units. */
    const val MAX_SENTENCE = 600

    /** Longest title or author shown or saved, in characters. */
    const val MAX_LABEL = 200

    /**
     * Splits text on blank lines and joins hard-wrapped lines
     * (Project Gutenberg wraps at ~70 columns). Paragraphs over [MAX_PARAGRAPH] are cut
     * after their last sentence end that fits, else at a space.
     */
    fun splitParagraphs(text: String): List<String> {
        val paragraphs = mutableListOf<String>()
        val current = mutableListOf<String>()

        fun flush() {
            if (current.isNotEmpty()) {
                paragraphs += bounded(current.joinToString(" "))
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

    /**
     * [paragraph] in pieces of at most [MAX_PARAGRAPH] UTF-16 units, each cut after the last
     * sentence end that fits, else at the last space, else (no spaces at all) at the limit.
     */
    internal fun bounded(paragraph: String): List<String> {
        var rest = paragraph
        val pieces = mutableListOf<String>()
        while (rest.length > MAX_PARAGRAPH) {
            // One unit past the limit, so the sentence-end lookahead can see what follows.
            val window = rest.substring(0, MAX_PARAGRAPH + 1)
            var cut = sentenceEnd.findAll(window).map { it.range.last + 1 }.lastOrNull { it <= MAX_PARAGRAPH } ?: 0
            if (cut == 0) cut = (MAX_PARAGRAPH - 1 downTo 1).firstOrNull { rest[it] == ' ' || rest[it] == '\t' } ?: MAX_PARAGRAPH
            if (Character.isHighSurrogate(rest[cut - 1])) cut--
            val piece = rest.substring(0, cut).trim()
            if (piece.isNotEmpty()) pieces += piece
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) pieces += rest
        return pieces
    }

    /**
     * Outside text shown as a title or author: no control or invisible formatting
     * characters (bidi overrides, zero-width), single spaces, at most [MAX_LABEL] characters.
     */
    fun cleanLabel(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            when (Character.getType(codePoint).toByte()) {
                Character.FORMAT -> {}
                Character.CONTROL, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> out.append(' ')
                else -> out.appendCodePoint(codePoint)
            }
            i += Character.charCount(codePoint)
        }
        val single = out.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        if (single.length <= MAX_LABEL) return single
        val end = if (Character.isHighSurrogate(single[MAX_LABEL - 1])) MAX_LABEL - 1 else MAX_LABEL
        return single.substring(0, end)
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
        val found = if (sentence.start == sentence.end) trimmed(paragraph, 0, paragraph.length) else sentence
        return capped(paragraph, found, offset)
    }

    /**
     * At most [MAX_SENTENCE] units of [range] around [offset], cut at spaces: a word's saved
     * context stays a readable snippet even in a text without sentence ends.
     */
    private fun capped(
        text: String,
        range: WordRange,
        offset: Int,
    ): WordRange {
        if (range.end - range.start <= MAX_SENTENCE) return range
        val end = minOf(range.end, maxOf(range.start, minOf(offset, range.end) - MAX_SENTENCE / 2) + MAX_SENTENCE)
        val start = maxOf(range.start, end - MAX_SENTENCE)
        var s = start
        var e = end
        // Start just after a space and end just before one, so no word is cut in half.
        if (s > range.start) while (s < e && !text[s - 1].isWhitespace()) s++
        if (e < range.end) while (e > s && !text[e].isWhitespace()) e--
        if (s >= e) {
            s = start
            e = end
        }
        return trimmed(text, s, e)
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
