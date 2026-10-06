package tk.glucodata

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import tk.glucodata.GlucoseRangeColors.Band
import tk.glucodata.GlucoseRangeColors.Palette

/**
 * "Colour values by range": the value takes the colour the range settings show
 * for its band. It used to take a separate three-tier palette, so a low came
 * out yellow beside the light-red "Low" the user had set.
 */
class GlucoseTrafficColorTests {

    private val fallback = 0x11223344

    @Before
    @After
    fun reset() {
        GlucoseRangeColors.setChangeListener(null)
        GlucoseRangeColors.setPalette(Palette.MUTED)
        GlucoseRangeColors.clearOverrides()
    }

    private fun color(
        value: Float,
        targetLow: Float = 80f,
        targetHigh: Float = 160f,
        alarmLow: Float = 70f,
        alarmHigh: Float = 250f,
        dark: Boolean = true,
        isMmol: Boolean = false
    ) = GlucoseRangeColors.trafficColorForValue(
        value, targetLow, targetHigh, alarmLow, alarmHigh, dark, isMmol, fallback
    )

    private fun mmolBand(value: Float) =
        GlucoseRangeColors.bandForValue(value, 3.9f, 10.0f, 3.0f, 13.9f, true)

    @Test
    fun eachRangeTakesItsBandColour() {
        assertEquals(GlucoseRangeColors.veryLow(true), color(55f))
        assertEquals(GlucoseRangeColors.low(true), color(75f))
        assertEquals(GlucoseRangeColors.inRange(true), color(110f))
        assertEquals(GlucoseRangeColors.high(true), color(200f))
        assertEquals(GlucoseRangeColors.veryHigh(true), color(320f))
        assertEquals(GlucoseRangeColors.inRange(false), color(110f, dark = false))
    }

    @Test
    fun boundariesBelongToTheExpectedBand() {
        assertEquals(GlucoseRangeColors.inRange(true), color(80f))
        assertEquals(GlucoseRangeColors.inRange(true), color(160f))
        assertEquals(GlucoseRangeColors.veryLow(true), color(70f))
        assertEquals(GlucoseRangeColors.veryHigh(true), color(250f))
    }

    @Test
    fun aLowMmolReadingTakesTheLowColourNotYellow() {
        // The reported case: mmol/L, target 3.9-10.0, very low 3.0, MUTED on a
        // dark theme. 3.5 is low, so it is the light red "Low" swatch.
        val low = GlucoseRangeColors.trafficColorForValue(
            3.5f, 3.9f, 10f, 3.0f, 13.9f, true, true, fallback
        )
        assertEquals(0xFFC97970.toInt(), low)
        assertNotEquals(GlucoseRangeColors.VALUE_BORDERLINE_DARK, low)
        val inRange = GlucoseRangeColors.trafficColorForValue(
            5.6f, 3.9f, 10f, 3.0f, 13.9f, true, true, fallback
        )
        assertEquals(GlucoseRangeColors.inRange(true), inRange)
    }

    @Test
    fun mmolBoundaries() {
        assertEquals(Band.VERY_LOW, mmolBand(2.9f))
        assertEquals(Band.VERY_LOW, mmolBand(3.0f))
        assertEquals(Band.LOW, mmolBand(3.1f))
        assertEquals(Band.LOW, mmolBand(3.8f))
        assertEquals(Band.IN_RANGE, mmolBand(3.9f))
        assertEquals(Band.IN_RANGE, mmolBand(10.0f))
        assertEquals(Band.HIGH, mmolBand(10.1f))
        assertEquals(Band.HIGH, mmolBand(13.8f))
        assertEquals(Band.VERY_HIGH, mmolBand(13.9f))
    }

    @Test
    fun followsThePresetAndTheTheme() {
        GlucoseRangeColors.setPalette(Palette.VIBRANT)
        assertEquals(0xFFE57373.toInt(), color(75f, dark = true))
        assertEquals(0xFFE53935.toInt(), color(75f, dark = false))
        assertEquals(0xFF81C784.toInt(), color(110f, dark = true))
    }

    @Test
    fun aBandOverrideIsWhatTheValueShows() {
        val custom = 0xFFFF8A80.toInt()
        GlucoseRangeColors.setOverride(Band.LOW, custom)
        assertEquals(custom, color(75f))
        assertEquals(custom, color(75f, dark = false))
        // Other bands keep the preset.
        assertEquals(GlucoseRangeColors.high(true), color(200f))
    }

    @Test
    fun gdhLikeKeepsItsThreeTiers() {
        // GDH-like maps its three colours onto the bands, so its users see what
        // the old three-tier scheme showed them.
        GlucoseRangeColors.setPalette(Palette.GDH_LIKE)
        assertEquals(0xFFFF0000.toInt(), color(55f))
        assertEquals(0xFFFFDC00.toInt(), color(75f))
        assertEquals(0xFF00FF00.toInt(), color(110f))
        assertEquals(0xFFFFDC00.toInt(), color(200f))
        assertEquals(0xFFFF0000.toInt(), color(320f))
    }

    @Test
    fun invalidValueFallsBack() {
        assertEquals(fallback, color(Float.NaN))
        assertEquals(fallback, color(0f))
        assertEquals(fallback, color(-5f))
        assertNull(GlucoseRangeColors.bandForValue(Float.NaN, 80f, 160f, 70f, 250f, false))
    }

    @Test
    fun unsetAlarmsFallBackToDefaults() {
        // mg/dL defaults: very low 54, very high 250.
        assertEquals(GlucoseRangeColors.veryLow(true), color(50f, alarmLow = 0f, alarmHigh = 0f))
        assertEquals(GlucoseRangeColors.low(true), color(60f, alarmLow = 0f, alarmHigh = 0f))
        assertEquals(GlucoseRangeColors.high(true), color(200f, alarmLow = 0f, alarmHigh = 0f))
        assertEquals(GlucoseRangeColors.veryHigh(true), color(260f, alarmLow = 0f, alarmHigh = 0f))
    }
}
