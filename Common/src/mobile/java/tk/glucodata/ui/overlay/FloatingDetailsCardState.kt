package tk.glucodata.ui.overlay

import tk.glucodata.Notify
import tk.glucodata.ui.GlucosePoint

/** What the details card loads its chart, Δ and IOB/COB line for; a new one reloads them. */
data class FloatingDetailsRequest(
    val point: GlucosePoint,
    val sensorId: String?,
    val isMmol: Boolean,
    val viewMode: Int,
    val displayGlucose: Float,
)

/**
 * The details card for the pill's current reading at the freshness clock's [nowMillis]:
 * the card follows each new reading while it is open, and its age and stale state move
 * with the clock, never with the time the card was opened. Pure, see the tests.
 */
internal data class FloatingDetailsCardState(
    val request: FloatingDetailsRequest,
    /** The time shown, and counted from: the pill's (FloatingPillReading.readingTime). */
    val readingTime: Long,
    val ageMinutes: Long,
    /** As the pill's stale flag: the card dims the reading by it (FloatingStaleValue). */
    val stale: Boolean,
) {
    companion object {
        /** Null without a reading. */
        fun of(
            reading: FloatingPillReading,
            isMmol: Boolean,
            viewMode: Int,
            nowMillis: Long,
            freshnessWindowMillis: Long = Notify.glucosetimeout,
        ): FloatingDetailsCardState? {
            val point = reading.point ?: return null
            val snapshotMillis = reading.snapshot?.timeMillis ?: 0L
            val readingTime = reading.readingTime
            return FloatingDetailsCardState(
                request = FloatingDetailsRequest(
                    point = point,
                    sensorId = reading.sensorId,
                    isMmol = isMmol,
                    viewMode = viewMode,
                    displayGlucose = reading.snapshot?.displayValues?.primaryValue ?: point.value,
                ),
                readingTime = readingTime,
                ageMinutes = ((nowMillis - readingTime) / 60_000L).coerceAtLeast(0L),
                stale = !overlayReadingIsFresh(point, snapshotMillis, nowMillis, freshnessWindowMillis),
            )
        }
    }
}
