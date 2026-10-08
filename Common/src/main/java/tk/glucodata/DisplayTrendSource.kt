package tk.glucodata

object DisplayTrendSource {
    /**
     * How much history the trend consumers keep on hand. This is a supply ceiling, not
     * the estimator's window: TrendEngine picks its own window from the sensor's cadence
     * and narrows this list further. It only has to be at least the estimator's longest
     * possible window (TrendEngine.MAX_WINDOW_MIN, 25 min) so a slow sensor is never
     * quietly served less history than it asked for. The two live in different source
     * sets, so they cannot share the constant — keep them in step by hand.
     */
    const val TREND_WINDOW_MS = 25L * 60L * 1000L

    @JvmStatic
    fun augmentHistory(
        historyPoints: List<GlucosePoint>?,
        current: CurrentDisplaySource.Snapshot?,
        activeSensorSerial: String?,
        startTimeMs: Long
    ): List<GlucosePoint> {
        val history = historyPoints ?: emptyList()
        if (current == null || current.timeMillis < startTimeMs) {
            return history
        }
        if (activeSensorSerial != null && current.sensorId != null &&
            !SensorIdentity.matches(current.sensorId, activeSensorSerial)
        ) {
            return history
        }

        var autoValue = current.autoValue
        var rawValue = current.rawValue
        if (!autoValue.isFinite() || autoValue <= 0.1f) {
            autoValue = 0f
        }
        if (!rawValue.isFinite() || rawValue <= 0.1f) {
            rawValue = 0f
        }
        if (autoValue <= 0f && rawValue <= 0f) {
            return history
        }

        val candidate = GlucosePoint(current.timeMillis, autoValue, rawValue)
        if (history.isEmpty()) {
            return listOf(candidate)
        }

        val merged = ArrayList<GlucosePoint>(history.size + 1)
        var inserted = false
        history.forEach { point ->
            if (!inserted && candidate.timestamp <= point.timestamp) {
                if (candidate.timestamp == point.timestamp) {
                    merged.add(if (pointScore(candidate) >= pointScore(point)) candidate else point)
                    inserted = true
                    return@forEach
                }
                merged.add(candidate)
                inserted = true
            }
            merged.add(point)
        }
        if (!inserted) {
            merged.add(candidate)
        }
        return merged
    }

    /**
     * The one point list every trend arrow regresses over: recent history plus
     * the live reading, cut to [TREND_WINDOW_MS] anchored at the newest point.
     *
     * Anchoring at the newest point instead of the wall clock matters: the
     * newest reading lags "now" by up to a full period, so a now-anchored load
     * window keeps or drops the reading sitting at the very edge of the trend
     * window depending on each consumer's own load timing. Two honest
     * consumers then regress over lists that differ by one tail point — a
     * nuance invisible everywhere except at the flat dead zone, where it flips
     * the glyph categorically for a full period.
     */
    @JvmStatic
    fun resolveTrendPoints(
        historyPoints: List<GlucosePoint>?,
        current: CurrentDisplaySource.Snapshot?,
        activeSensorSerial: String?
    ): List<GlucosePoint> {
        val augmented = augmentHistory(historyPoints, current, activeSensorSerial, 0L)
        val newestTimestamp = augmented.lastOrNull()?.timestamp ?: return augmented
        val cutoff = newestTimestamp - TREND_WINDOW_MS
        if (augmented.first().timestamp >= cutoff) {
            return augmented
        }
        return augmented.filter { it.timestamp >= cutoff }
    }

    @JvmStatic
    @JvmOverloads
    fun resolveArrowRate(
        recentPoints: List<GlucosePoint>?,
        current: CurrentDisplaySource.Snapshot?,
        viewMode: Int,
        isMmol: Boolean,
        fallbackRate: Float = 0f
    ): Float {
        val points = recentPoints ?: emptyList()
        val useRaw = viewMode == 1 || viewMode == 3
        if (hasUsableTrendHistory(points, useRaw)) {
            val historyRate = runCatching {
                TrendAccess.calculateVelocity(points, useRaw = useRaw, isMmol = isMmol)
            }.getOrNull()
            if (historyRate != null && historyRate.isFinite()) {
                return historyRate
            }
        }
        return current?.rate?.takeIf { it.isFinite() } ?: fallbackRate
    }

    /**
     * The rate a display arrow shows: [resolveTrendPoints], then [resolveArrowRate], the
     * computation behind the dashboard hero and the notification arrow.
     *
     * [historyPoints] must be measured rows, neither calibrated nor smoothed (both reshape
     * the recent slope), reaching back at least [TREND_WINDOW_MS] before the newest one.
     *
     * Not [CurrentDisplaySource.Snapshot.rate]: that one feeds the alert engine and is
     * measured over the locally smoothed series, which "collapse into chunks" can leave
     * without the newest reading. Near the ±0.5 mg/dL/min flat band the two differ enough
     * to put one arrow flat and the other one up for the same readings.
     */
    @JvmStatic
    fun resolveDisplayArrowRate(
        historyPoints: List<GlucosePoint>?,
        current: CurrentDisplaySource.Snapshot?,
        activeSensorSerial: String?,
        viewMode: Int,
        isMmol: Boolean
    ): Float {
        val live = current?.let { atStoredTime(historyPoints, it) }
        return resolveArrowRate(
            resolveTrendPoints(historyPoints, live, activeSensorSerial),
            live,
            viewMode,
            isMmol,
            Float.NaN
        )
    }

    /**
     * Closer than this, two rows are one reading: half the shortest interval any
     * sensor reads at (one minute).
     */
    private const val SAME_READING_MS = 30_000L

    /**
     * [current] under the time of its own stored row, when it has one.
     *
     * On the phone a live reading and its Room row share a timestamp, so
     * [augmentHistory] merges the two. A native row keeps whole seconds while a G7
     * read on the watch keeps the milliseconds it arrived at, so the same reading
     * went in twice, under a second apart. With local smoothing the snapshot's value
     * differs from the measured one, TrendEngine took the step for a > 20 mg/dL/min
     * artifact and measured the newest point alone: a flat arrow on any rise.
     */
    private fun atStoredTime(
        historyPoints: List<GlucosePoint>?,
        current: CurrentDisplaySource.Snapshot
    ): CurrentDisplaySource.Snapshot {
        val stored = historyPoints
            ?.minByOrNull { kotlin.math.abs(it.timestamp - current.timeMillis) }
            ?.timestamp
            ?: return current
        if (stored == current.timeMillis || kotlin.math.abs(stored - current.timeMillis) >= SAME_READING_MS) {
            return current
        }
        return current.copy(timeMillis = stored)
    }

    /**
     * [resolveDisplayArrowRate] for [current], over the rows stored for its sensor: for
     * surfaces that hold a snapshot but no history, such as the watch's complications.
     */
    @JvmStatic
    fun loadDisplayArrowRate(current: CurrentDisplaySource.Snapshot): Float {
        // Two windows back, as the alarm notification reads: resolveTrendPoints cuts at
        // the newest point, and the reading at the far edge of the window must not
        // depend on where this read starts.
        val history = try {
            NotificationHistorySource.getDisplayHistory(
                current.timeMillis - 2 * TREND_WINDOW_MS,
                current.isMmol,
                current.sensorId
            )
        } catch (_: Throwable) {
            emptyList()
        }
        return resolveDisplayArrowRate(history, current, current.sensorId, current.viewMode, current.isMmol)
    }

    private fun hasUsableTrendHistory(points: List<GlucosePoint>, useRaw: Boolean): Boolean {
        var usablePoints = 0
        var previousTimestamp = Long.MIN_VALUE
        points.asReversed().forEach { point ->
            val value = if (useRaw) point.rawValue else point.value
            if (!value.isFinite() || value <= 0.1f) {
                return@forEach
            }
            if (usablePoints > 0 && point.timestamp != previousTimestamp) {
                return true
            }
            usablePoints++
            previousTimestamp = point.timestamp
        }
        return false
    }

    private fun pointScore(point: GlucosePoint): Int {
        var score = 0
        if (point.value > 0f) {
            score += 1
        }
        if (point.rawValue > 0f) {
            score += 2
        }
        return score
    }
}
