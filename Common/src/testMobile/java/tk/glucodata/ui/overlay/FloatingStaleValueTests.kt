package tk.glucodata.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.ui.overlay.FloatingNextReading.FIVE_MINUTES_MS
import tk.glucodata.ui.overlay.FloatingNextReading.ONE_MINUTE_MS
import tk.glucodata.ui.overlay.FloatingStaleValue.DIMMED_ALPHA

class FloatingStaleValueTests {
    private val minute = ONE_MINUTE_MS
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
    fun aDimmedValueAlwaysHasALateBar() {
        // The pill hands both the same stale flag: whatever the sensor and the time,
        // a dimmed value sits beside a full, amber bar.
        for (interval in listOf(minute, 3 * minute, FIVE_MINUTES_MS)) {
            for (elapsed in listOf(0L, minute, 6 * minute, 60 * minute)) {
                val stale = true
                assertTrue(FloatingStaleValue.alpha(hasValue = true, stale = stale) < 1f)
                val bar = FloatingNextReading.state(t0, interval, t0 + elapsed, stale)
                assertEquals(FloatingNextReading.State(1f, late = true), bar)
            }
        }
    }
}
