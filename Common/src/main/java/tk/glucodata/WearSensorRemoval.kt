package tk.glucodata

import java.util.Locale

/**
 * Which native records a watch ends when a sensor is removed on the phone or
 * forgotten on the watch itself.
 *
 * Native keeps one physical sensor under more than one spelling: its full
 * name, the short alias it lists active records by, and sometimes a second
 * record made under that alias by a synced chunk. Every one of them still
 * listed as active gets a Bluetooth callback again on the next roster
 * rebuild, so all of them have to end, not only the spelling the removal
 * happened to carry.
 */
internal object WearSensorRemoval {
    /**
     * The native records that are [removed]: each of its [spellings] that
     * native holds a record under ([hasRecord]), and each [active] record that
     * is the same sensor ([sameSensor]). Empty when a managed driver owns the
     * sensor ([managedOwned]): that driver's own removal ends its native
     * mirror, as on the phone.
     */
    fun nativeRecordsToEnd(
        removed: String,
        managedOwned: Boolean,
        spellings: Iterable<String?>,
        active: Array<out String?>?,
        hasRecord: (String) -> Boolean,
        sameSensor: (String, String) -> Boolean,
    ): List<String> {
        if (managedOwned) return emptyList()
        val records = LinkedHashMap<String, String>()
        fun add(name: String) {
            records.putIfAbsent(name.uppercase(Locale.ROOT), name)
        }
        spellings.forEach { spelling ->
            val name = usable(spelling) ?: return@forEach
            if (hasRecord(name)) add(name)
        }
        active.orEmpty().forEach { listed ->
            val name = usable(listed) ?: return@forEach
            if (sameSensor(name, removed)) add(name)
        }
        return records.values.toList()
    }

    /**
     * Whether the Bluetooth callback named [callbackName], reading the native
     * record [callbackRecord] (negative when unknown), is one of the [ended]
     * records': named exactly as one of them, or reading one of [endedRecords].
     *
     * No identity matching: it relates a sensor's other spellings, but also
     * takes a callback without a name for any sensor, and freeing a callback is
     * not undone (a Dexcom one removes its sensor's bond). A callback without a
     * name is never one.
     */
    fun readsEndedRecord(
        callbackName: String?,
        callbackRecord: Int,
        ended: Collection<String>,
        endedRecords: Set<Int>,
    ): Boolean {
        val name = usable(callbackName) ?: return false
        return ended.any { it.trim().equals(name, ignoreCase = true) } ||
            (callbackRecord >= 0 && callbackRecord in endedRecords)
    }

    private fun usable(name: String?): String? =
        name?.trim()?.takeIf { SensorIdentity.isUsableSensorId(it) }
}
