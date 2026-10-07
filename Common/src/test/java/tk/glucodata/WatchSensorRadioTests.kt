package tk.glucodata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A watch told to read a G7 lost it for good on 2026-10-06 at 18:11: the phone's
 * /netinfo had cleared native's "use Bluetooth" flag, and the next roster rebuild
 * (the phone removing a Nightscout follower's record) acted on it and stopped
 * all of sensor Bluetooth. Only a revoke or the user's switch may stop it.
 */
class WatchSensorRadioTests {

    private fun keeps(
        isWearable: Boolean = true,
        nativeUseBluetooth: Boolean = false,
        directRequested: Boolean = false,
        autoSwitchEnabled: Boolean = false,
    ) = WatchSensorRadio.keepsBluetooth(isWearable, nativeUseBluetooth, directRequested, autoSwitchEnabled)

    @Test
    fun directModeKeepsBluetoothWhenNetinfoClearedTheFlag() {
        assertTrue(keeps(nativeUseBluetooth = false, directRequested = true))
    }

    @Test
    fun automaticSwitchingKeepsBluetoothWhenNetinfoClearedTheFlag() {
        assertTrue(keeps(nativeUseBluetooth = false, autoSwitchEnabled = true))
    }

    @Test
    fun revokedWatchFollowsTheFlag() {
        // /bluetooth off clears the request before it stops Bluetooth.
        assertFalse(keeps(nativeUseBluetooth = false))
        assertTrue(keeps(nativeUseBluetooth = true))
    }

    @Test
    fun phoneFollowsTheFlagAsBefore() {
        assertFalse(keeps(isWearable = false, nativeUseBluetooth = false, directRequested = true, autoSwitchEnabled = true))
        assertTrue(keeps(isWearable = false, nativeUseBluetooth = true))
    }

    private fun restart(
        isWearable: Boolean = true,
        directRequested: Boolean = true,
        autoSwitchEnabled: Boolean = false,
        adapterEnabled: Boolean = true,
        bluetoothRunning: Boolean = false,
    ) = WatchSensorRadio.shouldRestartBluetooth(
        isWearable, directRequested, autoSwitchEnabled, adapterEnabled, bluetoothRunning,
    )

    @Test
    fun watchToldToReadRestartsBluetoothThatWasTornDown() {
        assertTrue(restart())
        assertTrue(restart(directRequested = false, autoSwitchEnabled = true))
    }

    @Test
    fun noRestartWhileRunningOrNotWanted() {
        assertFalse(restart(bluetoothRunning = true))
        assertFalse(restart(directRequested = false, autoSwitchEnabled = false))
        assertFalse(restart(isWearable = false))
    }

    @Test
    fun noRestartWhileTheAdapterIsOff() {
        // Only the user turns the adapter back on; starting would only toast.
        assertFalse(restart(adapterEnabled = false))
    }

    private fun redial(
        isWearable: Boolean = true,
        directRequested: Boolean = true,
        readsAlongside: Boolean = true,
        released: Boolean = false,
        paused: Boolean = false,
        holdsSensor: Boolean = false,
        transportIdle: Boolean = true,
    ) = WatchSensorRadio.shouldRedial(
        isWearable, directRequested, readsAlongside, released, paused, holdsSensor, transportIdle,
    )

    @Test
    fun redialsAG7NothingIsDialling() {
        assertTrue(redial())
    }

    @Test
    fun leavesAnArmedOrHeldG7Alone() {
        // A pending autoConnect waits for the next session by itself.
        assertFalse(redial(transportIdle = false))
        // Read within the last two sessions: between sessions, or waiting on an alarm.
        assertFalse(redial(holdsSensor = true))
    }

    @Test
    fun respectsPauseReleaseAndRevoke() {
        assertFalse(redial(paused = true))
        assertFalse(redial(released = true))
        assertFalse(redial(directRequested = false))
    }

    @Test
    fun onlyAWatchAndOnlyASensorReadAlongside() {
        assertFalse(redial(isWearable = false))
        // A sensor that serves one device at a time is the arbitration's to hand over.
        assertFalse(redial(readsAlongside = false))
    }
}
