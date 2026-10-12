package app.lingleaf.core

import java.time.LocalDate
import java.time.LocalDateTime

data class SrsResult(val box: Int, val nextReview: LocalDateTime, val learned: Boolean)

/** Minimal Leitner spaced repetition. */
object Srs {
    /** Days until the next review for each box. */
    val intervals = listOf(1L, 3L, 7L, 14L, 30L)

    /**
     * Review days start at 04:00 local time, so a word saved late at night still gets a
     * night's sleep before its first review.
     */
    const val DAY_START_HOUR = 4L

    /** Max words in one review session. */
    const val SESSION_LIMIT = 20

    /**
     * "Got it" moves the word to the next box, due at the start of the day `intervals[box]`
     * days ahead (reviews land after a night's sleep, at any hour of that day); after the
     * last box it is learned. "Again" sends it back to the first box, due now (repeated in the session).
     */
    fun review(
        box: Int,
        correct: Boolean,
        now: LocalDateTime,
    ): SrsResult =
        when {
            !correct -> SrsResult(box = 0, nextReview = now, learned = false)
            box >= intervals.size -> SrsResult(box = box, nextReview = now, learned = true)
            else -> SrsResult(box = box + 1, nextReview = startOfDay(intervals[box], now), learned = false)
        }

    /**
     * The start (04:00) of the review day [days] after [date]'s review day; a review day
     * runs from 04:00 to 04:00, so 01:00 still belongs to the day before.
     */
    fun startOfDay(
        days: Long,
        date: LocalDateTime,
    ): LocalDateTime =
        date
            .minusHours(DAY_START_HOUR)
            .toLocalDate()
            .plusDays(days)
            .atStartOfDay()
            .plusHours(DAY_START_HOUR)

    /**
     * Up to [limit] words, interleaved by source (book) so one book doesn't fill the
     * session; within a book, the longest-due first.
     */
    fun session(
        due: List<VocabWord>,
        limit: Int = SESSION_LIMIT,
    ): List<VocabWord> {
        val bySource = due.sortedBy { it.nextReview }.groupBy { it.source }.values.toList()
        val out = mutableListOf<VocabWord>()
        var round = 0
        while (out.size < limit && bySource.any { it.size > round }) {
            for (words in bySource) {
                if (round < words.size && out.size < limit) out += words[round]
            }
            round++
        }
        return out
    }
}

fun dayKey(date: LocalDate): String = date.toString()

/**
 * Consecutive days meeting the goal. Today only counts once it's met, so an
 * unfinished today doesn't break yesterday's streak.
 */
fun readingStreak(
    secondsByDay: Map<String, Int>,
    goalSeconds: Int,
    today: LocalDate,
): Int {
    fun met(day: LocalDate) = (secondsByDay[dayKey(day)] ?: 0) >= goalSeconds
    var day = if (met(today)) today else today.minusDays(1)
    var count = 0
    while (met(day)) {
        count++
        day = day.minusDays(1)
    }
    return count
}
