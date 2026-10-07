package tk.glucodata.alerts

/**
 * Where a PERSISTENT_LOW episode stands between readings.
 *
 * [startedAtMs] is the reading time of the first reading below the threshold
 * (0 = no episode). [veryLowSeen] latches once VERY_LOW was active during the
 * episode, so the persistent low never rings on top of it.
 */
internal data class PersistentLowState(
    val startedAtMs: Long = 0L,
    val veryLowSeen: Boolean = false
)

internal enum class PersistentLowAction {
    /** No episode, or the alert cannot run: timer cleared, a running alarm resolved. */
    RESET,

    /** Episode running (or nothing to do yet); deliver nothing now. */
    WAIT,

    /** Episode running but held: running retries stop, the timer keeps counting. */
    HOLD,

    /** The duration is reached and the current reading is still below the threshold. */
    FIRE
}

internal data class PersistentLowDecision(
    val action: PersistentLowAction,
    val state: PersistentLowState,
    val reason: String
)

/**
 * Decision rule behind PERSISTENT_LOW, the mirror of the persistent high:
 * "you have been low for this long". Its job is to ride out the compression
 * dips that make a plain LOW ring at night, while a low that stays low still
 * rings after the configured duration.
 *
 * - The timer starts at the first reading below the threshold, at that
 *   reading's time.
 * - It fires once the time between that reading and the current one reaches
 *   the duration and the current reading is still below the threshold. Time is
 *   measured between readings, not on the wall clock, so at the 5-minute G7
 *   cadence 10 min means 3 low readings in a row and 15 min means 4: the
 *   periodic check never fires on a reading that is already 5 minutes old.
 * - Only a recovery to threshold + margin resets it. A reading between the
 *   threshold and that line neither resets the timer nor fires, so a real low
 *   hovering at the line (3.8, 4.0, 3.8) keeps its count.
 * - Disabled, no threshold or duration, or outside the time window: reset.
 *   A missing value does not reset: a lost sample is not a recovery.
 * - VERY_LOW active at any point in the episode holds it for the rest of the
 *   episode: the urgent alarm owns that low.
 * - Optional "hold while rising" (the mirror of persistent high's fall rule):
 *   while the value rises at least [AlertConfig.riseRateSuppress] mg/dl per
 *   minute the alarm is held, but the timer is not reset, so a rise that stalls
 *   below the line speaks at once instead of waiting out a fresh duration.
 * - Snoozed: the timer keeps counting, nothing is delivered.
 */
internal object PersistentLowPolicy {

    /**
     * Slack on the between-readings duration. Readings come at a nominal
     * cadence with a few seconds of jitter; without it, three G7 readings
     * 4:59 apart would fall one second short of 10 minutes. Small enough that
     * one-minute sensors still need the full count.
     */
    const val READING_TIME_TOLERANCE_MS = 30_000L

    /**
     * Float slack on the recovery line: 3.9f + 0.2f lands just above 4.1f, so
     * without it a reading of exactly 4.1 mmol/L would not count as recovered.
     */
    private const val RECOVERY_EPSILON = 0.001f

    fun defaultRearmMargin(isMmol: Boolean): Float =
        if (isMmol) AlertDefaults.PERSISTENT_LOW_REARM_MARGIN_MMOL else AlertDefaults.PERSISTENT_LOW_REARM_MARGIN_MGDL

    /** Rising at least [riseRateSuppress] mg/dl per minute (magnitude); null/0 = off. */
    fun risingHolds(rate: Float, riseRateSuppress: Float?): Boolean {
        val limit = riseRateSuppress?.takeIf { it.isFinite() && it > 0f } ?: return false
        return rate.isFinite() && rate >= limit
    }

    /**
     * @param value the current reading in display units, null when none is known.
     * @param readingTimeMs time of that reading.
     * @param rate mg/dl per minute, positive = rising.
     * @param veryLowActive VERY_LOW's episode is active (its condition holds, or it fired and was not yet reset).
     */
    fun decide(
        state: PersistentLowState,
        config: AlertConfig,
        isMmol: Boolean,
        activeNow: Boolean,
        value: Float?,
        readingTimeMs: Long,
        rate: Float,
        veryLowActive: Boolean,
        snoozed: Boolean
    ): PersistentLowDecision {
        val threshold = config.threshold?.takeIf { it.isFinite() && it > 0f }
        val durationMs = (config.durationMinutes ?: 0).coerceAtLeast(0) * 60_000L
        if (!config.enabled || threshold == null || durationMs <= 0L) {
            return reset("persistent-low-disabled")
        }
        if (!activeNow) {
            return reset("persistent-low-time-inactive")
        }
        if (value == null || !value.isFinite()) {
            return PersistentLowDecision(PersistentLowAction.WAIT, state, "persistent-low-no-value")
        }

        val margin = (config.rearmMargin ?: defaultRearmMargin(isMmol)).takeIf { it.isFinite() }
            ?.coerceAtLeast(0f) ?: defaultRearmMargin(isMmol)
        val recoveredAt = if (margin > 0f) threshold + margin - RECOVERY_EPSILON else threshold
        if (value >= recoveredAt) {
            return reset("persistent-low-cleared")
        }

        val below = value < threshold
        var next = state
        if (below && next.startedAtMs == 0L) {
            next = next.copy(startedAtMs = readingTimeMs)
        }
        if (next.startedAtMs == 0L) {
            // In the band above the threshold without ever having gone below it.
            return PersistentLowDecision(PersistentLowAction.WAIT, next, "persistent-low-not-low")
        }
        if (veryLowActive && !next.veryLowSeen) {
            next = next.copy(veryLowSeen = true)
        }
        if (next.veryLowSeen) {
            return PersistentLowDecision(PersistentLowAction.HOLD, next, "persistent-low-very-low")
        }
        if (risingHolds(rate, config.riseRateSuppress)) {
            return PersistentLowDecision(PersistentLowAction.HOLD, next, "persistent-low-rising")
        }
        if (snoozed) {
            return PersistentLowDecision(PersistentLowAction.WAIT, next, "persistent-low-snoozed")
        }
        if (!below) {
            return PersistentLowDecision(PersistentLowAction.WAIT, next, "persistent-low-in-band")
        }
        if (readingTimeMs - next.startedAtMs + READING_TIME_TOLERANCE_MS < durationMs) {
            return PersistentLowDecision(PersistentLowAction.WAIT, next, "persistent-low-timing")
        }
        return PersistentLowDecision(PersistentLowAction.FIRE, next, "persistent-low-due")
    }

    private fun reset(reason: String) =
        PersistentLowDecision(PersistentLowAction.RESET, PersistentLowState(), reason)
}
