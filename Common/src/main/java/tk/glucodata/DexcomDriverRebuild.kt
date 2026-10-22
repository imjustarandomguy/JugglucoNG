package tk.glucodata

/**
 * Which kept Bluetooth callbacks SensorBluetooth.updateDevicers builds again.
 *
 * A callback is built for its record's kind once, when the record first shows
 * up. A G7 handed to a watch (WearSync2.adoptDexcomSensor) makes the record the
 * watch already kept for it, a plain one its synced readings went into, a
 * Dexcom record, and the callback built for the plain record stayed: a Libre
 * driver, scanning for a Libre's advertisement, so nothing dialled the G7
 * until every driver was rebuilt.
 */
object DexcomDriverRebuild {
    /**
     * Whether a kept callback is replaced by its record's Dexcom driver: a
     * build with Dexcom support, a callback that is neither a Dexcom nor a
     * managed driver, and a record that is a G7's ([recordIsDexcom], asked
     * only then).
     */
    @JvmStatic
    fun needed(
        dexcomBuild: Boolean,
        isDexcomDriver: Boolean,
        isManagedDriver: Boolean,
        recordIsDexcom: () -> Boolean,
    ): Boolean = dexcomBuild && !isDexcomDriver && !isManagedDriver && recordIsDexcom()
}
