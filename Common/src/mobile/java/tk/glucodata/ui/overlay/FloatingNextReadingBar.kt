package tk.glucodata.ui.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tk.glucodata.SensorSourceResolver
import kotlin.math.sqrt

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
}

/** Height of the bar along the pill's bottom edge. */
private val NextReadingBarThickness = 2.5.dp

/**
 * The pill's time to its next reading, as a modifier for the pill's clipped background:
 * a thin bar along its bottom edge, filling from the reading toward the next one, full
 * and in [lateColor] once that one is late. [Modifier] (nothing) when [enabled] is off,
 * there is no reading, or the interval is not known.
 *
 * The one place it is drawn: another style (a border around the pill) would go here.
 *
 * Battery: nothing animates. The bar is redrawn in steps of [FloatingNextReading.STEP_MS]
 * at most, and not at all once it is late. Each step waits for a frame of the pill's
 * recomposer first, whose clock FloatingGlucoseService pauses while the screen is off,
 * so with the screen off it stops at the next step and nothing runs. A step only
 * redraws the bar: the time is read in its draw pass, so nothing recomposes.
 */
@Composable
internal fun nextReadingIndicator(
    enabled: Boolean,
    readingTime: Long,
    intervalMillis: Long,
    stale: Boolean,
    color: Color,
    lateColor: Color,
    cornerRadius: Dp,
): Modifier {
    val shown = enabled && readingTime > 0L && intervalMillis > 0L
    // A stale pill's bar is late already: nothing to step.
    val now = rememberNextReadingClock(readingTime, intervalMillis, active = shown && !stale)
    if (!shown) return Modifier
    return Modifier.drawWithContent {
        drawContent()
        val state = FloatingNextReading.state(readingTime, intervalMillis, now.longValue, stale)
            ?: return@drawWithContent
        val thickness = NextReadingBarThickness.toPx().coerceAtMost(size.height / 2f)
        if (thickness <= 0f || size.width <= 0f) return@drawWithContent
        val radius = minOf(cornerRadius.toPx(), size.minDimension / 2f)
        val inset = FloatingNextReading.visibleInset(radius, thickness)
        val top = size.height - thickness
        // The pill's shape clips both to its rounded bottom edge.
        drawRect(color.copy(alpha = 0.14f), Offset(0f, top), Size(size.width, thickness))
        val fillEnd = inset + (size.width - 2f * inset).coerceAtLeast(0f) * state.progress
        if (fillEnd > 0f) {
            val fill = if (state.late) lateColor else color.copy(alpha = 0.5f)
            drawRect(fill, Offset(0f, top), Size(fillEnd, thickness))
        }
    }
}

/**
 * The wall clock, set at each of the bar's steps while [active]; see nextReadingIndicator.
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
