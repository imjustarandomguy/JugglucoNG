package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.ui.overlay.FloatingStaleValue
import tk.glucodata.ui.overlay.nextOverlayFreshnessCheckDelay
import tk.glucodata.ui.overlay.overlayReadingIsFresh

/**
 * The floating overlay only recomposes on new data, so its own clock is the only
 * thing that can mark a reading stale once the sensor goes quiet (#554). Every layout
 * (pill, side and top island) dims value and arrow by [overlayReadingIsFresh], so
 * these tests replay the overlay's clock against it: current while fresh, stale (and
 * dimmed) at the timeout, current again with the next reading. The composable itself
 * can't be rendered here: it calls into JNI (Natives) while composing.
 */
class FloatingOverlayFreshnessTests {
    private val start = 1_700_000_000_000L
    private val window = 330_000L

    private fun point(at: Long) = GlucosePoint(value = 6.1f, time = "", timestamp = at)

    /** Walk the overlay's clock from [from] until it stops; returns where it stopped. */
    private fun runClock(latestMillis: Long, from: Long): Long {
        var now = from
        while (true) {
            val wait = nextOverlayFreshnessCheckDelay(latestMillis, now, window) ?: return now
            now += wait
        }
    }

    private fun fresh(latest: GlucosePoint?, now: Long, snapshotMillis: Long = 0L) =
        overlayReadingIsFresh(latest, snapshotMillis, now, window)

    @Test
    fun aFreshReadingIsCurrent() {
        val reading = point(start)
        assertTrue(fresh(reading, start + 60_000L))
    }

    @Test
    fun theClockMarksTheReadingStaleJustPastTheTimeoutThenStops() {
        val reading = point(start)
        val stoppedAt = runClock(reading.timestamp, start + 10_000L)
        assertEquals(start + window + 1L, stoppedAt)
        assertTrue(fresh(reading, stoppedAt - 1L))
        assertFalse(fresh(reading, stoppedAt))
    }

    @Test
    fun aStaleReadingStaysShownDimmed() {
        // Not blanked to "---": the value stays, at the dimmed opacity.
        val reading = point(start)
        val stoppedAt = runClock(reading.timestamp, start)
        val stale = !fresh(reading, stoppedAt)
        assertTrue(stale)
        assertEquals(FloatingStaleValue.DIMMED_ALPHA, FloatingStaleValue.alpha(hasValue = true, stale = stale), 0f)
    }

    @Test
    fun aNewReadingAfterStalenessIsCurrentAgain() {
        val old = point(start)
        val stoppedAt = runClock(old.timestamp, start)
        assertFalse(fresh(old, stoppedAt))

        val next = point(stoppedAt + 120_000L)
        assertTrue(fresh(next, next.timestamp))
        assertEquals(1f, FloatingStaleValue.alpha(hasValue = true, stale = !fresh(next, next.timestamp)), 0f)
        assertEquals(15_000L, nextOverlayFreshnessCheckDelay(next.timestamp, next.timestamp, window))
    }

    @Test
    fun aFresherCurrentSnapshotKeepsAnOlderHistoryTailCurrent() {
        val tail = point(start)
        val now = start + window + 60_000L
        assertFalse(fresh(tail, now))
        assertTrue(fresh(tail, now, snapshotMillis = start + 120_000L))
    }

    @Test
    fun noReadingMeansNoDataAndNoClock() {
        assertFalse(fresh(null, start))
        assertNull(nextOverlayFreshnessCheckDelay(0L, start, window))
    }

    @Test
    fun aReadingAlreadyStaleOnStartDoesNotStartTheClock() {
        val reading = point(start - window - 60_000L)
        assertFalse(fresh(reading, start))
        assertNull(nextOverlayFreshnessCheckDelay(reading.timestamp, start, window))
    }
}
