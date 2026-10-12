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

    /**
     * The plain-text edition, straight from Project Gutenberg's file cache: the catalog's
     * "ebooks/<id>.txt.utf-8" links redirect there, and every hop is another chance to fail.
     * Null when the catalog lists no plain-text edition.
     */
    val textUrl: String?
        get() =
            if (formats.any { it.key.startsWith("text/plain") && !it.value.endsWith(".zip") }) GutenbergClient.textUrl(id) else null
}

/** Why talking to Project Gutenberg failed, so the learner gets the right message and action. */
class GutenbergException(
    val reason: Reason,
) : Exception(reason.name) {
    enum class Reason {
        /** No internet connection. */
        OFFLINE,

        /** The server didn't answer in time. */
        TIMED_OUT,

        /** The book can't be had right now (403, 404, 410, no plain text, or empty). */
        UNAVAILABLE,

        /** Anything else (5xx, a broken answer). */
        FAILED,
    }

    companion object {
        fun of(status: Int) = GutenbergException(if (status in setOf(403, 404, 410)) Reason.UNAVAILABLE else Reason.FAILED)

        fun of(error: NetworkException) =
            GutenbergException(
                when (error.kind) {
                    NetworkException.Kind.OFFLINE -> Reason.OFFLINE
                    NetworkException.Kind.TIMED_OUT -> Reason.TIMED_OUT
                    NetworkException.Kind.OTHER -> Reason.FAILED
                },
            )
    }
}

@Serializable
private data class GutendexResponse(val results: List<GutenbergBook>)

class GutenbergClient(private val http: HttpFetcher) {
    /** Searches the catalog by title or author. Throws [GutenbergException]; cancelling the coroutine cancels the request. */
    suspend fun search(query: String): List<GutenbergBook> {
        val response = call { http.get("$BASE?languages=en&search=${URLEncoder.encode(query, "UTF-8")}") }
        if (response.status != 200) throw GutenbergException.of(response.status)
        return try {
            json.decodeFromString<GutendexResponse>(response.text).results
        } catch (_: IllegalArgumentException) {
            throw GutenbergException(GutenbergException.Reason.FAILED)
        }
    }

    /**
     * The book's text without the license header and footer, reporting progress (0…1)
     * while it downloads. Throws [GutenbergException]; cancelling the coroutine cancels the download.
     */
    suspend fun downloadText(
        book: GutenbergBook,
        progress: ((Double) -> Unit)? = null,
    ): String {
        val url = book.textUrl ?: throw GutenbergException(GutenbergException.Reason.UNAVAILABLE)
        val response = call { http.get(url, progress) }
        if (response.status != 200) throw GutenbergException.of(response.status)
        return stripBoilerplate(response.text).ifEmpty { throw GutenbergException(GutenbergException.Reason.UNAVAILABLE) }
    }

    /** Runs a request, turning network errors into [GutenbergException]. */
    private suspend fun call(request: suspend () -> HttpResponse): HttpResponse =
        try {
            request()
        } catch (error: NetworkException) {
            throw GutenbergException.of(error)
        }

    companion object {
        private const val BASE = "https://gutendex.com/books"
        private val json = Json { ignoreUnknownKeys = true }

        /** The plain-text edition in Project Gutenberg's file cache. */
        fun textUrl(id: Int) = "https://www.gutenberg.org/cache/epub/$id/pg$id.txt"

        /**
         * The recommended classics, shipped with the app (title, author, level), so Discover
         * shows them at once and without the catalog. Same order as [RECOMMENDED].
         */
        val RECOMMENDED_BOOKS: List<GutenbergBook> by lazy {
            listOf(
                Triple(21, "Aesop's Fables", "Aesop"),
                Triple(55, "The Wonderful Wizard of Oz", "Baum, L. Frank"),
                Triple(16, "Peter Pan", "Barrie, J. M."),
                Triple(236, "The Jungle Book", "Kipling, Rudyard"),
                Triple(11, "Alice's Adventures in Wonderland", "Carroll, Lewis"),
                Triple(74, "The Adventures of Tom Sawyer", "Twain, Mark"),
                Triple(120, "Treasure Island", "Stevenson, Robert Louis"),
                Triple(1661, "The Adventures of Sherlock Holmes", "Doyle, Arthur Conan"),
                Triple(1342, "Pride and Prejudice", "Austen, Jane"),
                Triple(84, "Frankenstein", "Shelley, Mary Wollstonecraft"),
                Triple(174, "The Picture of Dorian Gray", "Wilde, Oscar"),
                Triple(1400, "Great Expectations", "Dickens, Charles"),
                Triple(345, "Dracula", "Stoker, Bram"),
                Triple(2701, "Moby-Dick", "Melville, Herman"),
                Triple(219, "Heart of Darkness", "Conrad, Joseph"),
                Triple(145, "Middlemarch", "Eliot, George"),
            ).map { (id, title, author) ->
                GutenbergBook(
                    id = id,
                    title = title,
                    authors = listOf(GutenbergBook.Person(author)),
                    formats = mapOf("text/plain; charset=utf-8" to textUrl(id)),
                )
            }
        }

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
