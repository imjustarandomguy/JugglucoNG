package tk.glucodata.alerts

import tk.glucodata.Log
import tk.glucodata.sms.SmsWatchdog

/**
 * Tracks episode state for active alerts.
 *
 * The first firing for an episode comes from the display-lane alert runtime.
 * Timed retries are scheduled by Notify after that first firing, so this
 * tracker only needs to answer "has this episode already fired or been
 * acknowledged?".
 */
object AlertStateTracker {
    private const val LOG_ID = "AlertStateTracker"
    private const val DEFAULT_REARM_COOLDOWN_MS = 5L * 60L * 1000L

    // Last time an alert of this type was triggered (ms)
    private val lastTriggerTime = mutableMapOf<AlertType, Long>()
    private val cooldownUntilTime = mutableMapOf<AlertType, Long>()

    // Acknowledgement of the last delivery outlives its episode and snooze.
    // Only a new real firing replaces it; the directional window owns expiry/reversal.
    private val lastFiringAcknowledged = mutableMapOf<AlertType, Boolean>()
    
    // User explicitly dismissed this alert for the current episode.
    // It stays suppressed until the condition clears and resetState() is called.
    private val dismissedAlerts = mutableSetOf<AlertType>()

    // Manual tests use the real delivery surface, but must never acknowledge,
    // snooze, or cool down the corresponding production alert episode.
    private val manualTests = ManualAlertTestState<AlertType>()

    /**
     * Determine if the runtime should fire an alert now.
     *
     * Once an episode has fired, timed retries are handled by Notify rather than
     * by subsequent glucose readings, so repeated live readings stay suppressed
     * until resetState() is called.
     */
    @Synchronized
    fun shouldTrigger(type: AlertType, config: AlertConfig): Boolean {
        if (manualTests.consumeBypassAndActivate(type)) {
            Log.i(LOG_ID, "${type.name}: Manual test bypass")
            return true
        }

        if (!config.isActiveNow()) {
            // Treat inactive time windows as condition-cleared boundaries so the alert
            // rearms cleanly when the active window starts again.
            resetState(type)
            return false
        }

        // 1. Snooze Check (Global priority)
        if (SnoozeManager.isSnoozed(type)) {
            Log.i(LOG_ID, "${type.name}: Suppressed by snooze")
            return false
        }

        if (dismissedAlerts.contains(type)) {
            Log.i(LOG_ID, "${type.name}: Suppressed by episode dismissal")
            return false
        }

        val now = System.currentTimeMillis()
        val cooldownUntil = cooldownUntilTime[type] ?: 0L
        if (cooldownUntil > now) {
            Log.i(LOG_ID, "${type.name}: Suppressed by rearm cooldown for ${cooldownUntil - now}ms")
            return false
        }

        val lastTime = lastTriggerTime[type] ?: 0L
        if (lastTime == 0L) {
            // Only an accepted real trigger supersedes an unacknowledged test surface.
            manualTests.supersede(type)
            Log.i(LOG_ID, "${type.name}: First trigger")
            return true
        }

        return false
    }

    /**
     * Call this when the alert ACTUALLY fires (sound/notification played).
     * Updates timestamps and counters.
     */
    @JvmOverloads
    @Synchronized
    fun onAlertTriggered(type: AlertType, config: AlertConfig? = null): Boolean {
        if (manualTests.isActive(type)) {
            return false
        }
        dismissedAlerts.remove(type)
        lastFiringAcknowledged[type] = false
        lastTriggerTime[type] = System.currentTimeMillis()
        cooldownUntilTime[type] = lastTriggerTime.getValue(type) + effectiveRearmCooldownMs(config)
        SmsWatchdog.onAlertFired(type.id)
        return true
    }

    /**
     * A firing the other device sounds ([AlarmRouting]): spends the episode and
     * its rearm cooldown here as [onAlertTriggered] does, so this device does
     * not ring for it later, when the other one drops out of reach say. Arms no
     * SMS watchdog: nothing shown here can be acknowledged here.
     */
    @Synchronized
    fun onAlertHeld(type: AlertType, config: AlertConfig? = null) {
        if (manualTests.isActive(type)) {
            return
        }
        dismissedAlerts.remove(type)
        lastTriggerTime[type] = System.currentTimeMillis()
        cooldownUntilTime[type] = lastTriggerTime.getValue(type) + effectiveRearmCooldownMs(config)
    }

    /**
     * The rearm cooldown after a firing. The configured minimum re-arm
     * interval (forecast alerts) extends the built-in short cooldown, never
     * shortens it; unset/0 keeps today's behaviour.
     */
    internal fun effectiveRearmCooldownMs(config: AlertConfig?): Long {
        val configuredMs = (config?.rearmMinIntervalMinutes ?: 0).coerceAtLeast(0) * 60_000L
        return maxOf(DEFAULT_REARM_COOLDOWN_MS, configuredMs)
    }

    // Set while a dismissal from the other device runs through the one below.
    private var dismissingFromPeer = false

    /**
     * A person dismissed [type]'s alarm on the other device ([AlarmSilenceSync]):
     * this device takes it as its own dismissal, through the same path, and does
     * not send it back.
     */
    @Synchronized
    fun onAlertDismissed(type: AlertType, fromPeer: Boolean): Boolean {
        if (!fromPeer) return onAlertDismissed(type)
        dismissingFromPeer = true
        try {
            return onAlertDismissed(type)
        } finally {
            dismissingFromPeer = false
        }
    }

    @Synchronized
    fun onAlertDismissed(type: AlertType): Boolean {
        if (manualTests.consumeAction(type)) {
            // A test alarm: nothing to record, but the test stops on the other device too.
            if (!dismissingFromPeer) AlarmTestSync.onLocalTestStopped(type)
            return false
        }
        dismissedAlerts.add(type)
        lastFiringAcknowledged.replace(type, true)
        SmsWatchdog.onAlertAcknowledged(type.id)
        // Acknowledged: a quiet window's silenced episode must not break through now.
        QuietWindow.clearSilencedEpisode(type.id)
        Log.i(LOG_ID, "Dismissed ${type.name} for current episode")
        // The same alarm stops on the other device; one it dismissed is not sent back.
        if (!dismissingFromPeer) AlarmSilenceSync.onLocalDismiss(type)
        return true
    }

    /** A snooze of [type]'s alarm: true, and nothing snoozed, when that alarm is a test. */
    @Synchronized
    fun consumeManualTestAction(type: AlertType): Boolean {
        if (!manualTests.consumeAction(type)) return false
        // The test stops on the other device too ([AlarmTestSync]).
        AlarmTestSync.onLocalTestStopped(type)
        return true
    }

    /**
     * The other device stopped the test alarm of [type] ([AlarmTestSync]): it ends here
     * as a dismissal of it here would, recording nothing and sending nothing back. False
     * when no test of [type] is on here: a real alarm took over, or it was answered here.
     */
    @Synchronized
    fun endManualTestFromPeer(type: AlertType): Boolean = manualTests.consumeAction(type)

    @Synchronized
    fun isWaitingForRearmCooldown(type: AlertType): Boolean {
        return type !in dismissedAlerts &&
            (cooldownUntilTime[type] ?: 0L) > System.currentTimeMillis()
    }

    @Synchronized
    fun allowNextTriggerForTest(type: AlertType) {
        manualTests.arm(type)
    }

    /** True between the episode's first firing and [resetState]. */
    @Synchronized
    fun isEpisodeActive(type: AlertType): Boolean = lastTriggerTime.containsKey(type)

    /** True once [onAlertDismissed] took this episode, until [resetState]. */
    @Synchronized
    fun isDismissed(type: AlertType): Boolean = type in dismissedAlerts

    /**
     * When the running episode first fired, or was held for the other device; 0 with
     * none. A dismissal from the other device reaches only an episode that started
     * before it ([AlarmSilencePolicy.dismissApplies]).
     */
    @Synchronized
    fun episodeStartedAtMs(type: AlertType): Long = lastTriggerTime[type] ?: 0L

    /** Whether the last real firing was acknowledged for the cross-family quiet period. */
    @Synchronized
    internal fun wasLastFiringAcknowledged(type: AlertType): Boolean = lastFiringAcknowledged[type] == true

    /** Called for an accepted alarm snooze, never for a preemptive snooze. */
    @Synchronized
    internal fun onAlertSnoozed(type: AlertType) {
        if (!manualTests.isActive(type)) {
            lastFiringAcknowledged.replace(type, true)
        }
    }

    /**
     * Reset state for an alert type.
     * Call this when:
     * - Glucose returns to normal.
     * - The alert's active time window closes.
     */
    @Synchronized
    fun resetState(type: AlertType) {
        if (
            lastTriggerTime.containsKey(type) ||
            dismissedAlerts.contains(type) ||
            manualTests.isPending(type)
        ) {
            Log.i(LOG_ID, "Resetting state for ${type.name}")
        }
        // The condition cleared: a quiet window's silenced episode for this kind
        // is over too, so it must not break through later.
        QuietWindow.clearSilencedEpisode(type.id)
        lastTriggerTime.remove(type)
        dismissedAlerts.remove(type)
        manualTests.clearPending(type)
        SmsWatchdog.onAlertResolved(type.id)
    }
}

/** Keeps manual-test actions separate from production alert episode state. */
internal class ManualAlertTestState<T> {
    private val pending = mutableSetOf<T>()
    private val active = mutableSetOf<T>()

    fun arm(key: T) {
        pending.add(key)
    }

    fun consumeBypassAndActivate(key: T): Boolean {
        if (!pending.remove(key)) return false
        active.add(key)
        return true
    }

    fun supersede(key: T) {
        active.remove(key)
    }

    fun consumeAction(key: T): Boolean = active.remove(key)

    fun clearPending(key: T) {
        pending.remove(key)
    }

    fun isPending(key: T): Boolean = key in pending

    fun isActive(key: T): Boolean = key in active
}
