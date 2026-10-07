package tk.glucodata

import java.util.concurrent.ConcurrentHashMap

/**
 * When this device last took a reading straight from each sensor: over a GATT
 * it connected itself, never one synced from the other device or imported
 * over Clone.
 *
 * Kept across restarts, so a device that has just come back up and not read
 * the sensor yet still says when it last did rather than nothing.
 */
object DirectSensorReadings {
    private const val PREFS = "direct_sensor_readings"

    /** A record this old is a sensor long gone. */
    private const val FORGET_AFTER_MS = 60L * 24L * 60L * 60L * 1000L

    private val bySensor = ConcurrentHashMap<String, Long>()
    @Volatile private var loaded = false

    private fun key(serial: String): String =
        (runCatching { SensorIdentity.canonicalSensorId(serial) }.getOrNull() ?: serial).lowercase()

    private fun prefs() = runCatching {
        Applic.app?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
    }.getOrNull()

    private fun load() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val now = System.currentTimeMillis()
            val stale = ArrayList<String>()
            runCatching {
                prefs()?.all?.forEach { (id, value) ->
                    val at = (value as? Long) ?: return@forEach
                    if (now - at > FORGET_AFTER_MS) stale.add(id) else bySensor.merge(id, at, ::maxOf)
                }
            }
            if (stale.isNotEmpty()) {
                runCatching {
                    prefs()?.edit()?.apply { stale.forEach(::remove) }?.apply()
                }
            }
            loaded = true
        }
    }

    /** A reading from [serial] arrived over this device's own connection at [atMs]. */
    @JvmStatic
    fun note(serial: String?, atMs: Long) {
        val target = serial?.trim()?.takeIf { SensorIdentity.isUsableSensorId(it) } ?: return
        if (atMs <= 0L) return
        load()
        val id = key(target)
        if ((bySensor[id] ?: 0L) >= atMs) return
        bySensor[id] = atMs
        runCatching { prefs()?.edit()?.putLong(id, atMs)?.apply() }
    }

    /** When this device last read [serial] itself, 0 for never. */
    @JvmStatic
    fun lastMs(serial: String?): Long {
        val target = serial?.trim()?.takeIf { SensorIdentity.isUsableSensorId(it) } ?: return 0L
        load()
        bySensor[key(target)]?.let { return it }
        // One sensor, several spellings: match by identity, as the peer reports are.
        return bySensor.entries
            .filter { (id, _) -> SensorIdentity.matches(id, target) }
            .maxOfOrNull { it.value } ?: 0L
    }
}
