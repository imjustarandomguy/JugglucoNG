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
}
