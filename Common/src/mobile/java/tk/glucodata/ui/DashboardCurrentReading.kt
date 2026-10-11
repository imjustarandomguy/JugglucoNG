package tk.glucodata.ui

import tk.glucodata.DisplayDataState
import tk.glucodata.DisplayedReading
import tk.glucodata.SensorIdentity
import tk.glucodata.logic.TrendEngine

/**
 * The dashboard hero's share of the current reading ([tk.glucodata.CurrentReadingHub]): the
 * value, arrow and freshness the notification and every other surface show, changed in the
 * same publish. The chart and the reading rows keep drawing their history.
 */
internal object DashboardCurrentReading {
    /** [reading] once published, when it is the reading of [sensorName] (blank: any). */
    fun forSensor(
        reading: DisplayedReading?,
        sensorName: String,
        matches: (String?, String?) -> Boolean = SensorIdentity::matches,
    ): DisplayedReading? = reading?.takeIf {
        it.revision > 0L && (sensorName.isBlank() || it.sensorId.isNullOrBlank() || matches(it.sensorId, sensorName))
    }

    /**
     * The hero's data state for [reading] at [nowMillis]: a value still shown (dimmed once
     * the next reading is overdue) is fresh data; a value taken down is stale, which the
     * hero says as "no data … ago". The kind changes only with a publish; [nowMillis] only
     * ages that label.
     */
    fun dataState(reading: DisplayedReading, sensorPresent: Boolean, nowMillis: Long): DisplayDataState.Status {
        val kind = when (reading.freshness) {
            DisplayedReading.Freshness.FRESH,
            DisplayedReading.Freshness.STALE ->
                if (reading.showsValue) DisplayDataState.Kind.FRESH else DisplayDataState.Kind.STALE
            DisplayedReading.Freshness.NO_VALUE -> DisplayDataState.Kind.STALE
            DisplayedReading.Freshness.AWAITING -> DisplayDataState.Kind.AWAITING_DATA
            DisplayedReading.Freshness.NO_SENSOR ->
                if (sensorPresent) DisplayDataState.Kind.AWAITING_DATA else DisplayDataState.Kind.NO_SENSOR
        }
        val time = reading.timeMillis
        return DisplayDataState.Status(
            kind = kind,
            sensorPresent = sensorPresent || reading.freshness != DisplayedReading.Freshness.NO_SENSOR,
            latestTimestampMillis = time,
            ageMillis = if (time > 0L) (nowMillis - time).coerceAtLeast(0L) else Long.MAX_VALUE,
        )
    }

    /**
     * The hero's arrow: the current reading's rate, the one every surface draws, where the
     * hero used to fit its own over the merged history of every sensor. That fit lends only
     * its noise level. None while the reading is stale.
     */
    fun trend(reading: DisplayedReading, historyTrend: TrendEngine.TrendResult): TrendEngine.TrendResult {
        val rate = reading.shownRate
        if (!rate.isFinite()) {
            return TrendEngine.TrendResult(TrendEngine.TrendState.Unknown, 0f, 0f, 0f, historyTrend.noiseLevel)
        }
        return historyTrend.copy(state = TrendEngine.stateFor(rate), velocity = rate)
    }
}
