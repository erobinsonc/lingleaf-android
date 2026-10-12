package app.lingleaf

import android.content.Context
import android.content.res.Resources
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import app.lingleaf.core.BundledStory
import app.lingleaf.core.DictionaryClient
import app.lingleaf.core.GutenbergClient
import app.lingleaf.core.HttpFetcher
import app.lingleaf.core.HttpResponse
import app.lingleaf.core.Library
import app.lingleaf.core.NetworkException
import app.lingleaf.core.Settings
import app.lingleaf.core.Storage
import app.lingleaf.core.TranslationLanguages
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    fun speak(
        text: String,
        rate: Float,
    )

    fun stop()
}

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
                    val translation = translateOrNull(word.word, target) ?: break
                    library.setTranslation(word.word, translation, target)
                }
            } finally {
                filling.set(false)
            }
        }
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
    context: Context,
) : Speaker,
    TextToSpeech.OnInitListener {
    private val speaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = speaking.asStateFlow()
    private var ready = false
    private var pending: Pair<String, Float>? = null
    private val tts = TextToSpeech(context, this)

    init {
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    speaking.value = true
                }

                override fun onDone(utteranceId: String?) {
                    speaking.value = false
                }

                @Deprecated("Required override for older Android versions")
                override fun onError(utteranceId: String?) {
                    speaking.value = false
                }

                override fun onStop(
                    utteranceId: String?,
                    interrupted: Boolean,
                ) {
                    speaking.value = false
                }
            },
        )
    }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.US
            pending?.let { (text, rate) -> speak(text, rate) }
        }
        pending = null
    }

    override fun speak(
        text: String,
        rate: Float,
    ) {
        if (!ready) {
            pending = text to rate
            return
        }
        tts.setSpeechRate(rate)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lingleaf")
    }

    override fun stop() {
        tts.stop()
        speaking.value = false
    }
}

/**
 * HTTPS-only fetcher. Plain HTTP is refused before any connection is made, and
 * responses larger than [maxBytes] are cut off with an error.
 */
class HttpsFetcher(
    private val maxBytes: Int = MAX_BYTES,
) : HttpFetcher {
    override suspend fun get(url: String): HttpResponse = get(url, null)

    /**
     * Reads the body in buffered chunks, reporting progress when the length is known.
     * No connection, a timeout or other failures become [NetworkException] kinds;
     * cancelling the coroutine stops the read between chunks.
     */
    override suspend fun get(
        url: String,
        progress: ((Double) -> Unit)?,
    ): HttpResponse =
        withContext(Dispatchers.IO) {
            if (!url.startsWith("https://")) throw NetworkException("Only HTTPS is allowed")
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "Lingleaf/0.1 (+https://github.com/erobinsonc/lingleaf-android)")
            try {
                val status = connection.responseCode
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
    override fun read(name: String): String? = File(dir, name).takeIf { it.exists() }?.readText()

    override fun write(
        name: String,
        content: String,
    ) {
        val target = File(dir, name)
        val temp = File(dir, "$name.tmp")
        temp.writeText(content)
        // rename(2) replaces the target atomically; if it fails, keep the old file rather than half-write it.
        if (!temp.renameTo(target)) {
            temp.delete()
            Log.w("Lingleaf", "Could not save $name")
        }
    }

    override fun delete(name: String) {
        File(dir, name).delete()
    }
}
