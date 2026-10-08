package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardChartRangeTests {

    @Test
    fun autoRangeIsTheConfiguredRangeWithNothingInView() {
        val mmol = autoExpandedChartYRange(
            baselineMin = 3f,
            baselineMax = 12f,
            visibleMin = null,
            visibleMax = null,
            isMmol = true
        )
        val mgdl = autoExpandedChartYRange(
            baselineMin = 54f,
            baselineMax = 216f,
            visibleMin = null,
            visibleMax = null,
            isMmol = false
        )

        assertEquals(3f, mmol.min, 0.001f)
        assertEquals(12f, mmol.max, 0.001f)
        assertEquals(54f, mgdl.min, 0.001f)
        assertEquals(216f, mgdl.max, 0.001f)
    }

    @Test
    fun autoRangeKeepsBaselineWhileVisibleValuesAreInsideIt() {
        val range = autoExpandedChartYRange(
            baselineMin = 0f,
            baselineMax = 13f,
            visibleMin = 3.8f,
            visibleMax = 12.9f,
            isMmol = true
        )

        assertEquals(0f, range.min, 0.001f)
        assertEquals(13f, range.max, 0.001f)
    }

    @Test
    fun autoRangeAddsRoundedMmolHeadroomAboveBaseline() {
        val range = autoExpandedChartYRange(
            baselineMin = 0f,
            baselineMax = 13f,
            visibleMin = 4.2f,
            visibleMax = 14.2f,
            isMmol = true
        )

        assertEquals(0f, range.min, 0.001f)
        assertEquals(15f, range.max, 0.001f)
    }

    @Test
    fun autoRangeCanExpandBelowANonZeroBaseline() {
        val range = autoExpandedChartYRange(
            baselineMin = 3f,
            baselineMax = 13f,
            visibleMin = 2.7f,
            visibleMax = 8f,
            isMmol = true
        )

        assertEquals(2f, range.min, 0.001f)
        assertEquals(13f, range.max, 0.001f)
    }

    @Test
    fun autoRangeWidensTheMinForAMgdlLow() {
        val range = autoExpandedChartYRange(
            baselineMin = 70f,
            baselineMax = 250f,
            visibleMin = 55f,
            visibleMax = 180f,
            isMmol = false
        )

        assertEquals(36f, range.min, 0.001f)
        assertEquals(250f, range.max, 0.001f)
    }

    @Test
    fun autoRangeWidensBothEndsForValuesOnBothSides() {
        val range = autoExpandedChartYRange(
            baselineMin = 4f,
            baselineMax = 10f,
            visibleMin = 3.5f,
            visibleMax = 11.2f,
            isMmol = true
        )

        assertEquals(3f, range.min, 0.001f)
        assertEquals(12f, range.max, 0.001f)
    }

    @Test
    fun autoRangeReturnsToBaselineWhenOutlierLeavesViewport() {
        val withOutlier = autoExpandedChartYRange(
            baselineMin = 0f,
            baselineMax = 234f,
            visibleMin = 90f,
            visibleMax = 260f,
            isMmol = false
        )
        val outlierScrolledOut = autoExpandedChartYRange(
            baselineMin = 0f,
            baselineMax = 234f,
            visibleMin = 90f,
            visibleMax = 220f,
            isMmol = false
        )

        assertEquals(270f, withOutlier.max, 0.001f)
        assertEquals(0f, outlierScrolledOut.min, 0.001f)
        assertEquals(234f, outlierScrolledOut.max, 0.001f)
    }

    @Test
    fun autoRangeUsesMgdlSizedExpansionSteps() {
        val range = autoExpandedChartYRange(
            baselineMin = 0f,
            baselineMax = 234f,
            visibleMin = 80f,
            visibleMax = 240f,
            isMmol = false
        )

        assertEquals(252f, range.max, 0.001f)
    }

    @Test
    fun autoRangePaddingCarriesAHighValuePastTheNextStep() {
        // 4% of the span as padding: 0.52 mmol/L over 0..13, 9.36 mg/dL over 0..234.
        val mmol = autoExpandedChartYRange(
            baselineMin = 0f,
            baselineMax = 13f,
            visibleMin = 5f,
            visibleMax = 14.8f,
            isMmol = true
        )
        val mgdl = autoExpandedChartYRange(
            baselineMin = 0f,
            baselineMax = 234f,
            visibleMin = 90f,
            visibleMax = 245f,
            isMmol = false
        )

        assertEquals(16f, mmol.max, 0.001f)
        assertEquals(270f, mgdl.max, 0.001f)
    }

    @Test
    fun autoRangePaddingCarriesALowValuePastTheNextStep() {
        val range = autoExpandedChartYRange(
            baselineMin = 3f,
            baselineMax = 13f,
            visibleMin = 2.2f,
            visibleMax = 8f,
            isMmol = true
        )

        assertEquals(1f, range.min, 0.001f)
        assertEquals(13f, range.max, 0.001f)
    }

    @Test
    fun autoRangeUsesTheMinimumPaddingOnANarrowRange() {
        // 4% of these spans is under the 0.4 mmol/L and 7 mg/dL minimum.
        val mmol = autoExpandedChartYRange(
            baselineMin = 4f,
            baselineMax = 6f,
            visibleMin = 4.5f,
            visibleMax = 6.7f,
            isMmol = true
        )
        val mgdl = autoExpandedChartYRange(
            baselineMin = 70f,
            baselineMax = 140f,
            visibleMin = 90f,
            visibleMax = 141f,
            isMmol = false
        )

        assertEquals(8f, mmol.max, 0.001f)
        assertEquals(162f, mgdl.max, 0.001f)
    }

    @Test
    fun autoRangeStopsAtZero() {
        val range = autoExpandedChartYRange(
            baselineMin = 3f,
            baselineMax = 13f,
            visibleMin = 0.3f,
            visibleMax = 8f,
            isMmol = true
        )

        assertEquals(0f, range.min, 0.001f)
    }

    @Test
    fun coerceChartYToDrawableRangeUsesInsetWhenThereIsRoom() {
        assertEquals(12f, coerceChartYToDrawableRange(12f, chartHeight = 100f, edgeInset = 6f), 0.001f)
        assertEquals(6f, coerceChartYToDrawableRange(-20f, chartHeight = 100f, edgeInset = 6f), 0.001f)
        assertEquals(94f, coerceChartYToDrawableRange(140f, chartHeight = 100f, edgeInset = 6f), 0.001f)
    }

    @Test
    fun coerceChartYToDrawableRangeHandlesCollapsedInsetRange() {
        val chartHeight = 1f
        val edgeInset = 19.5f

        assertEquals(1f, coerceChartYToDrawableRange(20f, chartHeight, edgeInset), 0.001f)
        assertEquals(0f, coerceChartYToDrawableRange(-20f, chartHeight, edgeInset), 0.001f)
        assertEquals(0.5f, coerceChartYToDrawableRange(Float.NaN, chartHeight, edgeInset), 0.001f)
    }

    @Test
    fun previewCenterTimeForWindowEndAnchorsPreviewAtRightEdge() {
        val windowEnd = 1_000_000_000L

        assertEquals(windowEnd, previewCenterTimeForWindowEnd(windowEnd) + 12L * 60L * 60L * 1000L)
    }

    @Test
    fun previewCenterTimeContainingViewportKeepsViewportInsidePreviewBand() {
        val hour = 60L * 60L * 1000L
        val previewCenter = 12L * hour

        assertEquals(12L * hour, previewCenterTimeContainingViewport(previewCenter, 12L * hour, 6L * hour))
        assertEquals(14L * hour, previewCenterTimeContainingViewport(previewCenter, 23L * hour, 6L * hour))
        assertEquals(10L * hour, previewCenterTimeContainingViewport(previewCenter, 1L * hour, 6L * hour))
    }
}
