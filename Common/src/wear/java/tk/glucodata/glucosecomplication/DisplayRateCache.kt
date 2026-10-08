package tk.glucodata.glucosecomplication

/**
 * The display arrow rate of the readings shown on the watch, worked out once per
 * reading. Each computation loads the trend window's history; the watch face asks
 * on every frame and every arrow complication on each update.
 *
 * Holds the last few keys, for the multi-sensor complication's sensors.
 */
internal class DisplayRateCache(private val capacity: Int = 4) {
    /**
     * What the rate depends on: the reading, the lane and unit it is measured in, and
     * [dataRevision], which moves whenever stored readings change (a backfill can fill
     * the window behind the same newest reading).
     */
    data class Key(
        val sensorId: String?,
        val timeMillis: Long,
        val viewMode: Int,
        val isMmol: Boolean,
        val autoValue: Float,
        val rawValue: Float,
        val dataRevision: Long,
    )

    private val rates = object : LinkedHashMap<Key, Float>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Float>?): Boolean = size > capacity
    }

    /** The rate stored for [key], else [compute]'s, stored. [compute] runs outside the lock. */
    fun rate(key: Key, compute: () -> Float): Float {
        synchronized(rates) { rates[key] }?.let { return it }
        val rate = compute()
        synchronized(rates) { rates[key] = rate }
        return rate
    }
}
