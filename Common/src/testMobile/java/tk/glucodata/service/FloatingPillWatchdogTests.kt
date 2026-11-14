package tk.glucodata.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.service.FloatingPillWatchdog.Action
import tk.glucodata.service.FloatingPillWatchdog.ResolveInputs

class FloatingPillWatchdogTests {
    private val minute = 60_000L
    private val t0 = 1_790_000_000_000L
    private val sensor = "G7-ABC123"

    /** A pill fully in step: the store, the readings, the live reading and the frame agree. */
    private fun FloatingPillWatchdog.checkInStep(
        storedNewest: Long = t0,
        loadedNewest: Long = t0,
        liveNow: Long = t0,
        liveResolved: Long = t0,
        handed: Long = 7L,
        drawn: Long = 7L,
    ): Action = check(
        storedNewest = storedNewest,
        loadedNewest = loadedNewest,
        inputsNow = ResolveInputs(sensor, liveNow),
        inputsResolved = ResolveInputs(sensor, liveResolved),
        handed = handed,
        drawn = drawn,
    )

    @Test
    fun aPillInStepIsLeftAlone() {
        val watchdog = FloatingPillWatchdog()
        assertEquals(Action.NONE, watchdog.checkInStep())
        assertEquals(Action.NONE, watchdog.checkInStep())
    }

    @Test
    fun aLaterStoredReadingThanTheFollowedOnesReloadsThemOncePerReading() {
        val watchdog = FloatingPillWatchdog()
        val later = t0 + 5 * minute
        assertEquals(Action.RELOAD, watchdog.checkInStep(storedNewest = later))
        // Reloaded and still not among them (a reading the display leaves out): not again.
        assertEquals(Action.NONE, watchdog.checkInStep(storedNewest = later))
        // The next reading gets its own reload.
        assertEquals(Action.RELOAD, watchdog.checkInStep(storedNewest = later + 5 * minute))
    }

    @Test
    fun theSameReadingStampedBySourcesAMinuteApartIsNotLater() {
        val watchdog = FloatingPillWatchdog()
        assertEquals(Action.NONE, watchdog.checkInStep(storedNewest = t0 + minute))
        assertEquals(Action.NONE, watchdog.checkInStep(storedNewest = t0 - 2 * minute))
    }

    @Test
    fun aLiveReadingThatChangedOrExpiredSinceTheResolutionResolvesAgain() {
        val watchdog = FloatingPillWatchdog()
        // A new live reading the refresh request missed.
        assertEquals(Action.RESOLVE, watchdog.checkInStep(liveNow = t0 + 5 * minute))
        // The resolved live reading expired: the current value falls back to history.
        assertEquals(Action.RESOLVE, watchdog.checkInStep(liveNow = 0L))
        // Resolved with it: in step again.
        assertEquals(Action.NONE, watchdog.checkInStep(liveNow = 0L, liveResolved = 0L))
    }

    @Test
    fun aChangeOfMainSensorResolvesAgain() {
        val watchdog = FloatingPillWatchdog()
        val action = watchdog.check(
            storedNewest = t0,
            loadedNewest = t0,
            inputsNow = ResolveInputs("G7-OTHER", t0),
            inputsResolved = ResolveInputs(sensor, t0),
            handed = 7L,
            drawn = 7L,
        )
        assertEquals(Action.RESOLVE, action)
    }

    @Test
    fun nothingIsResolvedAgainBeforeTheFirstResolution() {
        val watchdog = FloatingPillWatchdog()
        val action = watchdog.check(
            storedNewest = t0,
            loadedNewest = t0,
            inputsNow = ResolveInputs(sensor, t0),
            inputsResolved = null,
            handed = 0L,
            drawn = 0L,
        )
        assertEquals(Action.NONE, action)
    }

    @Test
    fun aReadingNotDrawnIsRedrawnThenGivenANewWindowThenLeft() {
        val watchdog = FloatingPillWatchdog()
        assertEquals(Action.REDRAW, watchdog.checkInStep(handed = 8L, drawn = 7L))
        assertEquals(Action.REATTACH, watchdog.checkInStep(handed = 8L, drawn = 7L))
        // Neither helped: no window churn on every check.
        assertEquals(Action.NONE, watchdog.checkInStep(handed = 8L, drawn = 7L))
        // The next reading is tried afresh.
        assertEquals(Action.REDRAW, watchdog.checkInStep(handed = 9L, drawn = 7L))
    }

    @Test
    fun aRedrawThatWorkedNeedsNoNewWindow() {
        val watchdog = FloatingPillWatchdog()
        assertEquals(Action.REDRAW, watchdog.checkInStep(handed = 8L, drawn = 7L))
        assertEquals(Action.NONE, watchdog.checkInStep(handed = 8L, drawn = 8L))
    }

    @Test
    fun theFirstLinkBehindIsRepairedFirst() {
        val watchdog = FloatingPillWatchdog()
        val later = t0 + 5 * minute
        // Everything behind: the readings first, since the rest follows from them.
        assertEquals(
            Action.RELOAD,
            watchdog.checkInStep(storedNewest = later, liveNow = later, handed = 8L, drawn = 7L),
        )
        assertEquals(
            Action.RESOLVE,
            watchdog.checkInStep(storedNewest = later, liveNow = later, handed = 8L, drawn = 7L),
        )
        assertEquals(
            Action.REDRAW,
            watchdog.checkInStep(storedNewest = later, liveNow = later, liveResolved = later, handed = 8L, drawn = 7L),
        )
    }

    @Test
    fun theStoreIsAheadOfLiveOnlyWithAnOlderLiveReading() {
        // The phone's own reading, then one from the watch's gap fill five minutes later.
        assertTrue(FloatingPillWatchdog.storeIsAheadOfLive(liveTime = t0, storedNewest = t0 + 5 * minute))
        // The same reading stored with its own stamp.
        assertFalse(FloatingPillWatchdog.storeIsAheadOfLive(liveTime = t0, storedNewest = t0 + 30_000L))
        // Live is the newest.
        assertFalse(FloatingPillWatchdog.storeIsAheadOfLive(liveTime = t0 + 5 * minute, storedNewest = t0))
        // No live reading: the current value already comes from history.
        assertFalse(FloatingPillWatchdog.storeIsAheadOfLive(liveTime = 0L, storedNewest = t0))
    }

    @Test
    fun laterReadingNeedsAReading() {
        assertFalse(FloatingPillWatchdog.isLaterReading(later = 0L, than = 0L))
        assertTrue(FloatingPillWatchdog.isLaterReading(later = t0, than = 0L))
        assertFalse(FloatingPillWatchdog.isLaterReading(later = t0, than = t0))
    }
}
