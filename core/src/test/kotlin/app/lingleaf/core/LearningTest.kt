package app.lingleaf.core

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LearningTest {
    private val now = LocalDateTime.of(2026, 10, 9, 8, 0)

    @Test
    fun `correct answer moves to the next box`() {
        assertEquals(SrsResult(1, LocalDateTime.of(2026, 10, 10, 4, 0), false), Srs.review(0, true, now))
    }

    @Test
    fun `wrong answer resets`() {
        assertEquals(SrsResult(0, now, false), Srs.review(3, false, now))
    }

    @Test
    fun `last box marks the word learned`() {
        assertTrue(Srs.review(Srs.intervals.size, true, now).learned)
    }

    @Test
    fun `streak counts consecutive days`() {
        val log = mapOf("2026-10-07" to 600, "2026-10-08" to 900, "2026-10-09" to 600)
        assertEquals(3, readingStreak(log, 600, LocalDate.of(2026, 10, 9)))
    }

    @Test
    fun `unfinished today keeps yesterday's streak`() {
        val log = mapOf("2026-10-07" to 600, "2026-10-08" to 600, "2026-10-09" to 60)
        assertEquals(2, readingStreak(log, 600, LocalDate.of(2026, 10, 9)))
    }

    @Test
    fun `a gap breaks the streak and months roll over`() {
        assertEquals(1, readingStreak(mapOf("2026-10-06" to 600, "2026-10-08" to 600), 600, LocalDate.of(2026, 10, 9)))
        assertEquals(2, readingStreak(mapOf("2026-09-30" to 600, "2026-10-01" to 600), 600, LocalDate.of(2026, 10, 1)))
    }

    @Test
    fun `review days start at four in the morning`() {
        assertEquals(LocalDateTime.of(2026, 10, 10, 4, 0), Srs.startOfDay(1, LocalDateTime.of(2026, 10, 9, 23, 50)))
        assertEquals(
            LocalDateTime.of(2026, 10, 10, 4, 0),
            Srs.startOfDay(1, LocalDateTime.of(2026, 10, 10, 1, 0)),
            "01:00 still belongs to the day before",
        )
        assertEquals(LocalDateTime.of(2026, 10, 11, 4, 0), Srs.startOfDay(1, LocalDateTime.of(2026, 10, 10, 4, 0)))
    }
}
