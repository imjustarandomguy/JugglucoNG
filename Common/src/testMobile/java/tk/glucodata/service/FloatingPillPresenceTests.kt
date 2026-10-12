package tk.glucodata.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingPillPresenceTests {
    @Test
    fun offScreenUntilTheScreenIsOnAndTheReadingsAreLoaded() {
        val presence = FloatingPillPresence()
        assertFalse(presence.shown)

        assertTrue(presence.onScreen(true))
        assertFalse(presence.shown)

        presence.onReadingsLoaded(1L)
        assertTrue(presence.shown)
    }

    @Test
    fun screenOffTakesThePillOffAndItsReadingsCountAsStale() {
        val presence = FloatingPillPresence()
        presence.onScreen(true)
        presence.onReadingsLoaded(1L)

        assertFalse(presence.onScreen(false))
        assertFalse(presence.shown)

        // Back on: what was loaded before the screen went off is not current.
        assertTrue(presence.onScreen(true))
        assertFalse(presence.shown)
        presence.onReadingsLoaded(1L)
        assertTrue(presence.shown)
    }

    @Test
    fun readingsLoadedWhileTheScreenIsOffAreIgnored() {
        val presence = FloatingPillPresence()
        presence.onReadingsLoaded(1L)
        assertFalse(presence.shown)

        presence.onScreen(true)
        presence.onReadingsLoaded(1L)
        presence.onScreen(false)
        presence.onReadingsLoaded(2L)
        assertTrue(presence.onScreen(true))
        assertFalse(presence.shown)
    }

    @Test
    fun repeatedScreenStatesDoNotReloadOrHide() {
        val presence = FloatingPillPresence()
        presence.onScreen(true)
        presence.onReadingsLoaded(1L)

        assertFalse(presence.onScreen(true))
        assertTrue(presence.shown)

        presence.onScreen(false)
        assertFalse(presence.onScreen(false))
        assertFalse(presence.shown)
    }

    /** A pill that was on screen with [revision] composed, then the screen off. */
    private fun composedThenScreenOff(revision: Long) = FloatingPillPresence().apply {
        onScreen(true)
        onReadingsLoaded(revision)
        onReadingComposed(revision)
        onScreen(false)
    }

    @Test
    fun aReadingHandedAtScreenOnIsComposedBeforeTheWindowGoesIn() {
        // The device case: a reading arrived with the screen off, so the reading resolved
        // at screen on is a revision the composition has not seen.
        val presence = composedThenScreenOff(7L)
        presence.onScreen(true)
        presence.onReadingsLoaded(8L)
        assertFalse(presence.shown)
        assertTrue(presence.awaitingComposition)

        // Other state recomposed, still with the old reading: not yet.
        presence.onReadingComposed(7L)
        assertFalse(presence.shown)

        presence.onReadingComposed(8L)
        assertTrue(presence.shown)
        assertFalse(presence.awaitingComposition)
        assertEquals(8L, presence.composedRevision)
    }

    @Test
    fun anUnchangedReadingGoesBackOnScreenAtOnce() {
        val presence = composedThenScreenOff(7L)
        presence.onScreen(true)
        presence.onReadingsLoaded(7L)
        assertTrue(presence.shown)
    }

    @Test
    fun aPillThatNeverComposedGoesOnScreenOnceLoaded() {
        // Its first composition is made as its first window goes in, from the handed reading.
        val presence = FloatingPillPresence()
        presence.onScreen(true)
        presence.onReadingsLoaded(1L)
        assertTrue(presence.shown)
        assertNull(presence.composedRevision)
    }

    @Test
    fun theNewestHandedReadingIsTheOneAwaited() {
        val presence = composedThenScreenOff(7L)
        presence.onScreen(true)
        presence.onReadingsLoaded(8L)
        presence.onReadingsLoaded(9L)

        presence.onReadingComposed(8L)
        assertFalse(presence.shown)
        presence.onReadingComposed(9L)
        assertTrue(presence.shown)
    }

    @Test
    fun aCompositionAheadOfTheHandedReadingCounts() {
        val presence = composedThenScreenOff(7L)
        presence.onScreen(true)
        presence.onReadingsLoaded(8L)
        presence.onReadingComposed(9L)
        assertTrue(presence.shown)
    }

    @Test
    fun aCompositionWhileTheScreenIsOffIsKeptButShowsNothing() {
        val presence = composedThenScreenOff(7L)
        presence.onReadingComposed(8L)
        assertFalse(presence.shown)
        assertFalse(presence.awaitingComposition)

        presence.onScreen(true)
        assertFalse(presence.shown)
        presence.onReadingsLoaded(8L)
        assertTrue(presence.shown)
    }

    @Test
    fun onceOnScreenALaterReadingDoesNotTakeThePillOff() {
        val presence = composedThenScreenOff(7L)
        presence.onScreen(true)
        presence.onReadingsLoaded(7L)
        assertTrue(presence.shown)

        presence.onReadingsLoaded(8L)
        assertTrue(presence.shown)
        assertFalse(presence.awaitingComposition)
    }

    @Test
    fun aCompositionThatNeverComesLeavesThePillToTheTimeout() {
        val presence = composedThenScreenOff(7L)

        // With the screen off, and before the readings are in, the timeout does nothing.
        presence.onComposeTimedOut()
        assertFalse(presence.shown)
        presence.onScreen(true)
        presence.onComposeTimedOut()
        assertFalse(presence.shown)

        presence.onReadingsLoaded(8L)
        presence.onComposeTimedOut()
        assertTrue(presence.shown)
        assertEquals(7L, presence.composedRevision)
    }

    @Test
    fun theScreenGoingOffWhileAwaitingEndsTheWait() {
        val presence = composedThenScreenOff(7L)
        presence.onScreen(true)
        presence.onReadingsLoaded(8L)
        presence.onScreen(false)
        assertFalse(presence.awaitingComposition)

        presence.onReadingComposed(8L)
        assertFalse(presence.shown)
    }
}
