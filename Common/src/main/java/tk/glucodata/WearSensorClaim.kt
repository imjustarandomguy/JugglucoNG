package tk.glucodata

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class WearSensorClaimState(val wireValue: Int) {
    PHONE_OWNS(0),
    REQUESTING(1),
    CONNECTED(2);

    companion object {
        fun fromWireValue(value: Int): WearSensorClaimState? = entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * The /sensorclaimstatus payload: the claim state's wire value, then a
 * [STOPPED_ON_WATCH] byte only when the watch's own button has just turned
 * "Direct sensor on watch" off. A phone reads the first byte alone, so an
 * older phone reads the longer payload as before.
 */
internal object WearSensorClaimWire {
    const val STOPPED_ON_WATCH: Byte = 1

    fun encode(state: WearSensorClaimState, stoppedOnWatch: Boolean): ByteArray {
        val value = state.wireValue.toByte()
        return if (stoppedOnWatch) byteArrayOf(value, STOPPED_ON_WATCH) else byteArrayOf(value)
    }

    /** The watch's user stopped direct mode on the watch: the phone's switch follows. */
    fun stoppedOnWatch(data: ByteArray?): Boolean =
        data != null && data.size >= 2 &&
            data[0].toInt() == WearSensorClaimState.PHONE_OWNS.wireValue &&
            data[1] == STOPPED_ON_WATCH
}

/** Last process-visible claim state reported by each remote watch. */
object WearSensorClaimStatus {
    private const val LOG_ID = "WearSensorClaimStatus"
    private const val REMOTE_STATUS_MAX_AGE_MS = 4L * 60L * 1000L

    // The phone's "Direct sensor on watch" record (WearRoutingRequest, phone-only code).
    private const val ROUTING_PREFS = "wear_routing_request"
    private const val DIRECT_KEY_PREFIX = "direct."
    private const val SENSOR_KEY_PREFIX = "sensor."

    private data class RemoteStatus(val state: WearSensorClaimState, val receivedAtMs: Long)

    private val remoteByNode = ConcurrentHashMap<String, RemoteStatus>()
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()

    @JvmStatic
    fun onRemoteStatus(nodeId: String?, data: ByteArray?) {
        if (Applic.isWearable || nodeId.isNullOrBlank() || data == null || data.isEmpty()) return
        val state = WearSensorClaimState.fromWireValue(data[0].toInt()) ?: run {
            Log.w(LOG_ID, "Ignoring invalid watch claim state=${data[0].toInt()} node=$nodeId")
            return
        }
        val previous = remoteByNode.put(nodeId, RemoteStatus(state, System.currentTimeMillis()))?.state
        val routeDropped = WearSensorClaimWire.stoppedOnWatch(data) && dropDirectRoute(nodeId)
        if (previous != state || routeDropped) {
            Log.i(LOG_ID, "watch claim state node=$nodeId ${previous ?: "unknown"} -> $state")
            _revision.value = _revision.value + 1L
        }
    }

    /**
     * What switching "Direct sensor on watch" off on the phone does for
     * [nodeId] (WearRoutingRequest.record, then the phone's Bluetooth back on),
     * for a watch whose user switched it off there. Without it the phone kept
     * the request: its switch stayed on, and its screens kept waiting for the
     * watch to read. False when the phone held no request for that watch.
     */
    private fun dropDirectRoute(nodeId: String): Boolean {
        val app = Applic.app ?: return false
        val dropped = runCatching {
            val prefs = app.getSharedPreferences(ROUTING_PREFS, android.content.Context.MODE_PRIVATE)
            if (!prefs.getBoolean(DIRECT_KEY_PREFIX + nodeId, false)) return@runCatching false
            prefs.edit()
                .putBoolean(DIRECT_KEY_PREFIX + nodeId, false)
                .remove(SENSOR_KEY_PREFIX + nodeId)
                .remove(ManagedSensorHandoff.HANDED_DEXCOM_KEY_PREFIX + nodeId)
                .apply()
            true
        }.onFailure { Log.stack(LOG_ID, "drop direct route $nodeId", it) }.getOrDefault(false)
        if (!dropped) return false
        Log.i(LOG_ID, "watch $nodeId stopped direct sensor itself; phone request dropped")
        runCatching { Applic.setbluetooth(app, true) }
            .onFailure { Log.stack(LOG_ID, "setbluetooth after watch stop", it) }
        return true
    }

    @JvmStatic
    fun remoteState(nodeId: String?): WearSensorClaimState? {
        if (nodeId.isNullOrBlank()) return null
        val status = remoteByNode[nodeId] ?: return null
        if (System.currentTimeMillis() - status.receivedAtMs <= REMOTE_STATUS_MAX_AGE_MS) {
            return status.state
        }
        remoteByNode.remove(nodeId, status)
        return null
    }
}

internal object WearSensorClaimPolicy {
    fun hasLocalOwnershipProof(
        targetSensorId: String?,
        callbackSerial: String?,
        hasLocallyConnectedGatt: Boolean,
        requestedAtMs: Long,
        localReadingSerial: String?,
        localReadingAcceptedAtMs: Long,
    ): Boolean {
        if (
            targetSensorId.isNullOrBlank() ||
            callbackSerial.isNullOrBlank() ||
            localReadingSerial.isNullOrBlank() ||
            !hasLocallyConnectedGatt ||
            requestedAtMs <= 0L ||
            localReadingAcceptedAtMs < requestedAtMs
        ) {
            return false
        }
        return SensorIdentity.matches(callbackSerial, targetSensorId) &&
            SensorIdentity.matches(localReadingSerial, targetSensorId) &&
            SensorIdentity.matches(localReadingSerial, callbackSerial)
    }
}

/** Watch-only, process-local ownership claim for direct sensor routing. */
object WearSensorClaim {
    private const val LOG_ID = "WearSensorClaim"
    private const val CLAIM_TIMEOUT_MS = 3L * 60L * 1000L
    private const val POLL_MS = 2_000L

    /**
     * While the watch owns the sensor it re-publishes that fact this often. The
     * phone gives up its own Bluetooth only for as long as these keep arriving,
     * so a watch that goes out of range or loses power lets the phone take the
     * sensor back instead of leaving nobody reading it.
     */
    const val OWNERSHIP_HEARTBEAT_MS = 60_000L

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "WearSensorClaim").apply { isDaemon = true }
    }

    @Volatile private var directRequested = false
    @Volatile private var state = WearSensorClaimState.PHONE_OWNS
    @Volatile private var requestedAtMs = 0L
    @Volatile private var localReadingSerial: String? = null
    @Volatile private var localReadingAcceptedAtMs = 0L
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()
    private var lastWaitingReason = ""
    private var monitor: ScheduledFuture<*>? = null
    private var heartbeat: ScheduledFuture<*>? = null

    @JvmStatic
    fun setDirectRequested(enabled: Boolean) {
        if (!Applic.isWearable) return
        applyDirectRequested(enabled)
        publishState()
    }

    /**
     * The watch's own button ("Return sensor to phone" / "Stop reading on the
     * watch"): direct mode off here, and the phone told that this watch's user
     * did it, so its "Direct sensor on watch" switch goes off too. A stop the
     * phone sent (/bluetooth) goes through [setDirectRequested] and is not
     * echoed back.
     */
    @JvmStatic
    fun stopOnWatch() {
        if (!Applic.isWearable) return
        applyDirectRequested(false)
        publishState(stoppedOnWatch = true)
    }

    private fun applyDirectRequested(enabled: Boolean) {
        storeDirectRequested(enabled)
        synchronized(this) {
            directRequested = enabled
            requestedAtMs = if (enabled) System.currentTimeMillis() else 0L
            clearLocalReadingLocked()
            lastWaitingReason = ""
            monitor?.cancel(false)
            monitor = null
            transitionLocked(
                if (enabled) WearSensorClaimState.REQUESTING else WearSensorClaimState.PHONE_OWNS,
                if (enabled) "direct mode requested; waiting for local GATT + local reading"
                else "direct mode disabled",
            )
            if (enabled) scheduleMonitorLocked()
        }
    }

    @JvmStatic
    fun netInfoValue(): Int =
        if (Applic.isWearable && state == WearSensorClaimState.CONNECTED) 1 else -1

    @JvmStatic
    fun currentState(): WearSensorClaimState = state

    @JvmStatic
    fun currentStateValue(): Int = state.wireValue

    /**
     * Called only by a local sensor driver's accepted live-reading callback.
     * WearSync2/store hydration must never call this.
     */
    @JvmStatic
    fun onLocalReadingAccepted(serial: String?, sampleTimeMs: Long = 0L) {
        if (!Applic.isWearable || serial.isNullOrBlank()) return
        val acceptedAtMs = System.currentTimeMillis()
        synchronized(this) {
            if (
                !directRequested ||
                state == WearSensorClaimState.CONNECTED ||
                acceptedAtMs < requestedAtMs
            ) {
                return
            }
            localReadingSerial = serial
            localReadingAcceptedAtMs = acceptedAtMs
            Log.i(
                LOG_ID,
                "local reading evidence serial=$serial sample=$sampleTimeMs acceptedAt=$acceptedAtMs " +
                    "requestedAt=$requestedAtMs",
            )
        }
        checkClaim()
    }

    /** Called directly from the local Android GATT callback/transport close path. */
    @JvmStatic
    fun onLocalGattDisconnected(serial: String?) {
        if (!Applic.isWearable || serial.isNullOrBlank()) return
        val activeSensor = runCatching { SensorIdentity.resolveMainSensor() }.getOrNull()
        if (activeSensor != null && !SensorIdentity.matches(activeSensor, serial)) return

        var publish = false
        synchronized(this) {
            if (!directRequested) return
            val wasConnected = state == WearSensorClaimState.CONNECTED
            if (wasConnected) {
                requestedAtMs = System.currentTimeMillis()
            }
            clearLocalReadingLocked()
            lastWaitingReason = ""
            transitionLocked(
                WearSensorClaimState.REQUESTING,
                if (wasConnected) "local GATT disconnected; released claim and restarted scan window"
                else "local GATT disconnected while requesting",
            )
            scheduleMonitorLocked()
            publish = true
        }
        if (publish) publishState()
    }

    private fun checkClaim() {
        val requestStart: Long
        val readingSerial: String?
        val readingAcceptedAt: Long
        synchronized(this) {
            if (!directRequested || state == WearSensorClaimState.CONNECTED) return
            requestStart = requestedAtMs
            readingSerial = localReadingSerial
            readingAcceptedAt = localReadingAcceptedAtMs
        }

        val sensorId = runCatching { SensorIdentity.resolveMainSensor() }.getOrNull()
        val callback = sensorId?.let { id ->
            SensorBluetooth.mygatts()?.firstOrNull { candidate ->
                candidate.supportsWatchClaim() && SensorIdentity.matches(candidate.SerialNumber, id)
            }
        }
        val now = System.currentTimeMillis()
        // The phone stands down only for the window, so a claim past it is void.
        // A sensor read alongside the phone has no window: keep trying until it
        // is reached and paired, however long that takes.
        val alongside = callback?.readsAlongside() == true
        if (!alongside && now - requestStart >= CLAIM_TIMEOUT_MS) {
            synchronized(this) {
                if (!directRequested || requestedAtMs != requestStart) return
                directRequested = false
                requestedAtMs = 0L
                clearLocalReadingLocked()
                monitor?.cancel(false)
                monitor = null
                transitionLocked(WearSensorClaimState.PHONE_OWNS, "local connection scan timed out")
            }
            publishState()
            return
        }

        if (sensorId.isNullOrBlank()) {
            logWaiting("no active sensor identity")
            return
        }
        if (callback == null) {
            logWaiting("no matching local GATT callback for $sensorId")
            return
        }
        if (!callback.hasLocallyConnectedGatt()) {
            logWaiting("matching callback has no locally connected GATT")
            return
        }
        if (!WearSensorClaimPolicy.hasLocalOwnershipProof(
                targetSensorId = sensorId,
                callbackSerial = callback.SerialNumber,
                hasLocallyConnectedGatt = true,
                requestedAtMs = requestStart,
                localReadingSerial = readingSerial,
                localReadingAcceptedAtMs = readingAcceptedAt,
            )
        ) {
            logWaiting("local GATT connected; waiting for its accepted live reading")
            return
        }

        synchronized(this) {
            if (
                !directRequested ||
                requestedAtMs != requestStart ||
                state == WearSensorClaimState.CONNECTED ||
                !callback.hasLocallyConnectedGatt()
            ) {
                return
            }
            monitor?.cancel(false)
            monitor = null
            lastWaitingReason = ""
            transitionLocked(
                WearSensorClaimState.CONNECTED,
                "matching local GATT connected and accepted local reading from ${callback.SerialNumber}",
            )
        }
        publishState()
    }

    @Synchronized
    private fun logWaiting(reason: String) {
        if (lastWaitingReason == reason) return
        lastWaitingReason = reason
        Log.i(LOG_ID, "claim remains REQUESTING: $reason")
    }

    private fun clearLocalReadingLocked() {
        localReadingSerial = null
        localReadingAcceptedAtMs = 0L
    }

    private fun scheduleMonitorLocked() {
        if (monitor?.isDone == false) return
        monitor = executor.scheduleWithFixedDelay(::checkClaim, 0L, POLL_MS, TimeUnit.MILLISECONDS)
    }

    private fun transitionLocked(next: WearSensorClaimState, reason: String) {
        val previous = state
        state = next
        if (previous == next) {
            Log.i(LOG_ID, "watch sensor claim remains $next: $reason")
        } else {
            Log.i(LOG_ID, "watch sensor claim $previous -> $next: $reason")
            _revision.value = _revision.value + 1L
        }
    }

    private const val PREFS = "wear_sensor_claim"
    private const val KEY_DIRECT_REQUESTED = "direct_requested"

    private fun storeDirectRequested(enabled: Boolean) {
        runCatching {
            Applic.app?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                ?.edit()
                ?.putBoolean(KEY_DIRECT_REQUESTED, enabled)
                ?.apply()
        }
    }

    /**
     * Re-arms direct mode after the watch app restarts.
     *
     * The request used to live only in this process, and Bluetooth was only
     * turned on by the phone's /bluetooth message. So a handoff worked until the
     * watch app was restarted — or the watch rebooted — and then the watch
     * quietly stopped scanning for the sensor it was supposed to own, with
     * nothing on either screen saying so.
     */
    /** The persisted user intent: has this watch been told to take the sensor. */
    @JvmStatic
    fun isDirectRequested(): Boolean {
        if (!Applic.isWearable) return false
        if (directRequested) return true
        return runCatching {
            Applic.app?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                ?.getBoolean(KEY_DIRECT_REQUESTED, false)
        }.getOrNull() ?: false
    }

    @JvmStatic
    fun restoreOnStart() {
        if (!Applic.isWearable) return
        val wanted = runCatching {
            Applic.app?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                ?.getBoolean(KEY_DIRECT_REQUESTED, false)
        }.getOrNull() ?: false
        if (!wanted) return
        Log.i(LOG_ID, "restoring direct sensor mode after restart")
        setDirectRequested(true)
        val context = MainActivity.thisone ?: Applic.app ?: return
        runCatching { Applic.setbluetooth(context, true) }
            .onFailure { Log.stack(LOG_ID, "restoreOnStart setbluetooth", it) }
    }

    private fun publishState(stoppedOnWatch: Boolean = false) {
        MessageSender.sendSensorClaimStatus(stoppedOnWatch)
        MessageSender.sendnetinfo()
        syncHeartbeat()
    }

    /** Runs only while this watch holds the sensor. */
    private fun syncHeartbeat() {
        synchronized(this) {
            val wanted = state == WearSensorClaimState.CONNECTED
            if (!wanted) {
                heartbeat?.cancel(false)
                heartbeat = null
                return
            }
            if (heartbeat != null) return
            heartbeat = runCatching {
                executor.scheduleWithFixedDelay(
                    ::publishOwnershipHeartbeat,
                    OWNERSHIP_HEARTBEAT_MS,
                    OWNERSHIP_HEARTBEAT_MS,
                    TimeUnit.MILLISECONDS,
                )
            }.getOrNull()
        }
    }

    private fun publishOwnershipHeartbeat() {
        if (state != WearSensorClaimState.CONNECTED) {
            syncHeartbeat()
            return
        }
        runCatching { MessageSender.sendSensorClaimStatus() }
    }
}
