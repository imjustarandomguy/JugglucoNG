package tk.glucodata.alerts

internal enum class PersistentHighAction {
    /** No episode, or the alert cannot run: timer cleared, a running alarm resolved. */
    RESET,

    /** Episode running (or nothing to do yet); deliver nothing now. */
    WAIT,

    /** Falling: running retries stop, the timer keeps counting. */
    HOLD,

    /** The duration is reached between readings and the current reading is still high. */
    FIRE
}

/** [startedAtMs] is the reading time of the episode's first high reading, 0 with no episode. */
internal data class PersistentHighDecision(
    val action: PersistentHighAction,
    val startedAtMs: Long,
    val reason: String
)

/**
 * Decision rule behind PERSISTENT_HIGH: "you have been above the threshold for this long".
 *
 * - The timer starts at the first reading above the threshold, at that reading's time,
 *   and a reading at or below the threshold resets it.
 * - It fires once the time between that reading and the current one reaches the
 *   duration. Time is measured between readings, never on the wall clock: the alert
 *   runtime also evaluates every 15 s, and on that tick the reading is the same one, so
 *   the tick cannot fire before the reading that completes the duration arrives.
 *
 *   It used to fire on `now - start`, so the tick fired as soon as the clock passed the
 *   duration, a few seconds before the reading that completes it. Phone and watch read
 *   the same G7 and each ticks on its own phase (and not at all while its CPU sleeps),
 *   so they rang up to a tick apart, the watch first when its tick came first. Now both
 *   ring on that reading, a few seconds apart at most: the time between the two devices
 *   receiving it. At the G7's 5-minute cadence a 60-minute duration fires on the 13th
 *   high reading in a row; a duration that is not a whole number of readings fires on
 *   the first reading past it.
 * - Falling at least [AlertConfig.fallRateSuppress] holds the alarm (see
 *   [FallSuppressionPolicy.fallingSuppresses]) without resetting the timer: if the value
 *   stalls above the threshold again, the high phase was continuous and the alarm speaks
 *   at once instead of waiting out a fresh duration.
 * - Disabled, no threshold or duration, no value, or outside the time window: reset.
 * - Snoozed: the timer keeps counting, nothing is delivered.
 */
internal object PersistentHighPolicy {

    /**
     * Slack on the between-readings duration. Readings come at a nominal cadence with a
     * few seconds of jitter; without it, twelve G7 intervals of 4:59 would fall twelve
     * seconds short of an hour and the alarm would wait for one more reading. Small
     * enough that one-minute sensors still need the full count.
     */
    const val READING_TIME_TOLERANCE_MS = 30_000L

    /**
     * @param startedAtMs the running episode's start (a reading time), 0 for none.
     * @param value the current reading in display units, null when none is known.
     * @param readingTimeMs time of that reading.
     * @param rate mg/dl per minute, negative = falling.
     */
    fun decide(
        startedAtMs: Long,
        config: AlertConfig,
        activeNow: Boolean,
        value: Float?,
        readingTimeMs: Long,
        rate: Float,
        snoozed: Boolean
    ): PersistentHighDecision {
        val threshold = config.threshold
        val durationMs = (config.durationMinutes ?: 0) * 60_000L
        if (!config.enabled || threshold == null || durationMs <= 0L || value == null || !value.isFinite()) {
            return reset("persistent-high-disabled")
        }
        if (value <= threshold) {
            return reset("persistent-high-cleared")
        }
        val started = if (startedAtMs == 0L) readingTimeMs else startedAtMs
        if (!activeNow) {
            return reset("persistent-high-time-inactive")
        }
        if (FallSuppressionPolicy.fallingSuppresses(rate, config.fallRateSuppress)) {
            return PersistentHighDecision(PersistentHighAction.HOLD, started, "persistent-high-falling")
        }
        if (snoozed) {
            return PersistentHighDecision(PersistentHighAction.WAIT, started, "persistent-high-snoozed")
        }
        if (readingTimeMs - started + READING_TIME_TOLERANCE_MS < durationMs) {
            return PersistentHighDecision(PersistentHighAction.WAIT, started, "persistent-high-timing")
        }
        return PersistentHighDecision(PersistentHighAction.FIRE, started, "persistent-high-due")
    }

    private fun reset(reason: String) = PersistentHighDecision(PersistentHighAction.RESET, 0L, reason)
}
