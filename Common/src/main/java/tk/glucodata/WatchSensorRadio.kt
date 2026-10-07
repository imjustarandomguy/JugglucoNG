package tk.glucodata

/**
 * Keeps a watch that was told to read a sensor reading it.
 *
 * The watch's sensor Bluetooth hung off native's "use Bluetooth" flag, and the
 * phone's /netinfo still speaks the legacy protocol: every one that says "the
 * phone has the sensor" clears that flag on the watch (netinfo.cpp, the
 * watch branch of setmynetinfo). Direct mode never says otherwise, so the flag
 * went off with the first new /netinfo after the watch app started, and stayed
 * off unnoticed while the driver already running kept reading. The next roster
 * rebuild — a sensor removed on the phone (a Nightscout follower switched back
 * to Upload), a G7 handed over, a sensor forgotten on the watch — read the flag,
 * tore the whole of sensor Bluetooth down and left nothing that would bring it
 * back: the 2026-10-06 18:11 outage.
 *
 * So on a watch the flag is not the decision. "Direct sensor on watch" and
 * automatic switching each mean the watch keeps its radio, and only the phone's
 * /bluetooth off (a revoke, or the user turning the switch off), which clears
 * both, lets it go. [keepReading] is the backstop for every other way a driver
 * can end up with nobody dialling it.
 */
object WatchSensorRadio {
    private const val LOG_ID = "WatchSensorRadio"

    /**
     * Whether sensor Bluetooth must run on this device. A phone follows native's
     * flag as it always has; a watch also keeps it while it was told to read a
     * sensor ([directRequested]) or to take one over when the phone cannot
     * ([autoSwitchEnabled]).
     */
    @JvmStatic
    fun keepsBluetooth(
        isWearable: Boolean,
        nativeUseBluetooth: Boolean,
        directRequested: Boolean,
        autoSwitchEnabled: Boolean,
    ): Boolean = nativeUseBluetooth || (isWearable && (directRequested || autoSwitchEnabled))

    /**
     * Whether a watch has to bring sensor Bluetooth back up: it should be running
     * and is not. Not while the adapter itself is off, which only the user can
     * change and which starting would only answer with a toast.
     */
    @JvmStatic
    fun shouldRestartBluetooth(
        isWearable: Boolean,
        directRequested: Boolean,
        autoSwitchEnabled: Boolean,
        adapterEnabled: Boolean,
        bluetoothRunning: Boolean,
    ): Boolean = isWearable && (directRequested || autoSwitchEnabled) && adapterEnabled && !bluetoothRunning

    /**
     * Whether a watch has to dial a sensor it was told to read because nothing
     * else will: no GATT open or pending, no connect scheduled, and it does not
     * hold the sensor (for a G7, no reading of its own in the last two sessions).
     * A paused driver is the user's choice, and a released one the arbitration's.
     */
    @JvmStatic
    fun shouldRedial(
        isWearable: Boolean,
        directRequested: Boolean,
        readsAlongside: Boolean,
        released: Boolean,
        paused: Boolean,
        holdsSensor: Boolean,
        transportIdle: Boolean,
    ): Boolean = isWearable && directRequested && readsAlongside &&
        !released && !paused && !holdsSensor && transportIdle

    private fun autoSwitchEnabled(): Boolean =
        runCatching { AutoSensorSwitch.isEnabled() }.getOrDefault(false)

    /**
     * Whether sensor Bluetooth should run, for every path that would otherwise
     * read native's flag on its own. On a watch that must keep it, a cleared
     * flag is put back, so native and the screens that read it agree.
     */
    @JvmStatic
    fun bluetoothWanted(): Boolean {
        val nativeUse = runCatching { Natives.getusebluetooth() }.getOrDefault(true)
        if (nativeUse || !Applic.isWearable) return nativeUse
        val keep = keepsBluetooth(
            isWearable = true,
            nativeUseBluetooth = false,
            directRequested = WearSensorClaim.isDirectRequested(),
            autoSwitchEnabled = autoSwitchEnabled(),
        )
        if (keep) {
            Log.i(LOG_ID, "keeping sensor Bluetooth: this watch was told to read a sensor")
            runCatching { Natives.setusebluetooth(true) }
                .onFailure { Log.stack(LOG_ID, "setusebluetooth", it) }
        }
        return keep
    }

    /**
     * Watch backstop, run with each ownership reconciliation: starts sensor
     * Bluetooth again if it is down, and dials a sensor read alongside the phone
     * that nothing is dialling. [isReleased] is the arbitration's own record.
     */
    @JvmStatic
    fun keepReading(isReleased: (String) -> Boolean) {
        if (!Applic.isWearable) return
        val direct = WearSensorClaim.isDirectRequested()
        val auto = autoSwitchEnabled()
        if (!direct && !auto) return
        if (shouldRestartBluetooth(
                isWearable = true,
                directRequested = direct,
                autoSwitchEnabled = auto,
                adapterEnabled = SensorBluetooth.bluetoothIsEnabled(),
                bluetoothRunning = SensorBluetooth.blueone != null,
            )
        ) {
            // A revoke arriving meanwhile clears the request before it stops
            // Bluetooth; look again rather than undo it.
            if (!WearSensorClaim.isDirectRequested() && !autoSwitchEnabled()) return
            Log.w(LOG_ID, "sensor Bluetooth was down on a watch told to read a sensor: starting it again")
            // The application context: this runs off the main thread, where the
            // Activity's own start-up prompts must not run.
            val context = Applic.app ?: return
            Applic.setbluetooth(context, true)
            return
        }
        if (!direct) return
        val now = System.currentTimeMillis()
        SensorBluetooth.mygatts().forEach { gatt ->
            val serial = gatt.SerialNumber?.takeIf { it.isNotBlank() } ?: return@forEach
            if (shouldRedial(
                    isWearable = true,
                    directRequested = true,
                    readsAlongside = gatt.readsAlongside(),
                    released = isReleased(serial),
                    paused = SensorBluetooth.isSensorPaused(gatt),
                    holdsSensor = gatt.holdsSensor(now),
                    transportIdle = gatt.transportIdle(),
                )
            ) {
                Log.w(LOG_ID, "$serial: nothing was dialling it; connecting again")
                SensorBluetooth.redial(gatt)
            }
        }
    }
}
