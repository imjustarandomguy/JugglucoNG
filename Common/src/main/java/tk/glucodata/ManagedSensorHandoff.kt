package tk.glucodata

import android.content.Context
import android.content.SharedPreferences
import tk.glucodata.drivers.aidex.native.protocol.AiDexPairKeyBackup
import java.nio.charset.StandardCharsets
import java.util.LinkedHashSet
import org.json.JSONArray
import org.json.JSONObject
import tk.glucodata.drivers.ManagedSensorIdentityRegistry

object ManagedSensorHandoff {
    private const val LOG_ID = "ManagedSensorHandoff"
    private const val VERSION = 1
    private const val MAIN_PREFS = "tk.glucodata_preferences"
    private const val AIDEX_NATIVE_PREFS = "AiDexNativePrefs"
    // Redundant AiDex PAIR-key vault. Carrying it lets the receiving device (e.g. the watch)
    // stream from the sensor unbonded on the saved key, instead of pairing from scratch.
    private const val AIDEX_PAIR_KEY_PRIMARY_PREFS = "AiDexPairKeysPrimary"
    private const val AIDEX_PAIR_KEY_RECOVERY_PREFS = "AiDexPairKeysRecovery"
    private const val AIDEX_PAIR_KEY_ENTRY_PREFIX = "pairKey_v1_"
    private const val VIEW_MODE_PREFS = "managed_sensor_view_modes"
    private const val KEY_MANAGED_CURRENT = "managed_current_sensor"

    private val prefsToExport = arrayOf(
        MAIN_PREFS,
        AIDEX_NATIVE_PREFS,
        AIDEX_PAIR_KEY_PRIMARY_PREFS,
        AIDEX_PAIR_KEY_RECOVERY_PREFS,
        VIEW_MODE_PREFS,
    )
    private val aidexPairKeyPrefs = setOf(AIDEX_PAIR_KEY_PRIMARY_PREFS, AIDEX_PAIR_KEY_RECOVERY_PREFS)
    private val managedRecordSetKeys = setOf(
        "aidex_sensors",
        "anytime_sensors",
        "icanhealth_sensors",
        "mq_sensors",
        "ottai_sensors",
        // Sibionics was missing here and below, so a handoff carried nothing at
        // all about a Sibionics sensor: the watch received the takeover, had no
        // record, no auth material and no session state, and sat on "waiting to
        // connect" for good.
        "sibionics_managed_sensors",
    )
    private val managedKeyPrefixes = arrayOf(
        "aidex_",
        "anytime_",
        "icanhealth_",
        "mq_",
        "ottai_",
        "sibionics_",
        "api_glucose_source_",
        "nightscout_follower_",
        "view_mode_",
    )

    @JvmStatic
    fun createOutgoingPayload(context: Context? = Applic.app): ByteArray =
        createOutgoingPayload(context, refresh = false)

    /**
     * @param refresh the receiver already had this sensor's setup and is only
     *   being brought up to date; it skips the full-history request a real
     *   handover needs. Older receivers ignore the key and behave as before.
     */
    fun createOutgoingPayload(context: Context?, refresh: Boolean): ByteArray =
        buildPayload(context, refresh, SensorIdentity.resolveMainSensor())

    /** [dexcomSensor]: the G7 whose setup goes along ([DexcomWatchSetup]), if it is one. */
    private fun buildPayload(context: Context?, refresh: Boolean, dexcomSensor: String?): ByteArray {
        val ctx = context ?: Applic.app
        val candidates = collectManagedCandidates(ctx)
        val currentManaged = ManagedCurrentSensor.get()
            ?.takeIf { id -> candidates.any { SensorIdentity.matches(it, id) } }

        val root = JSONObject()
            .put("version", VERSION)
            .put("takeover", true)
            .put("managedSensorId", currentManaged ?: JSONObject.NULL)
            .put("refresh", refresh)

        val entries = JSONArray()
        if (ctx != null && candidates.isNotEmpty()) {
            prefsToExport.forEach { prefsName ->
                exportPrefs(ctx, prefsName, candidates, entries)
            }
        }
        root.put("entries", entries)
        // A Dexcom has no preferences to carry; its setup is the scan it came from.
        DexcomWatchSetup.attach(root, dexcomSensor)
        // Which namespaces actually travelled is otherwise invisible: a receiver that comes up
        // with no auth material looks exactly like one that was never sent any.
        run {
            val perPrefs = LinkedHashMap<String, Int>()
            for (i in 0 until entries.length()) {
                val name = entries.optJSONObject(i)?.optString("prefs").orEmpty()
                if (name.isNotEmpty()) perPrefs[name] = (perPrefs[name] ?: 0) + 1
            }
            Log.i(LOG_ID, "handoff payload: ${entries.length()} entries $perPrefs candidates=$candidates")
        }
        return root.toString().toByteArray(StandardCharsets.UTF_8)
    }

    @JvmStatic
    fun applyIncoming(context: Context? = Applic.app, data: ByteArray?): Boolean {
        val ctx = context ?: Applic.app ?: return false
        if (data == null) return false
        return runCatching {
            val root = JSONObject(String(data, StandardCharsets.UTF_8))
            if (root.optInt("version", 0) != VERSION || !root.optBoolean("takeover", false)) {
                return@runCatching false
            }
            // Checked here, at apply time, not only when the sender decided to
            // send: once this device reads the sensor its driver state is newer
            // than the copy in flight, and importing it would rewind the
            // sensor's cursors and replay or lose history. A payload can name
            // two sensors, the managed one and a Dexcom, and each is judged on
            // its own.
            val managedSensor = root.optString("managedSensorId", "").trim()
                .takeIf { it.isNotEmpty() && it != "null" }
            val dexcomSerial = DexcomWatchSetup.incomingSerial(root)
            val parts = handoffToApply(managedSensor, dexcomSerial) { SensorOwnershipRuntime.readsLocally(it) }
            if (parts == null) {
                Log.i(LOG_ID, "handoff ignored: already reading ${managedSensor ?: dexcomSerial} here")
                return@runCatching false
            }
            val entries = if (parts.managed) root.optJSONArray("entries") ?: JSONArray() else JSONArray()
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                importEntry(ctx, entry)
            }
            managedSensor
                ?.takeIf { parts.managed }
                ?.let { sensorId ->
                    if (!SensorIdentity.hasNativeSensorBacking(sensorId)) {
                        ManagedCurrentSensor.set(sensorId)
                    }
                }
            run {
            val perPrefs = LinkedHashMap<String, Int>()
            for (i in 0 until entries.length()) {
                val name = entries.optJSONObject(i)?.optString("prefs").orEmpty()
                if (name.isNotEmpty()) perPrefs[name] = (perPrefs[name] ?: 0) + 1
            }
                Log.i(LOG_ID, "handoff applied: ${entries.length()} entries $perPrefs")
            }
            val dexcomSetup = parts.dexcom && DexcomWatchSetup.carriesSetup(root)
            if (dexcomSetup) {
                // Builds a native record and the driver roster, like the netinfo
                // and start handlers, which run one at a time on this slot; run
                // beside them it could overlap a netinfo rebuild. The backlog is
                // asked for after it, so it lands in the record this makes.
                MessageReceiver.runNativeOp("Dexcom setup") {
                    val setUp = runCatching { DexcomWatchSetup.applyOnWatch(root) }
                        .onFailure { Log.stack(LOG_ID, "Dexcom setup", it) }
                        .getOrDefault(false)
                    // A claim that timed out while no driver here could reach the
                    // sensor would otherwise wait for the next /bluetooth.
                    if (setUp) WearSensorClaim.rearmIfRequested("G7 handed over")
                    SensorIdentity.invalidateCaches()
                    requestBacklog(root, mirrorDiscarded = true)
                }
            }
            SensorIdentity.invalidateCaches()
            if (!dexcomSetup) requestBacklog(root, mirrorDiscarded = false)
            // The record is now stored, but drivers are only built from storage at Bluetooth
            // start. Without this the receiving device holds the sensor on paper and never
            // opens a connection — no GATT attempt, no broadcast fallback — until the app is
            // restarted, and the claim times out back to the sender.
            SensorBluetooth.ensurePersistedManagedCallbacks()
            true
        }.getOrElse { th ->
            Log.stack(LOG_ID, "applyIncoming", th)
            false
        }
    }

    /**
     * @param mirrorDiscarded the Dexcom setup may have moved this device's mirrored copy of
     *   the sensor aside, which leaves nothing but what the sender serves now; it serves the
     *   full horizon again at once instead of waiting out its spacing between full serves.
     */
    private fun requestBacklog(root: JSONObject, mirrorDiscarded: Boolean) {
        // Ask the sender for its backlog now. A full-horizon serve is otherwise throttled
        // to its own schedule, so a device could take a sensor over holding only the last
        // few minutes and wait minutes for the rest — while the sender had all of it and
        // the Data Layer was idle.
        // The sender's AiDex download cursor travels with the sensor on purpose: it says
        // which offsets have already been pulled off that sensor, and the deep serve above
        // is what makes them true here. Zeroing it instead made the receiver re-fetch a
        // full history over BLE that sync had just delivered.
        // A refresh of setup this device already had needs no backlog.
        if (root.optBoolean("refresh", false)) return
        runCatching { WearSync2.requestSync(deep = true, force = mirrorDiscarded) }
            .onFailure { Log.stack(LOG_ID, "handoff deep sync request", it) }
    }

    /** [sensorId]'s G7, as this phone's Dexcom driver reads it; null for any other sensor. */
    private fun dexcomRecord(sensorId: String?): DexcomFollow.Record? {
        if (sensorId.isNullOrBlank()) return null
        val callback = runCatching {
            SensorBluetooth.mygatts()?.firstOrNull {
                it is DexGattCallback && SensorIdentity.matches(it.SerialNumber, sensorId)
            }
        }.getOrNull() ?: return null
        val serial = callback.SerialNumber?.takeIf { it.isNotBlank() } ?: return null
        // By its full record name, as watches handed one before remember it.
        val name = runCatching { Natives.resolveFullSensorName(serial) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: serial
        val dataptr = callback.dataptr.takeIf { it != 0L } ?: return null
        val start = runCatching { Natives.getSensorStartmsec(dataptr) / 1000L }.getOrDefault(0L)
            .takeIf { it > 0L } ?: return null
        return DexcomFollow.Record(name, start, setupReady = DexcomWatchSetup.canSendToWatch(name))
    }

    /**
     * What a watch handed the selected G7 remembers ([HANDED_DEXCOM_KEY_PREFIX]),
     * as the switch's own handoff carries it; null when that carries no G7.
     */
    @JvmStatic
    fun selectedDexcomHanded(): String? =
        dexcomRecord(SensorIdentity.resolveMainSensor())
            ?.takeIf { it.setupReady }
            ?.let(DexcomFollow::handedValue)

    /** Per watch: the G7 it was last handed and that G7's start ("Direct sensor on watch" preferences). */
    const val HANDED_DEXCOM_KEY_PREFIX = "dexcom."
    private const val ROUTING_PREFS = "wear_routing_request"
    private const val DIRECT_KEY_PREFIX = "direct."
    private const val SENSOR_KEY_PREFIX = "sensor."

    /** Per watch, the last reason [followNewestDexcom] did not hand it a G7, so each is logged once. */
    private val followSkips = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * "Direct sensor on watch" hands the watch the phone's selected G7 when it
     * is switched on. A sensor started later has to follow: called from the
     * phone's ownership tick, this hands every such watch the newest G7 once
     * the phone can send its setup ([DexcomFollow]), and again only if its
     * start moves.
     */
    @JvmStatic
    fun followNewestDexcom() {
        if (Applic.isWearable) return
        val prefs = Applic.app?.getSharedPreferences(ROUTING_PREFS, Context.MODE_PRIVATE) ?: return
        followNewestDexcom(prefs, SystemFollowAccess)
    }

    /** What [followNewestDexcom] reads and sends besides the routing preferences. */
    internal interface FollowAccess {
        /** Every active G7 record this phone can hand over. */
        fun activeDexcomRecords(): List<DexcomFollow.Record>

        fun isDexcom(sensorId: String?): Boolean

        fun watchUnreachable(): Boolean

        /** Sends [node] the handoff for [record]; true once it was delivered. */
        fun handOver(node: String, record: DexcomFollow.Record): Boolean

        /** The id the routing preferences name [sensorId] by (SensorIdentity.canonicalSensorId). */
        fun canonicalSensorId(sensorId: String): String
    }

    private object SystemFollowAccess : FollowAccess {
        override fun activeDexcomRecords(): List<DexcomFollow.Record> =
            runCatching { Natives.activeSensors() }.getOrNull()
                .orEmpty()
                .mapNotNull(::dexcomRecord)
                .distinctBy { it.name.uppercase(java.util.Locale.ROOT) }

        override fun isDexcom(sensorId: String?): Boolean = dexcomRecord(sensorId) != null

        override fun watchUnreachable(): Boolean =
            runCatching { MessageSender.peerUnreachable() }.getOrDefault(true)

        override fun handOver(node: String, record: DexcomFollow.Record): Boolean {
            val sender = MessageSender.getMessageSender() ?: return false
            return sender.sendSensorHandoff(node, buildPayload(Applic.app, refresh = false, dexcomSensor = record.name))
        }

        override fun canonicalSensorId(sensorId: String): String =
            runCatching { SensorIdentity.canonicalSensorId(sensorId) }.getOrNull() ?: sensorId
    }

    internal fun followNewestDexcom(prefs: SharedPreferences, access: FollowAccess) {
        val nodes = prefs.all
            .filter { (key, value) -> key.startsWith(DIRECT_KEY_PREFIX) && value == true }
            .keys
            .map { it.removePrefix(DIRECT_KEY_PREFIX) }
            .filter { it.isNotBlank() }
        if (nodes.isEmpty()) return
        val newest = DexcomFollow.newest(access.activeDexcomRecords()) ?: return
        val due = nodes.filter { node ->
            val decision = DexcomFollow.decide(
                handed = prefs.getString(HANDED_DEXCOM_KEY_PREFIX + node, null),
                assignedIsDexcom = { access.isDexcom(prefs.getString(SENSOR_KEY_PREFIX + node, null)) },
                newest = newest,
            )
            when (decision) {
                DexcomFollow.Decision.HAND -> true
                DexcomFollow.Decision.HANDED -> false
                DexcomFollow.Decision.NOT_READY -> {
                    noteFollowSkip(node, newest.name, "no reading on this phone yet")
                    false
                }
                DexcomFollow.Decision.NOT_FOLLOWING -> {
                    noteFollowSkip(node, newest.name, "the watch reads another sensor")
                    false
                }
            }
        }
        if (due.isEmpty()) return
        // The send waits for delivery: don't hold up the ownership tick for an absent watch.
        if (access.watchUnreachable()) {
            due.forEach { node -> noteFollowSkip(node, newest.name, "watch out of reach") }
            return
        }
        // The sensor a watch was told to read is the one the phone's Wear OS
        // screen reports on (its status, and what the phone offers or stands
        // down for), and was otherwise written only when direct mode went on:
        // it kept naming the old G7, removed or not.
        val assigned = access.canonicalSensorId(newest.name)
        due.forEach { node ->
            if (access.handOver(node, newest)) {
                prefs.edit()
                    .putString(HANDED_DEXCOM_KEY_PREFIX + node, DexcomFollow.handedValue(newest))
                    .putString(SENSOR_KEY_PREFIX + node, assigned)
                    .apply()
                followSkips.remove(node)
                Log.i(LOG_ID, "handed G7 ${newest.name} (start ${newest.start}) to watch $node")
            } else {
                noteFollowSkip(node, newest.name, "handoff not delivered")
            }
        }
    }

    private fun noteFollowSkip(node: String, name: String, reason: String) {
        val skip = "$name: $reason"
        if (followSkips.put(node, skip) != skip) {
            Log.i(LOG_ID, "G7 $name not handed to watch $node: $reason")
        }
    }

    private fun collectManagedCandidates(context: Context?): Set<String> {
        val out = LinkedHashSet<String>()
        fun add(id: String?) {
            id?.trim()?.takeIf { it.isNotEmpty() }?.let { sensorId ->
                out.add(sensorId)
                out.add(SensorIdentity.resolveAppSensorId(sensorId) ?: sensorId)
                out.add(SensorIdentity.resolveNativeSensorName(sensorId) ?: sensorId)
                out.add(SensorIdentity.resolveRoomStorageSensorId(sensorId) ?: sensorId)
                SensorIdentity.resolveNativeHistorySensorNames(sensorId).forEach(out::add)
            }
        }

        val selected = SensorIdentity.resolveMainSensor()
        add(ManagedCurrentSensor.get())
        add(selected)
        add(Natives.lastsensorname())
        SensorBluetooth.mygatts()?.forEach { callback ->
            if (selected == null || SensorIdentity.matches(callback.SerialNumber, selected)) {
                add(callback.SerialNumber)
            }
        }
        if (context != null) {
            ManagedSensorIdentityRegistry.persistedSensorIds(context).forEach { persisted ->
                if (selected == null || SensorIdentity.matches(persisted, selected)) {
                    add(persisted)
                }
            }
        }
        return out
    }

    private fun exportPrefs(
        context: Context,
        prefsName: String,
        candidates: Set<String>,
        entries: JSONArray
    ) {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        prefs.all.forEach { (key, value) ->
            if (shouldExportKey(key, value, candidates, prefsName)) {
                entries.put(encodeEntry(prefsName, key, value))
            }
        }
    }

    private fun shouldExportKey(
        key: String,
        value: Any?,
        candidates: Set<String>,
        prefsName: String
    ): Boolean {
        if (prefsName == VIEW_MODE_PREFS) {
            return keyMatchesCandidate(key, candidates)
        }
        if (key == KEY_MANAGED_CURRENT) {
            val current = value as? String ?: return false
            return candidates.any { SensorIdentity.matches(it, current) }
        }
        if (key in managedRecordSetKeys) {
            @Suppress("UNCHECKED_CAST")
            val set = value as? Set<String> ?: return false
            return set.any { recordMatchesCandidate(it, candidates) }
        }
        if (prefsName in aidexPairKeyPrefs) {
            // Vault entries are keyed by canonical bare serial (pairKey_v1_<SERIAL>); match on
            // the same canonicalization so a stored "X-..." candidate still lines up.
            if (!key.startsWith(AIDEX_PAIR_KEY_ENTRY_PREFIX)) return false
            val entrySerial = key.removePrefix(AIDEX_PAIR_KEY_ENTRY_PREFIX)
            return candidates.any { candidate ->
                entrySerial.equals(
                    AiDexPairKeyBackup.canonicalBareSerial(candidate),
                    ignoreCase = true,
                )
            }
        }
        if (prefsName == AIDEX_NATIVE_PREFS) {
            return keyMatchesCandidate(key, candidates)
        }
        return managedKeyPrefixes.any { key.startsWith(it) } && keyMatchesCandidate(key, candidates)
    }

    private fun encodeEntry(prefsName: String, key: String, value: Any?): JSONObject {
        val out = JSONObject()
            .put("prefs", prefsName)
            .put("key", key)
        when (value) {
            is String -> out.put("type", "string").put("value", value)
            is Int -> out.put("type", "int").put("value", value)
            is Long -> out.put("type", "long").put("value", value)
            is Float -> out.put("type", "float").put("value", value.toDouble())
            is Boolean -> out.put("type", "boolean").put("value", value)
            is Set<*> -> {
                val array = JSONArray()
                value.filterIsInstance<String>().forEach(array::put)
                out.put("type", "stringSet").put("value", array)
            }
            else -> out.put("type", "unsupported").put("value", JSONObject.NULL)
        }
        return out
    }

    private fun importEntry(context: Context, entry: JSONObject) {
        val prefsName = entry.optString("prefs", "").takeIf { it.isNotBlank() } ?: return
        val key = entry.optString("key", "").takeIf { it.isNotBlank() } ?: return
        val editor = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
        when (entry.optString("type")) {
            "string" -> editor.putString(key, entry.optString("value", ""))
            "int" -> editor.putInt(key, entry.optInt("value"))
            "long" -> editor.putLong(key, entry.optLong("value"))
            "float" -> editor.putFloat(key, entry.optDouble("value").toFloat())
            "boolean" -> editor.putBoolean(key, entry.optBoolean("value"))
            "stringSet" -> mergeStringSet(context, prefsName, key, entry.optJSONArray("value"), editor)
            else -> return
        }
        editor.commit()
        // Written straight through SharedPreferences, so the in-memory cache in
        // ManagedCurrentSensor would otherwise keep serving the pre-handoff value.
        if (prefsName == MAIN_PREFS && key == KEY_MANAGED_CURRENT) {
            ManagedCurrentSensor.invalidate()
        }
    }

    private fun mergeStringSet(
        context: Context,
        prefsName: String,
        key: String,
        array: JSONArray?,
        editor: SharedPreferences.Editor
    ) {
        val merged = LinkedHashSet<String>()
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getStringSet(key, emptySet())
            ?.let(merged::addAll)
        if (array != null) {
            for (index in 0 until array.length()) {
                array.optString(index, "").takeIf { it.isNotEmpty() }?.let(merged::add)
            }
        }
        editor.putStringSet(key, merged)
    }

    /**
     * Whether a per-sensor preference key would travel with a handoff. Exposed so
     * a test can pin every managed driver's namespace: a driver missing from the
     * lists above hands over a sensor the other device cannot connect to, and
     * nothing else fails loudly enough to notice.
     */
    internal fun exportsKeyForSensor(key: String, sensorId: String): Boolean =
        shouldExportKey(key, "", setOf(sensorId), MAIN_PREFS)

    /** Whether an AiDex PAIR-key vault entry would travel with a handoff. For tests. */
    internal fun exportsPairKeyEntryForSensor(entryKey: String, sensorId: String): Boolean =
        shouldExportKey(entryKey, "", setOf(sensorId), AIDEX_PAIR_KEY_PRIMARY_PREFS)

    private fun keyMatchesCandidate(key: String, candidates: Set<String>): Boolean =
        candidates.any { candidate ->
            val id = candidate.trim()
            id.isNotEmpty() && (
                key.equals(id, ignoreCase = true) ||
                    key.endsWith(id, ignoreCase = true) ||
                    key.contains(id, ignoreCase = true)
                )
        }

    private fun recordMatchesCandidate(record: String, candidates: Set<String>): Boolean =
        candidates.any { candidate ->
            SensorIdentity.matches(record.substringBefore('|'), candidate) ||
                record.contains(candidate, ignoreCase = true)
        }
}

/** Which parts of an incoming handoff this device takes: the managed sensor's state, the Dexcom setup. */
internal class HandoffApply(val managed: Boolean, val dexcom: Boolean)

/**
 * What to take from a handoff naming [managedSensor] and/or [dexcomSerial]:
 * every part whose sensor this device does not already read. Null when the
 * payload names sensors and this device reads all of them; a payload naming
 * none is taken whole.
 */
internal fun handoffToApply(
    managedSensor: String?,
    dexcomSerial: String?,
    readsHere: (String) -> Boolean,
): HandoffApply? {
    val managedHere = managedSensor != null && readsHere(managedSensor)
    val dexcomHere = dexcomSerial != null && readsHere(dexcomSerial)
    val namesSensor = managedSensor != null || dexcomSerial != null
    if (namesSensor && (managedSensor == null || managedHere) && (dexcomSerial == null || dexcomHere)) {
        return null
    }
    return HandoffApply(managed = !managedHere, dexcom = !dexcomHere)
}
