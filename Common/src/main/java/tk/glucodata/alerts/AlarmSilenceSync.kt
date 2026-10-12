package tk.glucodata.alerts

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.random.Random
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.MessageSender
import tk.glucodata.Notify
import tk.glucodata.WearMessagePath

/**
 * Snoozes, dismissals and the quiet window, shared between the phone and the watch.
 *
 * Each device evaluates and rings its own alarms, so each kept its own snoozes and its
 * own dismissals: a snooze set ahead of time on the phone left the watch ringing, and an
 * alarm answered on one device kept ringing and retrying on the other. Now:
 *
 *  - a snooze of an alert type, set or cancelled on either device, from a ringing alarm
 *    or ahead of time, holds on both, with the same end;
 *  - dismissing a ringing alarm stops the same alarm on the other device (sound,
 *    vibration, alarm screen, notification, retries) and acknowledges it there, unless
 *    that alarm started after the dismissal;
 *  - the quiet window (the phone's quick-settings tile) silences both, the same way, for
 *    the same time.
 *
 * Changes are applied through the functions a local one goes through, so a change from
 * the other device has the same effects as one made here (the alarm history records it,
 * the retry session ends, the quiet window's silenced episode clears): [SnoozeManager.snooze]
 * and [SnoozeManager.clearSnooze], [AlertStateTracker.onAlertDismissed], [QuietWindow.startUntil],
 * [QuietWindow.setMode] and [QuietWindow.end]. Each takes a `fromPeer` flag, which only keeps
 * the change from being sent back.
 *
 * The protocol is state, not events: a device sends everything it holds that is recent
 * ([AlarmSilencePolicy.RETENTION_MS]) whenever it changes something, when the other
 * device comes into reach, and again when it hears from that device after a send failed.
 * The receiver keeps the later change per alert type ([AlarmSilencePolicy.reconcile]) and
 * answers with its own state only when it holds something newer, never answering an
 * answer. A device that was out of reach therefore catches up as soon as either one hears
 * of the other. The rules and the wire format are in AlarmSilenceModel.kt; times cross
 * the wire as ages, so the two clocks need not agree on them, and each change carries a
 * hybrid logical clock id ([ChangeId]) that orders it the same way on both devices.
 *
 * With "Where alarms ring" ([AlarmRouting]), sharing does not depend on the mode:
 *
 * | mode                 | effect                                                          |
 * |----------------------|-----------------------------------------------------------------|
 * | BOTH                 | both ring; a snooze, a dismissal or a quiet window set on       |
 * |                      | either silences both                                            |
 * | WATCH_WHEN_CONNECTED | a snooze or quiet window set on the phone (tile, preemptive     |
 * |                      | snooze) silences the watch, which is the one ringing; a snooze  |
 * |                      | or dismissal on the watch also holds on the phone, so the phone |
 * |                      | does not ring for it when it takes over (watch away or charging)|
 * | PHONE_ONLY           | the watch never rings; it holds the same snoozes, shows them    |
 * |                      | on its alerts screen, and a cancel there reaches the phone      |
 *
 * A snooze from a ringing alarm also starts the alarm afresh on both devices once it ends,
 * as it always did on the device where it was made, so it comes back wherever it rings
 * by then. Custom alerts exist on the phone only and are not shared.
 *
 * Older builds do not know `/sync2/silence` and drop it (MessageReceiver logs the unknown
 * path once); WearProtocol.VERSION is unchanged.
 */
object AlarmSilenceSync {
    private const val LOG_ID = "AlarmSilenceSync"
    private const val PREFS_NAME = "tk.glucodata.alarmsilence"
    private const val KEY_SNOOZE_CHANGED = "snooze_changed_"
    private const val KEY_DISMISSED = "dismissed_"
    private const val KEY_QUIET_CHANGED = "quiet_changed"
    private const val KEY_ORIGIN = "origin"
    private const val KEY_CLOCK_MS = "hlc_ms"
    private const val KEY_CLOCK_COUNTER = "hlc_counter"

    // Suffixes of an entry's key for its change id ([ChangeId]); none when the change came
    // from an older build.
    private const val HLC_MS = "_hlc_ms"
    private const val COUNTER = "_hlc_counter"
    private const val ORIGIN = "_origin"

    /** After a failed send, hearing from the other device sends again, no more often than this. */
    private const val RESEND_MIN_INTERVAL_MS = 60_000L

    /** An alarm screen on show, so a change from the other device can close it. */
    fun interface AlarmScreen {
        /** Close, if this screen shows the built-in alert [alertTypeId]. Any thread. */
        fun closeFor(alertTypeId: Int)
    }

    private val screens = CopyOnWriteArraySet<AlarmScreen>()

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, LOG_ID).apply { isDaemon = true }
    }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val sendLock = Any()
    private var sendQueued = false
    private var queuedAsReply = true

    /** The last send did not reach the other device. */
    @Volatile
    private var owed = false

    @Volatile
    private var lastSendAttemptMs = 0L

    /** When the signal-loss alarm last started here; it has no episode to date it by. */
    @Volatile
    private var lossAlarmStartedAtMs = 0L

    /** Null without an app: the alert state tracker's unit tests reach the hooks. */
    private fun prefs(): SharedPreferences? =
        Applic.app?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val idLock = Any()

    /** This device's origin: random, made once, and made anew after its data is cleared. */
    private fun origin(store: SharedPreferences): Int = synchronized(idLock) {
        store.getInt(KEY_ORIGIN, 0).takeIf { it != 0 } ?: run {
            var made = 0
            while (made == 0) made = Random.nextInt()
            store.edit().putInt(KEY_ORIGIN, made).apply()
            made
        }
    }

    /** The highest id this device made or saw (its origin plays no part), null before any. */
    private fun clock(store: SharedPreferences): ChangeId? {
        val ms = store.getLong(KEY_CLOCK_MS, 0L)
        return if (ms > 0L) ChangeId(ms, store.getInt(KEY_CLOCK_COUNTER, 0), 0) else null
    }

    private fun setClock(store: SharedPreferences, id: ChangeId) {
        store.edit().putLong(KEY_CLOCK_MS, id.hlcMs).putInt(KEY_CLOCK_COUNTER, id.counter).apply()
    }

    /** The id of a change made here now: after every one this device made or saw. */
    private fun nextId(store: SharedPreferences): ChangeId = synchronized(idLock) {
        ChangeId.next(clock(store), System.currentTimeMillis(), origin(store)).also { setClock(store, it) }
    }

    /** Every message from the other device moves the clock up to the ids it carries. */
    private fun seen(store: SharedPreferences, state: SilenceState) {
        val newest = ChangeId.newest(state) ?: return
        synchronized(idLock) {
            val clock = clock(store)
            if (clock == null || newest > clock) setClock(store, newest)
        }
    }

    private fun SharedPreferences.Editor.putId(key: String, id: ChangeId?): SharedPreferences.Editor =
        if (id == null) {
            remove(key + HLC_MS).remove(key + COUNTER).remove(key + ORIGIN)
        } else {
            putLong(key + HLC_MS, id.hlcMs).putInt(key + COUNTER, id.counter).putInt(key + ORIGIN, id.origin)
        }

    private fun SharedPreferences.idOf(key: String): ChangeId? {
        val ms = getLong(key + HLC_MS, 0L)
        return if (ms > 0L) ChangeId(ms, getInt(key + COUNTER, 0), getInt(key + ORIGIN, 0)) else null
    }

    // ------------------------------------------------------------ local changes

    /**
     * [SnoozeManager] set or cancelled [type]'s snooze here, at the user's hand. Not called
     * for a change from the other device, nor when a snooze runs out.
     */
    @JvmStatic
    fun onLocalSnoozeChanged(type: AlertType) {
        if (!synced(type)) return
        val store = prefs() ?: return
        val key = KEY_SNOOZE_CHANGED + type.id
        store.edit().putLong(key, System.currentTimeMillis()).putId(key, nextId(store)).apply()
        requestSend(reply = false)
    }

    /** [AlertStateTracker] took a dismissal of [type] made here. */
    @JvmStatic
    fun onLocalDismiss(type: AlertType) {
        if (!synced(type)) return
        val store = prefs() ?: return
        val key = KEY_DISMISSED + type.id
        store.edit().putLong(key, System.currentTimeMillis()).putId(key, nextId(store)).apply()
        requestSend(reply = false)
    }

    /** [QuietWindow] started, ended or changed mode here. Not called when it runs out. */
    @JvmStatic
    fun onLocalQuietWindowChanged() {
        val store = prefs() ?: return
        store.edit().putLong(KEY_QUIET_CHANGED, System.currentTimeMillis())
            .putId(KEY_QUIET_CHANGED, nextId(store)).apply()
        requestSend(reply = false)
    }

    /** Notify started the signal-loss alarm. */
    @JvmStatic
    fun onLossAlarmSounding() {
        lossAlarmStartedAtMs = System.currentTimeMillis()
    }

    /** True once the other device's dismissal took the signal loss held here, until a reading. */
    @Volatile
    private var lossAnswered = false

    /** Notify held the signal-loss alarm for the other device: dated at its first hold. */
    @JvmStatic
    fun onLossAlarmHeld() {
        if (lossAlarmStartedAtMs == 0L) lossAlarmStartedAtMs = System.currentTimeMillis()
    }

    /** Whether the signal loss running here was dismissed on the other device. */
    @JvmStatic
    fun lossAnsweredOnPeer(): Boolean = lossAnswered

    /** A reading arrived: the signal loss is over. */
    @JvmStatic
    fun onLossOver() {
        lossAnswered = false
        lossAlarmStartedAtMs = 0L
    }

    // ------------------------------------------------------------ transport

    /** Discovery found, or lost, the other device. Coming into reach is when to catch up. */
    @JvmStatic
    fun onPeerReachabilityChanged(reachable: Boolean) {
        if (reachable) requestSend(reply = false)
    }

    /** Any message from the other device: it is in reach, so a failed send can go now. */
    @JvmStatic
    fun onPeerHeard() {
        if (!owed) return
        if (System.currentTimeMillis() - lastSendAttemptMs < RESEND_MIN_INTERVAL_MS) return
        requestSend(reply = false)
    }

    /** A `/sync2/silence` message. Never throws. */
    @JvmStatic
    fun onPeerMessage(data: ByteArray?) {
        if (Applic.app == null) return
        val message = AlarmSilenceCodec.decode(data, System.currentTimeMillis())
        if (message == null) {
            Log.w(LOG_ID, "ignoring an unreadable silence message (${data?.size ?: 0} bytes)")
            return
        }
        // The alarm paths this reaches (sound, alarm screen, retry session) run on the
        // main thread when a person acts; a change from the other device does too.
        mainHandler.post {
            try {
                apply(message)
            } catch (t: Throwable) {
                Log.stack(LOG_ID, "apply", t)
            }
        }
    }

    @JvmStatic
    fun registerAlarmScreen(screen: AlarmScreen) {
        screens.add(screen)
    }

    @JvmStatic
    fun unregisterAlarmScreen(screen: AlarmScreen) {
        screens.remove(screen)
    }

    private fun requestSend(reply: Boolean) {
        if (Applic.app == null) return
        synchronized(sendLock) {
            if (sendQueued) {
                // The queued send reads the state when it runs, so it carries this change too.
                if (!reply) queuedAsReply = false
                return
            }
            sendQueued = true
            queuedAsReply = reply
        }
        try {
            executor.execute {
                val asReply = synchronized(sendLock) {
                    sendQueued = false
                    queuedAsReply
                }
                send(asReply)
            }
        } catch (t: Throwable) {
            synchronized(sendLock) { sendQueued = false }
            Log.stack(LOG_ID, "requestSend", t)
        }
    }

    private fun send(reply: Boolean) {
        val nowMs = System.currentTimeMillis()
        lastSendAttemptMs = nowMs
        val delivered = try {
            val payload = AlarmSilenceCodec.encode(localState(nowMs), nowMs, reply)
            // Blocks until the Data Layer took it for every node in reach; false when none is.
            MessageSender.sendSyncMessageAwait(WearMessagePath.SYNC2_SILENCE, payload)
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "send", t)
            false
        }
        owed = !delivered
        if (!delivered) Log.i(LOG_ID, "silence state not delivered; sent again when the other device is heard")
    }

    // ------------------------------------------------------------ state

    private fun synced(type: AlertType): Boolean = !AlertType.isLegacyOnlyId(type.id)

    private fun syncedTypes(): List<AlertType> = AlertType.entries.filter(::synced)

    /** What this device holds, on its own clock. */
    private fun localState(nowMs: Long): SilenceState {
        val store = prefs() ?: return SilenceState()
        val snoozes = syncedTypes().mapNotNull { type ->
            val changedAt = store.getLong(KEY_SNOOZE_CHANGED + type.id, 0L)
            if (changedAt <= 0L) return@mapNotNull null
            val snooze = SnoozeManager.getSnoozeState(type)
            SnoozeEntry(
                type.id,
                changedAt,
                snooze?.snoozeUntilMillis ?: 0L,
                snooze?.isPreemptive ?: false,
                store.idOf(KEY_SNOOZE_CHANGED + type.id),
            )
        }
        val dismissals = syncedTypes().mapNotNull { type ->
            val at = store.getLong(KEY_DISMISSED + type.id, 0L)
            if (at > 0L) DismissEntry(type.id, at, store.idOf(KEY_DISMISSED + type.id)) else null
        }
        val quietChangedAt = store.getLong(KEY_QUIET_CHANGED, 0L)
        val quiet = if (quietChangedAt > 0L) {
            QuietEntry(
                changedAtMs = quietChangedAt,
                untilMs = QuietWindow.untilMs(nowMs),
                mode = QuietWindow.mode(),
                breakthroughMinutes = QuietWindow.breakthroughMinutes(),
                breakthroughScope = QuietWindow.breakthroughScope(),
                id = store.idOf(KEY_QUIET_CHANGED),
            )
        } else {
            null
        }
        return AlarmSilencePolicy.retained(SilenceState(snoozes, dismissals, quiet), nowMs)
    }

    // ------------------------------------------------------------ applying

    private fun apply(message: AlarmSilenceCodec.Message) {
        val nowMs = System.currentTimeMillis()
        prefs()?.let { seen(it, message.state) }
        val result = AlarmSilencePolicy.reconcile(localState(nowMs), message.state, message.reply, nowMs)
        for (action in result.actions) {
            try {
                applyAction(action)
            } catch (t: Throwable) {
                Log.stack(LOG_ID, "apply $action", t)
            }
        }
        if (result.actions.isNotEmpty()) {
            Log.i(LOG_ID, "applied ${result.actions.size} change(s) from the other device")
        }
        // Only what this device holds that is newer; never an answer to an answer.
        if (result.reply) requestSend(reply = true)
    }

    private fun applyAction(action: SilenceAction) {
        val store = prefs() ?: return
        when (action) {
            is SilenceAction.Snooze -> {
                val type = AlertType.fromId(action.typeId)?.takeIf(::synced) ?: return
                val kindBefore = Notify.resolveAlertKind(-1)
                SnoozeManager.snooze(
                    type,
                    action.minutes,
                    preemptive = action.preemptive,
                    fromPeer = true,
                    untilMs = action.untilMs,
                )
                store.edit().putLong(KEY_SNOOZE_CHANGED + type.id, action.changedAtMs)
                    .putId(KEY_SNOOZE_CHANGED + type.id, action.id).apply()
                if (!action.preemptive && AlertStateTracker.isEpisodeActive(type)) {
                    // A snooze from a ringing alarm: the other device also started the
                    // episode afresh, so the alarm comes back on both when it ends.
                    AlertStateTracker.resetState(type)
                }
                stopRinging(type, kindBefore, "peer-snooze")
                Log.i(LOG_ID, "snoozed ${type.name} from the other device (preemptive=${action.preemptive})")
            }
            is SilenceAction.ClearSnooze -> {
                val type = AlertType.fromId(action.typeId)?.takeIf(::synced) ?: return
                SnoozeManager.clearSnooze(type, fromPeer = true)
                store.edit().putLong(KEY_SNOOZE_CHANGED + type.id, action.changedAtMs)
                    .putId(KEY_SNOOZE_CHANGED + type.id, action.id).apply()
                Log.i(LOG_ID, "snooze of ${type.name} ended from the other device")
            }
            is SilenceAction.AdoptSnoozeTime -> {
                store.edit().putLong(KEY_SNOOZE_CHANGED + action.typeId, action.changedAtMs)
                    .putId(KEY_SNOOZE_CHANGED + action.typeId, action.id).apply()
            }
            is SilenceAction.Dismiss -> {
                val type = AlertType.fromId(action.typeId)?.takeIf(::synced) ?: return
                // Recorded whether or not an alarm here is the one dismissed: the same
                // dismissal is then not taken again.
                store.edit().putLong(KEY_DISMISSED + type.id, action.dismissedAtMs)
                    .putId(KEY_DISMISSED + type.id, action.id).apply()
                dismissFromPeer(type, action.dismissedAtMs, mayRecheck = true)
            }
            is SilenceAction.StartQuiet -> {
                val context = Applic.app ?: return
                val entry = action.entry
                if (Applic.isWearable) {
                    // The phone owns these settings, and the watch has no screen for them.
                    QuietWindow.setBreakthroughMinutes(entry.breakthroughMinutes)
                    QuietWindow.setBreakthroughScope(entry.breakthroughScope)
                }
                val runningUntil = QuietWindow.untilMs(System.currentTimeMillis())
                if (runningUntil > 0L &&
                    Math.abs(runningUntil - entry.untilMs) <= AlarmSilencePolicy.SAME_CHANGE_TOLERANCE_MS
                ) {
                    // The same window in another mode: a mode change, as it was made there.
                    // A fresh start would restart the breakthrough clock of a silenced alarm.
                    QuietWindow.setMode(context, entry.mode, fromPeer = true)
                } else {
                    QuietWindow.startUntil(context, entry.untilMs, entry.mode, fromPeer = true)
                }
                store.edit().putLong(KEY_QUIET_CHANGED, entry.changedAtMs).putId(KEY_QUIET_CHANGED, entry.id).apply()
                Log.i(LOG_ID, "quiet window from the other device until ${entry.untilMs}")
            }
            is SilenceAction.EndQuiet -> {
                val context = Applic.app ?: return
                if (QuietWindow.untilMs(System.currentTimeMillis()) > 0L) {
                    QuietWindow.end(context, fromPeer = true)
                }
                store.edit().putLong(KEY_QUIET_CHANGED, action.changedAtMs).putId(KEY_QUIET_CHANGED, action.id).apply()
                Log.i(LOG_ID, "quiet window ended from the other device")
            }
            is SilenceAction.AdoptQuietTime -> {
                store.edit().putLong(KEY_QUIET_CHANGED, action.changedAtMs).putId(KEY_QUIET_CHANGED, action.id).apply()
            }
        }
    }

    /**
     * The other device dismissed [type] at [dismissedAtMs]: this device acknowledges its own
     * alarm of that type as a local dismissal would, if that alarm started before then. An
     * alarm not running yet may still start within the tolerance, on the same reading a few
     * seconds later; it is looked for once more when the tolerance runs out.
     */
    private fun dismissFromPeer(type: AlertType, dismissedAtMs: Long, mayRecheck: Boolean) {
        // The signal-loss alarm has no episode in the tracker; its start is noted here.
        val tracked = type != AlertType.LOSS
        val startedAtMs = if (tracked) AlertStateTracker.episodeStartedAtMs(type) else lossAlarmStartedAtMs
        val alreadyDismissed = tracked && AlertStateTracker.isDismissed(type)
        if (!AlarmSilencePolicy.dismissApplies(dismissedAtMs, startedAtMs, alreadyDismissed)) {
            val delayMs = AlarmSilencePolicy.dismissRecheckDelayMs(dismissedAtMs, System.currentTimeMillis())
            if (mayRecheck && startedAtMs <= 0L && delayMs >= 0L) {
                mainHandler.postDelayed({
                    try {
                        dismissFromPeer(type, dismissedAtMs, mayRecheck = false)
                    } catch (t: Throwable) {
                        Log.stack(LOG_ID, "dismiss recheck", t)
                    }
                }, delayMs + 500L)
            }
            return
        }
        val kindBefore = Notify.resolveAlertKind(-1)
        // The local dismiss path: AlarmActivity, AlarmActionReceiver, Notify.acknowledgeCurrentAlert.
        if (AlertStateTracker.onAlertDismissed(type, fromPeer = true)) {
            val wasSnoozed = SnoozeManager.isSnoozed(type)
            SnoozeManager.clearSnooze(type, fromPeer = true)
            if (wasSnoozed) {
                // Ended by that dismissal, not by a change of its own: no id.
                prefs()?.edit()?.putLong(KEY_SNOOZE_CHANGED + type.id, dismissedAtMs)
                    ?.putId(KEY_SNOOZE_CHANGED + type.id, null)?.apply()
            }
            Notify.cancelRetrySession(type.id, "peer-dismiss")
        }
        if (!tracked) {
            lossAlarmStartedAtMs = 0L
            lossAnswered = true
        }
        stopRinging(type, kindBefore, "peer-dismiss")
        Log.i(LOG_ID, "dismissed ${type.name} from the other device")
    }

    /**
     * What a person's snooze or dismissal does to the alarm on show, for [type] only:
     * another alarm ringing here keeps ringing. [kindBefore] is the alert Notify was
     * sounding or retrying before the change, read before the change ended its retries.
     */
    private fun stopRinging(type: AlertType, kindBefore: Int, reason: String) {
        Notify.cancelQueuedAlarmActivityLaunch(type.id, null, reason)
        if (kindBefore == type.id) {
            Notify.stopalarm()
            // Stopping schedules the next retry of a session still open; it must not be.
            Notify.cancelRetrySession(type.id, "$reason-after-stop")
            Notify.cancelAlertNotification()
        }
        // The watch's alarm screen notification goes with the screen, once the sound is over too.
        Notify.cancelAlarmScreenNotification(type.id)
        closeAlarmScreens(type.id)
    }

    /**
     * Closes the alarm screens on show for the built-in alert [alertTypeId]: the other
     * device snoozed or dismissed it, or stopped its test alarm ([AlarmTestSync]).
     */
    @JvmStatic
    fun closeAlarmScreens(alertTypeId: Int) {
        screens.forEach { screen ->
            try {
                screen.closeFor(alertTypeId)
            } catch (t: Throwable) {
                Log.stack(LOG_ID, "close alarm screen", t)
            }
        }
    }
}
