package tk.glucodata

/**
 * When the push that follows a reading goes out ([WearSync2.pushTail]).
 *
 * A push used to be dropped, not put off, inside 45 s of the one before, so a
 * reading that came just after another (a second sensor's, a G7's answer to a
 * re-ask) reached the other device only with the next reading or its loss
 * alarm, minutes later. A push for a reading newer than the newest pushed of
 * its sensor now always goes at once. Only a repeat for no newer reading still
 * waits out [repeatIntervalMs]: it would carry nothing the last one did not.
 */
class WearPushGate(private val repeatIntervalMs: Long) {
    private val newestPushedMs = HashMap<String, Long>()
    private var lastPushMs = 0L

    /** Whether the push after the reading of [sensorKey] taken at [readingMs] goes now. */
    @Synchronized
    fun shouldPush(sensorKey: String, readingMs: Long, nowMs: Long): Boolean {
        val newer = readingMs > (newestPushedMs[sensorKey] ?: 0L)
        if (!newer && nowMs - lastPushMs < repeatIntervalMs) return false
        lastPushMs = nowMs
        if (newer) newestPushedMs[sensorKey] = readingMs
        return true
    }

    companion object {
        /**
         * The stored readings [points], oldest first, with [reading] after them
         * unless they hold it already. The push reads history this device writes
         * asynchronously, so it often lacked the very reading it followed, and
         * the other device got that one only with the next push.
         */
        @JvmStatic
        fun withReading(points: List<GlucosePoint>, reading: TakenReading?, fromMs: Long): List<GlucosePoint> {
            if (reading == null || reading.timeMs < fromMs || !GlucoseValuePlausibility.isPlausibleMgdl(reading.autoMgdl)) {
                return points
            }
            if (points.any { kotlin.math.abs(it.timestamp - reading.timeMs) < DirectReadingStatus.SAME_READING_MS }) {
                return points
            }
            val raw = reading.rawMgdl.takeIf { GlucoseValuePlausibility.isPlausibleMgdl(it) } ?: 0f
            return (points + GlucosePoint(reading.timeMs, reading.autoMgdl, raw)).sortedBy { it.timestamp }
        }
    }
}

/** A reading this device took itself, in mg/dL as it stores them, before calibration. */
data class TakenReading(val timeMs: Long, val autoMgdl: Float, val rawMgdl: Float)
