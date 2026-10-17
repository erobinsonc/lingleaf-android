package app.lingleaf.core

/** A sentence of a book: its paragraph and where it is in that paragraph. */
data class SentencePosition(
    val paragraph: Int,
    val range: WordRange,
)

/**
 * Reading aloud sentence by sentence (SPEC S6): where to start and what comes next.
 * Same rules as `ReadAloud` in lingleaf-ios.
 */
object ReadAloud {
    /**
     * The sentences of [paragraph], in order and trimmed, as [TextTools.sentenceRangeAt]
     * finds them; a very long one comes in consecutive pieces of at most
     * [TextTools.MAX_SENTENCE] units, cut at spaces, so nothing is read twice or skipped.
     */
    fun sentences(paragraph: String): List<WordRange> {
        val out = mutableListOf<WordRange>()
        var at = 0
        while (true) {
            while (at < paragraph.length && paragraph[at].isWhitespace()) at++
            if (at >= paragraph.length) break
            val sentence = TextTools.sentenceBounds(paragraph, at)
            // Each sentence ends after the last one; anything else would read a sentence twice.
            if (sentence.end <= at) break
            out += pieces(paragraph, WordRange(maxOf(at, sentence.start), sentence.end))
            at = sentence.end
        }
        return out
    }

    /** [range] in pieces of at most [TextTools.MAX_SENTENCE] units, cut before a space when there is one. */
    private fun pieces(
        text: String,
        range: WordRange,
    ): List<WordRange> {
        val out = mutableListOf<WordRange>()
        var start = range.start
        while (range.end - start > TextTools.MAX_SENTENCE) {
            var end = start + TextTools.MAX_SENTENCE
            while (end > start && !text[end].isWhitespace()) end--
            if (end == start) end = start + TextTools.MAX_SENTENCE
            out += WordRange(start, end)
            start = end
            while (start < range.end && text[start].isWhitespace()) start++
        }
        if (start < range.end) out += WordRange(start, range.end)
        return out
    }

    /**
     * The first sentence to read from [offset] of paragraph [index]: the one there, or the
     * next one, skipping empty paragraphs; null after the last sentence.
     */
    fun start(
        paragraphs: List<String>,
        index: Int,
        offset: Int = 0,
    ): SentencePosition? {
        for (paragraph in index.coerceAtLeast(0) until paragraphs.size) {
            val from = if (paragraph == index) offset else 0
            sentences(paragraphs[paragraph]).firstOrNull { it.end > from }?.let { return SentencePosition(paragraph, it) }
        }
        return null
    }

    /** The sentence after [current], or null when it was the book's last. */
    fun next(
        paragraphs: List<String>,
        current: SentencePosition,
    ): SentencePosition? = start(paragraphs, current.paragraph, current.range.end)
}
