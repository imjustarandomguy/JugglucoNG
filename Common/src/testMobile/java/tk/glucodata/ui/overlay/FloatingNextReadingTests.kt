package tk.glucodata.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.ui.overlay.FloatingNextReading.STEP_MS

/**
 * The ring around the pill's arrow: it fills from the reading to the time the hub expects
 * the next one, and turns late only when the hub says the reading is stale.
 */
class FloatingNextReadingTests {
    private val second = 1_000L
    private val minute = 60_000L
    private val t0 = 1_790_000_000_000L
    private val due = t0 + 5 * minute

    // Progress and lateness

    @Test
    fun noRingWithoutAReading() {
        assertNull(FloatingNextReading.state(0L, due, t0, late = false))
        assertNull(FloatingNextReading.state(0L, due, t0, late = true))
        assertNull(FloatingNextReading.state(t0, t0, t0, late = false))
    }

    @Test
    fun theRingFillsFromTheReadingToTheNextOne() {
        val atReading = FloatingNextReading.state(t0, due, t0, late = false)!!
        assertEquals(0f, atReading.progress, 0f)
        assertFalse(atReading.late)

        val halfway = FloatingNextReading.state(t0, due, t0 + 150 * second, late = false)!!
        assertEquals(0.5f, halfway.progress, 0.0001f)
        assertFalse(halfway.late)

        val atDue = FloatingNextReading.state(t0, due, due, late = false)!!
        assertEquals(1f, atDue.progress, 0f)
        assertFalse(atDue.late)
    }

    @Test
    fun aMinuteSensorsRingFillsInItsMinute() {
        val halfway = FloatingNextReading.state(t0, t0 + minute, t0 + 30 * second, late = false)!!
        assertEquals(0.5f, halfway.progress, 0.0001f)
    }

    @Test
    fun pastItsDueTimeTheRingStaysFullUntilTheHubSaysLate() {
        // The ring has no deadline of its own: however late, only the hub's stale flag turns it.
        for (after in listOf(1L, 19 * second, 10 * minute, 2 * 60 * minute)) {
            assertEquals(FloatingNextReading.State(1f, late = false), FloatingNextReading.state(t0, due, due + after, late = false))
        }
    }

    @Test
    fun aStaleReadingsRingIsLateWhateverTheTime() {
        assertEquals(FloatingNextReading.State(1f, late = true), FloatingNextReading.state(t0, due, t0 + 10 * second, late = true))
        assertEquals(FloatingNextReading.State(1f, late = true), FloatingNextReading.state(t0, due, due + minute, late = true))
    }

    @Test
    fun aReadingStampedAheadOfTheClockStartsEmpty() {
        val ahead = FloatingNextReading.state(t0, due, t0 - 4 * second, late = false)!!
        assertEquals(0f, ahead.progress, 0f)
        assertFalse(ahead.late)
    }

    // Steps

    @Test
    fun theRingMovesInStepsCountedFromTheReading() {
        assertEquals(STEP_MS, FloatingNextReading.nextStepDelay(t0, due, t0))
        assertEquals(3 * second, FloatingNextReading.nextStepDelay(t0, due, t0 + 2 * second))
        assertEquals(STEP_MS, FloatingNextReading.nextStepDelay(t0, due, t0 + STEP_MS))
        // Stamped ahead of the clock: the first step is the reading's own time.
        assertEquals(3 * second, FloatingNextReading.nextStepDelay(t0, due, t0 - 3 * second))
    }

    @Test
    fun theLastStepIsWhenTheNextReadingIsDueAndThenNothingRuns() {
        val minuteDue = t0 + minute
        assertEquals(2 * second, FloatingNextReading.nextStepDelay(t0, minuteDue + 3 * second, minuteDue + second))
        assertNull(FloatingNextReading.nextStepDelay(t0, minuteDue, minuteDue))
        assertNull(FloatingNextReading.nextStepDelay(t0, minuteDue, minuteDue + 60 * minute))
        assertNull(FloatingNextReading.nextStepDelay(0L, minuteDue, t0))
        assertNull(FloatingNextReading.nextStepDelay(t0, t0, t0))
    }

    @Test
    fun aWholeIntervalTakesAtMostOneRedrawEveryStep() {
        var now = t0
        var redraws = 0
        while (true) {
            val wait = FloatingNextReading.nextStepDelay(t0, due, now) ?: break
            assertTrue(wait in 1..STEP_MS)
            now += wait
            redraws++
        }
        assertEquals(5 * minute / STEP_MS, redraws.toLong())
        assertEquals(due, now)
        assertEquals(FloatingNextReading.State(1f, late = false), FloatingNextReading.state(t0, due, now, late = false))
    }

    // Drawing

    @Test
    fun theRingsSlotGrowsWithTheArrowAndTheRingClearsIt() {
        for (arrow in listOf(10f, 12f, 12.6f, 20f, 34f, 42f)) {
            val slot = FloatingNextReading.ringSlot(arrow)
            val stroke = FloatingNextReading.ringStroke(arrow)
            // The ring's inside edge is outside the circle the arrow's box holds...
            assertTrue(slot / 2f - stroke > arrow / 2f)
            // ...and the slot only as much larger as that needs.
            assertTrue(slot < 1.5f * arrow)
        }
        assertTrue(FloatingNextReading.ringSlot(20f) < FloatingNextReading.ringSlot(30f))
        assertEquals(1.5f, FloatingNextReading.ringStroke(10f), 0.001f)
        assertEquals(4.2f, FloatingNextReading.ringStroke(42f), 0.001f)
    }
}
