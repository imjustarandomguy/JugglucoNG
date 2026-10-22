package tk.glucodata.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tk.glucodata.GlucosePoint

class WidgetDeltaTests {
    private val minute = 60_000L

    @Test
    fun pairsTheNewestWithTheFirstPointOldEnoughForTheInterval() {
        val points = listOf(
            GlucosePoint(0L, 100f, 0f),
            GlucosePoint(5 * minute, 106f, 0f),
            // A near-duplicate of the newest reading (live vs stored timestamp).
            GlucosePoint(10 * minute - 2_000L, 109f, 0f),
            GlucosePoint(10 * minute, 109f, 0f),
        )
        assertEquals("+3", WidgetDelta.text(points, rawMode = false, intervalMinutes = 5, isMmol = false))
    }

    @Test
    fun noDeltaWithoutAPointOldEnoughOrWithTooLongAGap() {
        assertNull(WidgetDelta.text(listOf(GlucosePoint(0L, 100f, 0f)), false, 5, false))
        val close = listOf(GlucosePoint(0L, 100f, 0f), GlucosePoint(minute, 101f, 0f))
        assertNull(WidgetDelta.text(close, false, 5, false))
        val gap = listOf(GlucosePoint(0L, 100f, 0f), GlucosePoint(30 * minute, 120f, 0f))
        assertNull(WidgetDelta.text(gap, false, 5, false))
    }

    @Test
    fun rawViewModesUseTheRawLane() {
        val points = listOf(GlucosePoint(0L, 100f, 90f), GlucosePoint(5 * minute, 100f, 95f))
        assertEquals("+5", WidgetDelta.text(points, rawMode = true, intervalMinutes = 5, isMmol = false))
        assertEquals("0", WidgetDelta.text(points, rawMode = false, intervalMinutes = 5, isMmol = false))
    }
}
