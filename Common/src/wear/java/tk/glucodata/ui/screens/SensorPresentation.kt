package tk.glucodata.ui.screens

import android.text.format.DateUtils
import tk.glucodata.DexcomFollow
import tk.glucodata.Natives
import tk.glucodata.NotificationHistorySource
import tk.glucodata.SensorIdentity
import tk.glucodata.drivers.ManagedSensorRuntime
import tk.glucodata.drivers.ManagedSensorStatusPolicy

internal data class WearSensorPresentation(
    val serial: String,
    val connectionStatus: String,
    val detailedStatus: String,
    val dayValueText: String,
    val lifecycleProgress: Float?,
    val startTimeMs: Long,
    val lastReadingMs: Long,
    val rssi: Int?,
)

private const val DAY_MS = 24L * 60L * 60L * 1000L

internal fun displaySensorName(id: String): String = id.replace(Regex("^[A-Z0-9]{2,6}:"), "")

/**
 * The sensor list's name for a sensor otherwise shown as [shown]: the name its
 * device advertises over Bluetooth when one is known, by the rule the phone's
 * sensor list names a sensor it reads by (SuperGattCallback.mygetDeviceName when
 * it is a usable id). A G7's record name is a long run of digits, which the
 * phone lists as "DXCM" and two characters, so both lists name it alike.
 */
internal fun sensorListLabel(shown: String, deviceName: String?): String =
    deviceName?.takeIf { SensorIdentity.isUsableSensorId(it) } ?: shown

/**
 * [sensorListLabel] for [sensorId]: a G7 by the Bluetooth name kept with its
 * record, which the phone hands over with it and the watch keeps when it pairs
 * the sensor itself. Anything else, or a G7 not paired yet, as [shown].
 */
internal fun loadSensorListLabel(sensorId: String, shown: String): String = sensorListLabel(
    shown,
    runCatching { DexcomFollow.fromFields(Natives.dexHandoff(sensorId))?.deviceName }.getOrNull(),
)

internal fun loadWearSensorPresentation(sensorId: String, nowMs: Long): WearSensorPresentation {
    val managed = runCatching { ManagedSensorRuntime.resolveUiSnapshot(sensorId, sensorId) }.getOrNull()
    val nativeStart = if (managed == null) {
        runCatching { Natives.getSensorUiSnapshot(sensorId) }
            .getOrNull()
            ?.takeIf { it.size >= 3 }
            ?.get(2)
            ?.takeIf { it > 0L }
            ?: 0L
    } else {
        0L
    }
    val startTime = managed?.startTimeMs?.takeIf { it > 0L } ?: nativeStart
    val lifecycle = ManagedSensorStatusPolicy.resolveLifecycleSummary(
        startTimeMs = startTime,
        officialEndMs = managed?.officialEndMs ?: 0L,
        expectedEndMs = managed?.expectedEndMs ?: 0L,
        sensorRemainingHours = managed?.sensorRemainingHours ?: -1,
        sensorAgeHours = managed?.sensorAgeHours ?: -1,
        nowMs = nowMs,
    )
    val hasKnownTotal = managed != null && (
        managed.expectedEndMs > startTime ||
            managed.officialEndMs > startTime ||
            (managed.sensorAgeHours >= 0 && managed.sensorRemainingHours >= 0)
        )
    val knownCurrentDay = managed?.sensorAgeHours
        ?.takeIf { it >= 0 }
        ?.let { (it / 24) + 1 }
        ?: startTime.takeIf { it > 0L }?.let {
            (((nowMs - it).coerceAtLeast(0L) / DAY_MS) + 1L).toInt()
        }
    val dayValue = if (hasKnownTotal) {
        lifecycle.daysText
    } else {
        knownCurrentDay?.toString().orEmpty()
    }
    val managedReading = runCatching {
        ManagedSensorRuntime.resolveCurrentSnapshot(sensorId, Long.MAX_VALUE)?.timeMillis
    }.getOrNull()?.takeIf { it > 0L }
    val lastReading = managedReading ?: runCatching {
        NotificationHistorySource.getDisplayHistory(
            nowMs - 30L * DateUtils.DAY_IN_MILLIS,
            false,
            sensorId,
        ).lastOrNull()?.timestamp
    }.getOrNull()?.takeIf { it > 0L } ?: 0L

    return WearSensorPresentation(
        serial = displaySensorName(managed?.serial?.takeIf { it.isNotBlank() } ?: sensorId),
        connectionStatus = managed?.connectionStatus?.trim().orEmpty(),
        detailedStatus = managed?.detailedStatus?.trim().orEmpty(),
        dayValueText = dayValue,
        lifecycleProgress = lifecycle.progress.takeIf { hasKnownTotal },
        startTimeMs = startTime,
        lastReadingMs = lastReading,
        // 9999 is SuperGattCallback's "no RSSI read yet" sentinel; only a real
        // dBm figure may be shown ("9999 dBm" reached the watch UI).
        rssi = managed?.rssi?.takeIf { it in -120..0 },
    )
}

internal fun compactReadingAge(timestampMs: Long, nowMs: Long): String? {
    if (timestampMs <= 0L) return null
    val minutes = ((nowMs - timestampMs).coerceAtLeast(0L) / DateUtils.MINUTE_IN_MILLIS)
    return if (minutes < 60L) "${minutes}m" else "${minutes / 60L}h"
}
