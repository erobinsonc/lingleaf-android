package app.lingleaf.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/** IPA and an English definition from the free, open dictionaryapi.dev. */
data class DictionaryEntry(
    val word: String,
    val phonetic: String?,
    val partOfSpeech: String?,
    val definition: String?,
    /** Every pronunciation the entry gives (US and UK), to tell its etymology apart ("wind" /waɪnd/). */
    val phonetics: List<String> = emptyList(),
) {
    companion object {
        /**
         * Longest field kept from an answer. Real definitions are a sentence or two; a hostile
         * answer can't make the card or the inflection search work through megabytes of text.
         */
        const val MAX_DEFINITION = 500
        const val MAX_SHORT_FIELD = 100

        /** Entries walked in one answer, and meanings in each: real answers have a few. */
        const val MAX_ENTRIES = 10
        const val MAX_MEANINGS = 10

        /**
         * Every meaning of every entry of an answer, in order, each with its entry's word and
         * phonetic and its part of speech: so a reading can pick the meaning that matches it.
         */
        fun parseAll(answer: JsonArray): List<DictionaryEntry> =
            answer.take(MAX_ENTRIES).mapNotNull { it as? JsonObject }.flatMap { json ->
                val meanings = (json["meanings"] as? JsonArray)?.take(MAX_MEANINGS)?.mapNotNull { it as? JsonObject }.orEmpty()
                val first = parse(json).copy(phonetics = pronunciations(json))
                if (meanings.isEmpty()) {
                    listOf(first)
                } else {
                    meanings.map { meaning ->
                        val definition = (meaning["definitions"] as? JsonArray)?.firstOrNull() as? JsonObject
                        first.copy(
                            partOfSpeech =
                                (meaning["partOfSpeech"] as? JsonPrimitive)?.contentOrNull?.let {
                                    TextTools.truncated(
                                        it,
                                        MAX_SHORT_FIELD,
                                    )
                                },
                            definition =
                                (
                                    definition?.get(
                                        "definition",
                                    ) as? JsonPrimitive
                                )?.contentOrNull?.let { TextTools.truncated(it, MAX_DEFINITION) },
                        )
                    }
                }
            }

        /** The entry's pronunciations: its phonetic, then each in its phonetics, at most [MAX_MEANINGS]. */
        private fun pronunciations(json: JsonObject): List<String> {
            val all =
                listOfNotNull((json["phonetic"] as? JsonPrimitive)?.contentOrNull) +
                    (json["phonetics"] as? JsonArray)
                        ?.take(MAX_MEANINGS)
                        ?.mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull }
                        .orEmpty()
            return all.filter { it.isNotEmpty() }.map { TextTools.truncated(it, MAX_SHORT_FIELD) }.distinct().take(MAX_MEANINGS)
        }

        /** IPA compared without slashes, stress, syllable dots or spaces, and with r for ɹ and g for ɡ. */
        internal fun sameIpa(
            a: String,
            b: String,
        ): Boolean = ipaKey(a).isNotEmpty() && ipaKey(a) == ipaKey(b)

        private fun ipaKey(ipa: String): String =
            java.text.Normalizer
                .normalize(ipa, java.text.Normalizer.Form.NFC)
                .filterNot { it in "/[]()ˈˌ. " }
                .replace('ɹ', 'r')
                .replace('ɡ', 'g')

        fun parse(json: JsonObject): DictionaryEntry {
            val phonetic =
                (
                    json["phonetic"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
                        ?: (json["phonetics"] as? JsonArray)
                            ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                            ?.firstOrNull { it.isNotEmpty() }
                )?.let { TextTools.truncated(it, MAX_SHORT_FIELD) }
            val meaning = (json["meanings"] as? JsonArray)?.firstOrNull()?.jsonObject
            val definition = (meaning?.get("definitions") as? JsonArray)?.firstOrNull()?.jsonObject
            return DictionaryEntry(
                word = TextTools.truncated(json["word"]!!.jsonPrimitive.content, MAX_SHORT_FIELD),
                phonetic = phonetic,
                partOfSpeech = meaning?.get("partOfSpeech")?.jsonPrimitive?.contentOrNull?.let { TextTools.truncated(it, MAX_SHORT_FIELD) },
                definition = definition?.get("definition")?.jsonPrimitive?.contentOrNull?.let { TextTools.truncated(it, MAX_DEFINITION) },
            )
        }
    }
}

private val inflectionOf =
    Regex(
        "(?:plural|past tense|past participle|present participle|third-person singular|simple past|comparative|superlative)" +
            "[^.;]*?\\bof\\s+([a-z]+)",
        RegexOption.IGNORE_CASE,
    )

class DictionaryClient(private val http: HttpFetcher) {
    private val cache = mutableMapOf<String, List<DictionaryEntry>?>()

    /**
     * Looks [word] up, resolving inflected forms: "walked" → "walk", "animals" → "animal".
     * With the [reading] of a homograph in its sentence: a verb looks its base up first
     * ("she left" → "leave"); a noun is looked up as its base ("the leaves" → "leaf"; "turn
     * left" → "left") without following "past tense of …" in the definition; either takes the
     * first meaning of the reading's part of speech, with that entry's phonetic, or none. Unsure
     * (or none) looks the word up as before.
     */
    suspend fun lookup(
        word: String,
        reading: Reading? = null,
    ): DictionaryEntry? {
        val w = TextTools.normalize(word)
        // The regular twin's sense ("lied": to say what isn't true) has no definition of its own here.
        if (reading?.kind == Reading.Kind.HOMONYM) return null
        val certain = reading != null && reading.step != Reading.Step.NAME
        if (certain && (reading?.kind == Reading.Kind.VERB || reading?.kind == Reading.Kind.NOUN)) {
            val base = TextTools.normalize(reading.base)
            val verb = reading.kind == Reading.Kind.VERB
            val pos = if (verb) "verb" else "noun"
            // The table's IPA of the base tells which entry (etymology) is this verb ("wind" /waɪnd/).
            val ipa = if (verb) IrregularVerbs.shared.ipa(base, "base") else null
            return meaning(base, pos, ipa) ?: if (base != w) meaning(w, pos, null) else null
        }
        val direct = get(w)
        if (direct != null) {
            val base = direct.definition?.let { inflectionOf.find(it)?.groupValues?.get(1)?.lowercase() }
            return if (base != null && base != w) get(base) ?: direct else direct
        }
        for (candidate in TextTools.baseFormCandidates(w)) {
            get(candidate)?.let { return it }
        }
        return null
    }

    /**
     * The first meaning of [word] in [partOfSpeech], from the entry whose pronunciation is [ipa]
     * when one is; none of another part of speech.
     */
    private suspend fun meaning(
        word: String,
        partOfSpeech: String,
        ipa: String?,
    ): DictionaryEntry? {
        val matching = entries(word)?.filter { it.partOfSpeech == partOfSpeech } ?: return null
        if (ipa != null) matching.firstOrNull { e -> e.phonetics.any { DictionaryEntry.sameIpa(it, ipa) } }?.let { return it }
        return matching.firstOrNull()
    }

    /** The first meaning of [word] in [partOfSpeech] (any when null). */
    private suspend fun get(
        word: String,
        partOfSpeech: String? = null,
    ): DictionaryEntry? {
        val entries = entries(word) ?: return null
        return if (partOfSpeech == null) entries.firstOrNull() else entries.firstOrNull { it.partOfSpeech == partOfSpeech }
    }

    private suspend fun entries(word: String): List<DictionaryEntry>? {
        if (cache.containsKey(word)) return cache[word]
        val response =
            try {
                http.get("https://api.dictionaryapi.dev/api/v2/entries/en/${URLEncoder.encode(word, "UTF-8")}")
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                return null
            }
        if (response.status == 404) cache[word] = null
        if (response.status != 200 || !JsonGuard.isShallow(response.text)) return null
        val entries =
            try {
                DictionaryEntry.parseAll(Json.parseToJsonElement(response.text).jsonArray).takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                null
            }
        cache[word] = entries
        return entries
    }
}
