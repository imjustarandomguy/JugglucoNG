package tk.glucodata.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.DisplayedReading
import tk.glucodata.ReadingHub

class FloatingPillPresenceTests {
    @Test
    fun offScreenUntilTheScreenIsOnAndTheReadingIsHanded() {
        val presence = FloatingPillPresence()
        assertFalse(presence.shown)

        assertTrue(presence.onScreen(true))
        assertFalse(presence.shown)

        presence.onReadingHanded(1L)
        assertTrue(presence.shown)
    }

    @Test
    fun screenOffTakesThePillOffAndItsReadingIsHandedAgain() {
        val presence = FloatingPillPresence()
        presence.onScreen(true)
        presence.onReadingHanded(1L)

        assertFalse(presence.onScreen(false))
        assertFalse(presence.shown)

        // Back on: what was handed before the screen went off is not current.
        assertTrue(presence.onScreen(true))
        assertFalse(presence.shown)
        presence.onReadingHanded(1L)
        assertTrue(presence.shown)
    }

    @Test
    fun aReadingHandedWhileTheScreenIsOffIsIgnored() {
        val presence = FloatingPillPresence()
        presence.onReadingHanded(1L)
        assertFalse(presence.shown)

        presence.onScreen(true)
        presence.onReadingHanded(1L)
        presence.onScreen(false)
        presence.onReadingHanded(2L)
        assertTrue(presence.onScreen(true))
        assertFalse(presence.shown)
    }

    @Test
    fun repeatedScreenStatesDoNotReloadOrHide() {
        val presence = FloatingPillPresence()
        presence.onScreen(true)
        presence.onReadingHanded(1L)

        assertFalse(presence.onScreen(true))
        assertTrue(presence.shown)

        presence.onScreen(false)
        assertFalse(presence.onScreen(false))
        assertFalse(presence.shown)
    }

    /** A pill that was on screen with [revision] composed, then the screen off. */
    private fun composedThenScreenOff(revision: Long) = FloatingPillPresence().apply {
        onScreen(true)
        onReadingHanded(revision)
        onReadingComposed(revision)
        onScreen(false)
    }

    @Test
    fun aReadingHandedAtScreenOnIsComposedBeforeTheWindowGoesIn() {
        // The device case: a reading arrived with the screen off, so the reading handed
        // at screen on is a revision the composition has not seen.
        val presence = composedThenScreenOff(7L)
        presence.onScreen(true)
        presence.onReadingHanded(8L)
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
        presence.onReadingHanded(7L)
        assertTrue(presence.shown)
    }

    @Test
    fun aPillThatNeverComposedGoesOnScreenOnceHanded() {
        // Its first composition is made as its first window goes in, from the handed reading.
        val presence = FloatingPillPresence()
        presence.onScreen(true)
        presence.onReadingHanded(1L)
        assertTrue(presence.shown)
        assertNull(presence.composedRevision)
    }

    @Test
    fun theNewestHandedReadingIsTheOneAwaited() {
        val presence = composedThenScreenOff(7L)
        presence.onScreen(true)
        presence.onReadingHanded(8L)
        presence.onReadingHanded(9L)

        presence.onReadingComposed(8L)
        assertFalse(presence.shown)
        presence.onReadingComposed(9L)
        assertTrue(presence.shown)
    }

    @Test
    fun aCompositionAheadOfTheHandedReadingCounts() {
        val presence = composedThenScreenOff(7L)
        presence.onScreen(true)
        presence.onReadingHanded(8L)
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
        presence.onReadingHanded(8L)
        assertTrue(presence.shown)
    }

    @Test
    fun onceOnScreenALaterReadingDoesNotTakeThePillOff() {
        val presence = composedThenScreenOff(7L)
        presence.onScreen(true)
        presence.onReadingHanded(7L)
        assertTrue(presence.shown)

        presence.onReadingHanded(8L)
        assertTrue(presence.shown)
        assertFalse(presence.awaitingComposition)
    }

    @Test
    fun aCompositionThatNeverComesLeavesThePillToTheTimeout() {
        val presence = composedThenScreenOff(7L)

        // With the screen off, and before the reading is handed, the timeout does nothing.
        presence.onComposeTimedOut()
        assertFalse(presence.shown)
        presence.onScreen(true)
        presence.onComposeTimedOut()
        assertFalse(presence.shown)

        presence.onReadingHanded(8L)
        presence.onComposeTimedOut()
        assertTrue(presence.shown)
        assertEquals(7L, presence.composedRevision)
    }

    @Test
    fun theScreenGoingOffWhileAwaitingEndsTheWait() {
        val presence = composedThenScreenOff(7L)
        presence.onScreen(true)
        presence.onReadingHanded(8L)
        presence.onScreen(false)
        assertFalse(presence.awaitingComposition)

        presence.onReadingComposed(8L)
        assertFalse(presence.shown)
    }

    // The hub's reading at screen on

    private val minute = 60_000L
    private val t0 = 1_790_000_000_000L

    private fun published(now: Long, revision: Long = 3L): DisplayedReading = ReadingHub.compose(
        ReadingHub.Inputs(
            sensorId = "S1",
            reading = ReadingHub.Reading("S1", t0, 123f, "123", null, null, false, 0, 1.2f, 0),
            recentTimes = emptyList(),
            declaredIntervalMillis = 5 * minute,
        ),
        now,
    ).copy(revision = revision)

    @Test
    fun aReadingTheHubKeepsUpWithIsHandedAtOnce() {
        val fresh = published(t0 + minute)
        assertFalse(FloatingPillPresence.waitsForHub(fresh, t0 + 2 * minute))
        assertFalse(FloatingPillPresence.waitsForHub(fresh, fresh.staleAtMillis - 1L))
        val stale = published(fresh.staleAtMillis)
        assertFalse(FloatingPillPresence.waitsForHub(stale, t0 + 29 * minute))
        val gone = published(t0 + 30 * minute)
        assertFalse(FloatingPillPresence.waitsForHub(gone, t0 + 600 * minute))
    }

    @Test
    fun aDeadlineTheHubSleptThroughWaitsForItsRepublish() {
        // Published fresh, then the phone slept past the stale moment and past 30 minutes.
        val fresh = published(t0 + minute)
        assertTrue(FloatingPillPresence.waitsForHub(fresh, fresh.staleAtMillis))
        val stale = published(fresh.staleAtMillis)
        assertTrue(FloatingPillPresence.waitsForHub(stale, stale.noValueAtMillis))
    }

    @Test
    fun aHubThatHasNotPublishedYetIsWaitedFor() {
        assertTrue(FloatingPillPresence.waitsForHub(DisplayedReading.NONE, t0))
        assertFalse(FloatingPillPresence.waitsForHub(DisplayedReading.NONE.copy(revision = 1L), t0))
    }
}
