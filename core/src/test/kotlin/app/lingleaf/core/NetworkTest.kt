package app.lingleaf.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun ok(body: String) = HttpResponse(200, body.toByteArray())

private val notFound = HttpResponse(404, "[]".toByteArray())

class GutenbergTest {
    private val alice =
        """
        {"id": 11, "title": "Alice", "authors": [{"name": "Carroll, Lewis", "birth_year": 1832}],
         "formats": {"text/plain; charset=us-ascii": "https://example.com/11.txt",
                     "text/plain; charset=utf-8": "https://example.com/11.utf8.txt",
                     "application/zip": "https://example.com/11.zip",
                     "image/jpeg": "https://example.com/11.jpg"},
         "download_count": 5}
        """.trimIndent()

    @Test
    fun `strips license header and footer`() {
        val raw =
            "The Project Gutenberg eBook\r\nLicense\r\n*** START OF THE PROJECT GUTENBERG EBOOK X ***\r\n\r\n" +
                "Chapter 1\r\n\r\nOnce upon a time.\r\n*** END OF THE PROJECT GUTENBERG EBOOK X ***\r\nMore license"
        assertEquals("Chapter 1\n\nOnce upon a time.", GutenbergClient.stripBoilerplate(raw))
        assertEquals("Just a story.", GutenbergClient.stripBoilerplate("  Just a story.\n"))
    }

    @Test
    fun `catalog titles and authors are shortened for display`() {
        fun book(
            title: String,
            author: String,
        ) = GutenbergBook(1, title, listOf(GutenbergBook.Person(author)))
        assertEquals("Peter Pan", book("Peter Pan : \$b [Peter and Wendy]", "x").displayTitle)
        assertEquals("Moby-Dick", book("Moby-Dick; Or, The Whale", "x").displayTitle)
        assertEquals("Frankenstein", book("Frankenstein\r\nOr, The Modern Prometheus", "x").displayTitle)
        assertEquals("Treasure Island", book("Treasure Island", "x").displayTitle)
        assertEquals("L. Frank Baum", book("x", "Baum, L. Frank (Lyman Frank)").authorName)
        assertEquals("J. M. Barrie", book("x", "Barrie, J. M. (James Matthew)").authorName)
        assertEquals("Aesop", book("x", "Aesop").authorName)
    }

    @Test
    fun `recommended books ship with the app with direct links`() {
        val books = GutenbergClient.RECOMMENDED_BOOKS
        assertEquals(GutenbergClient.RECOMMENDED.keys.toList(), books.map { it.id }, "same books, same order as the levels")
        val alice = books.first { it.id == 11 }
        assertEquals("Alice's Adventures in Wonderland", alice.displayTitle)
        assertEquals("Lewis Carroll", alice.authorName)
        assertEquals("https://www.gutenberg.org/cache/epub/11/pg11.txt", alice.textUrl)
        assertEquals("https://www.gutenberg.org/cache/epub/11/pg11.cover.medium.jpg", alice.coverUrl)
        assertEquals("B2", GutenbergClient.levelFor(11))
    }

    @Test
    fun `catalog books download from the file cache`() =
        runTest {
            val book = GutenbergClient { ok("""{"results": [$alice]}""") }.search("alice").single()
            assertEquals("https://www.gutenberg.org/cache/epub/11/pg11.txt", book.textUrl, "not the catalog's redirecting link")
            assertNull(GutenbergBook(id = 2, title = "No text", formats = mapOf("application/zip" to "https://example.com/2.zip")).textUrl)
        }

    @Test
    fun `filter by level keeps order and null keeps everything`() {
        val books = GutenbergClient.RECOMMENDED.keys.map { GutenbergBook(id = it, title = "Book $it") }
        assertEquals(books, GutenbergClient.filter(books, null))
        assertEquals(listOf(55, 16, 236), GutenbergClient.filter(books, "B1").map { it.id })
        assertEquals(listOf(2701, 219, 145), GutenbergClient.filter(books, "C2").map { it.id })
        assertEquals(emptyList(), GutenbergClient.filter(books, "A1"))
        assertEquals(emptyList(), GutenbergClient.filter(listOf(GutenbergBook(id = 999_999, title = "Unlisted")), "B1"))
    }

    @Test
    fun `every recommended level is a known CEFR level`() {
        assertEquals(listOf("A1", "A2", "B1", "B2", "C1", "C2"), GutenbergClient.LEVELS)
        assertTrue(GutenbergClient.RECOMMENDED.values.all { it in GutenbergClient.LEVELS })
        assertEquals(listOf("A2", "B1", "B2", "C1", "C2"), GutenbergClient.RECOMMENDED.values.distinct())
    }

    private suspend fun reason(block: suspend () -> Unit) = assertFailsWith<GutenbergException> { block() }.reason

    @Test
    fun `search encodes the query and says why it failed`() =
        runTest {
            val client =
                GutenbergClient { url ->
                    when {
                        "search=tom+sawyer" in url -> ok("""{"results": []}""")
                        "search=offline" in url -> throw NetworkException("No internet", NetworkException.Kind.OFFLINE)
                        "search=slow" in url -> throw NetworkException("Timed out", NetworkException.Kind.TIMED_OUT)
                        "search=other" in url -> throw NetworkException("Other")
                        "search=broken" in url -> ok("<html>")
                        else -> HttpResponse(500, ByteArray(0))
                    }
                }
            assertEquals(emptyList(), client.search("tom sawyer"))
            assertEquals(GutenbergException.Reason.OFFLINE, reason { client.search("offline") })
            assertEquals(GutenbergException.Reason.TIMED_OUT, reason { client.search("slow") })
            assertEquals(GutenbergException.Reason.FAILED, reason { client.search("other") })
            assertEquals(GutenbergException.Reason.FAILED, reason { client.search("broken") })
            assertEquals(GutenbergException.Reason.FAILED, reason { client.search("server") })
        }

    @Test
    fun `download strips boilerplate, reports progress and says why it failed`() =
        runTest {
            val book = GutenbergBook(id = 1, title = "X", formats = mapOf("text/plain" to "https://example.com/1.txt"))
            val progress = mutableListOf<Double>()
            val text =
                GutenbergClient { url ->
                    assertEquals("https://www.gutenberg.org/cache/epub/1/pg1.txt", url)
                    ok("h\n*** START OF IT ***\nCafé story.\n*** END OF IT ***")
                }.downloadText(book) { progress += it }
            assertEquals("Café story.", text)
            assertEquals(listOf(1.0), progress)
            val unavailable = GutenbergException.Reason.UNAVAILABLE
            assertEquals(unavailable, reason { GutenbergClient { ok("*** START OF IT ***\n*** END OF IT ***") }.downloadText(book) })
            assertEquals(unavailable, reason { GutenbergClient { notFound }.downloadText(book) })
            assertEquals(unavailable, reason { GutenbergClient { HttpResponse(403, ByteArray(0)) }.downloadText(book) })
            assertEquals(
                GutenbergException.Reason.FAILED,
                reason { GutenbergClient { HttpResponse(503, ByteArray(0)) }.downloadText(book) },
            )
            assertEquals(unavailable, reason { GutenbergClient { ok("x") }.downloadText(GutenbergBook(id = 2, title = "No text")) })
        }
}

class DictionaryTest {
    private fun entry(
        word: String,
        definition: String,
        phonetic: String? = null,
    ) = ok(
        """[{"word": "$word", ${phonetic?.let { "\"phonetic\": \"$it\"," } ?: ""}
            "phonetics": [{"audio": ""}, {"text": "/$word/"}],
            "meanings": [{"partOfSpeech": "noun", "definitions": [{"definition": "$definition"}]}]}]""",
    )

    @Test
    fun `falls back to base forms when the word is missing`() =
        runTest {
            val requested = mutableListOf<String>()
            val client =
                DictionaryClient { url ->
                    val word = url.substringAfterLast('/')
                    requested += word
                    if (word == "animal") entry("animal", "A living creature.", "/ˈænɪməl/") else notFound
                }
            val result = client.lookup("Animals")!!
            assertEquals("animal", result.word)
            assertEquals("/ˈænɪməl/", result.phonetic)
            assertEquals("noun", result.partOfSpeech)
            assertEquals(listOf("animals", "animal"), requested)
            client.lookup("animals")
            assertEquals(2, requested.size, "results and misses are cached")
        }

    @Test
    fun `follows past tense of to the base word`() =
        runTest {
            val client =
                DictionaryClient { url ->
                    when (url.substringAfterLast('/')) {
                        "walked" -> entry("walked", "simple past tense and past participle of walk")
                        "walk" -> entry("walk", "To move on the feet.")
                        else -> notFound
                    }
                }
            val result = client.lookup("walked")!!
            assertEquals("walk", result.word)
            assertEquals("/walk/", result.phonetic)
        }

    @Test
    fun `returns null when offline, on server errors or bad JSON`() =
        runTest {
            assertNull(DictionaryClient { throw NetworkException("offline") }.lookup("equal"))
            assertNull(DictionaryClient { HttpResponse(500, ByteArray(0)) }.lookup("equal"))
            assertNull(DictionaryClient { ok("not json") }.lookup("equal"))
            assertNull(DictionaryClient { notFound }.lookup("zzzz"))
        }
}
