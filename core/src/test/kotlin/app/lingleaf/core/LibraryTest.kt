package app.lingleaf.core

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `learning words highlight inflected forms and survive restart`() {
        val library = library()
        library.saveWord("Animal", learned = false, translation = "animal", context = "All animals are equal.", source = "The Red Boat")
        val state = library.state.value
        assertTrue(state.isLearning("animals"))
        assertFalse(state.isLearning("equal"))
        assertEquals("animal", library.dueWords().single().word)

        library.saveWord("animal", learned = true, translation = "")
        assertFalse(library.state.value.isLearning("animals"))
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
}
