package tk.glucodata.alerts

/**
 * Where a PERSISTENT_LOW episode stands between readings.
 *
 * [startedAtMs] is the reading time the count runs from (0 = none). While
 * [heldByVeryLow], VERY_LOW's episode owns the low and the count waits; it
 * starts over at the reading where that episode ends.
 */
internal data class PersistentLowState(
    val startedAtMs: Long = 0L,
    val heldByVeryLow: Boolean = false
) {
    /** An episode is running: counting, or held by VERY_LOW. */
    val running: Boolean get() = startedAtMs != 0L || heldByVeryLow
}

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
 *   reading's time. When an episode starts, that reading is looked up in the
 *   stored readings ([startFromHistory]): walking back from the current
 *   reading while the value stayed below threshold + margin, it is the first
 *   reading below the threshold after the last one at or above that line.
 *   Phone and watch store the same readings, so both count from the same
 *   start, whenever each began to look: a setting received while asleep, a
 *   missed live reading or a restart in the middle of a low no longer moves
 *   it. In consequence, switching the alarm on, changing it, or its time
 *   window opening during a low that has already lasted the duration rings at
 *   the next check instead of waiting out a fresh duration. A hole of more
 *   than [EpisodeHistory.MAX_GAP_MS] between stored readings ends the stretch,
 *   and the walk looks back [EpisodeHistory.MAX_LOOKBACK_MS] at most.
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
 * - VERY_LOW holds it only while VERY_LOW's own episode is active (its
 *   condition holds, or it fired and was not reset): the urgent alarm owns
 *   that low. When that episode ends, the count starts over at that reading
 *   (or at the next one below the threshold), so a value left between the two
 *   lines after a dismissed VERY_LOW rings once it has lasted the duration.
 *   The hold itself is live only. The stored-start walk replays the restart
 *   with VERY_LOW's current lines (entry below its threshold, exit at
 *   threshold + its margin), so a restart finds the same start; at worst that
 *   delays this alarm by the duration, it never silences it.
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
     * @param historyStartMs the start [startFromHistory] finds for the low at this
     *   reading, 0 for none. Called only when an episode starts at this reading,
     *   so a running episode never reads the history; a start that is not
     *   earlier than this reading is ignored.
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
        snoozed: Boolean,
        historyStartMs: () -> Long = { 0L }
    ): PersistentLowDecision {
        val threshold = thresholdOf(config)
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

        if (value >= recoveredAt(threshold, config, isMmol)) {
            return reset("persistent-low-cleared")
        }

        if (veryLowActive) {
            return PersistentLowDecision(PersistentLowAction.HOLD, state.copy(heldByVeryLow = true), "persistent-low-very-low")
        }
        val below = value < threshold
        var next = state
        if (next.heldByVeryLow) {
            // VERY_LOW's episode ended at this reading: the count starts over here.
            next = PersistentLowState(startedAtMs = if (below) readingTimeMs else 0L)
        }
        if (below && next.startedAtMs == 0L) {
            next = next.copy(startedAtMs = historyStartMs().takeIf { it in 1 until readingTimeMs } ?: readingTimeMs)
        }
        if (next.startedAtMs == 0L) {
            // In the band above the threshold, with no reading below it yet.
            return PersistentLowDecision(PersistentLowAction.WAIT, next, "persistent-low-not-low")
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

    /**
     * The start of the low running at the current reading, the last of
     * [readings] (oldest first, display units), by the lines [decide] uses: the
     * first reading below the threshold after the last one at or above the
     * recovery line. A reading in the band between them neither starts the
     * episode nor ends it. Gaps and lookback are [EpisodeHistory.startOf]'s.
     *
     * With [veryLow] (VERY_LOW's config, passed only while it can hold now), the
     * stretch is replayed as [decide] lives it: a VERY_LOW episode in it (entry
     * below its threshold, exit at threshold + its rearm margin) restarts the
     * count at the reading where it ended, or at the next one below.
     *
     * Null without a threshold, when the stretch has no reading below it, or
     * when VERY_LOW's episode still runs at the current reading.
     */
    fun startFromHistory(
        readings: List<StoredReading>,
        config: AlertConfig,
        isMmol: Boolean,
        veryLow: AlertConfig? = null
    ): EpisodeStart? {
        val threshold = thresholdOf(config) ?: return null
        val recoveredAt = recoveredAt(threshold, config, isMmol)
        val start = EpisodeHistory.startOf(readings) { value ->
            when {
                value >= recoveredAt -> EpisodeHistory.Kind.END
                value < threshold -> EpisodeHistory.Kind.START
                else -> EpisodeHistory.Kind.BAND
            }
        } ?: return null
        val veryLowConfig = veryLow ?: return start
        val veryLowEntry = thresholdOf(veryLowConfig) ?: return start
        val veryLowExit = veryLowEntry + (veryLowConfig.rearmMargin
            ?: StandardGlucoseAlertEvaluator.defaultThresholdRearmMargin(isMmol)).coerceAtLeast(0f)
        // From the first reading below: VERY_LOW cannot be entered before it.
        var startedAtMs = 0L
        var inVeryLow = false
        readings.forEach { reading ->
            if (reading.timeMs < start.startedAtMs || !reading.value.isFinite()) return@forEach
            inVeryLow = reading.value < if (inVeryLow) veryLowExit else veryLowEntry
            if (inVeryLow) {
                startedAtMs = 0L
            } else if (startedAtMs == 0L && reading.value < threshold) {
                startedAtMs = reading.timeMs
            }
        }
        return if (startedAtMs > 0L) start.copy(startedAtMs = startedAtMs) else null
    }

    private fun thresholdOf(config: AlertConfig): Float? = config.threshold?.takeIf { it.isFinite() && it > 0f }

    /** At or above this the low is over and the timer resets: threshold + margin. */
    private fun recoveredAt(threshold: Float, config: AlertConfig, isMmol: Boolean): Float {
        val margin = (config.rearmMargin ?: defaultRearmMargin(isMmol)).takeIf { it.isFinite() }
            ?.coerceAtLeast(0f) ?: defaultRearmMargin(isMmol)
        return if (margin > 0f) threshold + margin - RECOVERY_EPSILON else threshold
    }

    private fun reset(reason: String) =
        PersistentLowDecision(PersistentLowAction.RESET, PersistentLowState(), reason)
}
