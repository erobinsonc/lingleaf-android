package app.lingleaf.core

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryStorage : Storage {
    val files = mutableMapOf<String, String>()

    override fun read(name: String) = files[name]

    override fun write(
        name: String,
        content: String,
    ) {
        files[name] = content
    }

    override fun delete(name: String) {
        files.remove(name)
    }
}

/** A clock tests can move forward. */
class TestClock(var instant: Instant = Instant.parse("2026-10-09T09:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = instant
}

class LibraryTest {
    private val storage = MemoryStorage()
    private val clock = TestClock()
    private val stories =
        listOf(
            BundledStory("The Red Boat", "A1", "All animals are equal.\n\nThe end."),
            BundledStory("A Day at the Market", "A2", "A short story."),
        )

    private fun library() = Library(storage, clock).apply { load() }

    @Test
    fun `keeps a copy of a file that cannot be read`() {
        storage.files["words.json"] = "not json"
        val library = library()
        assertTrue(library.state.value.words.isEmpty())
        assertEquals("not json", storage.files["words.json.corrupt"])
    }

    @Test
    fun `seeds bundled stories once`() {
        val library = library()
        library.seedIfNeeded(stories)
        library.seedIfNeeded(stories)
        assertEquals(listOf("The Red Boat", "A Day at the Market"), library.state.value.books.map { it.title })
        assertEquals("A1", library.state.value.books.first().level)
        assertEquals(2, library.state.value.books.first().paragraphCount)

        val reloaded = library()
        reloaded.seedIfNeeded(stories)
        assertEquals(2, reloaded.state.value.books.size, "books persist and are not seeded twice")
    }

    @Test
    fun `open, progress and delete a book`() {
        val library = library()
        val book = library.addBook("Alice", "Lewis Carroll", "One.\n\nTwo.\n\nThree.", level = "B2", gutenbergId = 11)
        assertEquals(listOf("One.", "Two.", "Three."), library.openBook(book.id))
        assertEquals(book.id, library.state.value.lastOpened?.id)

        library.updateProgress(book.id, 2)
        library.flush()
        assertEquals(1f, library().state.value.books.single().progress)

        library.deleteBook(book.id)
        assertTrue(library.state.value.books.isEmpty())
        assertNull(storage.read(book.fileName))
        assertEquals(emptyList(), library.openBook("missing"))
        library.deleteBook("missing")
    }

    @Test
    fun `a saved homograph is marked only where its sentence reads it that way`() {
        val library = library()
        library.saveWord("leave", learned = false, translation = "irse")
        val state = library.state.value
        val text = "Turn left, then she left the room."
        val ranges = TextTools.wordRanges(text).filter { text.substring(it.start, it.end) == "left" }
        assertFalse(state.isLearning(text, ranges[0]), "turn left: the side")
        assertTrue(state.isLearning(text, ranges[1]), "she left: leave")
        assertFalse(LibraryState().isLearning(text, ranges[1]), "nothing saved")
    }

    @Test
    fun `learning words highlight inflected forms and survive restart`() {
        val library = library()
        library.saveWord("Animal", learned = false, translation = "animal", context = "All animals are equal.", source = "The Red Boat")
        val state = library.state.value
        val sentence = "All animals are equal."
        assertTrue(state.isLearning(sentence, sentence.rangeOf("animals")))
        assertFalse(state.isLearning(sentence, sentence.rangeOf("equal")))
        assertTrue(library.dueWords().isEmpty(), "a new word is first reviewed tomorrow")
        clock.instant = java.time.Instant.parse("2026-10-10T04:00:00Z")
        assertEquals("animal", library.dueWords().single().word, "due from 04:00 the next day")

        library.saveWord("animal", learned = true, translation = "")
        assertFalse(library.state.value.isLearning(sentence, sentence.rangeOf("animals")))
        val saved = library().state.value.word("animal")
        assertNotNull(saved)
        assertTrue(saved.learned)
        assertEquals("animal", saved.translation, "blank values don't overwrite saved ones")
        assertEquals("All animals are equal.", saved.context)
    }

    @Test
    fun `review answers schedule the next review`() {
        val library = library()
        library.saveWord("equal", learned = false)
        clock.instant = clock.instant.plusSeconds(86_400)
        assertEquals(0, library.dueWords().single().box)
        library.answerReview("equal", correct = true)
        assertTrue(library.dueWords().isEmpty())
        clock.instant = clock.instant.plusSeconds(86_400)
        assertEquals(1, library.dueWords().single().box)

        library.answerReview("equal", correct = false)
        assertEquals(0, library.dueWords().single().box)
        library.answerReview("missing", correct = true)
    }

    @Test
    fun `marking learned and back, and deleting words`() {
        val library = library()
        library.saveWord("bay", learned = false)
        library.setLearned("bay", true)
        assertTrue(library.dueWords().isEmpty())
        library.setLearned("bay", false)
        assertEquals("bay", library.dueWords().single().word)
        library.setLearned("missing", true)
        library.deleteWord("bay")
        assertTrue(library.state.value.words.isEmpty())
    }

    // S4, S5, S15: saving returns the previous record so Undo can put it back.

    @Test
    fun `saveWord returns the previous record`() {
        val library = library()
        assertNull(library.saveWord("bay", learned = false, translation = "bahía", translationLanguage = "es"))
        val first = library.state.value.word("bay")
        assertNotNull(first)
        assertEquals("es", first.translationLanguage)

        val previous = library.saveWord("Bay", learned = true)
        assertEquals(first, previous)
        assertTrue(library.state.value.word("bay")!!.learned)
        assertEquals("es", library.state.value.word("bay")!!.translationLanguage, "no new translation keeps the language")
    }

    @Test
    fun `saving a known word puts it back to review`() {
        val library = library()
        library.saveWord("bay", learned = false)
        library.answerReview("bay", correct = true)
        library.saveWord("bay", learned = true)
        clock.instant = clock.instant.plusSeconds(60)
        library.saveWord("bay", learned = false)
        val saved = library.state.value.word("bay")!!
        assertFalse(saved.learned)
        assertEquals(0, saved.box)
        assertEquals("2026-10-10T04:00", saved.nextReview, "back to a first review tomorrow")
    }

    @Test
    fun `review session interleaves books and counts tomorrow`() {
        val library = library()
        for (word in listOf("a1", "a2", "a3")) library.saveWord(word, learned = false, source = "A")
        for (word in listOf("b1", "b2")) library.saveWord(word, learned = false, source = "B")
        library.saveWord("known", learned = true, source = "A")
        assertEquals(5, library.dueByEndOfTomorrow())
        clock.instant = clock.instant.plusSeconds(86_400)
        assertEquals(setOf("A", "B"), library.reviewSession().take(2).map { it.source }.toSet(), "one book doesn't fill the session")
        assertEquals(5, library.reviewSession().size)
        assertEquals(3, Srs.session(library.dueWords(), limit = 3).size)
        for (word in library.reviewSession()) library.answerReview(word.word, correct = true)
        assertEquals(5, library.dueByEndOfTomorrow(), "answered words are due again tomorrow")
    }

    @Test
    fun `a session holds at most twenty words`() {
        val library = library()
        for (index in 0 until 25) library.saveWord("w$index", learned = false)
        clock.instant = clock.instant.plusSeconds(86_400)
        assertEquals(Srs.SESSION_LIMIT, library.reviewSession().size)
    }

    @Test
    fun `context translation is kept with its sentence`() {
        val library = library()
        library.saveWord("sea", learned = false, context = "By the sea.", contextTranslation = "Junto al mar.", translationLanguage = "es")
        library.saveWord("sea", learned = false)
        assertEquals("Junto al mar.", library.state.value.word("sea")?.contextTranslation)
        library.saveWord("sea", learned = false, context = "The sea is blue.")
        assertNull(library.state.value.word("sea")?.contextTranslation, "a new sentence drops the old translation")
        assertEquals(listOf("sea"), library.contextsNeedingTranslation("es").map { it.word })
        library.setContextTranslation("sea", "El mar es azul.", "es")
        library.setContextTranslation("sea", " ", "es")
        library.setContextTranslation("missing", "x", "es")
        val sea = library().state.value.word("sea")
        assertEquals("El mar es azul.", sea?.contextTranslation("es"))
        assertNull(sea?.contextTranslation("pt"), "another language needs its own translation")
        assertTrue(library.contextsNeedingTranslation("es").isEmpty())
        assertEquals(listOf("sea"), library.contextsNeedingTranslation("pt").map { it.word })
    }

    @Test
    fun `restoreWord with no previous record removes the word`() {
        val library = library()
        val previous = library.saveWord("bay", learned = false, translation = "bahía")
        library.restoreWord("bay", previous)
        assertNull(library.state.value.word("bay"))
        assertNull(library().state.value.word("bay"), "the removal is saved")
    }

    @Test
    fun `restoreWord puts back exactly the previous record`() {
        val library = library()
        library.saveWord("bay", learned = false, translation = "bahía", context = "The bay.", source = "The Red Boat")
        library.answerReview("bay", correct = true)
        clock.instant = clock.instant.plusSeconds(60)
        library.saveWord("other", learned = false)
        val before = library.state.value.words

        clock.instant = clock.instant.plusSeconds(3_600)
        val previous = library.saveWord("bay", learned = true, translation = "baía", translationLanguage = "pt")
        library.restoreWord("Bay", previous)
        assertEquals(before, library.state.value.words)
        assertEquals(before, library().state.value.words, "the restored record is saved")
    }

    @Test
    fun `restoreWord brings back a deleted word`() {
        val library = library()
        library.saveWord("bay", learned = true, translation = "bahía")
        val deleted = library.state.value.word("bay")
        library.deleteWord("bay")
        library.restoreWord("bay", deleted)
        assertEquals(deleted, library.state.value.word("bay"))
    }

    // S12, S14: translations that arrive after saving.

    @Test
    fun `setTranslation fills in a missing translation`() {
        val library = library()
        library.saveWord("bay", learned = false)
        library.setTranslation("Bay", "bahía", "es")
        val saved = library().state.value.word("bay")!!
        assertEquals("bahía", saved.translation)
        assertEquals("es", saved.translationLanguage)
    }

    @Test
    fun `setTranslation keeps a translation already in that language`() {
        val library = library()
        library.saveWord("bay", learned = false, translation = "bahía", translationLanguage = "es")
        library.setTranslation("bay", "golfo", "es")
        assertEquals("bahía", library.state.value.word("bay")!!.translation)
    }

    @Test
    fun `setTranslation replaces a translation in another language`() {
        val library = library()
        library.saveWord("bay", learned = false, translation = "bahía", translationLanguage = "es")
        library.setTranslation("bay", "baía", "pt")
        val saved = library.state.value.word("bay")!!
        assertEquals("baía", saved.translation)
        assertEquals("pt", saved.translationLanguage)
    }

    @Test
    fun `setTranslation does nothing for a removed word or a blank text`() {
        val library = library()
        library.setTranslation("bay", "bahía", "es")
        assertNull(library.state.value.word("bay"))

        library.saveWord("bay", learned = false)
        library.setTranslation("bay", " ", "es")
        assertEquals("", library.state.value.word("bay")!!.translation)
    }

    @Test
    fun `wordsNeedingTranslation lists missing and other-language translations`() {
        val library = library()
        library.saveWord("bay", learned = false, translation = "bahía", translationLanguage = "es")
        library.saveWord("boat", learned = false)
        library.saveWord("sea", learned = true, translation = "mer", translationLanguage = "fr")
        library.saveWord("sun", learned = false, translation = "sol")

        assertEquals(setOf("boat", "sea", "sun"), library.wordsNeedingTranslation("es").map { it.word }.toSet())
        assertEquals(setOf("bay", "boat", "sun"), library.wordsNeedingTranslation("fr").map { it.word }.toSet())
    }

    @Test
    fun `words saved before translation languages were recorded still load`() {
        storage.files["words.json"] =
            """[{"word": "bay", "translation": "bahía", "createdAt": "2026-10-01T09:00", "nextReview": "2026-10-02T09:00"}]"""
        val saved = library().state.value.word("bay")!!
        assertNull(saved.translationLanguage)
        assertEquals("bahía", saved.translation)
    }

    @Test
    fun `reading time feeds today and the streak`() {
        val library = library()
        library.addReadingTime(240)
        assertEquals(240, library.secondsToday())
        assertEquals(0, library.streak())
        library.addReadingTime(360)
        assertEquals(1, library.streak())
        library.addReadingTime(0)
        library.addReadingTime(99_999)
        assertEquals(600 + 3600, library().secondsToday(), "a session counts at most one hour")
    }

    @Test
    fun `settings persist and corrupt files fall back to defaults`() {
        val library = library()
        library.updateSettings { it.copy(dailyGoalMinutes = 20, targetLanguage = "pt") }
        assertEquals(20, library().state.value.settings.dailyGoalMinutes)

        storage.files["settings.json"] = "{broken"
        assertEquals(Settings(), library().state.value.settings)
    }

    @Test
    fun `progress of single-paragraph books is zero`() {
        val book = Book(id = "1", title = "T", author = "A", fileName = "f", paragraphCount = 1, addedAt = "x")
        assertEquals(0f, book.progress)
    }

    // S10 Import. The system document picker can't be driven in UI tests, so the
    // import rules are covered here; LibraryScreen passes the picked file to importText.

    @Test
    fun `import title is the file name without its extension`() {
        assertEquals("Alice", Library.importTitle("Alice.txt"))
        assertEquals("my.story", Library.importTitle("my.story.TXT"))
        assertEquals("Book", Library.importTitle(".txt"))
        assertEquals("Book", Library.importTitle(""))
        assertEquals("Notes", Library.importTitle("  Notes .txt"))
    }

    @Test
    fun `importing text strips the Gutenberg boilerplate and adds the book`() {
        val library = library()
        val raw =
            "The Project Gutenberg eBook\r\nLicense\r\n*** START OF THE PROJECT GUTENBERG EBOOK X ***\r\n\r\n" +
                "Chapter 1\r\n\r\nOnce upon a time.\r\n*** END OF THE PROJECT GUTENBERG EBOOK X ***\r\nMore license"

        val book = library.importText("Alice.txt", raw)

        assertEquals("Alice", book.title)
        assertEquals("—", book.author)
        assertEquals(listOf(book), library.state.value.books)
        assertEquals("Chapter 1\n\nOnce upon a time.", storage.files[book.fileName])
        assertEquals(listOf("Chapter 1", "Once upon a time."), library.openBook(book.id))
    }

    @Test
    fun `importing empty or boilerplate-only text fails and adds nothing`() {
        val library = library()
        assertFailsWith<IllegalArgumentException> { library.importText("Empty.txt", "") }
        assertFailsWith<IllegalArgumentException> { library.importText("Blank.txt", "  \n\n ") }
        val licenseOnly =
            "Header\n*** START OF THE PROJECT GUTENBERG EBOOK X ***\n\n*** END OF THE PROJECT GUTENBERG EBOOK X ***\nFooter"
        assertFailsWith<IllegalArgumentException> { library.importText("License.txt", licenseOnly) }
        assertTrue(library.state.value.books.isEmpty())
        assertTrue(storage.files.keys.none { it.startsWith("book-") })
    }

    @Test
    fun `helper and modal verbs are saved as their verb group`() {
        val library = library()
        library.saveWord(
            "did not go",
            learned = false,
            translation = "no fue",
            translationLanguage = "es",
            kind = FunctionWord.Kind.HELPER,
            base = "do",
        )
        library.saveWord("doesn't like", learned = false, kind = FunctionWord.Kind.HELPER, base = "do")
        library.saveWord("Do", learned = true, translation = "hacer", translationLanguage = "es")
        val reloaded = library().state.value
        val past = assertNotNull(reloaded.word("Did not go"))
        assertEquals(FunctionWord.Kind.HELPER, past.kind)
        assertEquals("do", past.base)
        assertEquals("no fue", past.translation, "each verb group is its own record")
        assertEquals("do", reloaded.word("doesn't like")?.base)
        assertNull(reloaded.word("do")?.kind, "\"do\" as a word stays separate")
        assertFalse(reloaded.isLearning("did", WordRange(0, 3)), "a verb group doesn't underline the helper everywhere")
        assertEquals(listOf("doesn't like"), library.wordsNeedingTranslation("es").map { it.word })
    }

    @Test
    fun `storage accepts only the app's own file names`() {
        for (name in listOf("books.json", "book-1760000000000-0.txt", "words.json.corrupt", "seeded")) {
            assertTrue(StorageNames.isSafe(name), name)
        }
        for (name in listOf("../secret", "a/b", ".hidden", "", "book-..txt", "books.json\n", "a".repeat(200))) {
            assertFalse(StorageNames.isSafe(name), name)
        }
    }

    @Test
    fun `a huge context is never saved`() {
        val library = library()
        library.saveWord("sea", learned = false, context = "sea ".repeat(5_000), contextTranslation = "mar ".repeat(5_000))
        val saved = library.state.value.word("sea")!!
        assertEquals(TextTools.MAX_SENTENCE, saved.context.length)
        assertTrue(saved.contextTranslation!!.length <= 2 * TextTools.MAX_SENTENCE)
    }

    @Test
    fun `a huge saved context is cut when loaded`() {
        library().saveWord("sea", learned = false, context = "sea ".repeat(5_000))
        assertEquals(TextTools.MAX_SENTENCE, library().state.value.word("sea")!!.context.length)
    }

    @Test
    fun `restored settings and books are brought back into range`() {
        storage.write("settings.json", """{"dailyGoalMinutes": 0, "fontSize": 900.0, "speechRate": -3.0}""")
        val settings = library().state.value.settings
        assertEquals(5, settings.dailyGoalMinutes, "a goal of 0 would make every day count")
        assertEquals(34f, settings.fontSize)
        assertEquals(0.5f, settings.speechRate)
        assertTrue(library().streak() < MAX_STREAK_DAYS + 1, "the streak always ends")
    }

    @Test
    fun `a saved word with an unreadable date is due now instead of crashing`() {
        library().saveWord("sea", learned = false)
        storage.write(
            "words.json",
            storage.read("words.json")!!.replace(Regex("\"nextReview\":\"[^\"]*\""), "\"nextReview\":\"not-a-date\""),
        )
        val library = library()
        assertEquals(listOf("sea"), library.dueWords().map { it.word })
        assertNull(library.millisUntilNextDue())
    }
}

/** The first whole-word, case-sensitive match of [word]. */
internal fun String.rangeOf(word: String): WordRange = TextTools.wordRanges(this).first { substring(it.start, it.end) == word }
