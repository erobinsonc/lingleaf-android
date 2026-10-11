package app.lingleaf.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// SPEC S4, S15: how long the Undo message stays.
class UndoTimeoutTest {
    @Test
    fun withoutAssistiveServicesTheBaseTimeIsUsed() {
        assertEquals(4_000L, undoTimeoutMillis(4_000L, touchExploration = false, switchControl = false) { it })
    }

    @Test
    fun theSystemTimeToActMakesItLonger() {
        assertEquals(20_000L, undoTimeoutMillis(5_000L, touchExploration = false, switchControl = false) { 20_000L })
    }

    @Test
    fun itIsNeverShorterThanTheBaseTime() {
        assertEquals(4_000L, undoTimeoutMillis(4_000L, touchExploration = false, switchControl = false) { 1_000L })
    }

    @Test
    fun withAScreenReaderItStaysUntilDismissed() {
        assertNull(undoTimeoutMillis(4_000L, touchExploration = true, switchControl = false) { it })
    }

    @Test
    fun withSwitchControlItStaysUntilDismissed() {
        assertNull(undoTimeoutMillis(4_000L, touchExploration = false, switchControl = true) { it })
    }

    @Test
    fun withSwitchControlItStaysEvenWhenTheSystemAsksForATime() {
        assertNull(undoTimeoutMillis(5_000L, touchExploration = false, switchControl = true) { 20_000L })
    }

    @Test
    fun withAScreenReaderAndSwitchControlItStaysUntilDismissed() {
        assertNull(undoTimeoutMillis(4_000L, touchExploration = true, switchControl = true) { it })
    }

    @Test
    fun aServiceThatAsksForNoTimeoutKeepsItUntilDismissed() {
        assertNull(undoTimeoutMillis(5_000L, touchExploration = false, switchControl = false) { Long.MAX_VALUE })
    }
}
