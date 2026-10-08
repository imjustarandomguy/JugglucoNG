package tk.glucodata.ui.overlay

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.service.FloatingGlucoseService.CutoutEdge
import tk.glucodata.ui.overlay.FloatingNextReading.BarSide
import tk.glucodata.ui.overlay.FloatingNextReading.OutlinePiece
import tk.glucodata.ui.overlay.FloatingNextReadingStyle.ARROW_RING
import tk.glucodata.ui.overlay.FloatingNextReadingStyle.COLOURED_BAR
import tk.glucodata.ui.overlay.FloatingNextReadingStyle.CURRENT
import tk.glucodata.ui.overlay.FloatingNextReadingStyle.OUTLINE
import kotlin.math.PI
import kotlin.math.hypot

class FloatingNextReadingStyleTests {
    private val eps = 0.001f

    // The style

    @Test
    fun aStoredStyleIsReadByItsNameAndAnythingElseIsTheThinBar() {
        FloatingNextReadingStyle.entries.forEach { assertEquals(it, FloatingNextReadingStyle.fromKey(it.name)) }
        assertEquals(CURRENT, FloatingNextReadingStyle.fromKey(null))
        assertEquals(CURRENT, FloatingNextReadingStyle.fromKey(""))
        assertEquals(CURRENT, FloatingNextReadingStyle.fromKey("outline"))
    }

    @Test
    fun theRingNeedsAnArrowElseTheColouredBarIsDrawn() {
        assertEquals(ARROW_RING, FloatingNextReadingStyle.drawn(ARROW_RING, hasArrow = true))
        assertEquals(COLOURED_BAR, FloatingNextReadingStyle.drawn(ARROW_RING, hasArrow = false))
        for (style in listOf(CURRENT, COLOURED_BAR, OUTLINE)) {
            assertEquals(style, FloatingNextReadingStyle.drawn(style, hasArrow = true))
            assertEquals(style, FloatingNextReadingStyle.drawn(style, hasArrow = false))
        }
    }

    // The bars

    @Test
    fun anUprightIslandsBarRunsAlongItsLongSideFacingTheScreensMiddle() {
        assertEquals(BarSide.RIGHT, FloatingNextReading.barSide(CutoutEdge.LEFT))
        assertEquals(BarSide.LEFT, FloatingNextReading.barSide(CutoutEdge.RIGHT))
        assertEquals(BarSide.BOTTOM, FloatingNextReading.barSide(CutoutEdge.TOP))
        assertEquals(BarSide.BOTTOM, FloatingNextReading.barSide(CutoutEdge.BOTTOM))
        assertEquals(BarSide.BOTTOM, FloatingNextReading.barSide(CutoutEdge.NONE))
        assertEquals(BarSide.BOTTOM, FloatingNextReading.barSide(null))
    }

    @Test
    fun aBarFillsRightwardAlongTheBottomAndUpwardAlongASide() {
        // A 40 x 100 pill, the line 2 in from its side, from 10 to 30 along it.
        assertEquals(
            Offset(10f, 98f) to Offset(30f, 98f),
            FloatingNextReading.barLine(40f, 100f, BarSide.BOTTOM, depth = 2f, from = 10f, to = 30f),
        )
        assertEquals(
            Offset(38f, 90f) to Offset(38f, 70f),
            FloatingNextReading.barLine(40f, 100f, BarSide.RIGHT, depth = 2f, from = 10f, to = 30f),
        )
        assertEquals(
            Offset(2f, 90f) to Offset(2f, 70f),
            FloatingNextReading.barLine(40f, 100f, BarSide.LEFT, depth = 2f, from = 10f, to = 30f),
        )
    }

    @Test
    fun theThinBarFillsTheStretchTheCornersLeaveVisible() {
        val inset = FloatingNextReading.visibleInset(radius = 16f, thickness = 2.5f)
        val atReading = FloatingNextReading.flatBarSpan(100f, radius = 16f, thickness = 2.5f, progress = 0f)
        assertEquals(FloatingNextReading.BarSpan(0f, 100f, 0f, inset), atReading)
        val halfway = FloatingNextReading.flatBarSpan(100f, radius = 16f, thickness = 2.5f, progress = 0.5f)
        assertEquals(50f, halfway.fillTo, eps)
        val due = FloatingNextReading.flatBarSpan(100f, radius = 16f, thickness = 2.5f, progress = 1f)
        assertEquals(100f - inset, due.fillTo, eps)
    }

    @Test
    fun aRoundEndLiesWholeInsideTheCorner() {
        // Flush with the edge, an end fits only past the corner.
        assertEquals(16f, FloatingNextReading.capInset(radius = 16f, capRadius = 2f, depth = 2f), eps)
        // Centred as far in as the corner's centre, or by square or tiny corners: its own radius in.
        assertEquals(2f, FloatingNextReading.capInset(radius = 16f, capRadius = 2f, depth = 16f), eps)
        assertEquals(2f, FloatingNextReading.capInset(radius = 0f, capRadius = 2f, depth = 3.5f), eps)
        assertEquals(2f, FloatingNextReading.capInset(radius = 2f, capRadius = 2f, depth = 3.5f), eps)
        // In between, the end touches the corner from inside: its centre is the corner's
        // radius less its own from the corner's centre.
        val inset = FloatingNextReading.capInset(radius = 16f, capRadius = 2f, depth = 3.5f)
        assertEquals(14f, hypot(16f - inset, 16f - 3.5f), eps)
        assertTrue(inset > 2f && inset < 16f)
    }

    @Test
    fun theRoundBarRunsBetweenItsEndsAndIsADotAtTheReading() {
        // 4 thick and 1.5 in: its ends centred 3.5 in from the edge.
        val inset = FloatingNextReading.capInset(radius = 16f, capRadius = 2f, depth = 3.5f)
        val atReading = FloatingNextReading.roundBarSpan(100f, radius = 16f, thickness = 4f, margin = 1.5f, progress = 0f)
        assertEquals(inset, atReading.trackFrom, eps)
        assertEquals(100f - inset, atReading.trackTo, eps)
        assertEquals(atReading.trackFrom, atReading.fillFrom, eps)
        assertEquals(atReading.fillFrom, atReading.fillTo, eps)
        val halfway = FloatingNextReading.roundBarSpan(100f, radius = 16f, thickness = 4f, margin = 1.5f, progress = 0.5f)
        assertEquals(50f, halfway.fillTo, eps)
        val due = FloatingNextReading.roundBarSpan(100f, radius = 16f, thickness = 4f, margin = 1.5f, progress = 1f)
        assertEquals(due.trackTo, due.fillTo, eps)
        // A side too short for both ends: one dot in its middle.
        val short = FloatingNextReading.roundBarSpan(10f, radius = 16f, thickness = 4f, margin = 1.5f, progress = 0.5f)
        assertEquals(FloatingNextReading.BarSpan(5f, 5f, 5f, 5f), short)
    }

    // The outline

    private val pill = Rect(0f, 0f, 100f, 40f)

    private fun length(piece: OutlinePiece): Float = when (piece) {
        is OutlinePiece.Line -> (piece.to - piece.from).getDistance()
        is OutlinePiece.Arc -> piece.oval.width / 2f * piece.sweep * PI.toFloat() / 180f
    }

    @Test
    fun theOutlinesLengthIsItsStraightsAndItsCorners() {
        assertEquals(2 * 80f + 2 * 20f + 2 * PI.toFloat() * 10f, FloatingNextReading.outlineLength(pill, 10f), eps)
        assertEquals(280f, FloatingNextReading.outlineLength(pill, 0f), eps)
        // Corners larger than the pill allows are taken as half its height: round ends.
        assertEquals(
            FloatingNextReading.outlineLength(pill, 20f),
            FloatingNextReading.outlineLength(pill, 50f),
            eps,
        )
    }

    @Test
    fun theOutlineStartsAtTheMiddleOfTheBottomAndGoesLeftFirst() {
        assertTrue(FloatingNextReading.outline(pill, 10f, 0f).isEmpty())
        val length = FloatingNextReading.outlineLength(pill, 10f)
        val start = FloatingNextReading.outline(pill, 10f, 0.05f)
        val line = start.single() as OutlinePiece.Line
        assertEquals(Offset(50f, 40f), line.from)
        assertEquals(50f - 0.05f * length, line.to.x, eps)
        assertEquals(40f, line.to.y, eps)
    }

    @Test
    fun theWholeOutlineGoesRoundClockwiseBackToItsStart() {
        val pieces = FloatingNextReading.outline(pill, 10f, 1f)
        assertEquals(9, pieces.size)
        // Each corner a quarter turn clockwise: bottom left, top left, top right, bottom right.
        val corners = pieces.filterIsInstance<OutlinePiece.Arc>()
        assertEquals(listOf(90f, 180f, 270f, 0f), corners.map { it.startAngle })
        corners.forEach { assertEquals(90f, it.sweep, eps) }
        assertEquals(Rect(0f, 20f, 20f, 40f), corners.first().oval)
        assertEquals(Offset(50f, 40f), (pieces.last() as OutlinePiece.Line).to)
        assertEquals(FloatingNextReading.outlineLength(pill, 10f), pieces.sumOf { length(it).toDouble() }.toFloat(), 0.01f)
    }

    @Test
    fun aCornerPartlyDrawnSweepsItsShareOfTheQuarterTurn() {
        // Halfway round the first corner: the bottom's left half, then 45 degrees.
        val upTo = 40f + PI.toFloat() * 10f / 4f
        val pieces = FloatingNextReading.outline(pill, 10f, upTo / FloatingNextReading.outlineLength(pill, 10f))
        assertEquals(2, pieces.size)
        val corner = pieces[1] as OutlinePiece.Arc
        assertEquals(90f, corner.startAngle, eps)
        assertEquals(45f, corner.sweep, 0.01f)
    }

    @Test
    fun squareCornersMakeAnOutlineOfLinesOnly() {
        val pieces = FloatingNextReading.outline(pill, 0f, 1f)
        assertEquals(5, pieces.size)
        assertTrue(pieces.all { it is OutlinePiece.Line })
        assertEquals(280f, pieces.sumOf { length(it).toDouble() }.toFloat(), 0.01f)
    }

    @Test
    fun anUprightIslandsOutlineStartsAtTheMiddleOfItsRoundBottom() {
        // Round ends on a 40 x 120 upright pill: no straight bottom or top.
        val upright = Rect(0f, 0f, 40f, 120f)
        val pieces = FloatingNextReading.outline(upright, 20f, 1f)
        assertEquals(6, pieces.size)
        val first = pieces.first() as OutlinePiece.Arc
        assertEquals(Rect(0f, 80f, 40f, 120f), first.oval)
        assertEquals(90f, first.startAngle, eps)
        val leftSide = pieces[1] as OutlinePiece.Line
        assertEquals(Offset(0f, 100f), leftSide.from)
        assertEquals(Offset(0f, 20f), leftSide.to)
    }

    // The ring

    @Test
    fun theRingsSlotGrowsWithTheArrowAndTheRingClearsIt() {
        for (arrow in listOf(10f, 12f, 12.6f, 20f, 34f, 42f)) {
            val slot = FloatingNextReading.ringSlot(arrow)
            val stroke = FloatingNextReading.ringStroke(arrow)
            // The ring's inside edge is outside the circle the arrow's box holds...
            assertTrue(slot / 2f - stroke > arrow / 2f)
            // ...and the slot only as much larger as that needs.
            assertTrue(slot < 1.5f * arrow)
        }
        assertTrue(FloatingNextReading.ringSlot(20f) < FloatingNextReading.ringSlot(30f))
        assertEquals(1.5f, FloatingNextReading.ringStroke(10f), eps)
        assertEquals(4.2f, FloatingNextReading.ringStroke(42f), eps)
    }
}
