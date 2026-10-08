package tk.glucodata.ui

/**
 * How far the chart's visible window may travel. Every move of the window (pan,
 * fling, preview scrub, zoom, range change, auto-scroll, date jump) goes through
 * [clampCenter], so no path can leave the window over empty time.
 *
 * - The right edge stops [FUTURE_MARGIN_MS] past now, or, while the chart shows a
 *   prediction, the prediction horizon past now.
 * - The left edge stops [PAST_MARGIN_MS] before the earliest stored reading.
 * - When the data is shorter than the window, both cannot hold, and the window
 *   is pinned to the right bound.
 *
 * "Now" is the later of the clock and the latest reading, so a reading stamped a
 * little ahead of the clock stays reachable.
 */
internal data class ChartViewportLimits(
    val latestDataMs: Long,
    val earliestDataMs: Long,
    /** The prediction horizon while a prediction is drawn, else null. */
    val predictionHorizonMs: Long?
) {
    fun rightEdgeLimit(nowMs: Long): Long {
        val future = predictionHorizonMs?.takeIf { it > 0L } ?: FUTURE_MARGIN_MS
        return maxOf(nowMs, latestDataMs) + future
    }

    /** Null without data: there is nothing to stop at. */
    val leftEdgeLimit: Long?
        get() = earliestDataMs.takeIf { it > 0L }?.minus(PAST_MARGIN_MS)

    /** The furthest right the window's center may sit for [durationMs]. */
    fun maxCenter(durationMs: Long, nowMs: Long): Long = rightEdgeLimit(nowMs) - durationMs / 2L

    fun clampCenter(centerMs: Long, durationMs: Long, nowMs: Long): Long {
        val maxCenter = maxCenter(durationMs, nowMs)
        val minCenter = leftEdgeLimit?.plus(durationMs / 2L) ?: return centerMs.coerceAtMost(maxCenter)
        return if (minCenter > maxCenter) maxCenter else centerMs.coerceIn(minCenter, maxCenter)
    }

    companion object {
        const val FUTURE_MARGIN_MS = 10L * 60L * 1000L
        const val PAST_MARGIN_MS = 5L * 60L * 1000L
    }
}
