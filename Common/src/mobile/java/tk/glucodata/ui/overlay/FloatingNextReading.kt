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
import tk.glucodata.SensorSourceResolver

/**
 * The time to the pill's next reading: how far it is from its reading toward the next
 * one the sensor is expected to send, and whether that one is late. Pure, see the tests.
 */
internal object FloatingNextReading {
    const val ONE_MINUTE_MS = 60_000L
    const val FIVE_MINUTES_MS = 5L * ONE_MINUTE_MS

    /** How long past its expected time the next reading may come before it counts as late. */
    const val LATE_GRACE_MS = 30_000L

    /** The ring moves in steps this long, at most: no animation. */
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
     * Where the ring stands at [now] for the reading taken at [readingTime], or null when
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
     * How long from [now] until the ring has to be drawn again: the next step, counted
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
 * The pill's time to its next reading, as a ring around the trend arrow of [arrowSize]:
 * filling clockwise from the top, from the reading toward the next one, full and in
 * [lateColor] once that one is late. It fills in [rangeColor], the value's range colour,
 * over a faint track in [color], the text's. Nothing when [enabled] is off, there is no
 * reading, the interval is not known, or there is no arrow ([arrowSize] null) to put the
 * ring around.
 *
 * Battery: nothing animates. The ring is redrawn in steps of
 * [FloatingNextReading.STEP_MS] at most, and not at all once it is late. Each step waits for
 * a frame of the pill's recomposer first, whose clock FloatingGlucoseService pauses while
 * the screen is off, so with the screen off it stops at the next step and nothing runs. A
 * step only redraws the ring: the time is read in its draw pass, so nothing recomposes.
 */
@Composable
internal fun nextReadingIndicator(
    enabled: Boolean,
    readingTime: Long,
    intervalMillis: Long,
    stale: Boolean,
    color: Color,
    rangeColor: Color,
    lateColor: Color,
    arrowSize: Dp?,
): NextReadingIndicator {
    val shown = enabled && arrowSize != null && readingTime > 0L && intervalMillis > 0L
    // A stale pill's ring is late already: nothing to step.
    val now = rememberNextReadingClock(readingTime, intervalMillis, active = shown && !stale)
    if (!shown || arrowSize == null) return NextReadingIndicator.NONE
    val track = color.copy(alpha = TRACK_ALPHA)
    val ringStroke = FloatingNextReading.ringStroke(arrowSize.value).dp
    return NextReadingIndicator(
        // Read in the draw pass only, so a step redraws and recomposes nothing.
        arrowRing = Modifier.drawBehind {
            val state = FloatingNextReading.state(readingTime, intervalMillis, now.longValue, stale)
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
