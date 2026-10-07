package tk.glucodata.alerts

import android.util.AtomicFile
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.MessageSender
import tk.glucodata.WearMessagePath

/**
 * The alarm history at runtime: one call per moment in an alarm's life, from the
 * places that already decide those moments.
 *
 *  - [onFired]     Notify, on an alarm's first real firing (never a test alarm);
 *  - [onDismissed] AlertStateTracker, on a real dismissal;
 *  - [onSnoozed]   SnoozeManager, on any snooze;
 *  - [onCleared]   AlertRuntimeManager, when it clears an alert's runtime state.
 *
 * The rules are [AlarmHistoryPolicy]'s; this object only holds the list, keeps it
 * in a file and, on the watch, delivers it.
 *
 * Storage: a bounded text file per device ([AlarmHistoryCodec.encodeStore]), not a
 * Room table. The recording happens in shared code that runs on both devices, Room
 * is a phone-only dependency here, and the watch needs a persistent buffer of the
 * same events anyway; one file format serves both, and there is no schema or
 * migration to carry for a list capped at a few thousand short lines.
 *
 * Phone: keeps 30 days ([AlarmHistoryPolicy.RETENTION_MS]) of its own events and of
 * those its watch reported. Watch: keeps only what it has not delivered yet plus the
 * open events it may still close, at most [AlarmHistoryPolicy.WATCH_MAX_EVENTS], and
 * sends them on [WearMessagePath.SYNC2_ALARM_HISTORY] whenever it hears from the phone.
 *
 * All state is confined to one background thread; the hooks only enqueue work, so the
 * alarm path never waits on a file.
 */
object AlarmHistory {
    private const val LOG_ID = "AlarmHistory"
    private const val FILE_NAME = "alarm_history.txt"

    /** AlertRuntimeManager's clear for a standard alert, whatever made its condition go away. */
    private const val STANDARD_CLEAR_REASON = "standard-condition-cleared"

    /** The watch retries a failed delivery no more often than this when it hears the phone. */
    private const val PEER_FLUSH_MIN_INTERVAL_MS = 60_000L

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AlarmHistory").apply { isDaemon = true }
    }
    private val sendExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AlarmHistorySend").apply { isDaemon = true }
    }

    // ---- executor-confined ----
    private var loaded = false
    private var store: List<AlarmEvent> = emptyList()
    private var flushInFlight = false

    /**
     * Alert ids with an open event on this device, or null before the file is read. The
     * runtime clears every quiet alert every 15 seconds; this lets those calls return at once.
     */
    @Volatile
    private var openLocalTypes: Set<Int>? = null

    @Volatile
    private var hasPending = false

    @Volatile
    private var lastFlushAttemptMs = 0L

    private val _events = MutableStateFlow<List<AlarmEvent>>(emptyList())

    /** The history this device holds, oldest first. The phone's screen renders it. */
    val events: StateFlow<List<AlarmEvent>> = _events.asStateFlow()

    private val localDevice: AlarmDevice
        get() = if (Applic.isWearable) AlarmDevice.WATCH else AlarmDevice.PHONE

    // ------------------------------------------------------------------ hooks

    /** An alarm's first real firing. [value] is in the displayed unit; NaN when there is none. */
    @JvmStatic
    fun onFired(alertTypeId: Int, value: Float) {
        val nowMs = System.currentTimeMillis()
        val unit = Applic.unit
        submit {
            val device = localDevice
            val fired = AlarmEvent(
                id = AlarmHistoryPolicy.newId(device, alertTypeId, nowMs),
                firedAtMs = nowMs,
                alertTypeId = alertTypeId,
                value = if (value.isFinite()) value else Float.NaN,
                unit = unit,
                device = device,
                pendingDelivery = device == AlarmDevice.WATCH,
            )
            commit(AlarmHistoryPolicy.onFired(store, fired, nowMs))
        }
    }

    @JvmStatic
    fun onDismissed(alertTypeId: Int) {
        closeOpen(alertTypeId, AlarmOutcome.DISMISSED, 0)
    }

    @JvmStatic
    fun onSnoozed(alertTypeId: Int, minutes: Int) {
        closeOpen(alertTypeId, AlarmOutcome.SNOOZED, minutes)
    }

    /** The runtime cleared [alertTypeId] for [reason]; see [AlarmHistoryPolicy.outcomeForClearReason]. */
    @JvmStatic
    fun onCleared(alertTypeId: Int, reason: String?) {
        val open = openLocalTypes
        if (open != null && alertTypeId !in open) return
        val outcome = AlarmHistoryPolicy.outcomeForClearReason(reason) ?: return
        val nowMs = System.currentTimeMillis()
        submit {
            // The standard evaluator reports a switched-off or out-of-hours alert as an
            // ordinary clear; nothing recovered there.
            val resolved = if (reason == STANDARD_CLEAR_REASON && !alertStillActive(alertTypeId)) {
                AlarmOutcome.ENDED
            } else {
                outcome
            }
            closeOpenLocked(alertTypeId, resolved, 0, nowMs)
        }
    }

    // ------------------------------------------------------------------ phone

    /** Reads the file now, so a screen that opens shows the history without waiting for an alarm. */
    @JvmStatic
    fun ensureLoaded() {
        submit { }
    }

    /** Phone: events a watch reported. Only watch events are taken from a peer. */
    @JvmStatic
    fun onPeerEvents(data: ByteArray?) {
        if (Applic.isWearable) return
        val incoming = AlarmHistoryCodec.decodeMessage(data).filter { it.device == AlarmDevice.WATCH }
        if (incoming.isEmpty()) {
            Log.w(LOG_ID, "ignoring alarm history message without usable events")
            return
        }
        submit {
            commit(AlarmHistoryPolicy.upsert(store, incoming))
            Log.i(LOG_ID, "received ${incoming.size} watch alarm event(s)")
        }
    }

    // ------------------------------------------------------------------ watch

    /** Watch: the phone was just heard from, so what is held can go now. */
    @JvmStatic
    fun onPeerHeard() {
        if (!Applic.isWearable || !hasPending) return
        if (System.currentTimeMillis() - lastFlushAttemptMs < PEER_FLUSH_MIN_INTERVAL_MS) return
        flushToPhone()
    }

    /** Watch: sends what the phone has not had yet; a failed send stays pending. */
    @JvmStatic
    fun flushToPhone() {
        if (!Applic.isWearable) return
        submit {
            if (flushInFlight) return@submit
            val batch = store.filter { it.pendingDelivery }.take(AlarmHistoryCodec.MAX_EVENTS_PER_MESSAGE)
            if (batch.isEmpty()) return@submit
            flushInFlight = true
            lastFlushAttemptMs = System.currentTimeMillis()
            val payload = AlarmHistoryCodec.encodeMessage(batch)
            sendExecutor.execute {
                // Blocks until the Data Layer accepted it for every connected node.
                val delivered = runCatching {
                    MessageSender.sendSyncMessageAwait(WearMessagePath.SYNC2_ALARM_HISTORY, payload)
                }.onFailure { Log.stack(LOG_ID, "flushToPhone", it) }.getOrDefault(false)
                submit {
                    flushInFlight = false
                    if (delivered) {
                        commit(AlarmHistoryPolicy.acknowledge(store, batch))
                        Log.i(LOG_ID, "delivered ${batch.size} alarm event(s) to the phone")
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ internals

    /**
     * A person's action: rare, so it always goes through the queue, behind the firing it
     * answers (no shortcut that could read the open set before that firing is in it).
     */
    private fun closeOpen(alertTypeId: Int, outcome: AlarmOutcome, minutes: Int) {
        val nowMs = System.currentTimeMillis()
        submit { closeOpenLocked(alertTypeId, outcome, minutes, nowMs) }
    }

    private fun closeOpenLocked(alertTypeId: Int, outcome: AlarmOutcome, minutes: Int, nowMs: Long) {
        val (next, closed) = AlarmHistoryPolicy.close(store, alertTypeId, localDevice, outcome, minutes, nowMs)
        if (closed != null) commit(next)
    }

    private fun alertStillActive(alertTypeId: Int): Boolean {
        val type = AlertType.fromId(alertTypeId) ?: return true
        return runCatching {
            val config = AlertRepository.loadConfig(type)
            config.enabled && config.isActiveNow()
        }.getOrDefault(true)
    }

    private fun submit(block: () -> Unit) {
        try {
            executor.execute {
                try {
                    ensureLoadedLocked()
                    block()
                } catch (t: Throwable) {
                    Log.stack(LOG_ID, "task", t)
                }
            }
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "submit", t)
        }
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        val text = try {
            historyFile()?.readFully()?.toString(Charsets.UTF_8)
        } catch (_: java.io.FileNotFoundException) {
            null // nothing recorded yet
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "load", t)
            null
        }
        store = bound(AlarmHistoryCodec.decodeStore(text))
        publish()
    }

    private fun commit(next: List<AlarmEvent>) {
        store = bound(next)
        publish()
        persist()
        if (Applic.isWearable && hasPending) flushToPhone()
    }

    private fun bound(events: List<AlarmEvent>): List<AlarmEvent> {
        val nowMs = System.currentTimeMillis()
        return if (Applic.isWearable) {
            AlarmHistoryPolicy.boundWatchBuffer(
                AlarmHistoryPolicy.prune(events, nowMs, AlarmHistoryPolicy.WATCH_MAX_EVENTS)
            )
        } else {
            AlarmHistoryPolicy.prune(events, nowMs, AlarmHistoryPolicy.PHONE_MAX_EVENTS)
        }
    }

    private fun publish() {
        val device = localDevice
        openLocalTypes = store.filter { it.isOpen && it.device == device }.mapTo(HashSet()) { it.alertTypeId }
        hasPending = store.any { it.pendingDelivery }
        _events.value = store
    }

    private fun persist() {
        val file = historyFile() ?: return
        val bytes = AlarmHistoryCodec.encodeStore(store).toByteArray(Charsets.UTF_8)
        val out = try {
            file.startWrite()
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "persist start", t)
            return
        }
        try {
            out.write(bytes)
            file.finishWrite(out)
        } catch (t: Throwable) {
            file.failWrite(out)
            Log.stack(LOG_ID, "persist", t)
        }
    }

    private fun historyFile(): AtomicFile? {
        val dir = Applic.app?.filesDir ?: return null
        return AtomicFile(File(dir, FILE_NAME))
    }
}
