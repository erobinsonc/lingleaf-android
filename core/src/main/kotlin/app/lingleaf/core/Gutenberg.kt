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
            return TextTools.cleanLabel(if (parts.size == 2) "${parts[1].trim()} ${parts[0].trim()}" else name)
        }

    /**
     * Short title for lists: catalog titles carry subtitles and MARC markers
     * ("Peter Pan : $b [Peter and Wendy]", "Moby-Dick; Or, The Whale").
     */
    val displayTitle: String
        get() = TextTools.cleanLabel(title.split(" : ", ";", "\$b", "\n", "\r").first()).ifEmpty { TextTools.cleanLabel(title) }

    /**
     * The medium cover from Project Gutenberg's file cache, never a link taken from the
     * catalog: covers load only from www.gutenberg.org. Null when the catalog lists none.
     */
    val coverUrl: String?
        get() = if (formats.containsKey("image/jpeg")) GutenbergClient.coverUrl(id) else null

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
        if (!JsonGuard.isShallow(response.text)) throw GutenbergException(GutenbergException.Reason.FAILED)
        return try {
            cleanResults(json.decodeFromString<GutendexResponse>(response.text).results)
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

        /** Project Gutenberg's file cache: the plain-text edition and the medium cover. */
        fun textUrl(id: Int) = "https://www.gutenberg.org/cache/epub/$id/pg$id.txt"

        fun coverUrl(id: Int) = "https://www.gutenberg.org/cache/epub/$id/pg$id.cover.medium.jpg"

        /** A real cover is under 1000 px a side; anything far larger is refused before decoding. */
        const val COVER_MAX_SIDE = 4096
        const val COVER_MAX_PIXELS = 4_000_000

        /** Twice the 44×62 dp shown at the densest screens: sharp, yet a few hundred KB at most. */
        const val COVER_THUMBNAIL_WIDTH = 176
        const val COVER_THUMBNAIL_HEIGHT = 248

        /**
         * Whether [bytes] start like a JPEG file (FF D8 FF). Only JPEG covers reach the system's
         * image decoder: formats with a history of decoder exploits (WebP, HEIC…) never do.
         */
        fun isJpeg(bytes: ByteArray): Boolean =
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()

        /**
         * The power-of-two downsampling that keeps a cover of [width]×[height] px at least
         * thumbnail-sized, or null when the image is empty or too large to decode safely.
         */
        fun coverSample(
            width: Int,
            height: Int,
        ): Int? {
            if (width <= 0 || height <= 0 || width > COVER_MAX_SIDE || height > COVER_MAX_SIDE) return null
            if (width.toLong() * height > COVER_MAX_PIXELS) return null
            var sample = 1
            while (width / (sample * 2) >= COVER_THUMBNAIL_WIDTH && height / (sample * 2) >= COVER_THUMBNAIL_HEIGHT) sample *= 2
            return sample
        }

        /**
         * The recommended classics, shipped with the app (title, author, level), so Discover
         * shows them at once and without the catalog. Same order as [RECOMMENDED].
         */
        val RECOMMENDED_BOOKS: List<GutenbergBook> by lazy {
            listOf(
                Triple(25519, "Little Wizard Stories of Oz", "Baum, L. Frank"),
                Triple(55, "The Wonderful Wizard of Oz", "Baum, L. Frank"),
                Triple(54, "The Marvelous Land of Oz", "Baum, L. Frank"),
                Triple(486, "Ozma of Oz", "Baum, L. Frank"),
                Triple(517, "The Emerald City of Oz", "Baum, L. Frank"),
                Triple(1874, "The Railway Children", "Nesbit, E."),
                Triple(3536, "The Enchanted Castle", "Nesbit, E."),
                Triple(157, "Daddy-Long-Legs", "Webster, Jean"),
                Triple(11, "Alice's Adventures in Wonderland", "Carroll, Lewis"),
                Triple(14522, "The Canterville Ghost", "Wilde, Oscar"),
                Triple(160, "The Awakening and Selected Short Stories", "Chopin, Kate"),
                Triple(208, "Daisy Miller", "James, Henry"),
                Triple(209, "The Turn of the Screw", "James, Henry"),
            ).map { (id, title, author) ->
                GutenbergBook(
                    id = id,
                    title = title,
                    authors = listOf(GutenbergBook.Person(author)),
                    formats = mapOf("text/plain; charset=utf-8" to textUrl(id), "image/jpeg" to coverUrl(id)),
                )
            }
        }

        /**
         * Hand-picked classics with a CEFR level hint, easiest first (B1 to C2): public domain
         * worldwide (every author died in 1925 or earlier) and without old spellings such as "to-day".
         */
        val RECOMMENDED: Map<Int, String> =
            linkedMapOf(
                // Little Wizard Stories of Oz
                25519 to "B1",
                // The Wonderful Wizard of Oz
                55 to "B1",
                // The Marvelous Land of Oz
                54 to "B1",
                // Ozma of Oz
                486 to "B1",
                // The Emerald City of Oz
                517 to "B1",
                // The Railway Children
                1874 to "B2",
                // The Enchanted Castle
                3536 to "B2",
                // Daddy-Long-Legs
                157 to "B2",
                // Alice's Adventures in Wonderland
                11 to "B2",
                // The Canterville Ghost
                14522 to "B2",
                // The Awakening and Selected Short Stories
                160 to "C1",
                // Daisy Miller
                208 to "C1",
                // The Turn of the Screw
                209 to "C2",
            )

        /** CEFR levels, easiest first. */
        val LEVELS = listOf("A1", "A2", "B1", "B2", "C1", "C2")

        /** Levels that have at least one recommended book, easiest first: the filter's chips. */
        val FILTER_LEVELS: List<String> by lazy { LEVELS.filter { it in RECOMMENDED.values } }

        fun levelFor(id: Int): String? = RECOMMENDED[id]

        /** Most results shown for one search (one page of the catalog). */
        const val MAX_RESULTS = 32

        /**
         * One row per book, at most [MAX_RESULTS]. A recommended book always shows as shipped
         * with the app, so a catalog answer can't put another title under its level badge.
         */
        internal fun cleanResults(books: List<GutenbergBook>): List<GutenbergBook> =
            books.distinctBy { it.id }.take(MAX_RESULTS).map { book -> RECOMMENDED_BOOKS.firstOrNull { it.id == book.id } ?: book }

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
