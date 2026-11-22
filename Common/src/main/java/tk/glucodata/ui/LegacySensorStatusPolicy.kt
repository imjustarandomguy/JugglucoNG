package tk.glucodata.ui

/** Native `getsensortext` literals that the card needs to localize. */
enum class LegacySensorStatusText {
    SensorError,
    ReplaceSensor,
    SensorEnded,
    WarmingUp,
    Other,
}

/** Sensor kind the native layer reports for a Dexcom G7 / ONE+. */
const val DEXCOM_SENSOR_KIND = 0x40

/** Warm-up assumed when the record carries none; every 10-day sensor has 30 minutes. */
const val DEXCOM_DEFAULT_WARMUP_MINUTES = 30

fun classifyLegacySensorStatus(status: String): LegacySensorStatusText = when (status) {
    "Sensor Error" -> LegacySensorStatusText.SensorError
    "Replace Sensor?" -> LegacySensorStatusText.ReplaceSensor
    "Sensor Ended" -> LegacySensorStatusText.SensorEnded
    "Warming Up" -> LegacySensorStatusText.WarmingUp
    else -> LegacySensorStatusText.Other
}

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
 * countdown through the warmup by itself.
 */
fun dexcomWarmupStatus(
    startMs: Long,
    nowMs: Long,
    heardFromSensor: Boolean,
    lastReadingMs: Long,
    warmupMinutes: Int = DEXCOM_DEFAULT_WARMUP_MINUTES,
): DexcomWarmupStatus? {
    if (startMs <= 0L || lastReadingMs > 0L) return null
    val elapsed = nowMs - startMs
    if (elapsed < 0L) return null
    val duration = (if (warmupMinutes > 0) warmupMinutes else DEXCOM_DEFAULT_WARMUP_MINUTES) * 60_000L
    if (elapsed < duration) {
        if (!heardFromSensor) return DexcomWarmupStatus.WaitingForConnection
        val remaining = (duration - elapsed).coerceAtLeast(60_000L)
        return DexcomWarmupStatus.ReadyIn(((remaining + 59_999L) / 60_000L).toInt())
    }
    if (heardFromSensor && elapsed < duration + DEXCOM_FIRST_READING_GRACE_MS) {
        return DexcomWarmupStatus.WaitingForFirstReading
    }
    return null
}

/** Minutes left in a Dexcom G7 / ONE+ warm-up, or null outside it. */
fun dexcomWarmupRemainingMinutes(
    sensorKind: Int,
    startMs: Long,
    nowMs: Long,
    warmupMinutes: Int,
    nativeStatus: String,
): Int? {
    if (sensorKind != DEXCOM_SENSOR_KIND || startMs <= 0L) return null
    if (nativeStatus.isNotBlank() && nativeStatus != "Warming Up") return null
    // The sensor's own value (62 min for a 15-day G7, 30 for a 10-day one);
    // 30 is the value every older record carries.
    val minutes = if (warmupMinutes > 0) warmupMinutes else DEXCOM_DEFAULT_WARMUP_MINUTES
    val duration = minutes * 60_000L
    val elapsed = nowMs - startMs
    if (elapsed < 0 || elapsed >= duration) return null
    return ((duration - elapsed + 59_999L) / 60_000L).toInt()
}
