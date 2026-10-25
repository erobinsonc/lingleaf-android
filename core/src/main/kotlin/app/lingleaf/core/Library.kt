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
    /** Which meaning of [word] this is ([Sense]); null for a word saved without one. */
    val sense: String? = null,
    /** The word as tapped in [context] ("left") and where, so Review bolds only that occurrence. */
    val form: String? = null,
    val formStart: Int? = null,
    val formEnd: Int? = null,
    /**
     * A word saved unsure ([sense] null, [word] the tapped form): both readings with their
     * meanings in [translationLanguage], shown in place of a translation (SPEC S4, S7).
     */
    val meanings: List<SavedMeaning>? = null,
    /**
     * A word corrected to match its sentence that has no meaning yet: not due until its
     * translation arrives (SPEC S18, S12), so it is never rehearsed without one.
     */
    val waitsForMeaning: Boolean = false,
) {
    /** What identifies the saved word: its spelling and, when it has one, its sense. */
    val key: String get() = keyOf(word, sense)

    /** Saved unsure: the tapped form with both readings. */
    val isUnsure: Boolean get() = sense == null && meanings != null

    /** Where the tapped occurrence is in [context], when known and still matching. */
    val formRange: WordRange?
        get() {
            val start = formStart ?: return null
            val end = formEnd ?: return null
            val range = WordRange(start, end)
            if (form == null || start >= end || !range.fitsIn(context)) return null
            return range.takeIf { TextTools.normalize(context.substring(start, end)) == TextTools.normalize(form) }
        }

    /** [contextTranslation] when it is in [language]. */
    fun contextTranslation(language: String?) = contextTranslation.takeIf { contextTranslationLanguage == language }

    fun isDue(now: LocalDateTime) = !learned && !waitsForMeaning && !LocalDateTime.parse(nextReview).isAfter(now)

    /** Whether it has something to show as its meaning: a translation, or both readings' meanings. */
    val hasMeaning: Boolean
        get() = if (isUnsure) meanings.orEmpty().all { it.meaning.isNotBlank() } else translation.isNotBlank()

    fun needsTranslation(language: String) =
        if (isUnsure) {
            translationLanguage != language || meanings.orEmpty().any { it.meaning.isBlank() }
        } else {
            translation.isEmpty() || translationLanguage != language
        }

    /**
     * As read back from storage: an unknown sense, a form that isn't a word of [context] or more
     * than two meanings are dropped, so a restored or edited file can't confuse what is shown.
     */
    fun bounded(): VocabWord {
        val cleanForm = form?.takeIf { it.length <= MAX_FORM && formPattern.matches(it) }
        val withForm = copy(sense = sense?.takeIf { it in Sense.all }, form = cleanForm)
        val range = withForm.formRange
        return withForm.copy(
            formStart = range?.start,
            formEnd = range?.end,
            form = cleanForm.takeIf { range != null },
            meanings = if (withForm.sense != null) null else SavedMeaning.bounded(meanings),
        )
    }

    companion object {
        /** The key of [word] in [sense]: "leave", "leave#verb". */
        fun keyOf(
            word: String,
            sense: String?,
        ) = if (sense == null) word else "$word#$sense"

        /** Longest tapped form kept, in UTF-16 units. */
        const val MAX_FORM = 40
        private val formPattern = Regex("^[A-Za-z]+(?:['’][A-Za-z]+)*$")
    }
}

@Serializable
data class Settings(
    val dailyGoalMinutes: Int = 10,
    val fontSize: Float = 20f,
    val speechRate: Float = 0.9f,
    val targetLanguage: String? = null,
) {
    /**
     * Within the ranges Settings offers: a restored or edited settings file can't make the
     * streak count forever (a goal of 0) or the reader unreadable.
     */
    fun bounded(): Settings =
        copy(
            dailyGoalMinutes = dailyGoalMinutes.coerceIn(5, 60),
            fontSize = if (fontSize.isFinite()) fontSize.coerceIn(14f, 34f) else Settings().fontSize,
            speechRate = if (speechRate.isFinite()) speechRate.coerceIn(0.5f, 1.3f) else Settings().speechRate,
        )
}

data class BundledStory(val title: String, val level: String, val text: String)

data class LibraryState(
    val books: List<Book> = emptyList(),
    val words: List<VocabWord> = emptyList(),
    val readingLog: Map<String, Int> = emptyMap(),
    val settings: Settings = Settings(),
    /** Words whose sentence was read again after an update, until the learner closes the card (SPEC S18). */
    val corrections: List<Correction> = emptyList(),
) {
    /** Base forms of words being learned. */
    val learningWords: Set<String> = words.filterNot { it.learned }.map { it.word }.toSet()

    /** What the reader underlines: words being learned, with their senses (SPEC S4). */
    val learningIndex: LearningIndex = LearningIndex.of(words)

    /**
     * Whether the word at [range] of [text] is a form of a word being learned, a homograph
     * read in its sentence ("she left" for a saved "leave", not "turn left").
     */
    fun isLearning(
        text: String,
        range: WordRange,
    ): Boolean {
        if (learningIndex.isEmpty || !range.fitsIn(text)) return false
        val words = TextTools.wordRanges(text)
        val (surface, keys) = TextTools.senseKeys(text, words, words.indexOf(range)) ?: return false
        return learningIndex.matches(surface, keys)
    }

    /** The words of [text] ([words], its word ranges) that are forms of words being learned. */
    fun learning(
        text: String,
        words: List<WordRange>,
    ): List<WordRange> = TextTools.marked(text, words, learningIndex)

    /** The saved word with [key] ("leave", or "leave#verb" with its sense). */
    fun word(key: String) = words.firstOrNull { it.key == TextTools.normalize(key) }

    /**
     * How Words and Review name [word]: its spelling, or "to wind" / "the wind" when another
     * sense of the same spelling is saved too (SPEC S4).
     */
    fun displayName(word: VocabWord): String {
        val sense = word.sense ?: return word.word
        val others = words.filter { it.word == word.word && it.sense != null && it.sense != sense }
        if (others.isEmpty()) return word.word
        val name = if (sense == Sense.NOUN) "the ${word.word}" else "to ${word.word}"
        // Two verbs ("to lie": recline, say what isn't true): their first meanings tell them apart,
        // the table's in Spanish, else the translated one.
        val twin = others.any { it.sense != Sense.NOUN } && sense != Sense.NOUN
        if (!twin) return name
        val spanish = settings.targetLanguage?.substringBefore('-') == "es"
        val meaning =
            if (spanish) {
                IrregularVerbs.shared.meanings(word.word, homonym = sense == Sense.HOMONYM).firstOrNull()
            } else {
                word.translation.substringBefore(" · ").trim().takeIf { it.isNotEmpty() }
            }
        return if (meaning != null) "$name ($meaning)" else name
    }

    /**
     * The saved entry for [word] in [sense]: that sense's, or, for a sense, the same word saved
     * before senses existed (no sense, not unsure), which saving converts to the sense.
     */
    fun saved(
        word: String,
        sense: String?,
    ): VocabWord? {
        val name = TextTools.normalize(word)
        return word(VocabWord.keyOf(name, sense)) ?: if (sense == null) null else plainEntry(name)
    }

    internal fun plainEntry(name: String) =
        words.firstOrNull { it.word == name && it.sense == null && it.meanings == null && it.kind == null }

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

    /** Whether the irregular-verb table loaded; the corrections need it (tests can say it didn't). */
    internal var tableReady: () -> Boolean = { IrregularVerbs.loadError == null && IrregularVerbs.shared.formToBase.isNotEmpty() }

    private fun now() = LocalDateTime.now(clock)

    private fun isDate(text: String) = runCatching { LocalDateTime.parse(text) }.isSuccess

    private fun withReadableDates(word: VocabWord): VocabWord =
        if (isDate(word.nextReview) && isDate(word.createdAt)) {
            word
        } else {
            word.copy(nextReview = now().toString(), createdAt = now().toString())
        }

    /** Milliseconds until the next word being learned falls due; null when none is waiting. */
    fun millisUntilNextDue(): Long? {
        val now = now()
        return _state.value.words
            .filter { !it.learned }
            .map { LocalDateTime.parse(it.nextReview) }
            .filter { it.isAfter(now) }
            .minOrNull()
            ?.let { java.time.Duration.between(now, it).toMillis() }
    }

    @Synchronized
    fun load() {
        _state.value =
            LibraryState(
                // Progress stays inside the book, whatever a restored file says.
                books =
                    decode(BOOKS, ListSerializer(Book.serializer()))
                        ?.map { it.copy(progressParagraph = it.progressParagraph.coerceIn(0, maxOf(it.paragraphCount - 1, 0))) }
                        ?: emptyList(),
                // Saved before contexts were capped, or restored from a backup: cut to size.
                words =
                    decode(WORDS, ListSerializer(VocabWord.serializer()))
                        // A date that can't be read is due now rather than a crash on every screen.
                        ?.map(::storedWord)
                        // One entry per spelling and sense, whatever a restored file holds.
                        ?.distinctBy { it.key }
                        ?: emptyList(),
                readingLog = decode(READING_LOG, readingLogSerializer) ?: emptyMap(),
                settings = (decode(SETTINGS, Settings.serializer()) ?: Settings()).bounded(),
                // Read back like the word list: a restored or edited file can't put anything unbounded back.
                corrections =
                    decode(CORRECTIONS, ListSerializer(Correction.serializer()))
                        .orEmpty()
                        .take(MAX_CORRECTIONS)
                        .map { it.bounded(::storedWord) }
                        .distinctBy { it.original.key },
            )
    }

    /** A word as read back from storage: cut to size, bounded and with readable dates. */
    private fun storedWord(word: VocabWord): VocabWord =
        withReadableDates(word.copy(context = TextTools.truncated(word.context, TextTools.MAX_SENTENCE)).bounded())

    /**
     * Once after the update that reads homographs in their sentence (SPEC S18): a saved word
     * whose sentence now reads differently becomes what the sentence says ("leave" saved from
     * "Turn left" → "left", the noun), or, when it can't tell, the tapped form with both readings.
     * A corrected word in Learning starts its review again (first box, due now, once it has a
     * meaning); a known one stays known; one that matches an entry already saved is merged into
     * it. Words shows the corrections until closed, with Undo. The app runs this off the main
     * thread after [load]; at most [MAX_CORRECTED_PER_RUN] words are read per run.
     */
    @Synchronized
    fun correctWordsOnce() {
        val progress = storage.read(CORRECTED)
        if (progress == DONE || progress == LEGACY_DONE) return
        // Only with the table: a refused table would read every word as unsure.
        if (!tableReady()) return
        val after = progress?.removePrefix(PARTIAL)?.takeIf { progress.startsWith(PARTIAL) }
        val language = _state.value.settings.targetLanguage
        val eligible = _state.value.words.filter { after == null || it.createdAt < after }
        val batch = eligible.take(MAX_CORRECTED_PER_RUN)
        val items = mutableListOf<Correction>()
        var words = _state.value.words
        for (original in batch) {
            val corrected = Corrections.correct(original, language) ?: continue
            val merged = words.any { it.key == corrected.key && it !== original }
            val next =
                if (original.learned) {
                    corrected
                } else {
                    // Rehearsed with a wrong meaning: from the first box, due now, once it has a meaning.
                    corrected.copy(box = 0, nextReview = now().toString(), waitsForMeaning = !corrected.hasMeaning)
                }.bounded()
            words = words.filterNot { it === original }
            if (!merged) words = words + next
            val from = original.word
            items +=
                Correction(
                    from = from, to = corrected.word, original = original, key = next.key, merged = merged,
                    result =
                        next.takeUnless {
                            merged
                        },
                )
        }
        // The flag is written before the result is saved: a crash while saving can't make every
        // start run (and crash in) the migration again.
        val done = batch.size == eligible.size
        storage.write(CORRECTED, if (done) DONE else PARTIAL + batch.last().createdAt)
        if (items.isEmpty()) return
        _state.update {
            it.copy(
                words =
                    words.sortedByDescending {
                            w ->
                        w.createdAt
                    },
                corrections = (it.corrections + items).take(MAX_CORRECTIONS),
            )
        }
        saveWords()
        saveCorrections()
    }

    /**
     * Undo for the corrections card (SPEC S18): latest first, each corrected entry that is still
     * exactly as the correction left it is removed and its original put back (with its review
     * box); one changed since (reviewed, edited) stays as it is now, a word deleted since isn't
     * brought back, and an original whose place another entry has taken isn't forced in.
     */
    @Synchronized
    fun undoCorrections() {
        val items = _state.value.corrections
        if (items.isEmpty()) return
        var words = _state.value.words
        for (item in items.asReversed()) {
            if (!item.merged) {
                val result = item.result ?: continue
                val current = words.firstOrNull { it.key == result.key }
                if (current == null || current.changedSince(result)) continue
                words = words.filterNot { it === current }
            }
            if (words.none { it.key == item.original.key }) words = words + item.original.bounded()
        }
        _state.update { it.copy(words = words.sortedByDescending { w -> w.createdAt }, corrections = emptyList()) }
        saveWords()
        saveCorrections()
    }

    /**
     * Whether the learner changed this entry after the correction wrote it as [then]: reviewed,
     * marked known or not, or saved as another sense. A translation arriving is no change.
     */
    private fun VocabWord.changedSince(then: VocabWord) =
        box != then.box || learned != then.learned || nextReview != then.nextReview || sense != then.sense

    /** Closes the corrections card; the corrections stay. */
    @Synchronized
    fun closeCorrections() {
        _state.update { it.copy(corrections = emptyList()) }
        saveCorrections()
    }

    private fun saveCorrections() {
        val items = _state.value.corrections
        if (items.isEmpty()) {
            storage.delete(
                CORRECTIONS,
            )
        } else {
            storage.write(CORRECTIONS, json.encodeToString(ListSerializer(Correction.serializer()), items))
        }
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
     * [sense] saves one meaning of a homograph's spelling; [form] and [formRange] say which
     * occurrence of [context] was tapped; [meanings] save a word unsure (both readings).
     * [replacing] is the key of the same form saved unsure before: it is removed, and the new
     * entry keeps its review progress (Undo puts it back with [restoreWord]).
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
        sense: String? = null,
        form: String? = null,
        formRange: WordRange? = null,
        meanings: List<SavedMeaning>? = null,
        replacing: String? = null,
    ): VocabWord? {
        val name = TextTools.normalize(word)
        val key = VocabWord.keyOf(name, sense?.takeIf { it in Sense.all })
        val now = now().toString()
        // A word saved before senses existed becomes the sense it is saved as now, keeping its progress.
        val existing =
            _state.value.words.firstOrNull { it.key == key }
                ?: sense?.takeIf { it in Sense.all && kind == null }?.let { _state.value.plainEntry(name) }
        val replacedKey = replacing?.let(TextTools::normalize)?.takeIf { it != key }
        val replaced = replacedKey?.let { r -> _state.value.words.firstOrNull { it.key == r } }
        // A form saved unsure lends its review progress to the reading chosen now.
        val start =
            existing
                ?: replaced?.let {
                    VocabWord(word = name, createdAt = it.createdAt, nextReview = it.nextReview, box = it.box, learned = it.learned)
                }
        var entry = start ?: VocabWord(word = name, createdAt = now, nextReview = now)
        // A new word is first reviewed tomorrow, after a night's sleep (S7).
        if (!learned && (start == null || start.learned)) {
            entry = entry.copy(box = 0, nextReview = Srs.startOfDay(1, now()).toString())
        }
        val newTranslation = translation?.takeIf { it.isNotBlank() }
        val unsure = sense == null && kind == null && !meanings.isNullOrEmpty()
        val sameContext = context.isBlank() || context == entry.context
        entry =
            entry.copy(
                learned = learned,
                translation = if (unsure) "" else newTranslation ?: entry.translation,
                translationLanguage =
                    if (newTranslation != null || unsure) translationLanguage else entry.translationLanguage,
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
                sense = sense?.takeIf { it in Sense.all },
                form = if (form != null) form else entry.form.takeIf { sameContext },
                formStart = if (form != null) formRange?.start else entry.formStart.takeIf { sameContext },
                formEnd = if (form != null) formRange?.end else entry.formEnd.takeIf { sameContext },
                meanings = if (unsure) meanings else null,
            )
        val gone = listOfNotNull(replaced?.key, existing?.key?.takeIf { it != key })
        if (gone.isNotEmpty()) _state.update { st -> st.copy(words = st.words.filterNot { it.key in gone }) }
        replaceWord(key, entry)
        return existing
    }

    /** Undo: puts back exactly [previous] for [key], or removes the word when [previous] is null. */
    @Synchronized
    fun restoreWord(
        key: String,
        previous: VocabWord?,
    ) {
        val k = TextTools.normalize(key)
        if (previous == null) deleteWord(k) else replaceWord(k, previous)
    }

    /**
     * Records a translation that arrived after the word was saved. Does nothing
     * if the word no longer exists or already has a translation in [language].
     */
    @Synchronized
    fun setTranslation(
        key: String,
        translation: String,
        language: String,
    ) {
        val k = TextTools.normalize(key)
        val entry = _state.value.words.firstOrNull { it.key == k } ?: return
        if (translation.isBlank() || entry.isUnsure || !entry.needsTranslation(language)) return
        replaceWord(k, entry.copy(translation = translation, translationLanguage = language).withMeaningArrived(entry))
    }

    /** A corrected word waiting for its meaning is due (it already was, from the correction) once it has one (SPEC S18). */
    private fun VocabWord.withMeaningArrived(before: VocabWord): VocabWord =
        if (before.waitsForMeaning && hasMeaning) copy(waitsForMeaning = false) else this

    /** Records the meanings of a word saved unsure, in [language], in the order it has them. */
    @Synchronized
    fun setMeanings(
        key: String,
        meanings: List<String>,
        language: String,
    ) {
        val k = TextTools.normalize(key)
        val entry = _state.value.words.firstOrNull { it.key == k } ?: return
        val saved = entry.meanings ?: return
        if (meanings.size != saved.size || meanings.any { it.isBlank() }) return
        val updated = entry.copy(meanings = saved.zip(meanings) { m, t -> m.copy(meaning = t) }, translationLanguage = language)
        replaceWord(k, updated.withMeaningArrived(entry))
    }

    /** Records the translation of a word's sentence that arrived after it was saved. */
    @Synchronized
    fun setContextTranslation(
        key: String,
        translation: String,
        language: String,
    ) {
        val k = TextTools.normalize(key)
        val entry = _state.value.words.firstOrNull { it.key == k } ?: return
        if (translation.isBlank() || entry.context.isEmpty()) return
        replaceWord(k, entry.copy(contextTranslation = translation, contextTranslationLanguage = language))
    }

    /** Words whose sentence has no translation into [language] yet. */
    fun contextsNeedingTranslation(language: String): List<VocabWord> =
        _state.value.words.filter { it.context.isNotEmpty() && it.contextTranslation(language) == null }

    /** Words with no translation, or one in a language other than [language]. */
    fun wordsNeedingTranslation(language: String): List<VocabWord> = _state.value.words.filter { it.needsTranslation(language) }

    @Synchronized
    fun answerReview(
        key: String,
        correct: Boolean,
    ) {
        val entry = _state.value.words.firstOrNull { it.key == key } ?: return
        val result = Srs.review(entry.box, correct, now())
        replaceWord(key, entry.copy(box = result.box, nextReview = result.nextReview.toString(), learned = result.learned))
    }

    @Synchronized
    fun setLearned(
        key: String,
        learned: Boolean,
    ) {
        val entry = _state.value.words.firstOrNull { it.key == key } ?: return
        replaceWord(key, if (learned) entry.copy(learned = true) else entry.copy(learned = false, box = 0, nextReview = now().toString()))
    }

    @Synchronized
    fun deleteWord(key: String) {
        _state.update { s -> s.copy(words = s.words.filterNot { it.key == key }) }
        saveWords()
    }

    private fun replaceWord(
        key: String,
        entry: VocabWord,
    ) {
        // Every saved word passes here: whatever a caller hands in, nothing huge is kept.
        val bounded =
            entry
                .copy(
                    context = TextTools.truncated(entry.context, TextTools.MAX_SENTENCE),
                    contextTranslation = entry.contextTranslation?.let { TextTools.truncated(it, MAX_CONTEXT_TRANSLATION) },
                ).bounded()
        _state.update { s ->
            val others = s.words.filterNot { it.key == key }
            s.copy(words = (others + bounded).sortedByDescending { it.createdAt })
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
        private const val CORRECTED = "corrected-homographs"
        private const val CORRECTIONS = "corrections.json"
        private const val DONE = "done"
        private const val LEGACY_DONE = "1"
        private const val PARTIAL = "after:"

        /** Corrections kept for the card. */
        const val MAX_CORRECTIONS = 1000

        /** Saved words read against their sentence in one run of [correctWordsOnce]. */
        const val MAX_CORRECTED_PER_RUN = 5000
        private const val MAX_SESSION_SECONDS = 3600L
        private const val IMPORTED_AUTHOR = "—"
        private const val DEFAULT_TITLE = "Book"

        /** A translation can be longer than its sentence; twice the sentence's limit. */
        private const val MAX_CONTEXT_TRANSLATION = 2 * TextTools.MAX_SENTENCE

        /** "Alice.txt" → "Alice", "my.story.TXT" → "my.story"; an empty name becomes "Book". */
        fun importTitle(fileName: String): String {
            val dot = fileName.lastIndexOf('.')
            val stem = if (dot >= 0) fileName.substring(0, dot) else fileName
            return stem.trim().ifEmpty { DEFAULT_TITLE }
        }
    }
}
