package tk.glucodata.ui.util

import android.content.Context
import tk.glucodata.Natives
import tk.glucodata.R
import tk.glucodata.SensorBluetooth
import tk.glucodata.SuperGattCallback
import tk.glucodata.ui.DexcomWarmupStatus
import tk.glucodata.ui.dexcomWarmupStatus
import tk.glucodata.ui.libre3WarmupMinutes

private const val DEXCOM_SENSOR_KIND = 0x40

fun getLegacyWarmupStatus(
    context: Context,
    sensorKind: Int,
    startMs: Long,
    nativeStatus: String,
    warmupMinutes: Int = 60,
    gatt: SuperGattCallback? = null,
): String? {
    val nowMs = System.currentTimeMillis()
    libre3WarmupMinutes(sensorKind, startMs, nowMs, warmupMinutes, nativeStatus)?.let {
        return context.getString(R.string.status_ready_in_minutes, it)
    }
    if (sensorKind != DEXCOM_SENSOR_KIND) {
        return null
    }
    // While it lasts, the warmup frame's native status is contact too, also for a
    // sensor this process holds no callback for.
    val heard = nativeStatus.isNotBlank() || SensorBluetooth.heardFromSensor(gatt)
    return when (val status = dexcomWarmupStatus(startMs, nowMs, heard, SensorBluetooth.lastReadingAt(gatt))) {
        null -> null
        DexcomWarmupStatus.WaitingForConnection -> context.getString(R.string.status_waiting_for_connection)
        DexcomWarmupStatus.WaitingForFirstReading -> context.getString(R.string.status_waiting_for_first_reading)
        is DexcomWarmupStatus.ReadyIn -> context.getString(R.string.status_ready_in_minutes, status.minutes)
    }
}

fun getLegacyWarmupStatus(context: Context, gatt: SuperGattCallback, nativeStatus: String): String? {
    val dataptr = gatt.dataptr
    if (dataptr == 0L) {
        return null
    }
    val sensorKind = runCatching { Natives.getLibreVersion(dataptr) }.getOrDefault(-1)
    val startMs = runCatching { Natives.getSensorStartmsec(dataptr) }.getOrDefault(0L)
    val warmupMinutes = runCatching { Natives.getSensorWarmupMinutes(dataptr) }.getOrDefault(0)
    return getLegacyWarmupStatus(context, sensorKind, startMs, nativeStatus, warmupMinutes, gatt)
}
