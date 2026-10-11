package tk.glucodata.ui.util

import tk.glucodata.Applic
import tk.glucodata.SensorBluetooth
import tk.glucodata.SensorIdentity
import tk.glucodata.drivers.ManagedSensorRuntime
import tk.glucodata.drivers.ManagedSensorStatusPolicy

private fun managedDashboardStatus(serial: String): String {
    val snapshot = ManagedSensorRuntime.resolveUiSnapshot(serial, serial) ?: return ""
    return ManagedSensorStatusPolicy.collapseSummaryStatus(snapshot.detailedStatus)
        .ifBlank { ManagedSensorStatusPolicy.collapseSummaryStatus(snapshot.subtitleStatus) }
}

fun resolveDashboardSensorStatus(
    serial: String,
    sensorKind: Int,
    startMsec: Long,
    nativeStatus: String
): String {
    val gatt = runCatching {
        SensorBluetooth.mygatts().firstOrNull { SensorIdentity.matches(it.SerialNumber, serial) }
    }.getOrNull()
    val warmupStatus = getLegacyWarmupStatus(Applic.app, sensorKind, startMsec, nativeStatus, gatt = gatt)
    if (warmupStatus != null) {
        return warmupStatus
    }
    val managedStatus = managedDashboardStatus(serial)
    if (managedStatus.isNotBlank() || ManagedSensorRuntime.resolveUiSnapshot(serial, serial) != null) {
        return managedStatus
    }
    return nativeStatus
}

fun resolveDashboardSensorStatus(serial: String, nativeStatus: String): String {
    val managedStatus = managedDashboardStatus(serial)
    if (managedStatus.isNotBlank() || ManagedSensorRuntime.resolveUiSnapshot(serial, serial) != null) {
        return managedStatus
    }
    return nativeStatus
}
