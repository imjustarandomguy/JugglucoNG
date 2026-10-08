package tk.glucodata.drivers.nightscout

import android.content.Context
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.Locale
import tk.glucodata.Log
import tk.glucodata.ManagedCurrentSensor
import tk.glucodata.Natives
import tk.glucodata.SensorBluetooth
import tk.glucodata.SensorIdentity
import tk.glucodata.SuperGattCallback
import tk.glucodata.drivers.ManagedBluetoothSensorDriver
import tk.glucodata.drivers.ManagedSensorIdentityRegistry
import tk.glucodata.drivers.ManagedSensorUiSignals

object NightscoutFollowerRegistry {
    private const val TAG = "NightscoutFollower"
    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val PREF_ENABLED = "nightscout_follower_enabled"
    private const val PREF_URL = "nightscout_follower_url"
    private const val PREF_SECRET = "nightscout_follower_secret"
    //The uploader's v3 flag is an uploader setting and never reaches here: a follower may point
    //at an entirely different server than the one this phone uploads to.
    private const val PREF_USE_V3 = "nightscout_follower_v3"
    private const val PREF_COMPLETE_HISTORY_PREFIX = "nightscout_follower_complete_history_v1_"
    private const val PREF_POLL_MINUTES = "nightscout_follower_poll_minutes"
    const val SENSOR_PREFIX = "NSF-"

    data class Config(
        val enabled: Boolean,
        val url: String,
        val secret: String,
        val useV3: Boolean = false,
    ) {
        val sensorId: String get() = deriveSensorId(url)
        val isUsable: Boolean get() = enabled && url.isNotBlank()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun normalizeUrl(url: String?): String =
        url?.trim()
            ?.removeSuffix("/")
            ?.takeIf { it.isNotEmpty() }
            ?.let { raw ->
                if (raw.startsWith("http://", ignoreCase = true) ||
                    raw.startsWith("https://", ignoreCase = true)
                ) {
                    raw
                } else {
                    "https://$raw"
                }
            }
            .orEmpty()

    fun deriveSensorId(url: String?): String {
        val normalized = normalizeUrl(url)
        if (normalized.isEmpty()) return SENSOR_PREFIX + "UNCONFIGURED"
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(normalized.lowercase(Locale.US).toByteArray(Charsets.UTF_8))
            .take(6)
            .joinToString("") { "%02X".format(Locale.US, it) }
        return SENSOR_PREFIX + digest
    }

    fun loadConfig(context: Context): Config =
        Config(
            enabled = prefs(context).getBoolean(PREF_ENABLED, false),
            url = normalizeUrl(prefs(context).getString(PREF_URL, null)),
            secret = prefs(context).getString(PREF_SECRET, null).orEmpty(),
            useV3 = prefs(context).getBoolean(PREF_USE_V3, false),
        )

    fun saveConfig(
        context: Context,
        enabled: Boolean,
        url: String?,
        secret: String?,
        useV3: Boolean = loadConfig(context).useV3,
    ) {
        prefs(context).edit()
            .putBoolean(PREF_ENABLED, enabled)
            .putString(PREF_URL, normalizeUrl(url).takeIf { it.isNotEmpty() })
            .putString(PREF_SECRET, secret?.trim()?.takeIf { it.isNotEmpty() })
            .putBoolean(PREF_USE_V3, useV3)
            .apply()
        ManagedSensorUiSignals.markDeviceListDirty()
    }

    /** How often the follower asks, in minutes. Sanitized on the way out and on the way in. */
    fun loadPollMinutes(context: Context?): Int =
        context?.let {
            NightscoutFollowerPollPolicy.sanitizeMinutes(
                prefs(it).getInt(PREF_POLL_MINUTES, NightscoutFollowerPollPolicy.DEFAULT_MINUTES)
            )
        } ?: NightscoutFollowerPollPolicy.DEFAULT_MINUTES

    fun savePollMinutes(context: Context, minutes: Int) {
        prefs(context).edit()
            .putInt(PREF_POLL_MINUTES, NightscoutFollowerPollPolicy.sanitizeMinutes(minutes))
            .apply()
    }

    fun persistedSensorIds(context: Context): List<String> =
        loadConfig(context).takeIf { it.isUsable }?.let { listOf(it.sensorId) }.orEmpty()

    fun hasCompleteHistoryImport(context: Context?, sensorId: String): Boolean =
        context != null &&
            prefs(context).getBoolean(PREF_COMPLETE_HISTORY_PREFIX + sensorId.uppercase(Locale.US), false)

    fun markCompleteHistoryImport(context: Context?, sensorId: String) {
        if (context == null || sensorId.isBlank()) return
        prefs(context).edit()
            .putBoolean(PREF_COMPLETE_HISTORY_PREFIX + sensorId.uppercase(Locale.US), true)
            .apply()
    }

    fun createRestoredCallback(context: Context, sensorId: String, dataptr: Long): SuperGattCallback? {
        val config = loadConfig(context)
        if (!config.isUsable || !matchesSensorId(sensorId, config.sensorId)) return null
        return NightscoutFollowerManager(
            serial = config.sensorId,
            url = config.url,
            secret = config.secret,
            useV3 = config.useV3,
            dataptr = dataptr,
        )
    }

    /**
     * Restore the configured follower without requiring Bluetooth initialization.
     *
     * Application.onCreate runs before an alarm receiver. Restoring here means an alarm that
     * starts a fresh process has a callback to hand its wakelock to instead of ending the poll
     * chain. The list lock is the same one used by SensorBluetooth.mygatts().
     */
    @JvmOverloads
    fun restoreConfiguredFollower(
        context: Context,
        sensorId: String = loadConfig(context).sensorId,
    ): NightscoutFollowerManager? {
        val config = loadConfig(context)
        // Only the configured server's follower may run. One for another server is being
        // stopped (see stopInactiveFollowers); an alarm it left must not poll it again, nor
        // hand it the new server's credentials.
        if (!config.isUsable || !matchesSensorId(sensorId, config.sensorId)) return null
        val existing = findRunningFollower(sensorId)
        if (existing != null) {
            existing.updateSettings(config.secret, config.useV3)
            return existing
        }

        var added = false
        val follower = synchronized(SensorBluetooth.gattcallbacks) {
            findRunningFollowerLocked(sensorId)?.also {
                it.updateSettings(config.secret, config.useV3)
            } ?: run {
                val restored = createRestoredCallback(context, sensorId, 0L) as? NightscoutFollowerManager
                    ?: return@synchronized null
                SensorBluetooth.gattcallbacks.add(restored)
                Natives.setmaxsensors(SensorBluetooth.gattcallbacks.size)
                added = true
                restored
            }
        } ?: return null

        if (added) {
            SensorBluetooth.ensureCurrentSensorSelection()
            ManagedSensorUiSignals.markDeviceListDirty()
        }
        return follower
    }

    fun recoverOnNetworkAvailable(context: Context) {
        val config = loadConfig(context)
        if (!config.isUsable) return
        restoreConfiguredFollower(context, config.sensorId)
            ?.recoverIfNeeded("network", forceWhenIdle = true)
    }

    fun enableFollowerSensor(
        context: Context,
        url: String?,
        secret: String?,
        connectNow: Boolean = true,
        useV3: Boolean = loadConfig(context).useV3,
    ): String? {
        val normalizedUrl = normalizeUrl(url)
        if (normalizedUrl.isEmpty()) return null
        val sensorId = deriveSensorId(normalizedUrl)
        // Read before the previous server's follower goes: ending it moves the current sensor
        // off it, and the follower that replaces it should be what is shown instead.
        val takeOverCurrent = connectNow && followerTakesOverCurrent(
            currentMain = runCatching { SensorIdentity.resolveMainSensor() }.getOrNull(),
            sensorId = sensorId,
            fullName = ::nativeFullName,
        )
        saveConfig(context, enabled = true, url = normalizedUrl, secret = secret, useV3 = useV3)
        stopInactiveFollowers(context)
        if (connectNow) {
            connectSensor(context, sensorId)
            if (takeOverCurrent) SensorBluetooth.setCurrentSensorSelection(sensorId)
        } else {
            val config = loadConfig(context)
            findRunningFollower(sensorId)?.updateSettings(config.secret, config.useV3)
        }
        return sensorId
    }

    fun disableFollowerSensor(context: Context) {
        val config = loadConfig(context)
        NightscoutFollowerDeviceStatus.clear()
        ManagedCurrentSensor.clearIfMatches(config.sensorId)
        saveConfig(context, enabled = false, url = config.url, secret = config.secret, useV3 = config.useV3)
        stopInactiveFollowers(context)
    }

    /** What saving the Nightscout settings does to the follower; see [followerSettingsAction]. */
    internal enum class FollowerSettingsAction {
        /** Upload mode, or Nightscout off: no follower runs. */
        DISABLE,

        /** Follow mode without a URL yet: no follower can run. */
        AWAIT_URL,

        /** Follow mode: stop any other server's follower and start (or poll) this server's. */
        START,

        /** Follow mode, same server, no poll asked for: the running follower takes the new settings. */
        UPDATE,
    }

    /**
     * The follower half of saving the Nightscout settings. A follower that points at another
     * server than [url] always goes, even when the caller asked for no connection: following a
     * URL means following that server and no other, from the moment it is saved.
     */
    internal fun followerSettingsAction(
        follow: Boolean,
        url: String?,
        connectRequested: Boolean,
        previous: Config,
    ): FollowerSettingsAction {
        val normalizedUrl = normalizeUrl(url)
        return when {
            !follow -> FollowerSettingsAction.DISABLE
            normalizedUrl.isEmpty() -> FollowerSettingsAction.AWAIT_URL
            connectRequested -> FollowerSettingsAction.START
            !previous.isUsable || !matchesSensorId(previous.sensorId, deriveSensorId(normalizedUrl)) ->
                FollowerSettingsAction.START
            else -> FollowerSettingsAction.UPDATE
        }
    }

    /**
     * Stores the follower half of the Nightscout settings and brings the running followers in
     * line with it: afterwards only the follower of the server [url] names runs, and only in
     * Follow mode ([follow]). [connectNow] polls that follower straight away.
     */
    fun applyFollowerSettings(
        context: Context,
        follow: Boolean,
        url: String?,
        secret: String?,
        useV3: Boolean,
        connectNow: Boolean,
    ) {
        val normalizedUrl = normalizeUrl(url)
        when (followerSettingsAction(follow, normalizedUrl, connectNow, loadConfig(context))) {
            FollowerSettingsAction.DISABLE -> {
                // Not only when the follower was enabled: a follower left running without
                // being enabled is exactly the one that has to go.
                disableFollowerSensor(context)
                saveConfig(context, enabled = false, url = normalizedUrl, secret = secret, useV3 = useV3)
            }
            FollowerSettingsAction.AWAIT_URL -> {
                saveConfig(context, enabled = true, url = normalizedUrl, secret = secret, useV3 = useV3)
                stopInactiveFollowers(context)
            }
            FollowerSettingsAction.START ->
                enableFollowerSensor(context, normalizedUrl, secret, connectNow = true, useV3 = useV3)
            FollowerSettingsAction.UPDATE ->
                enableFollowerSensor(context, normalizedUrl, secret, connectNow = false, useV3 = useV3)
        }
    }

    /**
     * Stops every running follower that is not the enabled one, takes it off the callback
     * list, and ends its native record ([endInactiveFollowerRecords]). A follower is tied to
     * the server it was created for, so after the URL changes the old one would otherwise go on
     * polling the old server, and writing to its record, until the app restarted.
     */
    fun stopInactiveFollowers(context: Context) {
        val config = loadConfig(context)
        val inactive = inactiveFollowerCallbacks(
            callbacks = SensorBluetooth.mygatts(),
            serialOf = { it.SerialNumber },
            enabledSensorId = config.sensorId.takeIf { config.isUsable },
            fullName = ::nativeFullName,
        )
        inactive.forEach { retireFollowerCallback(context, it) }
        if (inactive.isNotEmpty()) ManagedSensorUiSignals.markDeviceListDirty()
        endInactiveFollowerRecords(context)
    }

    /**
     * The follower callbacks among [callbacks] that the enabled follower ([enabledSensorId],
     * null when none is) is not: each is named by its follower id, or by native's short name for
     * a follower record.
     */
    internal fun <T> inactiveFollowerCallbacks(
        callbacks: Iterable<T>,
        serialOf: (T) -> String?,
        enabledSensorId: String?,
        fullName: (String) -> String?,
    ): List<T> =
        callbacks.filter { callback ->
            val follower = followerRecordName(serialOf(callback), fullName) ?: return@filter false
            !matchesSensorId(follower, enabledSensorId)
        }

    /**
     * Whether the follower [sensorId] should become the current sensor when it starts: the
     * current one ([currentMain]) is the follower of another server, which is about to stop.
     */
    internal fun followerTakesOverCurrent(
        currentMain: String?,
        sensorId: String,
        fullName: (String) -> String?,
    ): Boolean {
        val current = followerRecordName(currentMain, fullName) ?: return false
        return !matchesSensorId(current, sensorId)
    }

    private fun retireFollowerCallback(context: Context, callback: SuperGattCallback) {
        val serial = callback.SerialNumber
        try {
            if (callback is ManagedBluetoothSensorDriver) {
                callback.terminateManagedSensor(wipeData = false)
            }
            SensorBluetooth.sensorEnded(serial)
            // sensorEnded() works through NG's Bluetooth object, which does not exist while its
            // Bluetooth is off (blueone == null). A follower runs without it, from the static
            // callback list, so it would stay listed: shown, still holding its poll alarm, and
            // found again by restoreConfiguredFollower.
            if (SensorBluetooth.mygatts().any { it === callback }) {
                SensorBluetooth.retireCloneSensor(serial)
                ManagedCurrentSensor.clearIfMatches(serial)
                ManagedSensorIdentityRegistry.removePersistedSensor(context, serial)
            }
            Log.i(TAG, "Stopped inactive follower $serial")
        } catch (t: Throwable) {
            Log.stack(TAG, "retireFollowerCallback($serial)", t)
        }
    }

    /** Native's full name for record [name], or null; a failed lookup must not stop a settings save. */
    private fun nativeFullName(name: String): String? =
        runCatching { Natives.resolveFullSensorName(name) }.getOrNull()

    /**
     * Ends the native record of every follower that is not the enabled one, the way a finished
     * sensor ends: its readings stay, but [Natives.activeSensors] stops listing it. While listed,
     * a stopped follower's record was still served to the watch, and the next roster rebuild gave
     * it a Libre callback that scanned for a sensor that does not exist. Enabling the follower
     * again reactivates the record with its next reading.
     *
     * Runs when the follower stops, and at startup for records an earlier version left active.
     */
    fun endInactiveFollowerRecords(context: Context) {
        val config = loadConfig(context)
        val active = runCatching { Natives.activeSensors() }
            .onFailure { Log.stack(TAG, "endInactiveFollowerRecords(activeSensors)", it) }
            .getOrNull() ?: return
        val inactive = inactiveFollowerRecords(
            activeNames = active,
            enabledSensorId = config.sensorId.takeIf { config.isUsable },
            fullName = Natives::resolveFullSensorName,
        )
        if (inactive.isEmpty()) return
        inactive.forEach(::endNativeRecord)
        ManagedSensorUiSignals.markDeviceListDirty()
    }

    /**
     * The follower records among [activeNames], as [Natives.activeSensors] lists them, that the
     * enabled follower ([enabledSensorId], null when none is) does not write to. Native lists a
     * 16-character record without its first five characters, which hides the NSF- prefix, so
     * each name is looked up as the record's full name first. Returns full names, each once.
     */
    internal fun inactiveFollowerRecords(
        activeNames: Array<out String?>?,
        enabledSensorId: String?,
        fullName: (String) -> String?,
    ): List<String> =
        activeNames.orEmpty()
            .asSequence()
            .mapNotNull { name -> followerRecordName(name, fullName) }
            .filterNot { matchesSensorId(it, enabledSensorId) }
            .distinctBy { it.uppercase(Locale.US) }
            .toList()

    /** The full follower id of native record [name] (full or short), or null for any other sensor. */
    internal fun followerRecordName(name: String?, fullName: (String) -> String?): String? {
        val trimmed = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (isFollowerSensorId(trimmed)) return trimmed
        return fullName(trimmed)?.trim()?.takeIf { isFollowerSensorId(it) }
    }

    private fun endNativeRecord(sensorId: String) {
        // finishSensor() works on a stream, which carries the record's exact list index; the
        // stream is only borrowed for that.
        val dataptr = runCatching { Natives.getdataptr(sensorId) }.getOrDefault(0L)
        if (dataptr == 0L) {
            Log.w(TAG, "No native record to end for $sensorId")
            return
        }
        try {
            Natives.finishSensor(dataptr)
            Log.i(TAG, "Ended native record of inactive follower $sensorId")
        } catch (t: Throwable) {
            Log.stack(TAG, "endNativeRecord($sensorId)", t)
            return
        } finally {
            runCatching { Natives.freedataptr(dataptr) }
        }
        runCatching {
            ManagedCurrentSensor.clearIfMatches(sensorId)
            if (SensorIdentity.matches(Natives.lastsensorname(), sensorId)) {
                SensorBluetooth.setCurrentSensorSelection(
                    SensorBluetooth.resolveReplacementSensorSerial(sensorId) ?: ""
                )
            }
        }.onFailure { Log.stack(TAG, "endNativeRecord($sensorId) current sensor", it) }
    }

    fun connectSensor(context: Context, sensorId: String) {
        val callback = restoreConfiguredFollower(context, sensorId) ?: return
        SensorBluetooth.ensureCurrentSensorSelection()
        callback.connectDevice(0)
        ManagedSensorUiSignals.markDeviceListDirty()
    }

    private fun findRunningFollower(sensorId: String): NightscoutFollowerManager? =
        SensorBluetooth.mygatts().firstOrNull { callback ->
            callback is NightscoutFollowerManager && callback.matchesManagedSensorId(sensorId)
        } as? NightscoutFollowerManager

    private fun findRunningFollowerLocked(sensorId: String): NightscoutFollowerManager? =
        SensorBluetooth.gattcallbacks.firstOrNull { callback ->
            callback is NightscoutFollowerManager && callback.matchesManagedSensorId(sensorId)
        } as? NightscoutFollowerManager

    fun matchesSensorId(candidate: String?, expected: String?): Boolean {
        val left = candidate?.trim().orEmpty()
        val right = expected?.trim().orEmpty()
        return left.isNotEmpty() && right.isNotEmpty() && left.equals(right, ignoreCase = true)
    }

    fun isFollowerSensorId(candidate: String?): Boolean =
        candidate?.trim()?.startsWith(SENSOR_PREFIX, ignoreCase = true) == true

    fun applyAuth(connection: HttpURLConnection, secret: String) {
        val trimmed = secret.trim()
        if (trimmed.isEmpty()) return
        if (trimmed.startsWith("Bearer ", ignoreCase = true)) {
            connection.setRequestProperty("Authorization", trimmed)
            return
        }
        if (trimmed.startsWith("token=", ignoreCase = true)) {
            connection.setRequestProperty("Authorization", "Bearer ${trimmed.substringAfter('=')}")
            return
        }
        connection.setRequestProperty(
            "api-secret",
            if (isSha1Hex(trimmed)) trimmed else sha1(trimmed)
        )
    }

    // Replaces Regex("^[0-9a-fA-F]{40}$") to avoid repeated ICU JNI allocation on
    // the NightscoutFollower HandlerThread.  On Samsung Android 15 with Scudo+MTE the
    // ReleaseIntArrayElements call inside MatcherNative_matchesImpl corrupts the chunk
    // header after many poll cycles, resulting in a fatal SIGABRT.
    private fun isSha1Hex(s: String): Boolean {
        if (s.length != 40) return false
        return s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    private fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.US, it) }
}
