package app.lingleaf.core

/** A word inside a paragraph as UTF-16 offsets, the units Android text layout uses. */
data class WordRange(val start: Int, val end: Int) {
    operator fun contains(offset: Int): Boolean = offset in start until end

    /** Inside [text] (possibly empty): safe to cut out of it. */
    fun fitsIn(text: String): Boolean = start in 0..end && end <= text.length
}

private val wordPattern = Regex("[A-Za-z]+(?:['’][A-Za-z]+)*")

// Starts only at the first mark of a run and never backtracks into it, so a long run of
// marks ("!!!!…x") is matched in one pass, not once per mark.
private val sentenceEnd = Regex("(?<![.!?])[.!?]++[\"”’)\\]]*+(?=\\s|$)")

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
     * Walks the text once with an index, so a 10 MB text without blank lines splits in linear time.
     */
    internal fun bounded(paragraph: String): List<String> {
        if (paragraph.length <= MAX_PARAGRAPH) return listOf(paragraph)
        val pieces = mutableListOf<String>()
        var start = 0
        while (true) {
            while (start < paragraph.length && paragraph[start].isWhitespace()) start++
            if (paragraph.length - start <= MAX_PARAGRAPH) {
                paragraph.substring(start).trim().takeIf { it.isNotEmpty() }?.let { pieces += it }
                return pieces
            }
            // One unit past the limit, so the sentence-end lookahead can see what follows.
            val window = paragraph.substring(start, start + MAX_PARAGRAPH + 1)
            var cut = sentenceEnd.findAll(window).map { it.range.last + 1 }.lastOrNull { it <= MAX_PARAGRAPH } ?: 0
            if (cut == 0) cut = (MAX_PARAGRAPH - 1 downTo 1).firstOrNull { window[it].isWhitespace() } ?: MAX_PARAGRAPH
            // Never between the halves of a surrogate pair, and always forward.
            if (cut > 1 && Character.isHighSurrogate(window[cut - 1])) cut--
            window.substring(0, cut).trim().takeIf { it.isNotEmpty() }?.let { pieces += it }
            start += cut
        }
    }

    /** At most [max] UTF-16 units of [text], never splitting a surrogate pair. */
    fun truncated(
        text: String,
        max: Int,
    ): String {
        if (text.length <= max) return text
        val end = if (max > 1 && Character.isHighSurrogate(text[max - 1])) max - 1 else max
        return text.substring(0, end)
    }

    /**
     * Outside text shown as a title or author: no control, invisible formatting or blank
     * filler characters (bidi overrides, zero-width, Hangul fillers), at most
     * [MAX_COMBINING_MARKS] accents on one letter, single spaces, at most [MAX_LABEL] UTF-16 units.
     */
    fun cleanLabel(text: String): String {
        val out = StringBuilder()
        var marks = 0
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            i += Character.charCount(codePoint)
            val type = Character.getType(codePoint).toByte()
            if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK) {
                if (++marks <= MAX_COMBINING_MARKS) out.appendCodePoint(codePoint)
                continue
            }
            marks = 0
            when {
                type == Character.FORMAT || codePoint in BLANK_FILLERS -> {}
                type == Character.CONTROL || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR -> out.append(' ')
                else -> out.appendCodePoint(codePoint)
            }
        }
        // Every Unicode space (no-break, ideographic…) counts, as on iOS. A loop, not a regex:
        // Android's regex engine rejects the (?U) flag that the JVM accepts.
        val single = StringBuilder()
        var space = false
        for (c in out) {
            if (c.isWhitespace()) {
                space = single.isNotEmpty()
            } else {
                if (space) single.append(' ')
                single.append(c)
                space = false
            }
        }
        return truncated(single.toString(), MAX_LABEL)
    }

    /** Accents kept on one letter: real text needs two or three; "Zalgo" text piles up thousands. */
    const val MAX_COMBINING_MARKS = 4

    /** Characters that show as blank space but are letters or symbols, not whitespace. */
    private val BLANK_FILLERS = setOf(0x115F, 0x1160, 0x3164, 0xFFA0, 0x2800)

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
    ): WordRange = capped(paragraph, sentenceBounds(paragraph, offset), offset)

    /**
     * Every sentence of [paragraph] in one pass, trimmed and not capped: the text between
     * sentence ends, as [sentenceRangeAt] finds them, skipping blank stretches.
     */
    internal fun sentenceRanges(paragraph: String): List<WordRange> {
        val out = mutableListOf<WordRange>()
        var start = 0
        for (end in sentenceEnd.findAll(paragraph).map { it.range.last + 1 } + paragraph.length) {
            if (end <= start) continue
            val sentence = trimmed(paragraph, start, end)
            if (sentence.start < sentence.end) out += sentence
            start = end
        }
        return out
    }

    /** The whole sentence of [paragraph] at [offset], trimmed and not capped. */
    private fun sentenceBounds(
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
     * ("animals", "walked", "stole") of a word being learned. A homograph of the
     * irregular-verb table ("left", "leaves") gives the base of its [reading] in its
     * sentence; without one (or unsure) only the base both readings share ("tears" → "tear").
     */
    fun baseFormCandidates(
        word: String,
        reading: Reading? = null,
    ): List<String> = candidates(word, reading, irregular = true)

    /** [irregular] false: a name ("Drew"), without the irregular-verb link. */
    private fun candidates(
        word: String,
        reading: Reading?,
        irregular: Boolean,
    ): List<String> {
        val w = normalize(word)
        val out = mutableListOf<String>()

        fun add(candidate: String) {
            if (candidate.length >= 2 && candidate != w && candidate !in out) out += candidate
        }

        val verbs = IrregularVerbs.shared
        if (verbs.isHomograph(w)) {
            val base = if (reading == null || reading.kind == Reading.Kind.UNSURE) verbs.sharedBase(w) else reading.base
            base?.let(::add)
            return out
        }
        ((if (irregular) verbs.formToBase[w] else null) ?: NON_VERB[w])?.let(::add)

        when {
            w.endsWith("'s") -> add(w.dropLast(2))
            w.endsWith("ies") && w.length > 4 -> add(w.dropLast(3) + "y")
            w.endsWith("es") && w.length > 3 -> {
                add(w.dropLast(1))
                add(w.dropLast(2))
            }
            w.endsWith("s") && !w.endsWith("ss") && w.length > 3 -> add(w.dropLast(1))
        }

        fun stemRules(stem: String) {
            when {
                // travell → travel, controll → control; not call, fill (one vowel group).
                stem.endsWith("ll") && vowelGroups(stem.dropLast(1)) >= 2 -> add(stem.dropLast(1))
                isDoubled(stem) -> add(stem.dropLast(1))
            }
            add(stem)
            add(stem + "e")
        }

        if (w.endsWith("ied") && w.length > 4) {
            add(w.dropLast(3) + "y")
        } else if (w.endsWith("ed") && w.length > 4) {
            val stem = w.dropLast(2)
            if (hasVowel(stem)) stemRules(stem)
        }

        if (w.endsWith("ing") && w.length > 5) {
            val stem = w.dropLast(3)
            if (stem.length >= 3 && hasVowel(stem)) stemRules(stem)
        }
        return out
    }

    /** [baseFormCandidates] of the word at [range] of [text], a homograph read in its sentence. */
    fun baseFormCandidates(
        text: String,
        range: WordRange,
    ): List<String> {
        if (!range.fitsIn(text) || range.start == range.end) return emptyList()
        val words = wordRanges(text)
        val index = words.indexOf(range)
        if (index < 0) return baseFormCandidates(text.substring(range.start, range.end))
        return readingAndCandidates(text, words, index).second
    }

    /** Word [index] of [words] (the word ranges of [text]): its reading and base forms. */
    private fun readingAndCandidates(
        text: String,
        words: List<WordRange>,
        index: Int,
    ): Pair<Reading?, List<String>> {
        val range = words[index] // checked by the callers
        val word = text.substring(range.start, range.end)
        val verbs = IrregularVerbs.shared
        val reading = verbs.resolve(text, words, index)
        return reading to candidates(word, reading, irregular = reading != null || !verbs.isName(text, words, index))
    }

    /**
     * Where a saved word appears in [sentence], to mark it: its forms ("animals" for
     * "animal", "stole" for "steal"; a homograph only where the sentence reads it that way),
     * or, for a saved group of words ("did not go"), that group (ignoring case).
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
        val words = wordRanges(sentence)
        return words.filterIndexed { index, _ -> key in matchKeys(sentence, words, index) }
    }

    /**
     * The words of [text] ([words], its word ranges) that are forms of one of [saved] (the
     * words being learned, normalised), homographs read in their sentence: what the reader
     * underlines. One pass over the text, constant work per word.
     */
    fun marked(
        text: String,
        words: List<WordRange>,
        saved: Set<String>,
    ): List<WordRange> {
        if (saved.isEmpty()) return emptyList()
        return words.filterIndexed { index, _ -> matchKeys(text, words, index).any { it in saved } }
    }

    /** The word plus its likely base forms: what to match against saved words. Homographs are unsure. */
    fun matchKeys(word: String): Set<String> = setOf(normalize(word)) + baseFormCandidates(word)

    /**
     * [matchKeys] of the word at [range] of [text]. A homograph read as a verb or noun with
     * another base ("she left" → "leave") doesn't match its own spelling: a saved "left"
     * (the side) isn't marked in "she left".
     */
    fun matchKeys(
        text: String,
        range: WordRange,
    ): Set<String> {
        if (!range.fitsIn(text) || range.start == range.end) return emptySet()
        val words = wordRanges(text)
        val index = words.indexOf(range)
        if (index < 0) return matchKeys(text.substring(range.start, range.end))
        return matchKeys(text, words, index)
    }

    /** [matchKeys] of word [index] of [words], the word ranges of [text]. */
    fun matchKeys(
        text: String,
        words: List<WordRange>,
        index: Int,
    ): Set<String> {
        val range = words.getOrNull(index)?.takeIf { it.fitsIn(text) } ?: return emptySet()
        val word = normalize(text.substring(range.start, range.end))
        val (reading, candidates) = readingAndCandidates(text, words, index)
        val claimed = reading != null && reading.kind != Reading.Kind.UNSURE && reading.base != word
        return if (claimed) candidates.toSet() else setOf(word) + candidates
    }

    private fun hasVowel(s: String) = s.any { it in "aeiouy" }

    private val vowelGroup = Regex("[aeiouy]+")

    private fun vowelGroups(s: String) = vowelGroup.findAll(s).count()

    private fun isDoubled(stem: String) = stem.length >= 3 && stem.last() == stem[stem.length - 2] && stem.last() !in "aeiouslz"

    /** Irregular nouns and comparatives; irregular verbs are in [IrregularVerbs]. */
    private val NON_VERB =
        mapOf(
            "children" to "child", "men" to "man", "women" to "woman", "feet" to "foot",
            "teeth" to "tooth", "mice" to "mouse", "geese" to "goose",
            "wives" to "wife", "knives" to "knife", "wolves" to "wolf",
            "better" to "good", "best" to "good", "worse" to "bad", "worst" to "bad",
        )
}
