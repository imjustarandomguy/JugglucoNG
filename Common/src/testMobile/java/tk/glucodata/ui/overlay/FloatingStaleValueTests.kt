package tk.glucodata.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.DisplayedReading
import tk.glucodata.ReadingHub
import tk.glucodata.ui.overlay.FloatingStaleValue.DIMMED_ALPHA

class FloatingStaleValueTests {
    private val minute = 60_000L
    private val t0 = 1_790_000_000_000L

    @Test
    fun aCurrentValueIsDrawnAtFullOpacity() {
        assertEquals(1f, FloatingStaleValue.alpha(hasValue = true, stale = false), 0f)
    }

    @Test
    fun aStaleValueStaysDimmedClearlyOldButReadable() {
        val alpha = FloatingStaleValue.alpha(hasValue = true, stale = true)
        assertEquals(DIMMED_ALPHA, alpha, 0f)
        assertTrue("dimmed to about 40-50 %: $alpha", alpha in 0.4f..0.5f)
    }

    @Test
    fun withoutAValueNothingIsDimmed() {
        // The pill shows "---" then, in its normal colour.
        assertEquals(1f, FloatingStaleValue.alpha(hasValue = false, stale = true), 0f)
        assertEquals(1f, FloatingStaleValue.alpha(hasValue = false, stale = false), 0f)
    }

    @Test
    fun aDimmedValueAlwaysHasALateRingAndNoArrow() {
        // The pill takes both from the hub's one stale flag: whatever the sensor and the
        // time, a dimmed value sits without its arrow beside a full, amber ring.
        for (interval in listOf(minute, 3 * minute, 5 * minute)) {
            for (elapsed in listOf(interval + 20_000L, interval + minute, 20 * minute, 29 * minute)) {
                val inputs = ReadingHub.Inputs(
                    sensorId = "S1",
                    reading = ReadingHub.Reading("S1", t0, 123f, "123", null, null, false, 0, 1.2f, 0),
                    recentTimes = emptyList(),
                    declaredIntervalMillis = interval,
                )
                val reading = ReadingHub.compose(inputs, t0 + elapsed)
                assertEquals(DisplayedReading.Freshness.STALE, reading.freshness)
                val look = FloatingPillLook.of(reading)
                assertTrue(look.valueAlpha < 1f)
                assertTrue(look.arrowRate.isNaN())
                val ring = FloatingNextReading.state(reading.timeMillis, reading.nextExpectedAtMillis, t0 + elapsed, look.stale)
                assertEquals(FloatingNextReading.State(1f, late = true), ring)
            }
        }
    }
}
