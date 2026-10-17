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
    fun sentences(paragraph: String): List<WordRange> =
        // One pass over the paragraph: a hostile one ("a.a.a.…") stays linear.
        TextTools.sentenceRanges(paragraph).flatMap { pieces(paragraph, it) }

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
            if (end == start) {
                end = start + TextTools.MAX_SENTENCE
                // No space to cut at: keep a surrogate pair (an emoji) whole.
                if (text[end].isLowSurrogate()) end--
            }
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

    /** The sentence before [current], skipping empty paragraphs, or null when it was the book's first. */
    fun previous(
        paragraphs: List<String>,
        current: SentencePosition,
    ): SentencePosition? {
        sentences(paragraphs.getOrElse(current.paragraph) { "" })
            .lastOrNull { it.end <= current.range.start }
            ?.let { return SentencePosition(current.paragraph, it) }
        for (paragraph in minOf(current.paragraph, paragraphs.size) - 1 downTo 0) {
            sentences(paragraphs[paragraph]).lastOrNull()?.let { return SentencePosition(paragraph, it) }
        }
        return null
    }

    /**
     * How many sentences there are from [first] to [last], both included (a repeated stretch:
     * "Repeating 3 sentences"); 0 when [last] comes before [first].
     */
    fun count(
        paragraphs: List<String>,
        first: SentencePosition,
        last: SentencePosition,
    ): Int {
        if (first.paragraph < 0 || last.paragraph >= paragraphs.size || first.paragraph > last.paragraph) return 0
        if (last.range !in sentences(paragraphs[last.paragraph])) return 0
        // One split per paragraph of the stretch, not one per sentence.
        return (first.paragraph..last.paragraph).sumOf { paragraph ->
            sentences(paragraphs[paragraph]).count {
                (paragraph > first.paragraph || it.start >= first.range.start) &&
                    (paragraph < last.paragraph || it.end <= last.range.end)
            }
        }
    }

    /** The sentence after [current], or null when it was the book's last. */
    fun next(
        paragraphs: List<String>,
        current: SentencePosition,
    ): SentencePosition? = start(paragraphs, current.paragraph, current.range.end)
}
