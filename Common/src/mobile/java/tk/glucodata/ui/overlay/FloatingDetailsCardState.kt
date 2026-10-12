package tk.glucodata.ui.overlay

import tk.glucodata.DisplayedReading

/** What the details card loads its chart, Δ and IOB/COB line for; a new one reloads them. */
data class FloatingDetailsRequest(
    val readingTime: Long,
    val sensorId: String?,
    val isMmol: Boolean,
    val viewMode: Int,
    val displayGlucose: Float,
)

/**
 * The details card for the pill's current reading at the card clock's [nowMillis]: the
 * card follows each new reading while it is open, its stale state is the pill's, set by
 * the hub's publish, and its age moves with the clock, never with the time the card was
 * opened. Pure, see the tests.
 */
internal data class FloatingDetailsCardState(
    val request: FloatingDetailsRequest,
    /** The time shown, and counted from: the hub's reading's. */
    val readingTime: Long,
    val ageMinutes: Long,
    /** As the pill's stale flag: the card dims the reading by it (FloatingStaleValue). */
    val stale: Boolean,
) {
    companion object {
        private const val MINUTE_MS = 60_000L

        /** Null without a reading. */
        fun of(
            reading: DisplayedReading,
            isMmol: Boolean,
            viewMode: Int,
            nowMillis: Long,
        ): FloatingDetailsCardState? {
            val readingTime = reading.timeMillis.takeIf { it > 0L } ?: return null
            return FloatingDetailsCardState(
                request = FloatingDetailsRequest(
                    readingTime = readingTime,
                    sensorId = reading.sensorId,
                    isMmol = isMmol,
                    viewMode = viewMode,
                    displayGlucose = reading.value,
                ),
                readingTime = readingTime,
                ageMinutes = ((nowMillis - readingTime) / MINUTE_MS).coerceAtLeast(0L),
                stale = FloatingPillLook.of(reading).stale,
            )
        }

        /**
         * How long from [nowMillis] until the age of the reading taken at [readingTime]
         * shown on the open card goes up a minute: the card's clock runs only for that,
         * and only while the card is open (FloatingGlucoseService).
         */
        fun nextAgeTickDelay(readingTime: Long, nowMillis: Long): Long {
            val age = nowMillis - readingTime
            // Stamped ahead of the clock: its first minute starts at its own time.
            if (age < 0L) return -age
            return MINUTE_MS - age % MINUTE_MS
        }
    }
}
