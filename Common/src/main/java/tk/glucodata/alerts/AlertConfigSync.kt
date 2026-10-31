package tk.glucodata.alerts

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.WearProtocol
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Mirrors each alert's complete configuration from the phone onto the watch.
 *
 * Both devices evaluate alerts, but only the on/off switch used to cross
 * ([tk.glucodata.WearToggleSync]): the watch ran its own thresholds, active
 * hours, durations and delivery settings, which in practice meant its
 * defaults. The phone is the authority, so its configuration now rides in the
 * same state message as the switches, one line per alert:
 *
 *     c:<alert id>=v1,<base64 of zlib of {"unit":<n>,"entries":{<store key>:<value>}}>
 *
 * (zlib because the store keys repeat: twelve alerts take about 5 KB instead
 * of 14, and the checksum rejects a damaged line.)
 *
 * The entries are exactly what [AlertRepository.saveConfig] stores for the
 * phone's current configuration, recorded from the same writer, and the watch
 * reads them with the same reader as its own store. So there is no list of
 * fields here: a field added to the repository travels without touching this
 * file, and an entry this build does not know is never read.
 *
 * An older watch reads the message with a parser that needs a strict
 * true/false after '=', so it drops these lines and keeps working on the
 * switches alone. That is why [WearProtocol.VERSION] is not bumped: a newer
 * version makes an older watch discard the whole message, switches included.
 * The `v1` versions the line format on its own, so a later change is skipped
 * line by line instead of message by message.
 *
 * Not carried: the keys naming something on one device only
 * ([AlertRepository.deviceLocalKeys]), and state, which is not configuration.
 * Snoozes and the sensor-expiry warnings already given live in other stores,
 * so they stay per device.
 *
 * After the alert lines comes one global line, for the settings every alert
 * shares that also change what the watch does ([GlobalAlertSettings]): where
 * alarms ring, how the watch rings them, the same-direction quiet period and
 * acknowledged-high coverage.
 *
 *     g:alerts=v1,<base64 of zlib of {"entries":{<store key>:<value>}}>
 *
 * Same body, same rules: recorded from the store's writer
 * ([AlertRepository.writeGlobalEntries]), read with its readers, and dropped by
 * an older watch, whose parsers want true/false after '=' or a "c:" scope.
 * A watch that reads the line but predates one of its keys reads its own keys
 * by name and never looks at the others, so a setting added here needs no new
 * format: "how the watch rings them" (watch_alarm_style) came that way.
 */
object AlertConfigSync {
    private const val LOG_ID = "AlertConfigSync"

    /** The line prefix, next to the switches' scopes in the same message. */
    const val SCOPE = "c"

    /** This build's line format. */
    const val FORMAT = "v1"

    /** The global line's scope and id: `g:alerts=`. */
    const val GLOBAL_SCOPE = "g"
    const val GLOBAL_ID = "alerts"
    private const val GLOBAL_PREFIX = "$GLOBAL_SCOPE:$GLOBAL_ID="

    private const val KEY_UNIT = "unit"
    private const val KEY_ENTRIES = "entries"

    /** One alert's JSON is about 1 KB; anything far past that is not a line from a phone. */
    private const val MAX_JSON_BYTES = 64 * 1024

    /**
     * One alert's configuration as it arrived: the sender's store entries
     * (a null value is a key the sender removed) and the unit they are in.
     */
    data class Received(val type: AlertType, val unit: Int, val entries: Map<String, Any?>)

    // ------------------------------------------------------------- phone side

    /**
     * Phone: one line per alert the settings show, from the configuration in
     * force, then the global line.
     */
    @JvmStatic
    fun currentLines(): List<String> {
        // The stored unit rather than Applic.unit, which reads 0 until Notify
        // has started: a line claiming mg/dL over mmol/L values would arm the
        // watch's low alarm at 3.6 mg/dL.
        val unit = runCatching { Natives.getunit() }.getOrDefault(Applic.unit)
        val alertLines = AlertType.settingsEntries.mapNotNull { type ->
            runCatching { encodeLine(AlertRepository.loadConfig(type), unit) }
                .onFailure { Log.stack(LOG_ID, "encode ${type.id}", it) }
                .getOrNull()
        }
        val globalLine = runCatching { encodeGlobalLine(AlertRepository.loadGlobalSettings()) }
            .onFailure { Log.stack(LOG_ID, "encode global", it) }
            .getOrNull()
        return alertLines + listOfNotNull(globalLine)
    }

    /** What the store holds for [config], less this device's own keys. */
    @JvmStatic
    fun entriesOf(config: AlertConfig): Map<String, Any?> {
        val recorder = RecordingEditor()
        AlertRepository.writeConfigEntries(config, recorder)
        return recorder.entries - AlertRepository.deviceLocalKeys(config.type)
    }

    @JvmStatic
    fun encodeLine(config: AlertConfig, unit: Int): String =
        encodeLine(config.type.id, unit, entriesOf(config))

    internal fun encodeLine(typeId: Int, unit: Int, entries: Map<String, Any?>): String {
        val json = JSONObject()
            .put(KEY_UNIT, unit)
            .put(KEY_ENTRIES, encodeEntries(entries))
        return "$SCOPE:$typeId=$FORMAT,${packBody(json.toString())}"
    }

    /** What the store holds for [settings], recorded from its own writer. */
    @JvmStatic
    fun globalEntriesOf(settings: GlobalAlertSettings): Map<String, Any?> {
        val recorder = RecordingEditor()
        AlertRepository.writeGlobalEntries(settings, recorder)
        return recorder.entries
    }

    @JvmStatic
    fun encodeGlobalLine(settings: GlobalAlertSettings): String = encodeGlobalLine(globalEntriesOf(settings))

    internal fun encodeGlobalLine(entries: Map<String, Any?>): String {
        val json = JSONObject().put(KEY_ENTRIES, encodeEntries(entries))
        return "$GLOBAL_PREFIX$FORMAT,${packBody(json.toString())}"
    }

    /** base64(zlib(utf8)): no newline, ':' or '=' before the padding, and checksummed. */
    internal fun packBody(json: String): String {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try {
            deflater.setInput(json.toByteArray(Charsets.UTF_8))
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer))
            }
            return Base64.getEncoder().encodeToString(out.toByteArray())
        } finally {
            deflater.end()
        }
    }

    /** The inverse of [packBody]; throws on anything it did not produce. */
    internal fun unpackBody(body: String): String {
        val inflater = Inflater()
        try {
            inflater.setInput(Base64.getDecoder().decode(body))
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw DataFormatException("truncated")
                }
                out.write(buffer, 0, count)
                if (out.size() > MAX_JSON_BYTES) throw DataFormatException("too large")
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        } finally {
            inflater.end()
        }
    }

    // ------------------------------------------------------------- watch side

    /**
     * Watch: applies every usable alert line of a state message. Each goes
     * through [AlertRepository.saveConfig], so the native-backed types'
     * switches and thresholds land where the natives read them. On the watch
     * that save sends nothing back, so this cannot echo. Returns the count of
     * alerts now matching the phone. Never throws: the switches in the same
     * message are applied after this, whatever happens here.
     */
    @JvmStatic
    fun onState(data: ByteArray?): Int {
        if (!Applic.isWearable) return 0
        val lines = runCatching { decode(data) }.getOrElse {
            Log.stack(LOG_ID, "decode", it)
            return 0
        }
        var applied = 0
        lines.forEach { received ->
            runCatching {
                if (!adoptUnit(received.unit)) {
                    Log.w(LOG_ID, "could not take unit ${received.unit}; alert ${received.type.id} left as it is")
                    return@forEach
                }
                val incoming = configFrom(received, AlertRepository.storedPreferences())
                if (incoming != AlertRepository.loadConfig(received.type)) {
                    AlertRepository.saveConfig(incoming)
                }
                applied++
            }.onFailure { Log.stack(LOG_ID, "apply ${received.type.id}", it) }
        }
        applyGlobal(data)
        return applied
    }

    /** Watch: stores the global line's settings, when the message has a usable one. Never throws. */
    private fun applyGlobal(data: ByteArray?) {
        runCatching {
            val entries = decodeGlobal(data) ?: return
            val incoming = globalSettingsFrom(entries, AlertRepository.storedPreferences())
            if (incoming != AlertRepository.loadGlobalSettings()) {
                AlertRepository.saveGlobalSettings(incoming)
                Log.i(LOG_ID, "following the phone's shared alert settings: $incoming")
            }
        }.onFailure { Log.stack(LOG_ID, "apply global", it) }
    }

    /**
     * The shared settings the global line's [entries] describe, read over
     * [base]: a key the line lacks keeps [base]'s value. A value of the wrong
     * type throws, rejecting the line rather than half reading it.
     */
    @JvmStatic
    fun globalSettingsFrom(entries: Map<String, Any?>, base: SharedPreferences?): GlobalAlertSettings =
        AlertRepository.readGlobalSettings(OverlayPreferences(base, entries))

    /** The entries of a state message's global line, or null when it has no usable one. */
    @JvmStatic
    fun decodeGlobal(data: ByteArray?): Map<String, Any?>? {
        if (data == null || data.isEmpty()) return null
        val text = runCatching { data.toString(Charsets.UTF_8) }.getOrNull() ?: return null
        if (!WearProtocol.accepts(WearProtocol.declaredVersion(text))) return null
        return text.lineSequence().firstNotNullOfOrNull(::decodeGlobalLine)
    }

    /** The entries of one global line, or null when [line] is not one this build can read. Never throws. */
    @JvmStatic
    fun decodeGlobalLine(line: String): Map<String, Any?>? {
        if (!line.startsWith(GLOBAL_PREFIX)) return null
        val value = line.substring(GLOBAL_PREFIX.length)
        val formatSplit = value.indexOf(',')
        if (formatSplit < 0 || value.substring(0, formatSplit) != FORMAT) {
            Log.w(LOG_ID, "skipping shared alert settings: line format is not $FORMAT")
            return null
        }
        return runCatching {
            decodeEntries(JSONObject(unpackBody(value.substring(formatSplit + 1))).getJSONObject(KEY_ENTRIES))
        }.onFailure { Log.w(LOG_ID, "skipping unreadable shared alert settings: $it") }
            .getOrNull()
    }

    /**
     * The configuration [received] describes, read over [base], this device's
     * store: received entries win, except this device's own keys, which keep
     * [base]'s value however the sender filled them in.
     */
    @JvmStatic
    fun configFrom(received: Received, base: SharedPreferences?): AlertConfig {
        val entries = received.entries - AlertRepository.deviceLocalKeys(received.type)
        return AlertRepository.readConfig(
            received.type,
            OverlayPreferences(base, entries),
            isMmol = received.unit == 1,
        )
    }

    /** Every usable alert line in a state message; a bad line costs only itself. */
    @JvmStatic
    fun decode(data: ByteArray?): List<Received> {
        if (data == null || data.isEmpty()) return emptyList()
        val text = runCatching { data.toString(Charsets.UTF_8) }.getOrNull() ?: return emptyList()
        // The switches' rule: a message from a newer protocol is not applied.
        if (!WearProtocol.accepts(WearProtocol.declaredVersion(text))) return emptyList()
        return text.lineSequence().mapNotNull(::decodeLine).toList()
    }

    /** One line, or null when it is not an alert line this build can apply. Never throws. */
    @JvmStatic
    fun decodeLine(line: String): Received? {
        val prefix = "$SCOPE:"
        if (!line.startsWith(prefix)) return null
        val valueSplit = line.indexOf('=')
        if (valueSplit < 0) return null
        val id = line.substring(prefix.length, valueSplit).toIntOrNull() ?: return null
        // A newer phone's new alert, or a hidden legacy-only one: skipped, as
        // for the switches.
        val type = AlertType.settingsEntries.firstOrNull { it.id == id }
        if (type == null) {
            Log.w(LOG_ID, "skipping configuration for unknown alert $id")
            return null
        }
        val value = line.substring(valueSplit + 1)
        val formatSplit = value.indexOf(',')
        if (formatSplit < 0 || value.substring(0, formatSplit) != FORMAT) {
            Log.w(LOG_ID, "skipping alert $id: line format is not $FORMAT")
            return null
        }
        return runCatching {
            val json = JSONObject(unpackBody(value.substring(formatSplit + 1)))
            Received(type, json.getInt(KEY_UNIT), decodeEntries(json.getJSONObject(KEY_ENTRIES)))
        }.onFailure { Log.w(LOG_ID, "skipping unreadable configuration for alert $id: $it") }
            .getOrNull()
    }

    /**
     * Thresholds and margins are stored in display units, so the watch has to
     * be in the unit they were written in before it stores them. The watch has
     * no unit setting of its own: it takes the phone's at the /start handshake
     * ([tk.glucodata.Natives.ontbytesettings]). This applies the same rule when
     * the phone's unit changed after that handshake.
     */
    private fun adoptUnit(unit: Int): Boolean {
        val wanted = unitToAdopt(unit, Applic.unit) ?: return true
        val app = Applic.app ?: return false
        app.setunit(wanted)
        Log.i(LOG_ID, "following the phone's unit $wanted for its alert thresholds")
        return Applic.unit == wanted
    }

    /**
     * The unit this device has to switch to before storing values written in
     * [senderUnit], or null when it already reads them the same way. Only 1 is
     * mmol/L; anything else reads as mg/dL, here as everywhere else.
     */
    @JvmStatic
    fun unitToAdopt(senderUnit: Int, localUnit: Int): Int? {
        val senderMmol = senderUnit == 1
        if (senderMmol == (localUnit == 1)) return null
        return if (senderMmol) 1 else 2
    }

    // ---------------------------------------------------------------- entries

    /** Typed so the reader gets back the type it stored: JSON alone mixes up 60 and 60.0. */
    internal fun encodeEntries(entries: Map<String, Any?>): JSONObject {
        val json = JSONObject()
        entries.forEach { (key, value) ->
            json.put(
                key,
                when (value) {
                    null -> JSONObject.NULL
                    is Boolean -> "b$value"
                    is Int -> "i$value"
                    is Long -> "l$value"
                    is Float -> "f$value"
                    is String -> "s$value"
                    is Set<*> -> JSONArray(value.map { it as String }.sorted())
                    else -> throw IllegalArgumentException("$key: unsupported ${value.javaClass.simpleName}")
                },
            )
        }
        return json
    }

    internal fun decodeEntries(json: JSONObject): Map<String, Any?> {
        val entries = LinkedHashMap<String, Any?>()
        json.keys().forEach { key ->
            entries[key] = if (json.isNull(key)) {
                null
            } else {
                when (val raw = json.get(key)) {
                    is JSONArray -> (0 until raw.length()).mapTo(LinkedHashSet()) { raw.getString(it) }
                    is String -> decodeValue(raw)
                    else -> throw IllegalArgumentException("$key: unreadable value")
                }
            }
        }
        return entries
    }

    private fun decodeValue(raw: String): Any {
        val body = raw.drop(1)
        return when (raw.firstOrNull()) {
            'b' -> body.toBooleanStrict()
            'i' -> body.toInt()
            'l' -> body.toLong()
            'f' -> body.toFloat()
            's' -> body
            else -> throw IllegalArgumentException("unknown value type in \"$raw\"")
        }
    }
}

/**
 * An editor that only remembers what it was told, so the store's own writer
 * can say what it would store. A null value is a removed key.
 */
internal class RecordingEditor : SharedPreferences.Editor {
    val entries = LinkedHashMap<String, Any?>()

    private fun record(key: String?, value: Any?): SharedPreferences.Editor {
        if (key != null) entries[key] = value
        return this
    }

    override fun putString(key: String?, value: String?) = record(key, value)
    override fun putStringSet(key: String?, values: MutableSet<String>?) = record(key, values?.toSet())
    override fun putInt(key: String?, value: Int) = record(key, value)
    override fun putLong(key: String?, value: Long) = record(key, value)
    override fun putFloat(key: String?, value: Float) = record(key, value)
    override fun putBoolean(key: String?, value: Boolean) = record(key, value)
    override fun remove(key: String?) = record(key, null)

    override fun clear(): SharedPreferences.Editor =
        throw UnsupportedOperationException("a config write never clears the store")

    override fun commit(): Boolean = true
    override fun apply() {}
}

/**
 * Read-only preferences with [entries] laid over [base]: a key present in
 * [entries] reads as its value there (null meaning absent), any other key as
 * in [base]. A value of the wrong type throws, as a real store does, so a line
 * that does not match this build's reader is rejected rather than half read.
 */
internal class OverlayPreferences(
    private val base: SharedPreferences?,
    private val entries: Map<String, Any?>,
) : SharedPreferences {

    private fun overrides(key: String?): Boolean = key != null && entries.containsKey(key)

    override fun getAll(): MutableMap<String, *> {
        val all = LinkedHashMap<String, Any?>(base?.all ?: emptyMap<String, Any?>())
        entries.forEach { (key, value) -> if (value == null) all.remove(key) else all[key] = value }
        return all
    }

    override fun getString(key: String?, defValue: String?): String? =
        if (overrides(key)) entries[key] as String? ?: defValue else base?.getString(key, defValue) ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        if (overrides(key)) {
            (entries[key] as Set<String>?)?.toMutableSet() ?: defValues
        } else {
            base?.getStringSet(key, defValues) ?: defValues
        }

    override fun getInt(key: String?, defValue: Int): Int =
        if (overrides(key)) entries[key] as Int? ?: defValue else base?.getInt(key, defValue) ?: defValue

    override fun getLong(key: String?, defValue: Long): Long =
        if (overrides(key)) entries[key] as Long? ?: defValue else base?.getLong(key, defValue) ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float =
        if (overrides(key)) entries[key] as Float? ?: defValue else base?.getFloat(key, defValue) ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        if (overrides(key)) entries[key] as Boolean? ?: defValue else base?.getBoolean(key, defValue) ?: defValue

    override fun contains(key: String?): Boolean =
        if (overrides(key)) entries[key] != null else base?.contains(key) == true

    override fun edit(): SharedPreferences.Editor =
        throw UnsupportedOperationException("read-only view of received alert settings")

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}
