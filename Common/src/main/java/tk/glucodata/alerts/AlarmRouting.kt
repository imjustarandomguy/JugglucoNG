package tk.glucodata.alerts

import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.MessageSender

/** The phone's "Where alarms ring" setting. Global, not per alert type. */
enum class AlarmRoutingMode {
    /** Phone and watch each ring, as before the setting existed. The default. */
    BOTH,

    /**
     * The watch rings. The phone stays silent while the watch is in reach and
     * off its charger, and rings in its place otherwise.
     */
    WATCH_WHEN_CONNECTED,

    /** The watch never rings or vibrates for glucose alarms. */
    PHONE_ONLY,
}

/** Settings shared by every alert that also change how the watch evaluates or rings. */
data class GlobalAlertSettings(
    val alarmRouting: AlarmRoutingMode = AlarmRoutingMode.BOTH,
    val sameDirectionSuppressionMinutes: Int = AlertDefaults.SAME_DIRECTION_SUPPRESSION_MINUTES,
    val acknowledgedHighCoverage: Boolean = AlertDefaults.ACKNOWLEDGED_HIGH_COVERAGE_ENABLED,
    /** "On the watch": how the watch rings the alarms it rings ([WatchAlarmStyle]). */
    val watchAlarmStyle: WatchAlarmStyle = WatchAlarmStyle.SAME_AS_PHONE,
) : java.io.Serializable

/**
 * Which device sounds a glucose alarm.
 *
 * Both devices still evaluate every alert on their own (AlertRuntimeManager);
 * the phone sends no alarms to the watch. Each device asks [ringsHere] when an
 * alarm would sound, at its first firing and at each timed retry, and stays
 * silent when the answer is no:
 *
 * | mode                 | watch | phone                                                    |
 * |----------------------|-------|----------------------------------------------------------|
 * | BOTH                 | rings | rings                                                    |
 * | WATCH_WHEN_CONNECTED | rings | silent only if the watch is reachable AND not charging;  |
 * |                      |       | rings when it is out of reach, charging, or unknown      |
 * | PHONE_ONLY           | never | rings                                                    |
 *
 * There is no escalation: an unanswered watch alarm never makes the phone ring,
 * and nothing is special about Very low. Whenever the phone cannot tell whether
 * the watch is reachable or charging, it rings. Reachable takes a fresh status
 * report from the watch ([WatchAlarmReadiness]), not only discovery finding its
 * app. A firing held for the watch stays pending ([offer]): if the watch drops out during the episode, the phone
 * rings for it.
 *
 * Only glucose alarms follow the setting ([routes]). Sensor expiry is a notice
 * about the sensor, not about glucose, and stays as it was: both ring.
 */
object AlarmRouting {
    private const val LOG_ID = "AlarmRouting"

    /**
     * The decision itself.
     *
     * [watchReachable] and [watchCharging] are the phone's view of the watch;
     * null is "cannot tell". The watch's own answer depends on the mode alone:
     * it is the device the setting prefers, so it never stands down for a phone
     * that might not ring.
     */
    @JvmStatic
    fun shouldRing(
        onWatch: Boolean,
        mode: AlarmRoutingMode,
        watchReachable: Boolean?,
        watchCharging: Boolean?,
    ): Boolean = when (mode) {
        AlarmRoutingMode.BOTH -> true
        AlarmRoutingMode.PHONE_ONLY -> !onWatch
        AlarmRoutingMode.WATCH_WHEN_CONNECTED ->
            onWatch || watchReachable != true || watchCharging != false
    }

    /**
     * Whether [type] follows the setting: every glucose alarm, including the
     * missed-reading and signal-loss ones, and any alert type added later.
     * Sensor expiry and the hidden legacy types keep ringing as before.
     */
    @JvmStatic
    fun routes(type: AlertType): Boolean =
        type != AlertType.SENSOR_EXPIRY && !AlertType.isLegacyOnlyId(type.id)

    /** Whether this device should sound [type] now. Never throws: any failure rings. */
    @JvmStatic
    fun ringsHere(type: AlertType): Boolean {
        if (!routes(type)) return true
        return try {
            val mode = AlertRepository.loadAlarmRouting()
            val onWatch = Applic.isWearable
            if (onWatch || mode != AlarmRoutingMode.WATCH_WHEN_CONNECTED) {
                shouldRing(onWatch, mode, watchReachable = null, watchCharging = null)
            } else {
                shouldRing(false, mode, watchAvailableFromPhone(), WatchAlarmReadiness.watchCharging())
            }
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "ringsHere ${type.name}", t)
            true
        }
    }

    /**
     * Offers a firing of [type] to this device. When it [ringsHere], [deliver]
     * sounds it and says whether it did. Otherwise it is held for the other
     * device ([AlertStateTracker.onAlertHeld]) once [mayStart], the first-fire
     * gate, lets the episode start: nothing is shown and the episode is not
     * spent, so the next offer (the next reading or check) asks again, and the
     * episode rings here as soon as the other device cannot take it. An
     * unanswered alarm on the other device never makes it ring here: only
     * [ringsHere] does. False while held.
     */
    internal fun offer(type: AlertType, ringsHere: Boolean, mayStart: () -> Boolean, deliver: () -> Boolean): Boolean {
        if (ringsHere) return deliver()
        if (!AlertStateTracker.isHeld(type) && mayStart() && AlertStateTracker.onAlertHeld(type)) {
            Log.i(LOG_ID, "Held ${type.name} for the other device (${describeInputs()})")
        }
        return false
    }

    /** What [ringsHere] decided from, for the log line of a held alarm. */
    @JvmStatic
    fun describeInputs(): String = runCatching {
        val mode = AlertRepository.loadAlarmRouting()
        if (Applic.isWearable) {
            "mode=$mode on watch"
        } else {
            "mode=$mode watchDiscovered=${watchReachableFromPhone()} watchReachable=${watchAvailableFromPhone()} " +
                "watchCharging=${WatchAlarmReadiness.watchCharging()} reportAgeMs=${WatchAlarmReadiness.reportAgeMs()}"
        }
    }.getOrDefault("inputs unavailable")

    /**
     * Phone: whether capability discovery currently finds the watch app (the
     * node list [MessageSender] keeps, the same one [MessageSender.peerUnreachable]
     * reads). Null when there is no way to know: the Wear OS companion is
     * switched off, the Wearable API is missing, or discovery has not run yet.
     */
    @JvmStatic
    fun watchReachableFromPhone(): Boolean? {
        if (Applic.isWearable) return null
        if (!MessageSender.outgoingAllowed() || !MessageSender.isWearTransportAvailable()) return null
        val nodes = MessageSender.getMessageSender()?.nodes ?: return null
        return nodes.isNotEmpty()
    }

    /**
     * Phone: whether the watch counts as reachable for [shouldRing]: discovery finds
     * it and its last status report is fresh ([WatchAlarmReadiness]). Null when
     * discovery cannot tell.
     */
    @JvmStatic
    fun watchAvailableFromPhone(): Boolean? = WatchAlarmReadiness.watchReachable(watchReachableFromPhone())
}
