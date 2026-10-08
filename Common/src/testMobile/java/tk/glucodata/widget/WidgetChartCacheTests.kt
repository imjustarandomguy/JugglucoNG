package tk.glucodata.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tk.glucodata.GlucosePoint

class WidgetChartCacheTests {
    private val points = listOf(GlucosePoint(1_000L, 110f, 0f), GlucosePoint(301_000L, 115f, 0f))

    private fun key(
        readingMillis: Long = 301_000L,
        history: List<GlucosePoint> = points,
        widthPx: Int = 800,
        heightPx: Int = 300,
        windowMillis: Long = 3L * 3_600_000L,
        dark: Boolean = true,
    ) = WidgetChartKey(
        readingMillis, WidgetChartKey.fingerprint(history), widthPx, heightPx, windowMillis,
        dark, isMmol = false, viewMode = 0, hasCalibration = false, sensorSerial = "G7",
    )

    @Test
    fun sameReadingSizeOptionsAndThemeMakeTheSameKey() {
        assertEquals(key(), key(history = points.map { GlucosePoint(it.timestamp, it.value, it.rawValue) }))
    }

    @Test
    fun anythingDrawnDifferentlyChangesTheKey() {
        val base = key()
        assertNotEquals(base, key(readingMillis = 601_000L))
        assertNotEquals(base, key(widthPx = 801))
        assertNotEquals(base, key(heightPx = 299))
        assertNotEquals(base, key(windowMillis = 3_600_000L))
        assertNotEquals(base, key(dark = false))
        // A backfill or a calibration changes the points without a new reading.
        assertNotEquals(base, key(history = points + GlucosePoint(151_000L, 112f, 0f)))
        assertNotEquals(base, key(history = listOf(points[0], GlucosePoint(301_000L, 116f, 0f))))
    }

    @Test
    fun anEqualKeyReusesTheChartWithoutDrawing() {
        val cache = WidgetChartCache<String>()
        var draws = 0
        val draw = { draws++; "chart$draws" }
        assertEquals("chart1", cache.get(7, key(), draw))
        assertEquals("chart1", cache.get(7, key(), draw))
        assertEquals(1, draws)
        // A new reading draws again; each widget keeps its own.
        assertEquals("chart2", cache.get(7, key(readingMillis = 601_000L), draw))
        assertEquals("chart3", cache.get(8, key(readingMillis = 601_000L), draw))
        assertEquals(2, cache.size)
    }

    @Test
    fun aFailedDrawIsNotCachedAndRemovedWidgetsAreForgotten() {
        val cache = WidgetChartCache<String>()
        assertNull(cache.get(1, key()) { null })
        assertEquals(0, cache.size)
        cache.get(1, key()) { "a" }
        cache.get(2, key()) { "b" }
        cache.remove(1)
        cache.retainOnly(listOf(3))
        assertEquals(0, cache.size)
    }
}
