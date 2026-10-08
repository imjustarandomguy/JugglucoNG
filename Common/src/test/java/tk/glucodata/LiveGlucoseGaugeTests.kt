package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.GlucoseRangeColors.Band

class LiveGlucoseGaugeTests {

    private fun bar(
        value: Float,
        mmol: Boolean,
        chartLow: Float,
        chartHigh: Float,
        targetLow: Float = Float.NaN,
        targetHigh: Float = Float.NaN,
        veryLow: Float = Float.NaN,
        veryHigh: Float = Float.NaN,
    ): LiveGlucoseGauge.Bar {
        val bar = LiveGlucoseGauge.bar(value, mmol, chartLow, chartHigh, targetLow, targetHigh, veryLow, veryHigh)
        assertNotNull(bar)
        return bar!!
    }

    private fun LiveGlucoseGauge.Bar.bands() = segments.map { it.band }
    private fun LiveGlucoseGauge.Bar.starts() = segments.map { it.start }
    private fun LiveGlucoseGauge.Bar.lengths() = segments.map { it.length }

    @Test
    fun unitsAreTenthsOfMmolOrMgdl() {
        assertEquals(58, LiveGlucoseGauge.units(5.8f, true))
        assertEquals(39, LiveGlucoseGauge.units(3.9f, true))
        assertEquals(139, LiveGlucoseGauge.units(13.9f, true))
        assertEquals(104, LiveGlucoseGauge.units(104f, false))
        assertEquals(104, LiveGlucoseGauge.units(104.4f, false))
    }

    @Test
    fun barSpansTheChartRangeInMmol() {
        // 2.0-22.0 mmol/L in tenths; 5.8 sits 3.8 from the start.
        val bar = bar(5.8f, true, 2.0f, 22.0f, 3.9f, 10.0f, 3.0f, 13.9f)
        assertEquals(200, bar.max)
        assertEquals(38, bar.progress)
        assertEquals(Band.IN_RANGE, bar.band)
        assertEquals(Band.values().toList(), bar.bands())
        assertEquals(listOf(10, 9, 61, 39, 81), bar.lengths())
        assertEquals(listOf(0, 10, 19, 80, 119), bar.starts())
    }

    @Test
    fun barSpansTheChartRangeInMgdl() {
        val bar = bar(104f, false, 40f, 400f, 70f, 180f, 54f, 250f)
        assertEquals(360, bar.max)
        assertEquals(64, bar.progress)
        assertEquals(Band.IN_RANGE, bar.band)
        assertEquals(Band.values().toList(), bar.bands())
        assertEquals(listOf(14, 16, 110, 70, 150), bar.lengths())
        assertEquals(listOf(0, 14, 30, 140, 210), bar.starts())
    }

    @Test
    fun unsetThresholdsTakeTheDefaults() {
        assertEquals(listOf(10, 9, 61, 39, 81), bar(5.8f, true, 2.0f, 22.0f).lengths())
        assertEquals(listOf(10, 9, 61, 39, 81), bar(5.8f, true, 2.0f, 22.0f, 0f, 0f, 0f, 0f).lengths())
        assertEquals(listOf(14, 16, 110, 70, 150), bar(104f, false, 40f, 400f).lengths())
    }

    @Test
    fun unusableChartRangeFallsBackToAFixedSpan() {
        for (span in listOf(Float.NaN to 22f, 2f to Float.NaN, 10f to 10f, 12f to 4f, -1f to 22f, 0f to 0f)) {
            val bar = bar(5.8f, true, span.first, span.second)
            assertEquals(200, bar.max)
            assertEquals(38, bar.progress)
        }
        val mgdl = bar(104f, false, 0f, 0f)
        assertEquals(360, mgdl.max)
        assertEquals(64, mgdl.progress)
        // A chart starting at zero is a real range.
        assertEquals(300, bar(5.8f, true, 0f, 30f).max)
    }

    @Test
    fun valuesOutsideTheSpanSitAtItsEnds() {
        val low = bar(1.5f, true, 2.0f, 22.0f)
        assertEquals(0, low.progress)
        assertEquals(Band.VERY_LOW, low.band)
        val high = bar(25f, true, 2.0f, 22.0f)
        assertEquals(200, high.progress)
        assertEquals(Band.VERY_HIGH, high.band)
        assertEquals(360, bar(450f, false, 40f, 400f).progress)
        assertEquals(0, bar(39f, false, 40f, 400f).progress)
    }

    @Test
    fun rangesOutsideTheSpanAreLeftOut() {
        // 4.0-12.0: below the low target and above 12 there is nothing to show.
        val bar = bar(11f, true, 4.0f, 12.0f, 3.9f, 10.0f, 3.0f, 13.9f)
        assertEquals(80, bar.max)
        assertEquals(listOf(Band.IN_RANGE, Band.HIGH), bar.bands())
        assertEquals(listOf(0, 60), bar.starts())
        assertEquals(listOf(60, 20), bar.lengths())
        assertEquals(70, bar.progress)
        assertEquals(Band.HIGH, bar.band)
    }

    @Test
    fun valueRangeFollowsTheRangeColorsBounds() {
        // As GlucoseRangeColors.colorForValue: the very low and in-range bounds are
        // inclusive, the very high threshold starts very high.
        fun band(value: Float) = bar(value, true, 2.0f, 22.0f, 3.9f, 10.0f, 3.0f, 13.9f).band
        assertEquals(Band.VERY_LOW, band(3.0f))
        assertEquals(Band.LOW, band(3.1f))
        assertEquals(Band.IN_RANGE, band(3.9f))
        assertEquals(Band.IN_RANGE, band(10.0f))
        assertEquals(Band.HIGH, band(10.1f))
        assertEquals(Band.VERY_HIGH, band(13.9f))
    }

    @Test
    fun segmentsCoverTheBarWithoutGapsOrEmptyRanges() {
        val spans = listOf(2f to 22f, 0f to 30f, 3f to 12f, 4.5f to 9f, 11f to 25f, 2.2f to 2.5f)
        val thresholds = listOf(
            floatArrayOf(3.9f, 10f, 3f, 13.9f),
            floatArrayOf(4.4f, 7.8f, 3.5f, 10f),
            floatArrayOf(Float.NaN, Float.NaN, Float.NaN, Float.NaN),
            // Inconsistent ones are put in order as the range colours do.
            floatArrayOf(5f, 4f, 6f, 3f),
        )
        for ((low, high) in spans)
            for (t in thresholds) {
                val bar = bar(6f, true, low, high, t[0], t[1], t[2], t[3])
                var next = 0
                for (segment in bar.segments) {
                    assertEquals(next, segment.start)
                    assertTrue(segment.length >= 1)
                    next += segment.length
                }
                assertEquals(bar.max, next)
                assertEquals(bar.bands().sortedBy { it.ordinal }, bar.bands())
                assertTrue(bar.progress in 0..bar.max)
            }
    }

    @Test
    fun fractionIsTheShareOfTheBar() {
        val bar = bar(5.8f, true, 2.0f, 22.0f)
        assertEquals(listOf(0f, 0.05f, 0.095f, 0.4f, 0.595f), bar.segments.map { bar.fraction(it.start) })
        assertEquals(1f, bar.fraction(bar.max), 0f)
        assertEquals(1f, bar.fraction(bar.max + 5), 0f)
    }

    @Test
    fun noValueNoBar() {
        assertNull(LiveGlucoseGauge.bar(Float.NaN, true, 2f, 22f, 3.9f, 10f, 3f, 13.9f))
        assertNull(LiveGlucoseGauge.bar(0f, false, 40f, 400f, 70f, 180f, 54f, 250f))
        assertNull(LiveGlucoseGauge.bar(-5f, false, 40f, 400f, 70f, 180f, 54f, 250f))
    }
}
