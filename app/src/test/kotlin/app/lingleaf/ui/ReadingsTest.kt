package app.lingleaf.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReadingsTest {
    /** Each " · " group is isolated on its own: a right-to-left one can't reorder the others. */
    @Test
    fun eachMeaningGroupIsIsolated() {
        val line = readingLine("to lie", "mentir · كذب", "ar").text
        val groups = line.substringAfter(" — ").split(" · ")
        assertEquals(2, groups.size)
        // The Arabic group is wrapped in its own direction marks; the Spanish one keeps its text.
        assertEquals("mentir", groups[0].filter { it.isLetter() })
        assertEquals("كذب", groups[1].filter { it.isLetter() })
        assertEquals(true, groups[1].first() != 'ك' || groups[1].last() != 'ب')
    }
}
