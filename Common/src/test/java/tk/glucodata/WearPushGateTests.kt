package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The push after a reading went at most once per 45 s and dropped the rest, and
 * it read history written asynchronously, so it could leave out the reading it
 * followed. [WearPushGate] lets every newer reading through at once and hands
 * the push the reading itself.
 */
class WearPushGateTests {
    private val t0 = 1_791_000_000_000L
    private val g7 = "dx01ab"

    @Test
    fun aNewerReadingIsPushedAtOnceEvenRightAfterAnotherPush() {
        val gate = WearPushGate(45_000L)
        assertTrue(gate.shouldPush(g7, t0, t0 + 2_000L))
        // A G7 re-ask answer, or a second sensor's reading, 10 s later.
        assertTrue(gate.shouldPush(g7, t0 + 5_000L, t0 + 12_000L))
        assertTrue(gate.shouldPush("libre", t0 - 60_000L, t0 + 13_000L))
    }

    @Test
    fun aRepeatForNoNewerReadingWaitsOutTheInterval() {
        val gate = WearPushGate(45_000L)
        assertTrue(gate.shouldPush(g7, t0, t0 + 2_000L))
        assertFalse(gate.shouldPush(g7, t0, t0 + 20_000L))
        assertFalse(gate.shouldPush(g7, t0 - 300_000L, t0 + 30_000L))
        assertTrue(gate.shouldPush(g7, t0, t0 + 47_000L))
    }

    @Test
    fun anUnknownReadingTimeIsARepeat() {
        val gate = WearPushGate(45_000L)
        assertTrue(gate.shouldPush(g7, t0, t0 + 2_000L))
        assertFalse(gate.shouldPush(g7, 0L, t0 + 3_000L))
    }

    @Test
    fun theReadingHistoryHasNotGotYetIsAddedAtTheEnd() {
        val stored = listOf(GlucosePoint(t0 - 300_000L, 120f, 118f))
        val points = WearPushGate.withReading(stored, TakenReading(t0, 126f, 124f), t0 - 480_000L)
        assertEquals(listOf(t0 - 300_000L, t0), points.map { it.timestamp })
        assertEquals(126f, points.last().value)
        assertEquals(124f, points.last().rawValue)
    }

    @Test
    fun aReadingHistoryHoldsIsNotSentTwice() {
        // Stored under native's own second, up to a second off the callback's.
        val stored = listOf(GlucosePoint(t0 - 300_000L, 120f), GlucosePoint(t0 + 600L, 126f))
        assertSame(stored, WearPushGate.withReading(stored, TakenReading(t0, 126f, 0f), t0 - 480_000L))
    }

    @Test
    fun onlyAPlausibleReadingInsideTheServedRangeIsAdded() {
        val stored = listOf(GlucosePoint(t0 - 300_000L, 120f))
        assertSame(stored, WearPushGate.withReading(stored, null, t0 - 480_000L))
        assertSame(stored, WearPushGate.withReading(stored, TakenReading(t0 - 600_000L, 110f, 0f), t0 - 480_000L))
        // A raw-only warmup value carries no auto lane, which the receiver needs.
        assertSame(stored, WearPushGate.withReading(stored, TakenReading(t0, 0f, 140f), t0 - 480_000L))
        val noRaw = WearPushGate.withReading(stored, TakenReading(t0, 126f, Float.NaN), t0 - 480_000L)
        assertEquals(0f, noRaw.last().rawValue)
    }

    @Test
    fun anOlderReadingKeepsTheOrder() {
        val stored = listOf(GlucosePoint(t0 - 600_000L, 110f), GlucosePoint(t0, 126f))
        val points = WearPushGate.withReading(stored, TakenReading(t0 - 300_000L, 120f, 0f), t0 - 900_000L)
        assertEquals(listOf(t0 - 600_000L, t0 - 300_000L, t0), points.map { it.timestamp })
    }
}
