package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardChartHeightAnchorsTests {

    private val middle = 300f
    private val max = 900f

    @Test
    fun anchorsMapToTheirBoosts() {
        assertEquals(0f, DashboardChartHeightAnchors.boostPx(DashboardChartHeightAnchors.COLLAPSED, middle, max), 0f)
        assertEquals(middle, DashboardChartHeightAnchors.boostPx(DashboardChartHeightAnchors.MIDDLE, middle, max), 0f)
        assertEquals(max, DashboardChartHeightAnchors.boostPx(DashboardChartHeightAnchors.FULL, middle, max), 0f)
        assertEquals(0f, DashboardChartHeightAnchors.boostPx(DashboardChartHeightAnchors.FULL, middle, 0f), 0f)
    }

    @Test
    fun anchorAtRecognisesOnlyRestingPositions() {
        assertEquals(DashboardChartHeightAnchors.COLLAPSED, DashboardChartHeightAnchors.anchorAt(0.5f, middle, max))
        assertEquals(DashboardChartHeightAnchors.MIDDLE, DashboardChartHeightAnchors.anchorAt(300.4f, middle, max))
        assertEquals(DashboardChartHeightAnchors.FULL, DashboardChartHeightAnchors.anchorAt(899.5f, middle, max))
        assertNull(DashboardChartHeightAnchors.anchorAt(450f, middle, max))
        assertNull(DashboardChartHeightAnchors.anchorAt(0f, middle, 0f))
    }

    @Test
    fun slowReleaseSettlesOnTheNearestZone() {
        assertEquals(0f, DashboardChartHeightAnchors.snapTargetPx(140f, middle, max, 0f), 0f)
        assertEquals(middle, DashboardChartHeightAnchors.snapTargetPx(160f, middle, max, 0f), 0f)
        assertEquals(middle, DashboardChartHeightAnchors.snapTargetPx(590f, middle, max, 0f), 0f)
        assertEquals(max, DashboardChartHeightAnchors.snapTargetPx(610f, middle, max, 0f), 0f)
    }

    @Test
    fun moderateFlingsStepOneAnchor() {
        assertEquals(middle, DashboardChartHeightAnchors.snapTargetPx(100f, middle, max, 1000f), 0f)
        assertEquals(max, DashboardChartHeightAnchors.snapTargetPx(400f, middle, max, 1000f), 0f)
        assertEquals(middle, DashboardChartHeightAnchors.snapTargetPx(800f, middle, max, -1000f), 0f)
        assertEquals(0f, DashboardChartHeightAnchors.snapTargetPx(250f, middle, max, -1000f), 0f)
    }

    @Test
    fun strongFlingsJumpToTheEnds() {
        assertEquals(max, DashboardChartHeightAnchors.snapTargetPx(50f, middle, max, 4000f), 0f)
        assertEquals(0f, DashboardChartHeightAnchors.snapTargetPx(850f, middle, max, -4000f), 0f)
    }

    @Test
    fun unknownStoredAnchorsFallBackToCollapsed() {
        assertEquals(DashboardChartHeightAnchors.FULL, DashboardChartHeightAnchors.fromPreference(2))
        assertEquals(DashboardChartHeightAnchors.COLLAPSED, DashboardChartHeightAnchors.fromPreference(7))
        assertEquals(DashboardChartHeightAnchors.COLLAPSED, DashboardChartHeightAnchors.fromPreference(-1))
    }

    // ---- Which resting heights are remembered ----

    private val collapsed = DashboardChartHeightAnchors.COLLAPSED
    private val middleAnchor = DashboardChartHeightAnchors.MIDDLE
    private val full = DashboardChartHeightAnchors.FULL

    @Test
    fun aChosenHeightIsRememberedOnceItRests() {
        val choice = DashboardChartHeightChoice()
        choice.onUserResize()

        assertNull("still settling", choice.anchorToRemember(null, listAtTop = true))
        assertEquals(middleAnchor, choice.anchorToRemember(middleAnchor, listAtTop = true))
        assertNull("remembered once", choice.anchorToRemember(middleAnchor, listAtTop = true))
    }

    @Test
    fun aCollapseAtTheTopOfTheListIsAChoiceToo() {
        val choice = DashboardChartHeightChoice()
        choice.onUserResize()

        assertEquals(collapsed, choice.anchorToRemember(collapsed, listAtTop = true))
    }

    @Test
    fun scrollingTheListDoesNotOverwriteTheChosenHeight() {
        val choice = DashboardChartHeightChoice()
        choice.onUserResize()
        assertEquals(full, choice.anchorToRemember(full, listAtTop = true))

        // A drag up shrinks the chart first, then scrolls the list on.
        choice.onUserResize()
        assertNull(choice.anchorToRemember(collapsed, listAtTop = false))
        // Back at the top, the chart is still collapsed from the scroll.
        assertNull(choice.anchorToRemember(collapsed, listAtTop = true))
    }

    @Test
    fun nothingIsRememberedWithoutAResize() {
        val choice = DashboardChartHeightChoice()

        assertNull(choice.anchorToRemember(collapsed, listAtTop = true))
        assertNull(choice.anchorToRemember(full, listAtTop = true))
    }
}
