package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartReadingsStyleTests {

    private val hour = 60L * 60L * 1000L

    @Test
    fun storedValuesOutsideTheStylesFallBackToTheLine() {
        assertEquals(ChartReadingsStyle.LINE, ChartReadingsStyle.fromPreference(0))
        assertEquals(ChartReadingsStyle.DOTS, ChartReadingsStyle.fromPreference(1))
        assertEquals(ChartReadingsStyle.LINE_AND_DOTS, ChartReadingsStyle.fromPreference(2))
        assertEquals(ChartReadingsStyle.LINE, ChartReadingsStyle.fromPreference(3))
        assertEquals(ChartReadingsStyle.LINE, ChartReadingsStyle.fromPreference(-1))
    }

    @Test
    fun eachStyleSaysWhatItDraws() {
        assertTrue(ChartReadingsStyle.drawsLine(ChartReadingsStyle.LINE))
        assertFalse(ChartReadingsStyle.drawsDots(ChartReadingsStyle.LINE))
        assertFalse(ChartReadingsStyle.drawsLine(ChartReadingsStyle.DOTS))
        assertTrue(ChartReadingsStyle.drawsDots(ChartReadingsStyle.DOTS))
        assertTrue(ChartReadingsStyle.drawsLine(ChartReadingsStyle.LINE_AND_DOTS))
        assertTrue(ChartReadingsStyle.drawsDots(ChartReadingsStyle.LINE_AND_DOTS))
    }

    @Test
    fun dotsAreAsWideAsTheLineUpToAnHour() {
        assertEquals(6f, ChartReadingsStyle.dotRadius(6f, hour), 0.001f)
        assertEquals(6f, ChartReadingsStyle.dotRadius(6f, hour / 2), 0.001f)
    }

    @Test
    fun dotsShrinkForLongerRanges() {
        assertEquals(6f * 0.7926f, ChartReadingsStyle.dotRadius(6f, 3 * hour), 0.01f)
        assertEquals(6f * 0.4f, ChartReadingsStyle.dotRadius(6f, 24 * hour), 0.001f)
        assertEquals(6f * 0.3f, ChartReadingsStyle.dotRadius(6f, 72 * hour), 0.001f)
        assertEquals(6f * 0.3f, ChartReadingsStyle.dotRadius(6f, 7 * 24 * hour), 0.001f)
    }

    @Test
    fun dotRadiusNeverGrowsWithTheRange() {
        var previous = Float.MAX_VALUE
        for (hours in listOf(1L, 2L, 3L, 6L, 12L, 24L, 48L, 72L, 168L)) {
            val radius = ChartReadingsStyle.dotRadius(8f, hours * hour)
            assertTrue("radius at $hours h", radius <= previous)
            previous = radius
        }
    }

    @Test
    fun dotsStayVisibleOnAThinLine() {
        assertEquals(0.75f, ChartReadingsStyle.dotRadius(1f, 72 * hour), 0.001f)
        assertEquals(0.75f, ChartReadingsStyle.dotRadius(0f, hour), 0.001f)
        assertEquals(0.75f, ChartReadingsStyle.dotRadius(Float.NaN, hour), 0.001f)
    }

    // ---- The spacing between readings on the plot ----

    private val minute = 60_000L
    private val durations = listOf(1L, 3L, 6L, 12L, 24L, 72L, 168L).map { it * hour }

    /** How far apart readings [intervalMs] apart land on a plot [widthPx] wide showing [durationMs]. */
    private fun spacing(widthPx: Float, durationMs: Long, intervalMs: Long) = widthPx * intervalMs / durationMs

    @Test
    fun fiveMinuteReadingsKeepTheDurationSizeOnEveryWidth() {
        for (width in listOf(320f, 450f, 640f, 1000f, 1440f)) for (duration in durations) {
            assertEquals(
                "$width px, ${duration / hour} h",
                ChartReadingsStyle.dotRadius(8f, duration),
                ChartReadingsStyle.dotRadius(8f, duration, width, spacing(width, duration, 5 * minute)),
                0f
            )
        }
    }

    @Test
    fun oneMinuteDotsShrinkUntilTheyJustTouch() {
        for (duration in listOf(3 * hour, 6 * hour)) {
            val gap = spacing(1000f, duration, minute)
            val radius = ChartReadingsStyle.dotRadius(8f, duration, 1000f, gap)

            assertTrue("${duration / hour} h: ${radius * 2} wide, $gap apart", radius * 2f <= gap + 0.001f)
            assertTrue(radius < ChartReadingsStyle.dotRadius(8f, duration))
        }
    }

    @Test
    fun oneMinuteDotsKeepTheirSizeWhileTheyAreApart() {
        assertEquals(8f, ChartReadingsStyle.dotRadius(8f, hour, 1000f, spacing(1000f, hour, minute)), 0.001f)
    }

    @Test
    fun oneMinuteDotsAreNoSmallerThanTheDurationSizeInProportion() {
        // A day of one-minute readings on a phone: they cannot stay apart, and the dots
        // keep a quarter of the size readings four minutes apart would have.
        val radius = ChartReadingsStyle.dotRadius(8f, 24 * hour, 1000f, spacing(1000f, 24 * hour, minute))
        assertEquals(ChartReadingsStyle.dotRadius(8f, 24 * hour) / 4f, radius, 0.001f)
        assertEquals(0.75f, ChartReadingsStyle.dotRadius(2f, 72 * hour, 300f, 0.01f), 0.001f)
    }

    @Test
    fun withoutASpacingTheDurationSizeHolds() {
        assertEquals(ChartReadingsStyle.dotRadius(8f, 6 * hour), ChartReadingsStyle.dotRadius(8f, 6 * hour, 1000f, 0f), 0f)
        assertEquals(ChartReadingsStyle.dotRadius(8f, 6 * hour), ChartReadingsStyle.dotRadius(8f, 6 * hour, 0f, 2f), 0f)
    }

    @Test
    fun spacingIsTheMedianOfTheNewestGaps() {
        // x, y pairs: 10 px apart, one missed reading (20 px) and one reading drawn twice.
        val xs = floatArrayOf(0f, 5f, 10f, 5f, 30f, 5f, 40f, 5f, 40f, 5f, 50f, 5f, 60f, 5f)
        assertEquals(10f, ChartReadingsStyle.typicalSpacing(xs, xs.size, 2), 0f)
        assertEquals("only the dots in use", 10f, ChartReadingsStyle.typicalSpacing(xs + FloatArray(6) { 999f }, xs.size, 2), 0f)
        assertEquals(3f, ChartReadingsStyle.typicalSpacing(floatArrayOf(9f, 6f, 3f), 3, 1), 0f)
        assertEquals(0f, ChartReadingsStyle.typicalSpacing(floatArrayOf(4f, 1f), 2, 2), 0f)
        assertEquals(0f, ChartReadingsStyle.typicalSpacing(FloatArray(0), 0, 1), 0f)
    }
}
