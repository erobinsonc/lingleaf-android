package app.lingleaf.core

import kotlinx.serialization.Serializable

/**
 * A saved word corrected to match its sentence (SPEC S18): shown as "“[from]” → “[to]”" until
 * the card is closed; Undo puts [original] back and removes the entry with [key] unless the
 * correction was [merged] into one saved before.
 */
@Serializable
data class Correction(
    val from: String,
    val to: String,
    val original: VocabWord,
    val key: String,
    val merged: Boolean,
)

/** Reads a word saved before homographs were read in their sentence against that sentence again. */
internal object Corrections {
    /**
     * [word] corrected to what its sentence says, or null when it still reads the same (or
     * can't be told apart: a verb group, a word without a sentence, one saved with a sense).
     * The tapped occurrence is the homograph of the sentence that led to [word] then ("left" for
     * "leave"); a word that some other word of the sentence still leads to is left alone.
     */
    fun correct(word: VocabWord): VocabWord? {
        if (word.kind != null || word.sense != null || word.meanings != null || word.context.isBlank() || ' ' in word.word) return null
        val text = word.context
        val words = TextTools.wordRanges(text)
        val verbs = IrregularVerbs.shared
        var tapped: Int? = null
        for ((index, range) in words.withIndex()) {
            val surface = TextTools.normalize(text.substring(range.start, range.end))
            if (verbs.isHomograph(surface)) {
                if (tapped == null && (word.word == surface || word.word in TextTools.oldCandidates(surface))) tapped = index
            } else if (word.word in TextTools.matchKeys(text, words, index)) {
                return null
            }
        }
        val index = tapped ?: return null
        val range = words[index]
        val shown = text.substring(range.start, range.end)
        val surface = TextTools.normalize(shown)
        val reading = verbs.resolve(text, words, index) ?: return null
        val fresh =
            word.copy(
                form = shown,
                formStart = range.start,
                formEnd = range.end,
                translation = "",
                translationLanguage = null,
                phonetic = "",
            )
        return when {
            reading.kind == Reading.Kind.UNSURE -> {
                if (word.word == surface) return null
                val choices = verbs.choices(text, words, index).map(SavedMeaning::of)
                if (choices.isEmpty()) return null
                fresh.copy(word = surface, sense = null, meanings = choices)
            }
            reading.base == word.word -> null
            // A name ("Aunt Rose") is the word itself, with no sense.
            reading.step == Reading.Step.NAME -> fresh.copy(word = reading.base, sense = null, meanings = null)
            else -> fresh.copy(word = reading.base, sense = Sense.of(reading), meanings = null)
        }
    }
}
