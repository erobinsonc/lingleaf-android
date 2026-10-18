package app.lingleaf.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
) {
    companion object {
        /**
         * Longest field kept from an answer. Real definitions are a sentence or two; a hostile
         * answer can't make the card or the inflection search work through megabytes of text.
         */
        const val MAX_DEFINITION = 500
        const val MAX_SHORT_FIELD = 100

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
    private val cache = mutableMapOf<String, DictionaryEntry?>()

    /**
     * Looks [word] up, resolving inflected forms: "walked" → "walk", "animals" → "animal".
     * With the [reading] of a homograph in its sentence: a verb looks its base up first
     * ("she left" → "leave"); a noun is looked up as its base ("the leaves" → "leaf"; "turn
     * left" → "left") without following "past tense of …" in the definition. Unsure (or none)
     * looks the word up as before.
     */
    suspend fun lookup(
        word: String,
        reading: Reading? = null,
    ): DictionaryEntry? {
        val w = TextTools.normalize(word)
        if (reading != null && reading.kind != Reading.Kind.UNSURE) {
            val base = TextTools.normalize(reading.base)
            return get(base) ?: if (base != w) get(w) else null
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

    private suspend fun get(word: String): DictionaryEntry? {
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
        val entry =
            try {
                Json.parseToJsonElement(response.text).jsonArray.firstOrNull()?.jsonObject?.let(DictionaryEntry::parse)
            } catch (e: Exception) {
                null
            }
        cache[word] = entry
        return entry
    }
}
