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
    /** The entry as the correction wrote it (null when merged): Undo only removes it if unchanged. */
    val result: VocabWord? = null,
) {
    /** As read back from storage: names cleaned and short, the key bounded, both words through [word]. */
    internal fun bounded(word: (VocabWord) -> VocabWord): Correction =
        copy(
            from = TextTools.truncated(TextTools.cleanLabel(from), MAX_NAME),
            to = TextTools.truncated(TextTools.cleanLabel(to), MAX_NAME),
            key = TextTools.truncated(TextTools.normalize(key), MAX_KEY),
            original = word(original),
            result = result?.let(word),
        )

    companion object {
        const val MAX_NAME = 40
        private const val MAX_KEY = 100
    }
}

/** Reads a word saved before homographs were read in their sentence against that sentence again. */
internal object Corrections {
    /**
     * [word] corrected to what its sentence says, or null when it still reads the same (or
     * can't be told apart: a verb group, a word without a sentence, one saved with a sense).
     * The tapped occurrence is the homograph of the sentence that led to [word] then ("left" for
     * "leave"); a word that some other word of the sentence still leads to is left alone.
     */
    fun correct(
        word: VocabWord,
        language: String? = null,
    ): VocabWord? {
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
                // Saved as the form itself, or as the base both readings share ("tear" from "Tears fell."): it stays.
                if (word.word == surface || word.word in TextTools.matchKeys(text, words, index)) return null
                val choices = verbs.choices(text, words, index)
                if (choices.isEmpty()) return null
                // A verb's meaning is the table's in Spanish; the others come with the translation.
                val meanings = choices.map { SavedMeaning.of(it).copy(meaning = Meanings.fromTable(it, language, first = true).orEmpty()) }
                fresh.copy(word = surface, sense = null, meanings = meanings, translationLanguage = language)
            }
            reading.base == word.word -> null
            // A name ("Aunt Rose") is the word itself, with no sense.
            reading.step == Reading.Step.NAME -> fresh.copy(word = reading.base, sense = null, meanings = null)
            // An expression ("bound for") becomes the unit, with its meaning in Spanish.
            reading.kind == Reading.Kind.EXPRESSION -> {
                val meaning = Meanings.fromTable(reading, language).orEmpty()
                fresh.copy(
                    word = reading.base,
                    sense = null,
                    meanings = null,
                    translation = meaning,
                    translationLanguage =
                        language.takeIf {
                            meaning.isNotEmpty()
                        },
                )
            }
            else -> {
                val sense = Sense.of(reading)
                val verb = sense == Sense.VERB || sense == Sense.HOMONYM
                // A verb gets the table's IPA for its base, and in Spanish the table's meaning now.
                val meaning = Meanings.fromTable(reading, language).orEmpty()
                fresh.copy(
                    word = reading.base,
                    sense = sense,
                    meanings = null,
                    // A verb the table's IPA for its base; a noun of tear, lead or wind its sentence's.
                    phonetic =
                        (if (verb) verbs.ipa(reading.base, "base") else verbs.nounIpa(text, words, index))?.let { "/$it/" }.orEmpty(),
                    translation = meaning,
                    translationLanguage = language.takeIf { meaning.isNotEmpty() },
                )
            }
        }
    }
}
