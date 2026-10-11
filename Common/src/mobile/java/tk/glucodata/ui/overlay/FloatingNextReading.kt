package tk.glucodata.ui.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The time to the pill's next reading: how far it is from its reading toward the next
 * one, due when CurrentReadingHub expects it, and whether that one is late. Pure, see
 * the tests.
 */
internal object FloatingNextReading {
    /** The ring moves in steps this long, at most: no animation. */
    const val STEP_MS = 5_000L

    /** [progress] from 0 at the reading to 1 at the next one; full once [late]. */
    data class State(val progress: Float, val late: Boolean)

    /**
     * Where the ring stands at [now] for the reading taken at [readingTime], the next one
     * due at [nextExpectedAt], or null when it is not shown: no reading. It is [late] when
     * the hub says the reading is stale, in the publish that dims the value: the ring
     * keeps no deadline of its own, so it can never turn late at another moment than the
     * other surfaces go stale.
     */
    fun state(readingTime: Long, nextExpectedAt: Long, now: Long, late: Boolean): State? {
        if (readingTime <= 0L || nextExpectedAt <= readingTime) return null
        if (late) return State(1f, late = true)
        val progress = (now - readingTime).toFloat() / (nextExpectedAt - readingTime)
        return State(progress.coerceIn(0f, 1f), late = false)
    }

    /**
     * How long from [now] until the ring has to be drawn again: the next step, counted
     * from the reading, or the moment the next one is due. Null from then on (or when it
     * is not shown): it stays full until the hub's next publish, a new reading or the
     * stale moment.
     */
    fun nextStepDelay(readingTime: Long, nextExpectedAt: Long, now: Long): Long? {
        if (readingTime <= 0L || nextExpectedAt <= readingTime || now >= nextExpectedAt) return null
        val nextStep = readingTime + (Math.floorDiv(now - readingTime, STEP_MS) + 1) * STEP_MS
        return minOf(nextStep, nextExpectedAt) - now
    }

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

/** Opacity of the track the ring fills, against the text's colour. */
private const val TRACK_ALPHA = 0.14f

/**
 * The pill's time to its next reading as a ring around the trend arrow: [arrowRing] goes
 * on a box of [arrowSlot] with the arrow in its middle. [NONE] when it is not drawn: the
 * arrow keeps its own size.
 */
internal class NextReadingIndicator(
    val arrowRing: Modifier = Modifier,
    val arrowSlot: Dp? = null,
) {
    companion object {
        val NONE = NextReadingIndicator()
    }
}

/**
 * The pill's time to its next reading, as a ring around the trend arrow's place, of
 * [arrowSize]: filling clockwise from the top, from the reading toward the next one due
 * at [nextExpectedAt], full and in [lateColor] once the reading is [late]. It fills in
 * [rangeColor], the value's range colour, over a faint track in [color], the text's.
 * Nothing when [enabled] is off or there is no reading.
 *
 * Battery: nothing animates. The ring is redrawn in steps of [FloatingNextReading.STEP_MS]
 * at most, until the next reading is due, and then not at all: it turns late with the
 * hub's publish, not on a clock. Each step waits for a frame of the pill's recomposer
 * first, whose clock FloatingGlucoseService pauses while the screen is off, so with the
 * screen off it stops at the next step and nothing runs. A step only redraws the ring:
 * the time is read in its draw pass, so nothing recomposes.
 */
@Composable
internal fun nextReadingIndicator(
    enabled: Boolean,
    readingTime: Long,
    nextExpectedAt: Long,
    late: Boolean,
    color: Color,
    rangeColor: Color,
    lateColor: Color,
    arrowSize: Dp,
): NextReadingIndicator {
    val shown = enabled && readingTime > 0L && nextExpectedAt > readingTime
    // A late ring is full already: nothing to step.
    val now = rememberNextReadingClock(readingTime, nextExpectedAt, active = shown && !late)
    if (!shown) return NextReadingIndicator.NONE
    val track = color.copy(alpha = TRACK_ALPHA)
    val ringStroke = FloatingNextReading.ringStroke(arrowSize.value).dp
    return NextReadingIndicator(
        // Read in the draw pass only, so a step redraws and recomposes nothing.
        arrowRing = Modifier.drawBehind {
            val state = FloatingNextReading.state(readingTime, nextExpectedAt, now.longValue, late)
                ?: return@drawBehind
            drawArrowRing(state.progress, ringStroke.toPx(), track, if (state.late) lateColor else rangeColor)
        },
        arrowSlot = FloatingNextReading.ringSlot(arrowSize.value).dp,
    )
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
 * The wall clock, set at each of the ring's steps while [active]; see nextReadingIndicator.
 */
@Composable
private fun rememberNextReadingClock(readingTime: Long, nextExpectedAt: Long, active: Boolean): LongState {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(readingTime, nextExpectedAt, active) {
        if (!active) return@LaunchedEffect
        while (true) {
            // Suspends while the pill's frames are paused (the screen is off).
            withFrameMillis { }
            val time = System.currentTimeMillis()
            now.longValue = time
            delay(FloatingNextReading.nextStepDelay(readingTime, nextExpectedAt, time) ?: break)
        }
    }
    return now
}
