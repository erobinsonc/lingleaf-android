package app.lingleaf.core

import kotlinx.serialization.Serializable

/**
 * Which meaning of a spelling a saved word is (SPEC S4): a homograph read as a verb or a noun
 * ("leave" from "she left", "left" from "turn left"), or a verb's regular twin ("lie" from "he
 * lied": to say what isn't true). One spelling can be saved once per sense.
 */
object Sense {
    const val VERB = "verb"
    const val NOUN = "noun"
    const val HOMONYM = "homonym"

    val all = setOf(VERB, NOUN, HOMONYM)

    /** The sense of a certain or chosen reading; null for unsure. */
    fun of(reading: Reading?): String? =
        when (reading?.kind) {
            Reading.Kind.VERB -> VERB
            Reading.Kind.NOUN -> NOUN
            else -> null
        }
}

/**
 * One reading of a word saved unsure (SPEC S4): a verb "to {base}" or a noun "the {base}", and
 * its meaning in the translation language (empty until translated).
 */
@Serializable
data class SavedMeaning(
    val kind: String,
    val base: String,
    val meaning: String = "",
) {
    /** "to leave", "the left". */
    val label: String get() = if (kind == Sense.NOUN) "the $base" else "to $base"

    companion object {
        const val MAX_MEANING = 200
        private val basePattern = Regex("^[a-z]{1,20}$")

        fun of(reading: Reading): SavedMeaning = SavedMeaning(Sense.of(reading) ?: Sense.VERB, reading.base)

        /** [meanings] as read back from storage: at most 2, each a known kind and a–z base, its meaning cleaned. */
        fun bounded(meanings: List<SavedMeaning>?): List<SavedMeaning>? =
            meanings
                ?.filter { (it.kind == Sense.VERB || it.kind == Sense.NOUN) && basePattern.matches(it.base) }
                ?.take(2)
                ?.map { it.copy(meaning = TextTools.truncated(TextTools.cleanLabel(it.meaning), MAX_MEANING)) }
                ?.takeIf { it.isNotEmpty() }
    }
}

/**
 * What the reader underlines (SPEC S4), from the words being learned: a word saved unsure
 * marks that exact form everywhere; a word with a sense only forms read as that sense; an
 * older word without one any of its forms, as before.
 */
data class LearningIndex(
    /** Words saved unsure: the tapped form, marked wherever it appears. */
    val exactForms: Set<String> = emptySet(),
    /** Words without a sense: marked by any of their forms. */
    val anySense: Set<String> = emptySet(),
    /** Words with a sense, and which senses of each are saved. */
    val sensed: Map<String, Set<String>> = emptyMap(),
) {
    val isEmpty: Boolean get() = exactForms.isEmpty() && anySense.isEmpty() && sensed.isEmpty()

    /** Whether a word whose spelling is [surface] and whose keys are [keys] is a form of a word being learned. */
    internal fun matches(
        surface: String,
        keys: List<MatchKey>,
    ): Boolean {
        if (surface in exactForms) return true
        return keys.any { key ->
            key.word in anySense ||
                sensed[key.word]?.let { senses ->
                    when (key.sense) {
                        null -> true
                        UNSURE -> false
                        else -> key.sense in senses
                    }
                } == true
        }
    }

    companion object {
        /** A key from an unsure homograph: never marks a word saved with a sense. */
        internal const val UNSURE = "unsure"

        fun of(words: Collection<VocabWord>): LearningIndex {
            val learning = words.filter { !it.learned }
            return LearningIndex(
                exactForms = learning.filter { it.sense == null && it.meanings != null }.mapTo(mutableSetOf()) { it.word },
                anySense = learning.filter { it.sense == null && it.meanings == null }.mapTo(mutableSetOf()) { it.word },
                sensed =
                    learning
                        .filter { it.sense != null }
                        .groupBy { it.word }
                        .mapValues { (_, list) -> list.mapNotNullTo(mutableSetOf()) { it.sense } },
            )
        }
    }
}

/** A base form a word leads to, and the sense it leads there in (null: any). */
internal data class MatchKey(val word: String, val sense: String?)

/** The translation of a reading's meaning (SPEC S3, L1). */
object Meanings {
    /** What to translate for [reading]: "to leave", "the left". */
    fun query(reading: Reading): String = if (reading.kind == Reading.Kind.NOUN) "the ${reading.base}" else "to ${reading.base}"

    /** What to translate for a saved meaning. */
    fun query(meaning: SavedMeaning): String = meaning.label

    /**
     * The table's Spanish meanings of a verb reading joined with " · " ("irse, salir · dejar")
     * when [language] is Spanish, else null (translate [query] instead). [first] keeps only the
     * first meaning group, for the unsure card's rows.
     */
    fun fromTable(
        reading: Reading,
        language: String?,
        first: Boolean = false,
        homonym: Boolean = false,
    ): String? {
        if (reading.kind != Reading.Kind.VERB || language?.substringBefore('-') != "es") return null
        val meanings = IrregularVerbs.shared.meanings(reading.base, homonym)
        if (meanings.isEmpty()) return null
        return if (first) meanings.first() else meanings.joinToString(" · ")
    }

    /**
     * A translation of [query] without what only the query needed: an English "to"/"the" the
     * translator kept, and the target language's leading article (a noun) or infinitive
     * marker (a verb): "la izquierda" → "izquierda", "zu verlassen" → "verlassen".
     */
    fun clean(
        translation: String,
        noun: Boolean,
        language: String?,
    ): String {
        var text = translation.trim().trimEnd('.', '。').trim()
        val english = if (noun) "the " else "to "
        if (text.startsWith(english, ignoreCase = true)) text = text.substring(english.length).trim()
        val lang = language?.substringBefore('-')?.lowercase()
        val leading = (if (noun) articles[lang] else markers[lang]).orEmpty()
        for (word in leading) {
            if (text.length > word.length && text.startsWith(word, ignoreCase = true)) {
                val rest = text.substring(word.length)
                // "l'" joins the noun; other words are followed by a space.
                if (word.endsWith("'") || word.endsWith("’") || rest.startsWith(" ")) {
                    text = rest.trim()
                    break
                }
            }
        }
        return text.ifEmpty { translation.trim() }
    }

    private val articles =
        mapOf(
            "es" to listOf("el", "la", "los", "las", "lo"),
            "pt" to listOf("o", "a", "os", "as"),
            "fr" to listOf("le", "la", "les", "l'", "l’"),
            "it" to listOf("il", "lo", "la", "i", "gli", "le", "l'", "l’"),
            "de" to listOf("der", "die", "das"),
            "nl" to listOf("de", "het"),
            "ca" to listOf("el", "la", "els", "les", "l'", "l’"),
        )

    private val markers =
        mapOf(
            "de" to listOf("zu"),
            "nl" to listOf("te"),
            "sv" to listOf("att"),
            "da" to listOf("at"),
            "nb" to listOf("å"),
            "no" to listOf("å"),
        )
}
