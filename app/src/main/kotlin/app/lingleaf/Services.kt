package app.lingleaf

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.lingleaf.core.BundledStory
import app.lingleaf.core.DictionaryClient
import app.lingleaf.core.GutenbergBook
import app.lingleaf.core.GutenbergClient
import app.lingleaf.core.GutenbergException
import app.lingleaf.core.Hosts
import app.lingleaf.core.HttpFetcher
import app.lingleaf.core.HttpResponse
import app.lingleaf.core.IrregularVerbs
import app.lingleaf.core.Library
import app.lingleaf.core.Meanings
import app.lingleaf.core.NetworkException
import app.lingleaf.core.Reading
import app.lingleaf.core.Sense
import app.lingleaf.core.Settings
import app.lingleaf.core.Storage
import app.lingleaf.core.StorageNames
import app.lingleaf.core.TranslationLanguages
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Whether a language can be translated into on the device right now. */
enum class ModelStatus { READY, DOWNLOADING, NEEDS_WIFI }

fun interface Translator {
    /** Translates English [text] into [target] (an ML Kit language code). */
    suspend fun translate(
        text: String,
        target: String,
    ): String

    /** Starts downloading [target]'s language model in the background when it's missing. */
    fun prepare(target: String) = Unit

    /** Whether [target]'s language model is on the device, being downloaded, or waiting for Wi‑Fi. */
    suspend fun modelStatus(target: String): ModelStatus = ModelStatus.READY
}

interface Speaker {
    val isSpeaking: StateFlow<Boolean>

    /**
     * Speaks [text], stopping anything being said. [onDone] runs on the main thread when it
     * ends by itself (not when stopped): reading aloud goes on with the next sentence.
     */
    fun speak(
        text: String,
        rate: Float,
        onDone: (() -> Unit)? = null,
        onError: (() -> Unit)? = null,
    )

    fun stop()

    /** No English voice can be used (no engine, or its English data is missing). */
    val noVoice: Boolean get() = false

    /** True after a request found no English voice, until dismissed: the app says so (SPEC S6). */
    val missedVoice: StateFlow<Boolean> get() = NEVER_MISSED

    fun dismissMissedVoice() {}

    /** The voice engine's package, so its install-voice screen is opened directly; null if unknown. */
    val engine: String? get() = null
}

private val NEVER_MISSED: StateFlow<Boolean> = MutableStateFlow(false)

/** Everything the UI needs. The app builds the real one; tests pass fakes. */
class AppContainer(
    val library: Library,
    val dictionary: DictionaryClient,
    val gutenberg: GutenbergClient,
    val translator: Translator,
    val speaker: Speaker,
    val bundledStories: () -> List<BundledStory>,
    /** The system's preferred languages as BCP 47 tags, most preferred first. */
    val preferredLanguages: () -> List<String> = ::systemLanguages,
    /** The phone's region (ISO 3166, e.g. "BR"), used to suggest a translation language. */
    val region: () -> String? = ::systemRegion,
    val io: CoroutineDispatcher = Dispatchers.IO,
    /** Loads a book cover; null loads none (UI tests, so they never depend on the network). */
    val covers: (suspend (String) -> ImageBitmap?)? = null,
) {
    /**
     * Outlives screens, for work that must finish after one closes (saving progress,
     * a translation that arrives after its word was saved).
     * A failed background job is logged instead of crashing the app.
     */
    val appScope =
        CoroutineScope(
            SupervisorJob() + io + CoroutineExceptionHandler { _, error -> Log.w("Lingleaf", "Background work failed", error) },
        )

    private val filling = AtomicBoolean(false)

    init {
        // Reads the irregular-verb table off the main thread, before the first tapped word. A
        // refused table leaves the regular rules working. Both outcomes are logged, so the
        // emulator tour can require the table in the shrunk build (release builds strip logs).
        appScope.launch {
            val forms = IrregularVerbs.shared.formToBase.size
            val error = IrregularVerbs.loadError
            if (error == null) {
                Log.i("Lingleaf", "Irregular-verb table loaded ($forms forms)")
            } else {
                Log.w("Lingleaf", "Irregular-verb table refused", error)
            }
        }
    }

    private val _downloads = MutableStateFlow<Map<Int, BookDownload>>(emptyMap())

    /**
     * Book downloads by Gutenberg id. They run in [appScope], so leaving Discover doesn't
     * cancel them; the screen only shows them (SPEC S9).
     */
    val downloads: StateFlow<Map<Int, BookDownload>> = _downloads.asStateFlow()

    /** Downloads [book] into the library; [unknownAuthor] names a book without one. */
    fun download(
        book: GutenbergBook,
        unknownAuthor: String,
    ) {
        if (_downloads.value[book.id] is BookDownload.Running) return
        val job =
            appScope.launch(start = CoroutineStart.LAZY) {
                try {
                    var shown = 0.0
                    val text =
                        gutenberg.downloadText(book) { fraction ->
                            // One update per percent, not one per 8 KB chunk.
                            if (fraction - shown >= 0.01 || fraction >= 1.0) {
                                shown = fraction
                                updateDownload(book.id) { (it as? BookDownload.Running)?.copy(fraction = fraction.toFloat()) }
                            }
                        }
                    library.addBook(
                        title = book.displayTitle,
                        author = book.authorName ?: unknownAuthor,
                        text = text,
                        level = GutenbergClient.levelFor(book.id),
                        gutenbergId = book.id,
                    )
                    updateDownload(book.id) { null }
                } catch (error: GutenbergException) {
                    updateDownload(book.id) { BookDownload.Failed(error.reason) }
                } catch (cancelled: CancellationException) {
                    updateDownload(book.id) { null }
                    throw cancelled
                }
            }
        updateDownload(book.id) { BookDownload.Running(0f, job) }
        job.start()
    }

    fun cancelDownload(id: Int) {
        (_downloads.value[id] as? BookDownload.Running)?.job?.cancel()
    }

    private fun updateDownload(
        id: Int,
        change: (BookDownload?) -> BookDownload?,
    ) {
        _downloads.update { downloads ->
            val next = change(downloads[id])
            if (next == null) downloads - id else downloads + (id to next)
        }
    }

    /** The language chosen in Settings, else the first suitable system language; null means ask (SPEC S14). */
    fun targetLanguage(settings: Settings): String? =
        settings.targetLanguage ?: TranslationLanguages.pick(preferredLanguages(), TRANSLATION_LANGUAGES)

    /** Translates [text] into [target], or null if that fails or takes longer than [TRANSLATION_TIMEOUT_MS]. */
    suspend fun translateOrNull(
        text: String,
        target: String,
    ): String? = withTimeoutOrNull(TRANSLATION_TIMEOUT_MS) { runCatching { translator.translate(text, target) }.getOrNull() }

    /**
     * Fills in, on the device, saved words with no translation or one in another
     * language (SPEC S12, S14). Stops at the first failure (e.g. no language model yet).
     */
    fun fillMissingTranslations(target: String) {
        if (!filling.compareAndSet(false, true)) return
        appScope.launch {
            try {
                for (word in library.wordsNeedingTranslation(target)) {
                    val meanings = word.meanings
                    if (word.isUnsure && meanings != null) {
                        // Saved unsure: each reading's meaning, as on the card (SPEC S3).
                        val translated = meanings.map { meaningOf(it.kind, it.base, target, first = true) }
                        if (translated.any { it == null }) break
                        library.setMeanings(word.key, translated.filterNotNull(), target)
                        continue
                    }
                    val sense = word.sense
                    val translation =
                        when (sense) {
                            null -> translateOrNull(word.word, target)
                            else -> meaningOf(sense, word.word, target, homonym = sense == Sense.HOMONYM)
                        } ?: break
                    library.setTranslation(word.key, translation, target)
                }
                // The saved sentences too, shown under the meaning in Review (SPEC S7).
                for (word in library.contextsNeedingTranslation(target)) {
                    val translation = translateOrNull(word.context, target) ?: break
                    library.setContextTranslation(word.key, translation, target)
                }
            } finally {
                filling.set(false)
            }
        }
    }

    /**
     * The meaning of a reading in [target] (SPEC S3): for a verb in Spanish the table's meanings
     * (the first group alone with [first]), else the translation of "to {base}" / "the {noun}"
     * without the article or infinitive marker the query added. Null if translation fails.
     */
    suspend fun meaningOf(
        sense: String,
        base: String,
        target: String,
        first: Boolean = false,
        homonym: Boolean = false,
    ): String? {
        val reading = Reading(if (sense == Sense.NOUN) Reading.Kind.NOUN else Reading.Kind.VERB, base, Reading.Step.CHOSEN)
        // The regular twin's sense ("lied": mentir) has the twin's meanings.
        Meanings.fromTable(reading, target, first, homonym || sense == Sense.HOMONYM)?.let { return it }
        val translated = translateOrNull(Meanings.query(reading), target) ?: return null
        return Meanings.clean(translated, noun = sense == Sense.NOUN, language = target)
    }

    companion object {
        private val STORIES =
            listOf(
                Triple("books/the-red-boat.txt", "The Red Boat", "A1"),
                Triple("books/a-day-at-the-market.txt", "A Day at the Market", "A2"),
                Triple("books/the-lighthouse-keeper.txt", "The Lighthouse Keeper", "B1"),
            )

        fun create(context: Context): AppContainer {
            val http = HttpsFetcher()
            return AppContainer(
                library = Library(FileStorage(context.filesDir)),
                dictionary = DictionaryClient(http),
                gutenberg = GutenbergClient(http),
                covers = CoverLoader(HttpsFetcher(maxBytes = MAX_COVER_BYTES))::load,
                translator = MlKitTranslator(context.applicationContext),
                speaker = AndroidSpeaker(context.applicationContext),
                bundledStories = {
                    STORIES.map { (asset, title, level) ->
                        BundledStory(title, level, context.assets.open(asset).bufferedReader().use { it.readText() })
                    }
                },
            )
        }
    }
}

/** Longest wait for one translation, including a language model download. */
const val TRANSLATION_TIMEOUT_MS = 20_000L

/** Languages the user can translate into, as ML Kit codes. */
val TRANSLATION_LANGUAGES =
    listOf("es", "pt", "fr", "it", "de", "nl", "pl", "uk", "ru", "tr", "ar", "hi", "id", "vi", "zh", "ja", "ko", "th")

/** The system's preferred-language list (not the app's own language, which is only English or Spanish). */
fun systemLanguages(): List<String> {
    val tags = Resources.getSystem().configuration.locales.toLanguageTags()
    return tags.split(',').filter { it.isNotBlank() }
}

fun systemRegion(): String? {
    val locales = Resources.getSystem().configuration.locales
    return if (locales.isEmpty()) null else locales[0].country.takeIf { it.isNotEmpty() }
}

/** A language's name in that language: "es" → "Español", "zh" → "中文". */
fun languageName(code: String): String {
    val locale = Locale.forLanguageTag(code)
    return locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
}

/**
 * On-device translation with Google ML Kit. Each language model (~30 MB) is
 * downloaded once, only over Wi‑Fi.
 */
class MlKitTranslator(
    context: Context,
) : Translator {
    private val clients = mutableMapOf<String, com.google.mlkit.nl.translate.Translator>()
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifiOnly = DownloadConditions.Builder().requireWifi().build()

    private fun mlKitLanguage(target: String) = TranslateLanguage.fromLanguageTag(target) ?: TranslateLanguage.SPANISH

    private fun client(target: String): com.google.mlkit.nl.translate.Translator {
        val language = mlKitLanguage(target)
        return synchronized(clients) {
            clients.getOrPut(language) {
                Translation.getClient(
                    TranslatorOptions
                        .Builder()
                        .setSourceLanguage(TranslateLanguage.ENGLISH)
                        .setTargetLanguage(language)
                        .build(),
                )
            }
        }
    }

    override suspend fun translate(
        text: String,
        target: String,
    ): String {
        val client = client(target)
        client.downloadModelIfNeeded(wifiOnly).await()
        return client.translate(text).await()
    }

    override fun prepare(target: String) {
        client(target).downloadModelIfNeeded(wifiOnly)
    }

    override suspend fun modelStatus(target: String): ModelStatus {
        val model = TranslateRemoteModel.Builder(mlKitLanguage(target)).build()
        val downloaded = runCatching { RemoteModelManager.getInstance().isModelDownloaded(model).await() }.getOrDefault(false)
        return when {
            downloaded -> ModelStatus.READY
            onWifi() -> ModelStatus.DOWNLOADING
            else -> ModelStatus.NEEDS_WIFI
        }
    }

    private fun onWifi(): Boolean =
        runCatching {
            val capabilities = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        }.getOrDefault(false)
}

private suspend fun <T> Task<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(it) }
        addOnFailureListener { continuation.resumeWithException(it) }
    }

/** English text-to-speech with the system engine. */
class AndroidSpeaker(
    private val context: Context,
) : Speaker,
    TextToSpeech.OnInitListener {
    private val speaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = speaking.asStateFlow()
    private val missed = MutableStateFlow(false)
    override val missedVoice: StateFlow<Boolean> = missed.asStateFlow()
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    // Losing the audio focus (a call, an alarm, another app playing) pauses: spoken content
    // pauses instead of being ducked, so nothing is read unheard.
    private val focusListener =
        AudioManager.OnAudioFocusChangeListener { change ->
            if (change < 0) {
                // The utterance playing when the focus was lost, not one started after.
                val id = "lingleaf-$utterance"
                main.post {
                    if (id == "lingleaf-$utterance") {
                        failed(id)
                        stop()
                    }
                }
            }
        }
    private val focusRequest =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(focusListener, main)
                .build()
        } else {
            null
        }

    // The engine's state; only touched on the main thread. With no engine, or no English in it,
    // a request fails at once, and the next one tries again (a voice may have been installed).
    private var ready = false
    private var initFailed = false
    private var languageMissing = false
    private var pending: (() -> Unit)? = null
    private var tts = TextToSpeech(context, this)

    // The utterance being spoken and what to run when it ends by itself; only touched on the main thread.
    private var utterance = 0
    private var onDone: (() -> Unit)? = null
    private var onError: (() -> Unit)? = null

    override val noVoice: Boolean get() = initFailed || languageMissing
    override val engine: String? get() = tts.defaultEngine?.takeIf { it.isNotEmpty() }

    init {
        listen()
    }

    private fun listen() {
        tts.setAudioAttributes(attributes)
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    speaking.value = true
                }

                override fun onDone(utteranceId: String?) {
                    speaking.value = false
                    main.post {
                        val done = onDone
                        if (utteranceId == "lingleaf-$utterance" && done != null) {
                            onDone = null
                            done()
                            // Nothing more to say (no next sentence was started): give the audio back.
                            if (utteranceId == "lingleaf-$utterance") abandonFocus()
                        } else if (utteranceId == "lingleaf-$utterance") {
                            abandonFocus()
                        }
                    }
                }

                @Deprecated("Required override for older Android versions")
                override fun onError(utteranceId: String?) {
                    speaking.value = false
                    // The engine failed: reading aloud stops instead of looking busy without sound.
                    main.post { failed(utteranceId) }
                }

                override fun onStop(
                    utteranceId: String?,
                    interrupted: Boolean,
                ) {
                    speaking.value = false
                    // Our own stops change the utterance first; a stop of the current one came from
                    // elsewhere, so reading aloud pauses.
                    main.post { failed(utteranceId) }
                }
            },
        )
    }

    /** If [utteranceId] is still the current one: gives the audio back and runs its error callback. */
    private fun failed(utteranceId: String?) {
        if (utteranceId != "lingleaf-$utterance") return
        val failed = onError
        onDone = null
        onError = null
        // Nothing is said any more: give the audio back, with or without a callback.
        abandonFocus()
        failed?.invoke()
    }

    override fun onInit(status: Int) {
        // Posted: with a synchronous failure this runs inside the constructor, before `tts` is set.
        main.post {
            initFailed = status != TextToSpeech.SUCCESS
            ready = !initFailed
            if (ready) checkLanguage()
            val waiting = pending
            pending = null
            waiting?.invoke()
        }
    }

    private fun checkLanguage() {
        val result = tts.setLanguage(Locale.US)
        languageMissing = result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun speak(
        text: String,
        rate: Float,
        onDone: (() -> Unit)?,
        onError: (() -> Unit)?,
    ) {
        if (initFailed) {
            // Try a new engine: one may have been installed or enabled since.
            initFailed = false
            ready = false
            tts.shutdown()
            pending = { speakNow(text, rate, onDone, onError) }
            tts = TextToSpeech(context, this)
            listen()
            return
        }
        if (!ready) {
            pending = { speakNow(text, rate, onDone, onError) }
            return
        }
        if (languageMissing) checkLanguage()
        speakNow(text, rate, onDone, onError)
    }

    private fun speakNow(
        text: String,
        rate: Float,
        onDone: (() -> Unit)?,
        onError: (() -> Unit)?,
    ) {
        if (noVoice) return refuse(onError)
        missed.value = false
        utterance++
        this.onDone = onDone
        this.onError = onError
        if (!requestFocus()) {
            val id = "lingleaf-$utterance"
            main.post { failed(id) }
            return
        }
        tts.setSpeechRate(rate)
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lingleaf-$utterance") != TextToSpeech.SUCCESS) {
            // Refused (the engine died or was unbound): no callback will come, so fail now.
            speaking.value = false
            val id = "lingleaf-$utterance"
            main.post { failed(id) }
        }
    }

    /** No English voice: say so (the app shows [missedVoice]) and fail the request. */
    private fun refuse(onError: (() -> Unit)?) {
        missed.value = true
        onError?.let { callback -> main.post { callback() } }
    }

    override fun dismissMissedVoice() {
        missed.value = false
    }

    private fun requestFocus(): Boolean {
        val granted =
            if (focusRequest != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audio.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }
        return granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /** Gives the audio focus back, so other apps return to full volume. */
    private fun abandonFocus() {
        if (focusRequest != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audio.abandonAudioFocusRequest(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audio.abandonAudioFocus(focusListener)
        }
    }

    override fun stop() {
        utterance++
        onDone = null
        onError = null
        pending = null
        tts.stop()
        speaking.value = false
        abandonFocus()
    }
}

/**
 * HTTPS-only fetcher. Plain HTTP is refused before any connection is made, and
 * responses larger than [maxBytes] are cut off with an error.
 */
class HttpsFetcher(
    private val maxBytes: Int = MAX_BYTES,
    private val allowedHosts: Set<String> = Hosts.ALLOWED,
) : HttpFetcher {
    override suspend fun get(url: String): HttpResponse = get(url, null)

    /**
     * Reads the body in buffered chunks, reporting progress when the length is known.
     * Only HTTPS to the allowed hosts; redirects are followed by hand, at most
     * [Hosts.MAX_REDIRECTS], and only to the allowed hosts (a refused one returns the 3xx).
     * No connection, a timeout or other failures become [NetworkException] kinds;
     * cancelling the coroutine stops the read between chunks.
     */
    override suspend fun get(
        url: String,
        progress: ((Double) -> Unit)?,
    ): HttpResponse =
        withContext(Dispatchers.IO) {
            if (!Hosts.isAllowed(url, allowedHosts)) throw NetworkException("Only HTTPS to the app's hosts is allowed")
            var connection = open(URL(url))
            try {
                var status = connection.responseCode
                var redirects = 0
                while (status in REDIRECTS && redirects < Hosts.MAX_REDIRECTS) {
                    val location = connection.getHeaderField("Location") ?: break
                    val next = runCatching { URL(connection.url, location) }.getOrNull()
                    if (next == null || !Hosts.isAllowed(next.toString(), allowedHosts)) break
                    connection.disconnect()
                    connection = open(next)
                    status = connection.responseCode
                    redirects++
                }
                val length = connection.contentLengthLong
                if (length > maxBytes) throw NetworkException("Response too large")
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body =
                    stream?.use {
                        it.readCapped(maxBytes) { read ->
                            ensureActive()
                            if (length > 0) progress?.invoke(read.toDouble() / length)
                        }
                    } ?: ByteArray(0)
                progress?.invoke(1.0)
                HttpResponse(status, body)
            } catch (error: UnknownHostException) {
                throw NetworkException(error.message ?: "No internet", NetworkException.Kind.OFFLINE)
            } catch (error: ConnectException) {
                throw NetworkException(error.message ?: "No internet", NetworkException.Kind.OFFLINE)
            } catch (error: SocketTimeoutException) {
                throw NetworkException(error.message ?: "Timed out", NetworkException.Kind.TIMED_OUT)
            } catch (error: IOException) {
                throw NetworkException(error.message ?: "Network error")
            } finally {
                connection.disconnect()
            }
        }

    private fun open(url: URL): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            // Followed by hand above, so every hop is checked against the allowed hosts.
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 30_000
            useCaches = false
            setRequestProperty("User-Agent", "Lingleaf/0.1 (+https://github.com/erobinsonc/lingleaf-android)")
        }

    private companion object {
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}

/** Largest download or imported file accepted. The longest recommended book (Middlemarch) is under 2 MB. */
const val MAX_BYTES = 10 * 1024 * 1024

/**
 * Reads at most [max] bytes; more than that is an error rather than a huge allocation.
 * [onChunk] gets the bytes read so far after each chunk.
 */
fun InputStream.readCapped(
    max: Int,
    onChunk: (Int) -> Unit = {},
): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        out.write(buffer, 0, read)
        if (out.size() > max) throw NetworkException("Too large")
        onChunk(out.size())
    }
    return out.toByteArray()
}

/** Files in the app's private storage. Writes go through a temp file so a crash never leaves half a file. */
class FileStorage(
    private val dir: File,
) : Storage {
    override fun read(name: String): String? = if (StorageNames.isSafe(name)) File(dir, name).takeIf { it.exists() }?.readText() else null

    override fun write(
        name: String,
        content: String,
    ) {
        if (!StorageNames.isSafe(name)) return
        val target = File(dir, name)
        val temp = File(dir, "$name.tmp")
        // rename(2) replaces the target atomically. If writing or renaming fails (a full disk, a
        // missing folder), keep the old file rather than half-write it or crash, as iOS does.
        val saved =
            try {
                temp.writeText(content)
                temp.renameTo(target)
            } catch (_: IOException) {
                false
            }
        if (!saved) {
            temp.delete()
            Log.w("Lingleaf", "Could not save $name")
        }
    }

    override fun delete(name: String) {
        if (StorageNames.isSafe(name)) File(dir, name).delete()
    }
}

/** A book download in progress (0…1, with its job) or why it failed. */
sealed interface BookDownload {
    data class Running(
        val fraction: Float,
        val job: Job,
    ) : BookDownload

    data class Failed(
        val reason: GutenbergException.Reason,
    ) : BookDownload
}

/** Largest cover image accepted; Gutenberg's medium covers are about 20–60 KB. */
const val MAX_COVER_BYTES = 2 * 1024 * 1024

/**
 * Book covers over HTTPS (through [HttpsFetcher], so HTTPS only and size-capped),
 * decoded as small thumbnails on the IO dispatcher and kept in memory for the session. Any failure
 * returns null and the drawn cover stays.
 */
class CoverLoader(
    private val http: HttpFetcher,
) {
    // Sized by bytes, not entries: a few dozen small thumbnails at most.
    private val cache =
        object : LruCache<String, ImageBitmap>(COVER_CACHE_BYTES) {
            override fun sizeOf(
                key: String,
                value: ImageBitmap,
            ) = value.width * value.height * 4
        }

    suspend fun load(url: String): ImageBitmap? {
        cache.get(url)?.let { return it }
        val image =
            withContext(Dispatchers.IO) {
                val response = runCatching { http.get(url) }.getOrNull()
                if (response == null || response.status != 200) return@withContext null
                decodeThumbnail(response.body)?.asImageBitmap()
            } ?: return null
        cache.put(url, image)
        return image
    }

    companion object {
        const val COVER_CACHE_BYTES = 8 * 1024 * 1024

        /**
         * Only JPEG, by its first bytes and by what the decoder reads. Reads the size first,
         * refuses huge images and decodes a downsampled copy, so a small file that claims enormous
         * dimensions can't exhaust memory. Null when it isn't a JPEG image.
         */
        fun decodeThumbnail(bytes: ByteArray): Bitmap? {
            if (!GutenbergClient.isJpeg(bytes)) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outMimeType != "image/jpeg") return null
            val sample = GutenbergClient.coverSample(bounds.outWidth, bounds.outHeight) ?: return null
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            return try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            } catch (_: OutOfMemoryError) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
