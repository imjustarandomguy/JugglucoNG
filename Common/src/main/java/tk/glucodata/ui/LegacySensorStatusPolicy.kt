package tk.glucodata.ui

/** A connecting or warming sensor is enabled unless its callback was paused. */
fun isSensorLocallyEnabled(streaming: Boolean, paused: Boolean?): Boolean =
    paused?.not() ?: streaming

fun libre3WarmupMinutes(
    sensorKind: Int,
    startMs: Long,
    nowMs: Long,
    warmupMinutes: Int,
    nativeStatus: String,
): Int? {
    if (sensorKind != 3 || startMs <= 0L || warmupMinutes <= 0) return null
    // Do not cover a sensor-reported error, ended state, or history transfer.
    if (nativeStatus.isNotBlank() && nativeStatus != "Warming Up") return null
    val elapsed = nowMs - startMs
    val duration = warmupMinutes * 60_000L
    if (elapsed < 0 || elapsed >= duration) return null
    return ((duration - elapsed + 59_999L) / 60_000L).toInt()
}

private const val DEXCOM_WARMUP_MS = 30L * 60L * 1000L
// The first reading follows warmup at the sensor's next slots. Silence past this
// is a lost signal again, so the Bluetooth status takes over.
private const val DEXCOM_FIRST_READING_GRACE_MS = 15L * 60L * 1000L

/** What a Dexcom status line shows from the session start until the first reading. */
sealed interface DexcomWarmupStatus {
    /** Nothing has arrived from the sensor yet. */
    data object WaitingForConnection : DexcomWarmupStatus
    data class ReadyIn(val minutes: Int) : DexcomWarmupStatus
    /** Warmup is over and the sensor was reached, but no reading has arrived yet. */
    data object WaitingForFirstReading : DexcomWarmupStatus
}

/**
 * [heardFromSensor] is any frame from the sensor, with or without a reading. The
 * warmup frame's own error status lasts only 15 minutes, so it cannot carry the
 * countdown through a 30-minute warmup by itself.
 */
fun dexcomWarmupStatus(
    startMs: Long,
    nowMs: Long,
    heardFromSensor: Boolean,
    lastReadingMs: Long,
): DexcomWarmupStatus? {
    if (startMs <= 0L || lastReadingMs > 0L) return null
    val elapsed = nowMs - startMs
    if (elapsed < 0L) return null
    if (elapsed < DEXCOM_WARMUP_MS) {
        if (!heardFromSensor) return DexcomWarmupStatus.WaitingForConnection
        val remaining = (DEXCOM_WARMUP_MS - elapsed).coerceAtLeast(60_000L)
        return DexcomWarmupStatus.ReadyIn(((remaining + 59_999L) / 60_000L).toInt())
    }
    if (heardFromSensor && elapsed < DEXCOM_WARMUP_MS + DEXCOM_FIRST_READING_GRACE_MS) {
        return DexcomWarmupStatus.WaitingForFirstReading
    }
    return null
}
