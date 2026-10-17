package app.lingleaf.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.LocalDateTime

@Serializable
data class Book(
    val id: String,
    val title: String,
    val author: String,
    val fileName: String,
    val paragraphCount: Int,
    val level: String? = null,
    val gutenbergId: Int? = null,
    val addedAt: String,
    val lastOpenedAt: String? = null,
    val progressParagraph: Int = 0,
) {
    val progress: Float
        get() = if (paragraphCount <= 1) 0f else (progressParagraph.toFloat() / (paragraphCount - 1)).coerceIn(0f, 1f)
}

/** A word the user chose to learn or marked as known. [word] is the lowercased base form. */
@Serializable
data class VocabWord(
    val word: String,
    val translation: String = "",
    val phonetic: String = "",
    val context: String = "",
    val source: String = "",
    val learned: Boolean = false,
    val box: Int = 0,
    val createdAt: String,
    val nextReview: String,
    /** Language code of [translation]; null for words saved before it was recorded. */
    val translationLanguage: String? = null,
    /**
     * Set when [word] is the verb group of a helper or modal verb ("did not go"): what is
     * learned and translated, since the helper alone means little. Null for other words.
     */
    val kind: FunctionWord.Kind? = null,
    /** The helper or modal verb's base form ("do" for "did not go"). */
    val base: String? = null,
    /**
     * The translation of [context] and the language it is in; filled in later when the
     * card didn't have it, and again when the translation language changes.
     */
    val contextTranslation: String? = null,
    val contextTranslationLanguage: String? = null,
) {
    /** [contextTranslation] when it is in [language]. */
    fun contextTranslation(language: String?) = contextTranslation.takeIf { contextTranslationLanguage == language }

    fun isDue(now: LocalDateTime) = !learned && !LocalDateTime.parse(nextReview).isAfter(now)

    fun needsTranslation(language: String) = translation.isEmpty() || translationLanguage != language
}

@Serializable
data class Settings(
    val dailyGoalMinutes: Int = 10,
    val fontSize: Float = 20f,
    val speechRate: Float = 0.9f,
    val targetLanguage: String? = null,
)

data class BundledStory(val title: String, val level: String, val text: String)

data class LibraryState(
    val books: List<Book> = emptyList(),
    val words: List<VocabWord> = emptyList(),
    val readingLog: Map<String, Int> = emptyMap(),
    val settings: Settings = Settings(),
) {
    /** Base forms of words being learned. */
    val learningWords: Set<String> = words.filterNot { it.learned }.map { it.word }.toSet()

    fun isLearning(token: String) = learningWords.isNotEmpty() && TextTools.matchKeys(token).any { it in learningWords }

    fun word(base: String) = words.firstOrNull { it.word == TextTools.normalize(base) }

    val lastOpened: Book? get() = books.filter { it.lastOpenedAt != null }.maxByOrNull { it.lastOpenedAt!! }
}

/**
 * Names a [Storage] accepts: the plain names the app makes ("books.json", "book-<id>.txt").
 * A name read back from a restored backup can't reach outside the app's folder.
 */
object StorageNames {
    private val safe = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

    fun isSafe(name: String): Boolean = ".." !in name && safe.matches(name)
}

/** Where files live. The app maps it to internal storage; tests to a temp dir. */
interface Storage {
    fun read(name: String): String?

    fun write(
        name: String,
        content: String,
    )

    fun delete(name: String)
}

/**
 * Books, words, reading time and settings. All persistence goes through
 * [Storage] as small JSON files; each book's text lives in its own file.
 */
class Library(
    private val storage: Storage,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val readingLogSerializer = MapSerializer(String.serializer(), Int.serializer())
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private fun now() = LocalDateTime.now(clock)

    @Synchronized
    fun load() {
        _state.value =
            LibraryState(
                books = decode(BOOKS, ListSerializer(Book.serializer())) ?: emptyList(),
                // Saved before contexts were capped, or restored from a backup: cut to size.
                words =
                    decode(WORDS, ListSerializer(VocabWord.serializer()))
                        ?.map { if (it.context.length > MAX_CONTEXT) it.copy(context = it.context.take(MAX_CONTEXT)) else it }
                        ?: emptyList(),
                readingLog = decode(READING_LOG, readingLogSerializer) ?: emptyMap(),
                settings = decode(SETTINGS, Settings.serializer()) ?: Settings(),
            )
    }

    /** Adds the bundled stories the first time the app runs. */
    @Synchronized
    fun seedIfNeeded(stories: List<BundledStory>) {
        if (storage.read(SEEDED) != null) return
        stories.forEach { addBook(it.title, "Lingleaf", it.text, level = it.level) }
        storage.write(SEEDED, "1")
    }

    // Books

    @Synchronized
    fun addBook(
        title: String,
        author: String,
        text: String,
        level: String? = null,
        gutenbergId: Int? = null,
    ): Book {
        val id = "${clock.millis()}-${_state.value.books.size}"
        val book =
            Book(
                id = id,
                title = TextTools.cleanLabel(title),
                author = TextTools.cleanLabel(author),
                fileName = "book-$id.txt",
                paragraphCount = TextTools.splitParagraphs(text).size,
                level = level,
                gutenbergId = gutenbergId,
                addedAt = now().toString(),
            )
        storage.write(book.fileName, text)
        _state.update { it.copy(books = it.books + book) }
        saveBooks()
        return book
    }

    /**
     * Adds an imported plain-text file: the Gutenberg license header and footer
     * are removed and the title comes from [fileName]. Text that is empty after
     * that is refused with [IllegalArgumentException] and nothing is added.
     */
    @Synchronized
    fun importText(
        fileName: String,
        text: String,
    ): Book {
        val body = GutenbergClient.stripBoilerplate(text)
        require(body.isNotBlank()) { "No text to import" }
        return addBook(importTitle(fileName), IMPORTED_AUTHOR, body)
    }

    @Synchronized
    fun deleteBook(id: String) {
        val book = _state.value.books.firstOrNull { it.id == id } ?: return
        storage.delete(book.fileName)
        _state.update { s -> s.copy(books = s.books.filterNot { it.id == id }) }
        saveBooks()
    }

    /** Marks the book as opened and returns its paragraphs. */
    @Synchronized
    fun openBook(id: String): List<String> {
        val book = _state.value.books.firstOrNull { it.id == id } ?: return emptyList()
        updateBook(id) { it.copy(lastOpenedAt = now().toString()) }
        saveBooks()
        return TextTools.splitParagraphs(storage.read(book.fileName).orEmpty())
    }

    /** Updates progress in memory; call [flush] to persist (e.g. when leaving the reader). */
    @Synchronized
    fun updateProgress(
        id: String,
        paragraph: Int,
    ) = updateBook(id) { it.copy(progressParagraph = paragraph) }

    @Synchronized
    fun flush() = saveBooks()

    private fun updateBook(
        id: String,
        transform: (Book) -> Book,
    ) = _state.update { s -> s.copy(books = s.books.map { if (it.id == id) transform(it) else it }) }

    // Words

    fun dueWords(): List<VocabWord> {
        val now = now()
        return _state.value.words.filter { it.isDue(now) }.sortedBy { it.nextReview }
    }

    /** Today's review session: at most [Srs.SESSION_LIMIT] due words, interleaved by book. */
    fun reviewSession(): List<VocabWord> = Srs.session(dueWords())

    /** Words being learned that come due before the end of tomorrow (not those due now), for "12 more tomorrow". */
    fun dueByEndOfTomorrow(): Int {
        val now = now()
        val end = Srs.startOfDay(2, now)
        return _state.value.words.count {
            val next = LocalDateTime.parse(it.nextReview)
            !it.learned && next.isAfter(now) && next.isBefore(end)
        }
    }

    /**
     * Saves [word] for review, or as known when [learned]. A word that was known
     * goes back to the first box. Blank values keep what was saved before.
     * Returns the previous record (null if the word was new), for Undo with [restoreWord].
     * For a helper or modal verb, [word] is its verb group and [kind]/[base] say what it is.
     */
    @Synchronized
    fun saveWord(
        word: String,
        learned: Boolean,
        translation: String? = null,
        phonetic: String? = null,
        context: String = "",
        source: String = "",
        translationLanguage: String? = null,
        kind: FunctionWord.Kind? = null,
        base: String? = null,
        contextTranslation: String? = null,
    ): VocabWord? {
        val key = TextTools.normalize(word)
        val now = now().toString()
        val existing = _state.value.words.firstOrNull { it.word == key }
        var entry = existing ?: VocabWord(word = key, createdAt = now, nextReview = now)
        // A new word is first reviewed tomorrow, after a night's sleep (S7).
        if (!learned && (existing == null || existing.learned)) {
            entry = entry.copy(box = 0, nextReview = Srs.startOfDay(1, now()).toString())
        }
        val newTranslation = translation?.takeIf { it.isNotBlank() }
        entry =
            entry.copy(
                learned = learned,
                translation = newTranslation ?: entry.translation,
                translationLanguage = if (newTranslation != null) translationLanguage else entry.translationLanguage,
                phonetic = phonetic?.takeIf { it.isNotBlank() } ?: entry.phonetic,
                context = context.ifBlank { entry.context },
                contextTranslation =
                    contextTranslation?.takeIf { it.isNotBlank() }
                        ?: entry.contextTranslation.takeIf { context.isBlank() || context == entry.context },
                contextTranslationLanguage =
                    if (!contextTranslation.isNullOrBlank()) {
                        translationLanguage
                    } else {
                        entry.contextTranslationLanguage.takeIf { context.isBlank() || context == entry.context }
                    },
                source = source.ifBlank { entry.source },
                kind = kind,
                base = if (kind == null) null else base,
            )
        replaceWord(key, entry)
        return existing
    }

    /** Undo: puts back exactly [previous] for [word], or removes the word when [previous] is null. */
    @Synchronized
    fun restoreWord(
        word: String,
        previous: VocabWord?,
    ) {
        val key = TextTools.normalize(word)
        if (previous == null) deleteWord(key) else replaceWord(key, previous)
    }

    /**
     * Records a translation that arrived after the word was saved. Does nothing
     * if the word no longer exists or already has a translation in [language].
     */
    @Synchronized
    fun setTranslation(
        word: String,
        translation: String,
        language: String,
    ) {
        val key = TextTools.normalize(word)
        val entry = _state.value.words.firstOrNull { it.word == key } ?: return
        if (translation.isBlank() || !entry.needsTranslation(language)) return
        replaceWord(key, entry.copy(translation = translation, translationLanguage = language))
    }

    /** Records the translation of a word's sentence that arrived after it was saved. */
    @Synchronized
    fun setContextTranslation(
        word: String,
        translation: String,
        language: String,
    ) {
        val key = TextTools.normalize(word)
        val entry = _state.value.words.firstOrNull { it.word == key } ?: return
        if (translation.isBlank() || entry.context.isEmpty()) return
        replaceWord(key, entry.copy(contextTranslation = translation, contextTranslationLanguage = language))
    }

    /** Words whose sentence has no translation into [language] yet. */
    fun contextsNeedingTranslation(language: String): List<VocabWord> =
        _state.value.words.filter { it.context.isNotEmpty() && it.contextTranslation(language) == null }

    /** Words with no translation, or one in a language other than [language]. */
    fun wordsNeedingTranslation(language: String): List<VocabWord> = _state.value.words.filter { it.needsTranslation(language) }

    @Synchronized
    fun answerReview(
        word: String,
        correct: Boolean,
    ) {
        val entry = _state.value.words.firstOrNull { it.word == word } ?: return
        val result = Srs.review(entry.box, correct, now())
        replaceWord(word, entry.copy(box = result.box, nextReview = result.nextReview.toString(), learned = result.learned))
    }

    @Synchronized
    fun setLearned(
        word: String,
        learned: Boolean,
    ) {
        val entry = _state.value.words.firstOrNull { it.word == word } ?: return
        replaceWord(word, if (learned) entry.copy(learned = true) else entry.copy(learned = false, box = 0, nextReview = now().toString()))
    }

    @Synchronized
    fun deleteWord(word: String) {
        _state.update { s -> s.copy(words = s.words.filterNot { it.word == word }) }
        saveWords()
    }

    private fun replaceWord(
        key: String,
        entry: VocabWord,
    ) {
        _state.update { s ->
            val others = s.words.filterNot { it.word == key }
            s.copy(words = (others + entry).sortedByDescending { it.createdAt })
        }
        saveWords()
    }

    // Reading time and settings

    fun secondsToday(): Int = _state.value.readingLog[dayKey(now().toLocalDate())] ?: 0

    fun streak(): Int = readingStreak(_state.value.readingLog, _state.value.settings.dailyGoalMinutes * 60, now().toLocalDate())

    @Synchronized
    fun addReadingTime(seconds: Long) {
        val add = seconds.coerceIn(0, MAX_SESSION_SECONDS).toInt()
        if (add == 0) return
        val key = dayKey(now().toLocalDate())
        _state.update { s -> s.copy(readingLog = s.readingLog + (key to (s.readingLog[key] ?: 0) + add)) }
        storage.write(READING_LOG, json.encodeToString(readingLogSerializer, _state.value.readingLog))
    }

    @Synchronized
    fun updateSettings(transform: (Settings) -> Settings) {
        _state.update { it.copy(settings = transform(it.settings)) }
        storage.write(SETTINGS, json.encodeToString(Settings.serializer(), _state.value.settings))
    }

    private fun saveBooks() = storage.write(BOOKS, json.encodeToString(ListSerializer(Book.serializer()), _state.value.books))

    private fun saveWords() = storage.write(WORDS, json.encodeToString(ListSerializer(VocabWord.serializer()), _state.value.words))

    /**
     * A file that exists but can't be read is kept as `<name>.corrupt` before the
     * next save replaces it, so a damaged library or word list can be recovered.
     */
    private fun <T> decode(
        name: String,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): T? =
        storage.read(name)?.let {
            try {
                require(JsonGuard.isShallow(it)) { "Nested too deeply" }
                json.decodeFromString(serializer, it)
            } catch (e: Exception) {
                storage.write("$name.corrupt", it)
                null
            }
        }

    companion object {
        private const val BOOKS = "books.json"
        private const val WORDS = "words.json"
        private const val READING_LOG = "reading-log.json"
        private const val SETTINGS = "settings.json"
        private const val SEEDED = "seeded"
        private const val MAX_SESSION_SECONDS = 3600L
        private const val IMPORTED_AUTHOR = "—"
        private const val DEFAULT_TITLE = "Book"
        private const val MAX_CONTEXT = TextTools.MAX_SENTENCE

        /** "Alice.txt" → "Alice", "my.story.TXT" → "my.story"; an empty name becomes "Book". */
        fun importTitle(fileName: String): String {
            val dot = fileName.lastIndexOf('.')
            val stem = if (dot >= 0) fileName.substring(0, dot) else fileName
            return stem.trim().ifEmpty { DEFAULT_TITLE }
        }
    }
}
