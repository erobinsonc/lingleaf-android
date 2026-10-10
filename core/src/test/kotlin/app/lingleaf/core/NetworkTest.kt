package app.lingleaf.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

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
    fun `recommended books parse and keep curated order`() =
        runTest {
            val client =
                GutenbergClient { url ->
                    assertEquals(true, "ids=21,55" in url)
                    ok("""{"count": 2, "results": [$alice, {"id": 21, "title": "Fables"}]}""")
                }
            val books = client.fetchRecommended()
            assertEquals(listOf(21, 11), books.map { it.id })
            assertNull(books[0].authorName)
            assertEquals("Lewis Carroll", books[1].authorName)
            assertEquals("https://example.com/11.utf8.txt", books[1].textUrl)
            assertEquals("https://example.com/11.jpg", books[1].coverUrl)
            assertEquals("B2", GutenbergClient.levelFor(11))
        }

    @Test
    fun `search encodes the query and errors on bad status`() =
        runTest {
            val client =
                GutenbergClient { url ->
                    if ("search=tom+sawyer" in url) ok("""{"results": []}""") else HttpResponse(500, ByteArray(0))
                }
            assertEquals(emptyList(), client.search("tom sawyer"))
            assertFailsWith<NetworkException> { client.search("other") }
        }

    @Test
    fun `download strips boilerplate and rejects empty books`() =
        runTest {
            val book = GutenbergBook(id = 1, title = "X", formats = mapOf("text/plain" to "https://example.com/1.txt"))
            assertEquals("Café story.", GutenbergClient { ok("h\n*** START OF IT ***\nCafé story.\n*** END OF IT ***") }.downloadText(book))
            assertFailsWith<NetworkException> { GutenbergClient { ok("*** START OF IT ***\n*** END OF IT ***") }.downloadText(book) }
            assertFailsWith<NetworkException> { GutenbergClient { notFound }.downloadText(book) }
            assertFailsWith<NetworkException> { GutenbergClient { notFound }.downloadText(GutenbergBook(id = 2, title = "No text")) }
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
