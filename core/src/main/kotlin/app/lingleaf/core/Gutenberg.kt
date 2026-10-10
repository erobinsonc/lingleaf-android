package app.lingleaf.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder

/** A public-domain book from Project Gutenberg, as listed by the Gutendex API. */
@Serializable
data class GutenbergBook(
    val id: Int,
    val title: String,
    val authors: List<Person> = emptyList(),
    val formats: Map<String, String> = emptyMap(),
) {
    @Serializable
    data class Person(val name: String)

    /** "Carroll, Lewis" → "Lewis Carroll" */
    val authorName: String?
        get() {
            val raw = authors.firstOrNull()?.name ?: return null
            // "Baum, L. Frank (Lyman Frank)" → "Baum, L. Frank": drop the catalog's full-name note.
            val name = raw.replace(Regex("""\s*\([^)]*\)"""), "").trim()
            val parts = name.split(",", limit = 2)
            return if (parts.size == 2) "${parts[1].trim()} ${parts[0].trim()}" else name
        }

    /**
     * Short title for lists: catalog titles carry subtitles and MARC markers
     * ("Peter Pan : $b [Peter and Wendy]", "Moby-Dick; Or, The Whale").
     */
    val displayTitle: String
        get() = title.split(" : ", ";", "\$b", "\n", "\r").first().trim().ifEmpty { title.trim() }

    val coverUrl: String? get() = formats["image/jpeg"]

    val textUrl: String?
        get() {
            val candidates = formats.filter { it.key.startsWith("text/plain") && !it.value.endsWith(".zip") }
            return (candidates.entries.firstOrNull { "utf-8" in it.key } ?: candidates.entries.firstOrNull())?.value
        }
}

@Serializable
private data class GutendexResponse(val results: List<GutenbergBook>)

class GutenbergClient(private val http: HttpFetcher) {
    suspend fun search(query: String): List<GutenbergBook> = fetch("$BASE?languages=en&search=${URLEncoder.encode(query, "UTF-8")}")

    suspend fun fetchRecommended(): List<GutenbergBook> {
        val ids = RECOMMENDED.keys.toList()
        return fetch("$BASE?ids=${ids.joinToString(",")}").sortedBy { ids.indexOf(it.id) }
    }

    suspend fun downloadText(book: GutenbergBook): String {
        val url = book.textUrl ?: throw NetworkException("No plain-text edition")
        val response = http.get(url)
        if (response.status != 200) throw NetworkException("HTTP ${response.status}")
        return stripBoilerplate(response.text).ifEmpty { throw NetworkException("Empty book") }
    }

    private suspend fun fetch(url: String): List<GutenbergBook> {
        val response = http.get(url)
        if (response.status != 200) throw NetworkException("HTTP ${response.status}")
        return json.decodeFromString<GutendexResponse>(response.text).results
    }

    companion object {
        private const val BASE = "https://gutendex.com/books"
        private val json = Json { ignoreUnknownKeys = true }

        /** Hand-picked classics with a CEFR level hint, easiest first (A2 to C2). */
        val RECOMMENDED: Map<Int, String> =
            linkedMapOf(
                // Aesop's Fables
                21 to "A2",
                // The Wonderful Wizard of Oz
                55 to "B1",
                // Peter Pan
                16 to "B1",
                // The Jungle Book
                236 to "B1",
                // Alice's Adventures in Wonderland
                11 to "B2",
                // The Adventures of Tom Sawyer
                74 to "B2",
                // Treasure Island
                120 to "B2",
                // The Adventures of Sherlock Holmes
                1661 to "B2",
                // Pride and Prejudice
                1342 to "C1",
                // Frankenstein
                84 to "C1",
                // The Picture of Dorian Gray
                174 to "C1",
                // Great Expectations
                1400 to "C1",
                // Dracula
                345 to "C1",
                // Moby-Dick
                2701 to "C2",
                // Heart of Darkness
                219 to "C2",
                // Middlemarch
                145 to "C2",
            )

        /** CEFR levels, easiest first. */
        val LEVELS = listOf("A1", "A2", "B1", "B2", "C1", "C2")

        fun levelFor(id: Int): String? = RECOMMENDED[id]

        /** Books at [level], in their original order; a null level keeps every book. */
        fun filter(
            books: List<GutenbergBook>,
            level: String?,
        ): List<GutenbergBook> = if (level == null) books else books.filter { levelFor(it.id) == level }

        /** Removes the Project Gutenberg license header and footer. */
        fun stripBoilerplate(text: String): String {
            var lines = text.replace("\r\n", "\n").split('\n')

            fun isMarker(
                line: String,
                kind: String,
            ) = line.startsWith("*** $kind OF") || line.startsWith("***$kind OF")
            val start = lines.indexOfFirst { isMarker(it, "START") }
            if (start >= 0) lines = lines.drop(start + 1)
            val end = lines.indexOfFirst { isMarker(it, "END") }
            if (end >= 0) lines = lines.take(end)
            return lines.joinToString("\n").trim()
        }
    }
}
