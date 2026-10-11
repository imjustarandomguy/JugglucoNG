package tk.glucodata.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.DisplayedReading
import tk.glucodata.ReadingHub

/**
 * A details card left open follows the pill: each new reading replaces the one it was
 * opened for, it goes stale in the hub's publish that dims the pill, and its age moves on
 * its own clock, which ticks once a minute of age and only while the card is open.
 */
class FloatingDetailsCardStateTests {
    private val start = 1_790_000_000_000L
    private val minute = 60_000L

    /** The hub's reading of a value of [value] taken at [at], as published at [now]. */
    private fun published(at: Long, now: Long, value: Float = 6.1f): DisplayedReading = ReadingHub.compose(
        ReadingHub.Inputs(
            sensorId = "S1",
            reading = ReadingHub.Reading("S1", at, value, "$value", null, null, true, 0, 0.2f, 0),
            recentTimes = emptyList(),
            declaredIntervalMillis = 5 * minute,
        ),
        now,
    )

    private fun state(reading: DisplayedReading, now: Long) =
        FloatingDetailsCardState.of(reading, isMmol = true, viewMode = 0, nowMillis = now)

    /** The card clock's ticks from [from] to [until], as the service runs it while the card is open. */
    private fun ticksWhileOpen(readingTime: Long, from: Long, until: Long): List<Long> {
        val ticks = mutableListOf(from)
        var now = from
        while (now < until) {
            now += FloatingDetailsCardState.nextAgeTickDelay(readingTime, now)
            ticks += now
        }
        return ticks
    }

    @Test
    fun aNewReadingWhileOpenReplacesTheOneItWasOpenedFor() {
        val first = state(published(start, start + minute, 6.1f), start + minute)!!
        val later = start + 5 * minute + 10_000L
        val second = state(published(start + 5 * minute, later, 6.8f), later)!!

        assertEquals(start + 5 * minute, second.readingTime)
        assertEquals(6.8f, second.request.displayGlucose, 0f)
        assertEquals(0L, second.ageMinutes)
        assertFalse(second.stale)
        // A new request: the chart, the Δ and the IOB/COB line are loaded again for it.
        assertNotEquals(first.request, second.request)
    }

    @Test
    fun theClockAloneAgesTheCardWithoutReloadingIt() {
        val shown = published(start, start)
        val opened = state(shown, start + 30_000L)!!
        val later = state(shown, start + 4 * minute + 30_000L)!!

        assertEquals(0L, opened.ageMinutes)
        assertEquals(4L, later.ageMinutes)
        assertEquals(opened.request, later.request)
    }

    @Test
    fun theCardGoesStaleWithTheHubsPublishAndDoesNotReloadForIt() {
        val fresh = published(start, start + 5 * minute + 19_999L)
        val stale = published(start, start + 5 * minute + 20_000L)
        assertEquals(DisplayedReading.Freshness.STALE, stale.freshness)

        assertFalse(state(fresh, fresh.staleAtMillis - 1L)!!.stale)
        val card = state(stale, stale.staleAtMillis)!!
        assertTrue(card.stale)
        assertEquals(FloatingPillLook.of(stale).stale, card.stale)
        assertEquals(state(fresh, start)!!.request, card.request)
        assertEquals(FloatingStaleValue.DIMMED_ALPHA, FloatingStaleValue.alpha(hasValue = true, stale = card.stale), 0f)
    }

    @Test
    fun theCardsClockTicksOnEachMinuteOfTheAge() {
        assertEquals(minute, FloatingDetailsCardState.nextAgeTickDelay(start, start))
        assertEquals(30_000L, FloatingDetailsCardState.nextAgeTickDelay(start, start + 30_000L))
        assertEquals(minute, FloatingDetailsCardState.nextAgeTickDelay(start, start + 3 * minute))
        // Stamped ahead of the clock: the first tick is at its own time.
        assertEquals(5_000L, FloatingDetailsCardState.nextAgeTickDelay(start, start - 5_000L))

        val shown = published(start, start)
        val ticks = ticksWhileOpen(start, start + 30_000L, start + 40 * minute)
        // One tick per minute of age, each showing the next minute.
        assertEquals(41, ticks.size)
        assertEquals((0L..40L).toList(), ticks.map { state(shown, it)!!.ageMinutes })
    }

    @Test
    fun noReadingNoCard() {
        assertNull(state(DisplayedReading.NONE, start))
        assertNotNull(state(published(start, start), start))
    }

    @Test
    fun aValueTakenDownStillShowsWhenTheLastReadingWas() {
        val gone = published(start, start + 30 * minute)
        assertEquals(DisplayedReading.Freshness.NO_VALUE, gone.freshness)
        val card = state(gone, start + 30 * minute)!!
        assertEquals(start, card.readingTime)
        assertEquals(30L, card.ageMinutes)
        assertTrue(card.stale)
    }

    @Test
    fun anAgeIsNeverNegative() {
        // The clock can be read just before a reading stamped a moment later arrives.
        assertEquals(0L, state(published(start, start), start - 5_000L)!!.ageMinutes)
    }
}
