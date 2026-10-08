package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveGlucoseTextTests {

    @Test
    fun arrowFollowsTheDrawnArrowsAngle() {
        // TrendArrowAngle: flat within 0.5 mg/dL/min, 45 degrees per mg/dL/min,
        // vertical from 2, doubled beyond 2.
        assertEquals("→", LiveGlucoseText.trendArrow(0f))
        assertEquals("→", LiveGlucoseText.trendArrow(0.6f))
        assertEquals("→", LiveGlucoseText.trendArrow(-0.7f))
        assertEquals("↗", LiveGlucoseText.trendArrow(0.75f))
        assertEquals("↗", LiveGlucoseText.trendArrow(1.4f))
        assertEquals("↘", LiveGlucoseText.trendArrow(-1f))
        assertEquals("↑", LiveGlucoseText.trendArrow(1.5f))
        assertEquals("↓", LiveGlucoseText.trendArrow(-2f))
        assertEquals("↑↑", LiveGlucoseText.trendArrow(2.5f))
        assertEquals("↓↓", LiveGlucoseText.trendArrow(-3f))
    }

    @Test
    fun unknownRateHasNoArrow() {
        assertEquals("", LiveGlucoseText.trendArrow(Float.NaN))
        assertEquals("5.8", LiveGlucoseText.title("5.8", Float.NaN))
        assertEquals("5.8", LiveGlucoseText.chip("5.8", Float.POSITIVE_INFINITY))
    }

    @Test
    fun titleIsValueAndArrowInEitherUnit() {
        assertEquals("5.8 ↗", LiveGlucoseText.title("5.8", 1f))
        assertEquals("104 →", LiveGlucoseText.title(" 104 ", 0.2f))
        assertEquals("12,4 ↓", LiveGlucoseText.title("12,4", -1.8f))
        assertEquals(LiveGlucoseText.NO_VALUE, LiveGlucoseText.title("", 1f))
        assertEquals(LiveGlucoseText.NO_VALUE, LiveGlucoseText.title(null, 1f))
    }

    @Test
    fun chipKeepsTheArrowOnlyWhenItFitsInFull() {
        assertEquals("5.8 ↗", LiveGlucoseText.chip("5.8", 1f))
        assertEquals("250 ↑↑", LiveGlucoseText.chip("250", 3f))
        assertEquals("12.4 ↑↑", LiveGlucoseText.chip("12.4", 3f))
        assertEquals(7, LiveGlucoseText.chip("12.4", 3f).length)
        // Raw and calibrated together do not fit with an arrow.
        assertEquals("12.4/13", LiveGlucoseText.chip("12.4/13", 1f))
        assertEquals(LiveGlucoseText.NO_VALUE, LiveGlucoseText.chip(" ", 1f))
        for (value in listOf("2.2", "33.3", "40", "500"))
            for (rate in listOf(-3f, -1f, 0f, 1f, 3f))
                assertTrue(LiveGlucoseText.chip(value, rate).length <= LiveGlucoseText.CHIP_MAX_LENGTH)
    }

    @Test
    fun detailIsChangeAndClockTime() {
        assertEquals("+0.3 · 10:32", LiveGlucoseText.detail("+0.3", "10:32"))
        assertEquals("−4 · 10:32 PM", LiveGlucoseText.detail("−4", "10:32 PM"))
        // Without a change (too few readings) the time stands alone.
        assertEquals("10:32", LiveGlucoseText.detail("", "10:32"))
        assertEquals("+0.3", LiveGlucoseText.detail("+0.3", null))
        assertEquals("", LiveGlucoseText.detail(null, ""))
    }

    @Test
    fun staleShowsTheLastValueOrNone() {
        assertEquals("5.8", LiveGlucoseText.lastValue("5.8"))
        assertEquals(LiveGlucoseText.NO_VALUE, LiveGlucoseText.lastValue(""))
    }

    @Test
    fun freshTimeoutEndsJustAfterTheReadingAges() {
        val freshness = 330_000L // Notify.glucosetimeout
        val grace = LiveGlucoseText.TIMEOUT_GRACE_MS
        val reading = 1_700_000_000_000L
        // Posted as it arrives: the reading's whole freshness plus the grace.
        assertEquals(freshness + grace, LiveGlucoseText.freshTimeoutMs(reading, reading, freshness))
        // Posted two minutes late: two minutes less.
        assertEquals(freshness + grace - 120_000L,
            LiveGlucoseText.freshTimeoutMs(reading, reading + 120_000L, freshness))
        // Never shorter than the floor, never longer than a whole freshness (clock ahead).
        assertEquals(LiveGlucoseText.MIN_TIMEOUT_MS,
            LiveGlucoseText.freshTimeoutMs(reading, reading + freshness + grace, freshness))
        assertEquals(freshness + grace,
            LiveGlucoseText.freshTimeoutMs(reading + 600_000L, reading, freshness))
    }
}
