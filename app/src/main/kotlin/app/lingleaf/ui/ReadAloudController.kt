package app.lingleaf.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.lingleaf.Speaker
import app.lingleaf.core.ReadAloud
import app.lingleaf.core.SentencePosition

/**
 * Reads a book aloud sentence by sentence (SPEC S6), with the sentence being read for the
 * reader to highlight. Pausing keeps the sentence, so reading goes on from it.
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
    private var paragraphs = emptyList<String>()

    fun read(
        paragraphs: List<String>,
        from: SentencePosition,
    ) {
        this.paragraphs = paragraphs
        isReading = true
        speak(from)
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
        speaker.speak(text, rate()) { if (isReading) advance(position) }
    }

    private fun advance(after: SentencePosition) {
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
