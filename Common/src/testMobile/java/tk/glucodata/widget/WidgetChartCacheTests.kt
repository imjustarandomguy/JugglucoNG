package tk.glucodata.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.GlucosePoint

class WidgetChartCacheTests {
    private val points = listOf(GlucosePoint(1_000L, 110f, 0f), GlucosePoint(301_000L, 115f, 0f))

    private val style = WidgetChartStyle(
        rangeColoredLine = false,
        targetLow = 70f,
        targetHigh = 180f,
        veryLow = 54f,
        veryHigh = 250f,
        bandColors = listOf(1, 2, 3, 4, 5, 11, 12, 13, 14, 15),
        sensorColor = null,
        smoothingMinutes = 0,
        collapseChunks = false,
        hideInitialWhenCalibrated = false,
        calibrationRevision = 7L,
    )

    private fun key(
        readingMillis: Long = 301_000L,
        history: List<GlucosePoint> = points,
        widthPx: Int = 800,
        heightPx: Int = 300,
        windowMillis: Long = 3L * 3_600_000L,
        dark: Boolean = true,
        isMmol: Boolean = false,
        style: WidgetChartStyle = this.style,
    ) = WidgetChartKey(
        readingMillis, WidgetChartKey.fingerprint(history), widthPx, heightPx, windowMillis,
        dark, isMmol = isMmol, viewMode = 0, hasCalibration = false, sensorSerial = "G7", style = style,
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
        assertNotEquals(base, key(isMmol = true))
        // A backfill or a calibration changes the points without a new reading.
        assertNotEquals(base, key(history = points + GlucosePoint(151_000L, 112f, 0f)))
        assertNotEquals(base, key(history = listOf(points[0], GlucosePoint(301_000L, 116f, 0f))))
    }

    @Test
    fun everySettingTheDrawerReadsChangesTheKey() {
        // Same reading, same history: only the setting changed, and the chart must be redrawn.
        val base = key()
        val changed = listOf(
            "range-coloured line" to style.copy(rangeColoredLine = true),
            "target low" to style.copy(targetLow = 72f),
            "target high" to style.copy(targetHigh = 160f),
            "very low" to style.copy(veryLow = 50f),
            "very high" to style.copy(veryHigh = 270f),
            "band colour" to style.copy(bandColors = style.bandColors.toMutableList().also { it[2] = 33 }),
            "sensor colour" to style.copy(sensorColor = 0xFF00FF00.toInt()),
            "smoothing" to style.copy(smoothingMinutes = 5),
            "collapsed chunks" to style.copy(collapseChunks = true),
            "hidden initial line" to style.copy(hideInitialWhenCalibrated = true),
            "calibration" to style.copy(calibrationRevision = 8L),
        )
        for ((what, changedStyle) in changed) {
            assertNotEquals(what, base, key(style = changedStyle))
        }
        assertEquals(base, key(style = style.copy()))
    }

    @Test
    fun aBandNotSetStaysEqualToItself() {
        // NaN thresholds (not set) must not make every key new, or nothing is ever reused.
        val unset = style.copy(targetLow = Float.NaN, veryHigh = Float.NaN)
        assertEquals(key(style = unset), key(style = unset.copy()))
    }

    @Test
    fun aChangedSettingRedrawsTheChartOfAnOtherwiseEqualRender() {
        val cache = WidgetChartCache<String>()
        var draws = 0
        val draw = { draws++; "chart$draws" }
        assertEquals("chart1", cache.get(7, key(), draw))
        assertEquals("chart2", cache.get(7, key(style = style.copy(rangeColoredLine = true)), draw))
        assertEquals(2, draws)
    }

    @Test
    fun aForcedRedrawSendsAgainWithAChartDrawnAfresh() {
        val cache = WidgetRenderCache<String, String>()
        var draws = 0
        val draw = { draws++; "chart$draws" }
        cache.chart(7, key(), draw)
        cache.sent(7, "model")
        assertTrue(cache.shows(7, "model"))
        assertEquals("chart1", cache.chart(7, key(), draw))

        // Not only the model: the chart of an equal key is drawn again too.
        cache.forget(7)
        assertFalse(cache.shows(7, "model"))
        assertEquals("chart2", cache.chart(7, key(), draw))
        cache.sent(7, "model")

        cache.chart(8, key(), draw)
        cache.sent(8, "model")
        cache.forgetAll()
        assertFalse(cache.shows(7, "model"))
        assertFalse(cache.shows(8, "model"))
        assertEquals("chart4", cache.chart(7, key(), draw))
        assertEquals("chart5", cache.chart(8, key(), draw))
    }

    @Test
    fun droppedChartsAreDrawnAgainAtTheNextChange() {
        val cache = WidgetRenderCache<String, String>()
        var draws = 0
        val draw = { draws++; "chart$draws" }
        cache.chart(7, key(), draw)
        cache.sent(7, "model")
        cache.dropCharts()
        // Nothing to send while the model is the same; drawn again when it changes.
        assertTrue(cache.shows(7, "model"))
        assertEquals("chart2", cache.chart(7, key(), draw))
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
