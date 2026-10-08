package tk.glucodata.ui.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tk.glucodata.SensorSourceResolver
import tk.glucodata.service.FloatingGlucoseService.CutoutEdge
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * How the pill draws its time to the next reading.
 *
 * Temporary: the four styles are here to be tried on the pill, picked live in the floating
 * glucose settings ("Next reading style"). Once one is chosen, the others, this option and
 * its setting are to be removed again.
 */
internal enum class FloatingNextReadingStyle {
    /** A thin bar along the bottom edge (the long inner side on an upright island), in the text's colour. */
    CURRENT,

    /** A thicker bar with round ends, along the same side, in the value's range colour. */
    COLOURED_BAR,

    /** Around the pill's border, from the middle of its bottom edge, in the value's range colour. */
    OUTLINE,

    /** A ring around the trend arrow, from its top, in the value's range colour. */
    ARROW_RING;

    companion object {
        /** The style stored under [key] (its name); [CURRENT] for anything else. */
        fun fromKey(key: String?): FloatingNextReadingStyle = entries.firstOrNull { it.name == key } ?: CURRENT

        /** The style drawn for [style]: the ring needs an arrow, without one the coloured bar. */
        fun drawn(style: FloatingNextReadingStyle, hasArrow: Boolean): FloatingNextReadingStyle =
            if (style == ARROW_RING && !hasArrow) COLOURED_BAR else style
    }
}

/**
 * The time to the pill's next reading: how far it is from its reading toward the next
 * one the sensor is expected to send, and whether that one is late. Pure, see the tests.
 */
internal object FloatingNextReading {
    const val ONE_MINUTE_MS = 60_000L
    const val FIVE_MINUTES_MS = 5L * ONE_MINUTE_MS

    /** How long past its expected time the next reading may come before it counts as late. */
    const val LATE_GRACE_MS = 30_000L

    /** The bar moves in steps this long, at most: no animation. */
    const val STEP_MS = 5_000L

    /** How many of the newest readings the spacing is taken from. */
    const val SPACING_READINGS = 6

    /** Readings closer than this are one reading from two sources, not a spacing. */
    private const val SAME_READING_MS = 30_000L

    /**
     * The reading interval of the sensor of [sensorKind] (a SensorSourceResolver kind),
     * or 0 when it cannot be told.
     *
     * A Dexcom or an Accu-Chek sends a reading every 5 minutes, as the native record's
     * stream slots say. Any other sensor goes by the spacing of its newest readings: the
     * Libre and Sibionics kinds stream every minute, but a sensor run by a Kotlin driver
     * (iCan and Anytime every 3 minutes) or a follower also has a native record, with the
     * Libre 2 kind. The spacing is the smallest between [readingTimes], in whole minutes,
     * within 1 to 5 minutes; a missed reading only widens one gap. With fewer than two
     * readings a streaming kind still has its minute.
     */
    fun intervalMillis(sensorKind: Int, readingTimes: List<Long>): Long {
        when (sensorKind) {
            SensorSourceResolver.SENSOR_KIND_DEXCOM,
            SensorSourceResolver.SENSOR_KIND_ACCUCHEK -> return FIVE_MINUTES_MS
        }
        val spacing = readingTimes
            .filter { it > 0L }
            .sorted()
            .takeLast(SPACING_READINGS)
            .zipWithNext { earlier, later -> later - earlier }
            .filter { it >= SAME_READING_MS }
            .minOrNull()
        if (spacing != null) {
            val minutes = (spacing + ONE_MINUTE_MS / 2) / ONE_MINUTE_MS
            return (minutes * ONE_MINUTE_MS).coerceIn(ONE_MINUTE_MS, FIVE_MINUTES_MS)
        }
        return when (sensorKind) {
            SensorSourceResolver.SENSOR_KIND_LIBRE2,
            SensorSourceResolver.SENSOR_KIND_LIBRE3,
            SensorSourceResolver.SENSOR_KIND_SIBIONICS,
            SensorSourceResolver.SENSOR_KIND_AIDEX -> ONE_MINUTE_MS
            else -> 0L
        }
    }

    /** [progress] from 0 at the reading to 1 at the next one; full once [late]. */
    data class State(val progress: Float, val late: Boolean)

    /**
     * Where the bar stands at [now] for the reading taken at [readingTime], or null when
     * it is not shown: no reading, or no interval. A [stale] pill (the reading too old to
     * be current, drawn without range colours) is late whatever the time says.
     */
    fun state(readingTime: Long, intervalMillis: Long, now: Long, stale: Boolean): State? {
        if (readingTime <= 0L || intervalMillis <= 0L) return null
        val elapsed = now - readingTime
        if (stale || elapsed >= intervalMillis + LATE_GRACE_MS) return State(1f, late = true)
        return State((elapsed.toFloat() / intervalMillis).coerceIn(0f, 1f), late = false)
    }

    /**
     * How long from [now] until the bar has to be drawn again: the next step, counted
     * from the reading, or the moment it turns late. Null once it is late (or not
     * shown): it then stays as it is until another reading.
     */
    fun nextStepDelay(readingTime: Long, intervalMillis: Long, now: Long): Long? {
        if (readingTime <= 0L || intervalMillis <= 0L) return null
        val lateAt = readingTime + intervalMillis + LATE_GRACE_MS
        if (now >= lateAt) return null
        val nextStep = readingTime + (Math.floorDiv(now - readingTime, STEP_MS) + 1) * STEP_MS
        return minOf(nextStep, lateAt) - now
    }

    /**
     * How far in from each end the bottom strip of [thickness] of a shape with
     * corners of [radius] begins: the corner's arc cuts it off before that. The bar
     * fills between the two, so it shows from the first step.
     */
    fun visibleInset(radius: Float, thickness: Float): Float {
        if (radius <= 0f || thickness >= radius) return 0f
        val rise = radius - thickness
        return radius - sqrt(radius * radius - rise * rise)
    }

    /** The side of the pill a bar runs along. */
    enum class BarSide { BOTTOM, LEFT, RIGHT }

    /**
     * The side a bar runs along: the bottom, except on an island beside a camera on the
     * left or right edge (landscape). The pill stands upright there, its bottom a short
     * side, so the bar runs along its long side facing the middle of the screen: the right
     * one beside a camera on the left, the left one beside a camera on the right.
     * [islandEdge] is the edge the island is docked to, null for the free pill.
     */
    fun barSide(islandEdge: CutoutEdge?): BarSide = when (islandEdge) {
        CutoutEdge.LEFT -> BarSide.RIGHT
        CutoutEdge.RIGHT -> BarSide.LEFT
        else -> BarSide.BOTTOM
    }

    /**
     * The centre line of a bar along [side] of a pill of [width] by [height], [depth] in
     * from that side, between [from] and [to] along it: from the left along the bottom,
     * and from the bottom up along a side, so it fills upward as a level does.
     */
    fun barLine(width: Float, height: Float, side: BarSide, depth: Float, from: Float, to: Float): Pair<Offset, Offset> =
        when (side) {
            BarSide.BOTTOM -> Offset(from, height - depth) to Offset(to, height - depth)
            BarSide.LEFT -> Offset(depth, height - from) to Offset(depth, height - to)
            BarSide.RIGHT -> Offset(width - depth, height - from) to Offset(width - depth, height - to)
        }

    /** Where along its side a bar's track and its fill run, as distances from where it starts. */
    data class BarSpan(val trackFrom: Float, val trackTo: Float, val fillFrom: Float, val fillTo: Float)

    /**
     * The thin bar, flush with a side of [length] of a pill with corners of [radius]: the
     * track the whole side (the pill's shape clips it to its corners), the fill from the
     * start to [progress] of the stretch the corners leave visible, so it shows from the
     * first step.
     */
    fun flatBarSpan(length: Float, radius: Float, thickness: Float, progress: Float): BarSpan {
        val inset = visibleInset(radius, thickness)
        return BarSpan(0f, length, 0f, inset + (length - 2f * inset).coerceAtLeast(0f) * progress)
    }

    /**
     * A bar with round ends, [thickness] thick and [margin] in from a side of [length] of
     * a pill with corners of [radius]: its ends centred as far in as the corners need for
     * them to lie whole inside the pill, the fill from the first end to [progress] of the
     * way to the other (a dot at 0).
     */
    fun roundBarSpan(length: Float, radius: Float, thickness: Float, margin: Float, progress: Float): BarSpan {
        val cap = thickness / 2f
        val start = capInset(radius, cap, margin + cap).coerceAtMost(length / 2f)
        val end = length - start
        return BarSpan(start, end, start, start + (end - start) * progress)
    }

    /**
     * How far in from the end of a side a round end of [capRadius], centred [depth] in from
     * that side, has to be centred to lie whole inside a pill with corners of [radius].
     */
    fun capInset(radius: Float, capRadius: Float, depth: Float): Float {
        // Its centre has to lie in the pill shrunk by the cap: corners of radius - capRadius
        // around the same centres.
        val inner = radius - capRadius
        if (inner <= 0f || depth >= radius) return capRadius
        val rise = radius - depth
        return radius - sqrt((inner * inner - rise * rise).coerceAtLeast(0f))
    }

    /** A piece of an outline: a line, or an arc of [Arc.oval] from [Arc.startAngle], [Arc.sweep] degrees clockwise. */
    sealed interface OutlinePiece {
        data class Line(val from: Offset, val to: Offset) : OutlinePiece
        data class Arc(val oval: Rect, val startAngle: Float, val sweep: Float) : OutlinePiece
    }

    /** Length of the outline of the rounded rectangle [rect] with corners of [radius]. */
    fun outlineLength(rect: Rect, radius: Float): Float {
        val r = outlineRadius(rect, radius)
        return 2f * (rect.width - 2f * r) + 2f * (rect.height - 2f * r) + 2f * PI.toFloat() * r
    }

    /**
     * The first [fraction] of the outline of the rounded rectangle [rect] with corners of
     * [radius], from the middle of its bottom edge, clockwise: left along the bottom, up the
     * left side, across the top, down the right side and back along the bottom.
     */
    fun outline(rect: Rect, radius: Float, fraction: Float): List<OutlinePiece> {
        val r = outlineRadius(rect, radius)
        val d = 2f * r
        val quarter = PI.toFloat() * r / 2f
        val l = rect.left
        val t = rect.top
        val rt = rect.right
        val b = rect.bottom
        val middle = (l + rt) / 2f
        val whole = listOf(
            OutlinePiece.Line(Offset(middle, b), Offset(l + r, b)) to middle - l - r,
            OutlinePiece.Arc(Rect(l, b - d, l + d, b), 90f, 90f) to quarter,
            OutlinePiece.Line(Offset(l, b - r), Offset(l, t + r)) to b - t - d,
            OutlinePiece.Arc(Rect(l, t, l + d, t + d), 180f, 90f) to quarter,
            OutlinePiece.Line(Offset(l + r, t), Offset(rt - r, t)) to rt - l - d,
            OutlinePiece.Arc(Rect(rt - d, t, rt, t + d), 270f, 90f) to quarter,
            OutlinePiece.Line(Offset(rt, t + r), Offset(rt, b - r)) to b - t - d,
            OutlinePiece.Arc(Rect(rt - d, b - d, rt, b), 0f, 90f) to quarter,
            OutlinePiece.Line(Offset(rt - r, b), Offset(middle, b)) to rt - r - middle,
        )
        var remaining = outlineLength(rect, radius) * fraction.coerceIn(0f, 1f)
        val pieces = ArrayList<OutlinePiece>(whole.size)
        for ((piece, length) in whole) {
            if (remaining <= 0f) break
            if (length <= 0f) continue
            if (remaining >= length) {
                pieces += piece
                remaining -= length
            } else {
                val part = remaining / length
                pieces += when (piece) {
                    is OutlinePiece.Line -> piece.copy(to = lerp(piece.from, piece.to, part))
                    is OutlinePiece.Arc -> piece.copy(sweep = piece.sweep * part)
                }
                break
            }
        }
        return pieces
    }

    private fun outlineRadius(rect: Rect, radius: Float): Float =
        radius.coerceIn(0f, minOf(rect.width, rect.height).coerceAtLeast(0f) / 2f)

    /** The ring's stroke, in dp, around a trend arrow of [arrowSize] dp: a tenth of it, at least 1.5 dp. */
    fun ringStroke(arrowSize: Float): Float = (arrowSize * RING_STROKE_FRACTION).coerceAtLeast(RING_MIN_STROKE_DP)

    /**
     * The side, in dp, of the slot for a trend arrow of [arrowSize] dp with the ring around
     * it: the arrow's box, a gap, then the ring. The arrow stays within about the circle its
     * box holds, so the ring clears it; the slot is only as much larger as the ring needs.
     */
    fun ringSlot(arrowSize: Float): Float = arrowSize * (1f + 2f * RING_GAP_FRACTION) + 2f * ringStroke(arrowSize)

    private const val RING_STROKE_FRACTION = 0.1f
    private const val RING_MIN_STROKE_DP = 1.5f
    private const val RING_GAP_FRACTION = 0.06f
}

/** Thickness of the thin bar, flush with its side. */
private val NextReadingBarThickness = 2.5.dp

/** Thickness of the bar with round ends, and how far in from its side it runs. */
private val RoundBarThickness = 4.dp
private val RoundBarMargin = 1.5.dp

/** Thickness of the outline, inside the pill's edge. */
private val OutlineThickness = 2.5.dp

/** Opacity of the track the indicator fills, against the text's colour. */
private const val TRACK_ALPHA = 0.14f

/** Opacity of the thin bar's fill, against the text's colour. */
private const val FLAT_FILL_ALPHA = 0.5f

/**
 * Where the pill draws its time to the next reading: [background] goes on the pill's
 * clipped background (the bars, the outline); [arrowRing] on a box of [arrowSlot] with the
 * trend arrow in its middle, when the ring is drawn (null otherwise: the arrow keeps its
 * own size).
 */
internal class NextReadingIndicator(
    val background: Modifier = Modifier,
    val arrowRing: Modifier = Modifier,
    val arrowSlot: Dp? = null,
) {
    companion object {
        val NONE = NextReadingIndicator()
    }
}

/**
 * The pill's time to its next reading, drawn in [style]: filling from the reading toward
 * the next one, full and in [lateColor] once that one is late. The thin bar fills in
 * [color], the text's; the other styles in [rangeColor], the value's range colour. A bar
 * runs along [side]; the ring goes around an arrow of [arrowSize] (null: no arrow). Nothing
 * when [enabled] is off, there is no reading, or the interval is not known.
 *
 * The one place it is drawn: see FloatingNextReadingStyle.
 *
 * Battery: nothing animates. The indicator is redrawn in steps of
 * [FloatingNextReading.STEP_MS] at most, and not at all once it is late. Each step waits for
 * a frame of the pill's recomposer first, whose clock FloatingGlucoseService pauses while
 * the screen is off, so with the screen off it stops at the next step and nothing runs. A
 * step only redraws the indicator: the time is read in its draw pass, so nothing recomposes.
 */
@Composable
internal fun nextReadingIndicator(
    enabled: Boolean,
    style: FloatingNextReadingStyle,
    side: FloatingNextReading.BarSide,
    readingTime: Long,
    intervalMillis: Long,
    stale: Boolean,
    color: Color,
    rangeColor: Color,
    lateColor: Color,
    cornerRadius: Dp,
    arrowSize: Dp?,
): NextReadingIndicator {
    val shown = enabled && readingTime > 0L && intervalMillis > 0L
    // A stale pill's indicator is late already: nothing to step.
    val now = rememberNextReadingClock(readingTime, intervalMillis, active = shown && !stale)
    if (!shown) return NextReadingIndicator.NONE
    val track = color.copy(alpha = TRACK_ALPHA)
    // Read in the draw passes only, so a step redraws and recomposes nothing.
    fun state() = FloatingNextReading.state(readingTime, intervalMillis, now.longValue, stale)
    return when (FloatingNextReadingStyle.drawn(style, hasArrow = arrowSize != null)) {
        FloatingNextReadingStyle.CURRENT -> NextReadingIndicator(
            background = Modifier.drawWithContent {
                drawContent()
                val state = state() ?: return@drawWithContent
                val fill = if (state.late) lateColor else color.copy(alpha = FLAT_FILL_ALPHA)
                drawFlatBar(state.progress, side, cornerRadius.toPx(), track, fill)
            }
        )
        FloatingNextReadingStyle.COLOURED_BAR -> NextReadingIndicator(
            background = Modifier.drawWithContent {
                drawContent()
                val state = state() ?: return@drawWithContent
                drawRoundBar(state.progress, side, cornerRadius.toPx(), track, if (state.late) lateColor else rangeColor)
            }
        )
        FloatingNextReadingStyle.OUTLINE -> NextReadingIndicator(
            background = Modifier.drawWithCache {
                val path = Path()
                onDrawWithContent {
                    drawContent()
                    val state = state() ?: return@onDrawWithContent
                    drawOutline(state.progress, cornerRadius.toPx(), path, track, if (state.late) lateColor else rangeColor)
                }
            }
        )
        FloatingNextReadingStyle.ARROW_RING -> {
            val arrow = arrowSize?.value ?: 0f
            val ringStroke = FloatingNextReading.ringStroke(arrow).dp
            NextReadingIndicator(
                arrowRing = Modifier.drawBehind {
                    val state = state() ?: return@drawBehind
                    drawArrowRing(state.progress, ringStroke.toPx(), track, if (state.late) lateColor else rangeColor)
                },
                arrowSlot = FloatingNextReading.ringSlot(arrow).dp,
            )
        }
    }
}

/**
 * The thin bar, flush with [side]: the pill's shape clips it to its rounded corners. See
 * FloatingNextReading.flatBarSpan.
 */
private fun DrawScope.drawFlatBar(progress: Float, side: FloatingNextReading.BarSide, cornerRadius: Float, track: Color, fill: Color) {
    val bottom = side == FloatingNextReading.BarSide.BOTTOM
    val across = if (bottom) size.height else size.width
    val length = if (bottom) size.width else size.height
    val thickness = NextReadingBarThickness.toPx().coerceAtMost(across / 2f)
    if (thickness <= 0f || length <= 0f) return
    val radius = minOf(cornerRadius, size.minDimension / 2f)
    val span = FloatingNextReading.flatBarSpan(length, radius, thickness, progress)
    drawBarSpan(side, thickness / 2f, thickness, span, track, fill, StrokeCap.Butt)
}

/**
 * The bar with round ends, a little in from [side], its ends where the corners leave room
 * for them. See FloatingNextReading.roundBarSpan.
 */
private fun DrawScope.drawRoundBar(progress: Float, side: FloatingNextReading.BarSide, cornerRadius: Float, track: Color, fill: Color) {
    val bottom = side == FloatingNextReading.BarSide.BOTTOM
    val across = if (bottom) size.height else size.width
    val length = if (bottom) size.width else size.height
    val margin = RoundBarMargin.toPx()
    val thickness = RoundBarThickness.toPx().coerceAtMost(across / 2f - margin)
    if (thickness <= 0f || length <= 0f) return
    val radius = minOf(cornerRadius, size.minDimension / 2f)
    val span = FloatingNextReading.roundBarSpan(length, radius, thickness, margin, progress)
    drawBarSpan(side, margin + thickness / 2f, thickness, span, track, fill, StrokeCap.Round)
}

private fun DrawScope.drawBarSpan(
    side: FloatingNextReading.BarSide,
    depth: Float,
    thickness: Float,
    span: FloatingNextReading.BarSpan,
    track: Color,
    fill: Color,
    cap: StrokeCap,
) {
    val (trackFrom, trackTo) = FloatingNextReading.barLine(size.width, size.height, side, depth, span.trackFrom, span.trackTo)
    drawLine(track, trackFrom, trackTo, strokeWidth = thickness, cap = cap)
    val (fillFrom, fillTo) = FloatingNextReading.barLine(size.width, size.height, side, depth, span.fillFrom, span.fillTo)
    if (cap == StrokeCap.Round && span.fillTo - span.fillFrom < 1f) {
        // Its first end alone: a dot.
        drawCircle(fill, thickness / 2f, fillFrom)
    } else if (span.fillTo > span.fillFrom) {
        drawLine(fill, fillFrom, fillTo, strokeWidth = thickness, cap = cap)
    }
}

/**
 * The outline: a stroke just inside the pill's edge, its corners concentric with the
 * pill's, so its clip leaves it whole; the faint track all round, the fill from the middle
 * of the bottom edge. See FloatingNextReading.outline.
 */
private fun DrawScope.drawOutline(progress: Float, cornerRadius: Float, path: Path, track: Color, fill: Color) {
    val stroke = OutlineThickness.toPx().coerceAtMost(size.minDimension / 2f)
    if (stroke <= 0f) return
    val half = stroke / 2f
    val rect = Rect(half, half, size.width - half, size.height - half)
    val radius = (minOf(cornerRadius, size.minDimension / 2f) - half).coerceAtLeast(0f)
    drawRoundRect(track, rect.topLeft, rect.size, CornerRadius(radius), style = Stroke(stroke))
    path.reset()
    path.moveTo(rect.center.x, rect.bottom)
    for (piece in FloatingNextReading.outline(rect, radius, progress)) {
        when (piece) {
            is FloatingNextReading.OutlinePiece.Line -> path.lineTo(piece.to.x, piece.to.y)
            is FloatingNextReading.OutlinePiece.Arc -> path.arcTo(piece.oval, piece.startAngle, piece.sweep, forceMoveTo = false)
        }
    }
    drawPath(path, fill, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/**
 * The ring in the arrow's slot: the faint track all round, the fill clockwise from the
 * top, [stroke] thick along the slot's edge.
 */
private fun DrawScope.drawArrowRing(progress: Float, stroke: Float, track: Color, fill: Color) {
    val width = stroke.coerceAtMost(size.minDimension / 2f)
    val radius = size.minDimension / 2f - width / 2f
    if (width <= 0f || radius <= 0f) return
    drawCircle(track, radius, center, style = Stroke(width))
    if (progress <= 0f) return
    drawArc(
        color = fill,
        startAngle = -90f,
        sweepAngle = 360f * progress,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(2f * radius, 2f * radius),
        style = Stroke(width, cap = StrokeCap.Round),
    )
}

/**
 * The wall clock, set at each of the indicator's steps while [active]; see nextReadingIndicator.
 */
@Composable
private fun rememberNextReadingClock(readingTime: Long, intervalMillis: Long, active: Boolean): LongState {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(readingTime, intervalMillis, active) {
        if (!active) return@LaunchedEffect
        while (true) {
            // Suspends while the pill's frames are paused (the screen is off).
            withFrameMillis { }
            val time = System.currentTimeMillis()
            now.longValue = time
            delay(FloatingNextReading.nextStepDelay(readingTime, intervalMillis, time) ?: break)
        }
    }
    return now
}
