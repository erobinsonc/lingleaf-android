package app.lingleaf.core

import java.time.LocalDate
import java.time.LocalDateTime

data class SrsResult(val box: Int, val nextReview: LocalDateTime, val learned: Boolean)

/** Minimal Leitner spaced repetition. */
object Srs {
    /** Days until the next review for each box. */
    val intervals = listOf(1L, 3L, 7L, 14L, 30L)

    fun review(
        box: Int,
        correct: Boolean,
        now: LocalDateTime,
    ): SrsResult =
        when {
            !correct -> SrsResult(box = 0, nextReview = now, learned = false)
            box >= intervals.size -> SrsResult(box = box, nextReview = now, learned = true)
            else -> SrsResult(box = box + 1, nextReview = now.plusDays(intervals[box]), learned = false)
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
