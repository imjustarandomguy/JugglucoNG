package tk.glucodata.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.ui.DisplayValues
import tk.glucodata.ui.GlucosePoint

/**
 * A details card left open follows the pill: each new reading replaces the one it was
 * opened for, and its age and stale state move with the freshness clock, which keeps
 * ticking while the card is open (nextOverlayFreshnessCheckDelay with ageShown).
 */
class FloatingDetailsCardStateTests {
    private val start = 1_790_000_000_000L
    private val minute = 60_000L
    private val window = 330_000L

    private fun point(at: Long, value: Float = 6.1f) = GlucosePoint(value = value, time = "", timestamp = at)

    private fun snapshot(at: Long, value: Float) = CurrentDisplaySource.Snapshot(
        timeMillis = at,
        rate = 0f,
        sensorId = "S1",
        sensorGen = 0,
        index = 0,
        viewMode = 0,
        source = "live",
        autoValue = value,
        rawValue = 0f,
        sharedDisplayValue = value,
        sharedMgdl = 0,
        isMmol = true,
        displayValues = DisplayValues(primaryValue = value, primaryStr = "$value", fullFormatted = "$value"),
    )

    private fun reading(point: GlucosePoint?, snapshot: CurrentDisplaySource.Snapshot? = null, revision: Long = 1L) =
        FloatingPillReading(point = point, snapshot = snapshot, sensorId = "S1", revision = revision)

    private fun state(reading: FloatingPillReading, now: Long) =
        FloatingDetailsCardState.of(reading, isMmol = true, viewMode = 0, nowMillis = now, freshnessWindowMillis = window)

    /** The clock's ticks from [from] to [until], as the service runs it with the card open. */
    private fun ticksWhileOpen(readingTime: Long, from: Long, until: Long): List<Long> {
        val ticks = mutableListOf(from)
        var now = from
        while (now < until) {
            now += nextOverlayFreshnessCheckDelay(readingTime, now, window, ageShown = true) ?: break
            ticks += now
        }
        return ticks
    }

    @Test
    fun aNewReadingWhileOpenReplacesTheOneItWasOpenedFor() {
        val first = state(reading(point(start, 6.1f), revision = 1L), start + minute)!!
        val second = state(reading(point(start + 5 * minute, 6.8f), revision = 2L), start + 5 * minute + 10_000L)!!

        assertEquals(start + 5 * minute, second.readingTime)
        assertEquals(6.8f, second.request.displayGlucose, 0f)
        assertEquals(0L, second.ageMinutes)
        assertFalse(second.stale)
        // A new request: the chart, the Δ and the IOB/COB line are loaded again for it.
        assertNotEquals(first.request, second.request)
    }

    @Test
    fun theClockAloneAgesTheCardWithoutReloadingIt() {
        val shown = reading(point(start))
        val opened = state(shown, start + 30_000L)!!
        val later = state(shown, start + 4 * minute + 30_000L)!!

        assertEquals(0L, opened.ageMinutes)
        assertEquals(4L, later.ageMinutes)
        assertEquals(opened.request, later.request)
    }

    @Test
    fun aReadingThatGoesStaleWhileOpenIsShownStaleAsOnThePill() {
        val shown = reading(point(start))
        val ticks = ticksWhileOpen(start, start + minute, start + 40 * minute)

        val beforeTimeout = ticks.last { it - start <= window }
        val afterTimeout = ticks.first { it - start > window }
        assertFalse(state(shown, beforeTimeout)!!.stale)
        val stale = state(shown, afterTimeout)!!
        assertTrue(stale.stale)
        assertEquals(!overlayReadingIsFresh(shown.point, 0L, afterTimeout, window), stale.stale)
        assertEquals(FloatingStaleValue.DIMMED_ALPHA, FloatingStaleValue.alpha(hasValue = true, stale = stale.stale), 0f)
    }

    @Test
    fun theAgeOfAStaleReadingGoesOnWhileTheCardIsOpen() {
        val shown = reading(point(start))
        val ticks = ticksWhileOpen(start, start + minute, start + 40 * minute)

        // Never more than the clock's cap between two ticks, past the timeout too.
        assertTrue(ticks.zipWithNext().all { (a, b) -> b - a in 1L..15_000L })
        assertTrue(ticks.last() >= start + 40 * minute)
        assertEquals(40L, state(shown, ticks.last())!!.ageMinutes)
    }

    @Test
    fun closedTheClockStopsAtTheTimeoutAsBefore() {
        assertNull(nextOverlayFreshnessCheckDelay(start, start + window + 1L, window, ageShown = false))
        assertEquals(15_000L, nextOverlayFreshnessCheckDelay(start, start + window + 1L, window, ageShown = true))
    }

    @Test
    fun theCardCountsFromTheValueThePillShows() {
        // The live value can be newer than the stored reading: time, age and freshness
        // are the pill's, from the newer one.
        val stored = point(start, 6.1f)
        val live = snapshot(start + 2 * minute, 6.4f)
        val now = start + window + minute
        val card = state(reading(stored, live), now)!!

        assertEquals(start + 2 * minute, card.readingTime)
        assertEquals(4L, card.ageMinutes)
        assertFalse(card.stale)
        assertEquals(6.4f, card.request.displayGlucose, 0f)
        assertEquals(stored, card.request.point)
    }

    @Test
    fun noReadingNoCard() {
        assertNull(state(reading(point = null), start))
        assertNotNull(state(reading(point(start)), start))
    }

    @Test
    fun anAgeIsNeverNegative() {
        // The clock can be read just before a reading stamped a moment later arrives.
        assertEquals(0L, state(reading(point(start)), start - 5_000L)!!.ageMinutes)
    }
}
