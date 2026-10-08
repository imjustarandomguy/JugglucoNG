package tk.glucodata.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetGeometryTests {
    // About IBM Plex's digit width; meta text a little narrower per character.
    private val digits = TextMeasure { text -> text.length * 0.57f }
    private val meta = TextMeasure { text -> text.length * 0.5f }

    private fun compute(
        width: Int,
        height: Int,
        chartWanted: Boolean = false,
        hasBackground: Boolean = false,
        textScale: Float = 1f,
        value: String = "123",
        arrow: Boolean = true,
        items: List<MetaItem> = listOf(MetaItem("12:34")),
    ) = WidgetGeometryCalculator.compute(
        WidgetSizeDp(width, height), chartWanted, hasBackground, textScale, value, digits, arrow, items, meta,
    )

    @Test
    fun oneRowIsSmallWhenNarrowAndWideWhenLong() {
        assertEquals(WidgetLayout.SMALL, WidgetGeometryCalculator.layoutFor(WidgetSizeDp(110, 56), chartWanted = false))
        assertEquals(WidgetLayout.SMALL, WidgetGeometryCalculator.layoutFor(WidgetSizeDp(130, 56), chartWanted = true))
        assertEquals(WidgetLayout.WIDE, WidgetGeometryCalculator.layoutFor(WidgetSizeDp(250, 56), chartWanted = false))
        // Long enough but too tall for its width to read as a strip.
        assertEquals(WidgetLayout.SMALL, WidgetGeometryCalculator.layoutFor(WidgetSizeDp(180, 90), chartWanted = false))
    }

    @Test
    fun tallShowsTheChartOnlyWhenWantedAndItFits() {
        assertEquals(WidgetLayout.TALL, WidgetGeometryCalculator.layoutFor(WidgetSizeDp(300, 150), chartWanted = true))
        // The value widget, or the chart switched off: tall just centres a bigger value.
        assertEquals(WidgetLayout.SMALL, WidgetGeometryCalculator.layoutFor(WidgetSizeDp(300, 150), chartWanted = false))
        // Two rows on a phone are just enough.
        assertEquals(WidgetLayout.TALL, WidgetGeometryCalculator.layoutFor(WidgetSizeDp(300, 100), chartWanted = true))
    }

    @Test
    fun lockScreenRowKeepsTheTimeUnderTheValue() {
        for (height in listOf(50, 56, 60)) {
            val geometry = compute(width = 110, height = height)
            assertEquals(WidgetLayout.SMALL, geometry.layout)
            assertEquals(listOf("12:34"), geometry.metaLines)
            val digitHeight = geometry.valueSizeDp * WidgetGeometryCalculator.DIGIT_HEIGHT_EM
            val metaHeight = geometry.metaSizeDp * WidgetGeometryCalculator.META_LINE_EM
            assertTrue("height $height", digitHeight + metaHeight + 2 * geometry.paddingDp <= height)
            // The value and arrow fit the width.
            assertTrue(geometry.valueSizeDp * (digits.em("123") + 0.8f) <= 110 - 2 * geometry.paddingDp)
            assertTrue(geometry.arrowSizeDp > 0f)
        }
    }

    @Test
    fun aMetaLineThatDoesNotFitLosesItemsThenFallsBackToItsShortForm() {
        val stale = MetaItem(listOf("No new value since 12:34", "12:34"))
        assertEquals(listOf("12:34"), compute(width = 90, height = 60, items = listOf(stale)).metaLines)
        val items = listOf(MetaItem("12:34"), MetaItem("Δ +3"), MetaItem("IOB 1.2 U"))
        assertEquals(listOf("12:34 · Δ +3 · IOB 1.2 U"), compute(width = 200, height = 90, items = items).metaLines)
        assertEquals(listOf("12:34"), compute(width = 70, height = 90, items = items).metaLines)
    }

    @Test
    fun wideStacksTheMetaBesideTheValue() {
        val items = listOf(MetaItem("12:34"), MetaItem("Δ +3"), MetaItem("IOB 1.2 U"))
        val roomy = compute(width = 300, height = 80, items = items)
        assertEquals(WidgetLayout.WIDE, roomy.layout)
        assertEquals(listOf("12:34", "Δ +3", "IOB 1.2 U"), roomy.metaLines)
        // Too short for three readable lines: the last ones share a line.
        val short = compute(width = 260, height = 30, items = items)
        assertEquals(WidgetLayout.WIDE, short.layout)
        assertTrue(short.metaLines.size < 3)
        assertTrue(short.metaSizeDp >= 8.5f)
    }

    @Test
    fun tallReservesTheChartUnderTheValueRow() {
        val geometry = compute(width = 300, height = 150, chartWanted = true, hasBackground = true)
        assertEquals(WidgetLayout.TALL, geometry.layout)
        assertTrue(geometry.chartHeightDp >= WidgetGeometryCalculator.MIN_CHART_HEIGHT_DP)
        assertEquals(300 - 2 * geometry.paddingDp, geometry.chartWidthDp, 0.01f)
        assertEquals(150 - 2 * geometry.paddingDp, geometry.headerHeightDp + 4f + geometry.chartHeightDp, 0.01f)
    }

    @Test
    fun textSizeScalesWithinWhatFits() {
        val small = compute(width = 250, height = 120, textScale = 0.8f)
        val normal = compute(width = 250, height = 120)
        val large = compute(width = 250, height = 120, textScale = 1.3f)
        assertTrue(small.valueSizeDp < normal.valueSizeDp)
        assertTrue(normal.valueSizeDp < large.valueSizeDp)
        // Never past the room there is.
        val huge = compute(width = 110, height = 56, textScale = 5f)
        val max = compute(width = 110, height = 56, textScale = 1.3f)
        assertEquals(max.valueSizeDp, huge.valueSizeDp, 0.001f)
    }

    @Test
    fun noArrowGivesTheValueTheRoom() {
        val withArrow = compute(width = 110, height = 120)
        val without = compute(width = 110, height = 120, arrow = false)
        assertEquals(0f, without.arrowSizeDp, 0f)
        assertTrue(without.valueSizeDp > withArrow.valueSizeDp)
    }

    @Test
    fun hostSizeFollowsOrientationAndFallsBackToTheProviderSize() {
        val fallback = WidgetSizeDp(180, 50)
        assertEquals(WidgetSizeDp(110, 120), WidgetSizing.current(110, 60, 200, 120, portrait = true, fallback))
        assertEquals(WidgetSizeDp(200, 60), WidgetSizing.current(110, 60, 200, 120, portrait = false, fallback))
        // A host that reports only the minimums.
        assertEquals(WidgetSizeDp(110, 60), WidgetSizing.current(110, 60, 0, 0, portrait = true, fallback))
        assertEquals(fallback, WidgetSizing.current(0, 0, 0, 0, portrait = true, fallback))
    }
}
