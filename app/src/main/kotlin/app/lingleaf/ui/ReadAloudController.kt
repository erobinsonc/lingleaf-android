package app.lingleaf.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.lingleaf.Speaker
import app.lingleaf.core.ReadAloud
import app.lingleaf.core.SentencePosition

/**
 * Reads a book aloud sentence by sentence (SPEC S6), with the sentence being read for the
 * reader to highlight. Pausing keeps the sentence, so reading goes on from it. Repeat loops
 * the sentence being read; Previous sentence then adds one more before it to the stretch.
 */
class ReadAloudController(
    private val speaker: Speaker,
    private val rate: () -> Float,
    /** Called with each sentence as it starts, to save the reading position. */
    private val onSentence: (SentencePosition) -> Unit,
) {
    var current by mutableStateOf<SentencePosition?>(null)
        private set
    var isReading by mutableStateOf(false)
        private set

    /** The repeated stretch, first and last sentence; null when not repeating. */
    var loop by mutableStateOf<Pair<SentencePosition, SentencePosition>?>(null)
        private set
    private var paragraphs = emptyList<String>()

    /** How many sentences are repeated: "Repeating 3 sentences"; counted when the stretch changes. */
    var loopCount by mutableStateOf(0)
        private set

    private fun changeLoop(stretch: Pair<SentencePosition, SentencePosition>?) {
        loop = stretch
        loopCount = stretch?.let { (first, last) -> ReadAloud.count(paragraphs, first, last) } ?: 0
    }

    fun read(
        paragraphs: List<String>,
        from: SentencePosition,
    ) {
        this.paragraphs = paragraphs
        changeLoop(null)
        isReading = true
        speak(from)
    }

    /** Goes on from the start of the paused sentence. */
    fun play() {
        val now = current ?: return
        isReading = true
        speak(now)
    }

    /**
     * Back to the start of the previous sentence and reads on. While repeating, it adds that
     * sentence to the repeated stretch instead and reads the stretch from its new start.
     */
    fun previous() {
        val now = current ?: return
        val repeated = loop
        val back = ReadAloud.previous(paragraphs, repeated?.first ?: now)
        if (repeated != null) {
            if (back == null) return
            changeLoop(back to repeated.second)
        }
        isReading = true
        speak(back ?: now)
    }

    /** Repeats the sentence being read until turned off; then reading goes on forward. */
    fun toggleRepeat() {
        val now = current ?: return
        changeLoop(if (loop == null) now to now else null)
    }

    fun pause() {
        if (!isReading) return
        isReading = false
        speaker.stop()
    }

    private fun speak(position: SentencePosition) {
        current = position
        onSentence(position)
        val text = paragraphs[position.paragraph].substring(position.range.start, position.range.end)
        // The speed setting applies from the next sentence on.
        speaker.speak(text, rate(), onDone = { if (isReading) advance(position) }, onError = ::pause)
    }

    private fun advance(after: SentencePosition) {
        val repeated = loop
        if (repeated != null && after == repeated.second) return speak(repeated.first)
        val next = ReadAloud.next(paragraphs, after)
        if (next != null) {
            speak(next)
        } else {
            // The end of the book.
            isReading = false
            current = null
        }
    }
}
