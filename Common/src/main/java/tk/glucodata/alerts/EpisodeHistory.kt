package tk.glucodata.alerts

import tk.glucodata.CurrentDisplaySource

/** One reading of the sensor the alerts evaluate: its time, and its value in display units. */
internal data class StoredReading(val timeMs: Long, val value: Float)

/** Where an episode began ([startedAtMs], a reading time), and how many readings the walk took to find it. */
internal data class EpisodeStart(val startedAtMs: Long, val readingsWalked: Int)

/**
 * The start of a timed episode ("low for 15 minutes", "high for an hour") taken
 * from the stored readings rather than from the moment this device began to
 * look.
 *
 * Phone and watch can read the same sensor and evaluate every alert on their
 * own. A start kept in memory is the first reading each of them happened to
 * evaluate: a settings change that reached the watch while it slept, a reading
 * one of them missed or a restart in the middle of a low each gave the two a
 * different start, so the duration ran out at different times, or on one of
 * them only. Both store the same readings, so both find the same start here.
 */
internal object EpisodeHistory {

    /**
     * Longest hole between two stored readings that still counts as one
     * stretch: three missed readings in a row at the 5-minute G7 cadence, and
     * more than the 15 minutes between Libre history records. Over a longer
     * hole the value may have recovered unseen, so the episode does not reach
     * back across it.
     */
    const val MAX_GAP_MS = 20 * 60_000L

    /**
     * How far back the walk goes from the current reading. Longer than the
     * longest duration the settings offer (2 hours), so a stretch that reaches
     * it is due either way, and the work stays bounded.
     */
    const val MAX_LOOKBACK_MS = 3 * 60 * 60_000L

    /** What one reading means for an episode still running at the current reading. */
    enum class Kind {
        /** The episode's condition holds: it runs, and may have started here. */
        START,

        /** Between the threshold and the reset line: it runs, but does not start here. */
        BAND,

        /** The reset condition: the episode cannot reach back past this reading. */
        END
    }

    /**
     * Walks [readings] (oldest first, the last one the current reading) back
     * from the current one while [kind] says the episode runs, and returns its
     * oldest [Kind.START] reading. The walk stops at an [Kind.END] reading, at a
     * hole longer than [MAX_GAP_MS] and at [MAX_LOOKBACK_MS]. A value that is
     * not finite is skipped, like a missing reading: it is no recovery.
     *
     * Null when no reading of the stretch is a [Kind.START], the current one
     * included.
     */
    fun startOf(readings: List<StoredReading>, kind: (Float) -> Kind): EpisodeStart? {
        val current = readings.lastOrNull() ?: return null
        var startedAtMs = 0L
        var walked = 0
        var newerTimeMs = current.timeMs
        for (index in readings.indices.reversed()) {
            val reading = readings[index]
            if (current.timeMs - reading.timeMs > MAX_LOOKBACK_MS) break
            if (newerTimeMs - reading.timeMs > MAX_GAP_MS) break
            if (!reading.value.isFinite()) continue
            when (kind(reading.value)) {
                Kind.END -> break
                Kind.START -> startedAtMs = reading.timeMs
                Kind.BAND -> Unit
            }
            walked++
            newerTimeMs = reading.timeMs
        }
        return if (startedAtMs > 0L) EpisodeStart(startedAtMs, walked) else null
    }

    /**
     * The input of [startOf] for the current reading: the stored readings of
     * [sensorId] over [MAX_LOOKBACK_MS] before it, then the current reading
     * with the value the alert evaluates. Stored values come through
     * [CurrentDisplaySource.resolveHistoryValues], in the units, lane,
     * calibration and smoothing of that live value.
     */
    fun load(sensorId: String?, currentTimeMs: Long, currentValue: Float): List<StoredReading> {
        val stored = CurrentDisplaySource.resolveHistoryValues(currentTimeMs - MAX_LOOKBACK_MS, sensorId)
        val readings = ArrayList<StoredReading>(stored.size + 1)
        stored.forEach { (timeMs, value) ->
            // The current reading may be stored already, under its own time or
            // its minute (native storage); it is added below with the live value.
            if (timeMs / 60_000L < currentTimeMs / 60_000L) {
                readings.add(StoredReading(timeMs, value))
            }
        }
        readings.add(StoredReading(currentTimeMs, currentValue))
        return readings
    }
}
