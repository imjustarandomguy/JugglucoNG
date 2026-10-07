package tk.glucodata

import java.util.Locale
import tk.glucodata.drivers.api.ApiGlucoseSourceRegistry
import tk.glucodata.drivers.mq.MQConstants
import tk.glucodata.drivers.nightscout.NightscoutFollowerRegistry

/**
 * The native records cloud sources mirror their readings into: the Nightscout
 * follower (NSF-), the glucose API source (API-) and the Glutec follower (MQF-).
 *
 * Nothing transmits under these names. The phone serves their readings to the
 * watch like any sensor's, and native lists a 16-character record by its last
 * eleven characters, which reads like a Libre serial (NSF-3073E464C8CB is listed
 * as 073E464C8CB). A watch rebuilding its roster from that list gave the record
 * a generic Libre callback, which scans for a sensor that does not exist.
 */
object CloudSensorRecord {
    private val PREFIXES = listOf(
        NightscoutFollowerRegistry.SENSOR_PREFIX,
        ApiGlucoseSourceRegistry.SENSOR_PREFIX,
        MQConstants.FOLLOWER_SENSOR_PREFIX,
    ).map { it.uppercase(Locale.US) }

    /** Whether [id] is a cloud source's id, by its prefix. */
    @JvmStatic
    fun isCloudSensorId(id: String?): Boolean {
        val upper = id?.trim()?.uppercase(Locale.US)?.takeIf { it.isNotEmpty() } ?: return false
        return PREFIXES.any { upper.startsWith(it) && upper.length > it.length }
    }

    /**
     * The cloud id behind native record [name], full or as native's short alias,
     * or null for any other record. [fullName] is native's full name for a listed
     * name (Natives.resolveFullSensorName), null when it has none.
     */
    @JvmStatic
    fun cloudRecordId(name: String?, fullName: (String) -> String?): String? {
        val trimmed = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (isCloudSensorId(trimmed)) return trimmed
        return runCatching { fullName(trimmed) }.getOrNull()
            ?.trim()
            ?.takeIf { isCloudSensorId(it) }
    }

    /**
     * [names] without the cloud records, for a watch building its Bluetooth
     * callbacks: every cloud record a watch holds came from the phone's sync, and
     * no driver on the watch reads one. [onSkipped] gets each name dropped.
     */
    @JvmStatic
    @JvmOverloads
    fun withoutCloudRecords(
        names: Array<String?>?,
        fullName: (String) -> String?,
        onSkipped: (String) -> Unit = {},
    ): Array<String?>? {
        if (names == null) return null
        return names.filter { name ->
            val cloud = name != null && cloudRecordId(name, fullName) != null
            if (cloud) onSkipped(name!!)
            !cloud
        }.toTypedArray()
    }
}
