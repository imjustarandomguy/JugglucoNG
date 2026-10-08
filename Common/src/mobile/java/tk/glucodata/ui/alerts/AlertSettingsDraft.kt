package tk.glucodata.ui.alerts

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.util.Base64
import kotlin.math.abs
import org.json.JSONArray
import tk.glucodata.alerts.AlertConfig
import tk.glucodata.alerts.AlertDeliveryMode
import tk.glucodata.alerts.AlertType
import tk.glucodata.alerts.CustomAlertConfig
import tk.glucodata.alerts.CustomAlertType
import tk.glucodata.alerts.GlobalAlertSettings
import tk.glucodata.alerts.HapticProfile
import tk.glucodata.alerts.sanitizeAlertDurationSeconds

/**
 * The quiet window's settings, as against a window itself: what a silenced
 * alarm keeps, how long it may stay silent, which alarms sound anyway, and how
 * long the quick-settings tile starts one for.
 */
data class QuietWindowSettings(
    val mode: String,
    val breakthroughMinutes: Int,
    val breakthroughScope: String,
    val defaultMinutes: Int,
) : Serializable

/** Everything the alert screen edits, as one value. */
data class AlertSettingsValues(
    val configs: Map<AlertType, AlertConfig>,
    val global: GlobalAlertSettings,
    val quietWindow: QuietWindowSettings,
    val customAlerts: List<CustomAlertConfig>,
)

/** Where a Save writes: the repositories on the screen, a recorder in a test. */
interface AlertSettingsStore {
    fun saveConfig(config: AlertConfig)
    fun saveGlobal(settings: GlobalAlertSettings)
    fun saveQuietWindow(saved: QuietWindowSettings, settings: QuietWindowSettings)
    fun saveCustomAlerts(saved: List<CustomAlertConfig>, draft: List<CustomAlertConfig>)

    /** Runs the saves in [block] as one, so the watch hears of them once. */
    fun together(block: () -> Unit) = block()

    /** What is stored now, for Save to merge over; null when it cannot be read. */
    fun load(): AlertSettingsValues? = null
}

/**
 * The parts of a draft that were edited, each whole, and each part as it was
 * stored when the draft began (the base of [mergeFields]). A draft kept by a
 * build without bases has none, and its parts then count whole.
 */
data class PendingAlertEdits(
    val configs: Map<AlertType, AlertConfig> = emptyMap(),
    val global: GlobalAlertSettings? = null,
    val quietWindow: QuietWindowSettings? = null,
    val customAlerts: List<CustomAlertConfig>? = null,
    val baseConfigs: Map<AlertType, AlertConfig> = emptyMap(),
    val baseGlobal: GlobalAlertSettings? = null,
    val baseQuietWindow: QuietWindowSettings? = null,
    val baseCustomAlerts: List<CustomAlertConfig>? = null,
) {
    val isEmpty: Boolean
        get() = configs.isEmpty() && global == null && quietWindow == null && customAlerts == null
}

/**
 * The alert screen's edits: what is stored ([saved]) and what the screen shows
 * ([draft]). Nothing reaches the alerts, the watch or the stores until [saveTo].
 *
 * Each part - one alert's [AlertConfig], the [GlobalAlertSettings], the quiet
 * window's settings, the custom alerts - is compared whole, with equals, so a
 * setting added to any of them is a change without being listed here. Only the
 * change summary ([changes]) names settings, and it falls back to "Other
 * settings" for one it does not know.
 *
 * What is stored can change under the draft (the watch switches an alert, a
 * notification action, another screen). The draft is laid over it again field
 * by field ([rebase], [mergeFields]) when the page comes back and at Save, so
 * Save never undoes such a change to a setting the draft left alone. A setting
 * changed both here and elsewhere keeps the draft's value: it is the one on
 * screen, and the one Save is asked to store.
 */
data class AlertSettingsDraft(
    val saved: AlertSettingsValues,
    val draft: AlertSettingsValues = saved,
) {
    val isDirty: Boolean
        get() = draft != saved

    fun savedConfig(type: AlertType): AlertConfig? = saved.configs[type]

    fun isDirty(type: AlertType): Boolean = draft.configs[type] != saved.configs[type]

    /**
     * The draft with [config] in it. A value that differs from the stored one
     * only by less than the screen shows counts as the stored one ([matchedTo]).
     */
    fun withConfig(config: AlertConfig, isMmol: Boolean): AlertSettingsDraft {
        val stored = saved.configs[config.type] ?: return this
        return copy(draft = draft.copy(configs = draft.configs + (config.type to config.matchedTo(stored, isMmol))))
    }

    /** Every alert's configuration through [transform]: the master switch, Apply to all. */
    fun withConfigs(isMmol: Boolean, transform: (AlertConfig) -> AlertConfig): AlertSettingsDraft =
        draft.configs.values.fold(this) { result, config -> result.withConfig(transform(config), isMmol) }

    fun withGlobal(global: GlobalAlertSettings): AlertSettingsDraft =
        copy(draft = draft.copy(global = global))

    fun withQuietWindow(settings: QuietWindowSettings): AlertSettingsDraft =
        copy(draft = draft.copy(quietWindow = settings))

    fun withCustomAlerts(alerts: List<CustomAlertConfig>): AlertSettingsDraft =
        copy(draft = draft.copy(customAlerts = alerts.matchedTo(saved.customAlerts)))

    fun discard(): AlertSettingsDraft = copy(draft = saved)

    fun pendingEdits(): PendingAlertEdits {
        val configs = draft.configs.filter { (type, config) -> saved.configs[type] != config }
        val global = draft.global.takeIf { it != saved.global }
        val quietWindow = draft.quietWindow.takeIf { it != saved.quietWindow }
        val customAlerts = draft.customAlerts.takeIf { it != saved.customAlerts }
        return PendingAlertEdits(
            configs = configs,
            global = global,
            quietWindow = quietWindow,
            customAlerts = customAlerts,
            baseConfigs = saved.configs.filterKeys { it in configs },
            baseGlobal = saved.global.takeIf { global != null },
            baseQuietWindow = saved.quietWindow.takeIf { quietWindow != null },
            baseCustomAlerts = saved.customAlerts.takeIf { customAlerts != null },
        )
    }

    /**
     * The same edits over [stored], read again, field by field ([mergeFields]): a
     * setting the draft left alone follows what is stored now (the watch switched
     * an alert, a quiet window started), a setting it changed keeps the edit.
     */
    fun rebase(stored: AlertSettingsValues, isMmol: Boolean): AlertSettingsDraft =
        restore(stored, pendingEdits(), isMmol)

    /**
     * Writes each edited part once, all inside one [AlertSettingsStore.together],
     * and returns the draft with nothing left to save. The draft is first laid
     * over what [AlertSettingsStore.load] reads now, field by field, so a setting
     * changed elsewhere since the last look is not written back over.
     */
    fun saveTo(store: AlertSettingsStore): AlertSettingsDraft {
        if (!isDirty) return this
        val merged = store.load()?.let { layOver(it, pendingEdits(), isMmol = null) } ?: this
        if (!merged.isDirty) return merged
        val saved = merged.saved
        val draft = merged.draft
        store.together {
            draft.configs.values.forEach { config ->
                if (saved.configs[config.type] != config) store.saveConfig(config)
            }
            if (draft.global != saved.global) store.saveGlobal(draft.global)
            if (draft.quietWindow != saved.quietWindow) store.saveQuietWindow(saved.quietWindow, draft.quietWindow)
            if (draft.customAlerts != saved.customAlerts) store.saveCustomAlerts(saved.customAlerts, draft.customAlerts)
        }
        return AlertSettingsDraft(saved = draft)
    }

    /**
     * Each setting the draft changes, in [order] for the alerts (the screen's own),
     * then the custom alerts, the settings for all alerts and the quiet window.
     */
    fun changes(order: List<AlertType> = AlertType.settingsEntries): List<AlertSettingChange> = buildList {
        (order + draft.configs.keys.filterNot { it in order }).forEach { type ->
            val before = saved.configs[type] ?: return@forEach
            val after = draft.configs[type] ?: return@forEach
            addAll(diff(AlertSettingSubject.Alert(type), before, after, CONFIG_FIELDS))
        }
        addAll(customAlertChanges(saved.customAlerts, draft.customAlerts))
        addAll(diff(AlertSettingSubject.AllAlerts, saved.global, draft.global, GLOBAL_FIELDS))
        addAll(diff(AlertSettingSubject.QuietWindow, saved.quietWindow, draft.quietWindow, QUIET_WINDOW_FIELDS))
    }

    companion object {
        /**
         * [edits] laid over [stored], field by field from their bases ([mergeFields]):
         * a draft whose edits survived the screen, or the process.
         */
        fun restore(stored: AlertSettingsValues, edits: PendingAlertEdits, isMmol: Boolean): AlertSettingsDraft =
            layOver(stored, edits, isMmol)

        /**
         * [restore], and Save's merge with [isMmol] null: a value the draft sets is
         * then written as it is, without matching it to the stored one ([matchedTo]).
         */
        private fun layOver(stored: AlertSettingsValues, edits: PendingAlertEdits, isMmol: Boolean?): AlertSettingsDraft =
            AlertSettingsDraft(
                saved = stored,
                draft = AlertSettingsValues(
                    configs = stored.configs.mapValues { (type, storedConfig) ->
                        val edit = edits.configs[type]?.takeIf { it.type == type } ?: return@mapValues storedConfig
                        val base = edits.baseConfigs[type]?.takeIf { it.type == type }
                        val merged = if (base != null) mergeFields(base, edit, storedConfig) else edit
                        if (isMmol != null) merged.matchedTo(storedConfig, isMmol) else merged
                    },
                    global = edits.global?.let { edit ->
                        edits.baseGlobal?.let { mergeFields(it, edit, stored.global) } ?: edit
                    } ?: stored.global,
                    quietWindow = edits.quietWindow?.let { edit ->
                        edits.baseQuietWindow?.let { mergeFields(it, edit, stored.quietWindow) } ?: edit
                    } ?: stored.quietWindow,
                    customAlerts = edits.customAlerts?.let { edit ->
                        (edits.baseCustomAlerts?.let { mergeCustomAlerts(it, edit, stored.customAlerts) } ?: edit)
                            .matchedTo(stored.customAlerts)
                    } ?: stored.customAlerts,
                ),
            )
    }
}

/**
 * [this] with each value that differs from [saved] by less than the screen
 * shows put back to the stored one, so that it is no edit: a legacy alarm's
 * threshold comes back from native storage as whole mg/dl (3.6 mmol/L reads
 * 3.61), and a fall rule switched off again stores 0 where it had none.
 */
internal fun AlertConfig.matchedTo(saved: AlertConfig, isMmol: Boolean): AlertConfig {
    var result = this
    val value = threshold
    val stored = saved.threshold
    if (value != stored && value != null && stored != null && abs(value - stored) < if (isMmol) 0.05f else 0.5f) {
        result = result.copy(threshold = stored)
    }
    if (fallRateSuppress != saved.fallRateSuppress && (fallRateSuppress ?: 0f) <= 0f && (saved.fallRateSuppress ?: 0f) <= 0f) {
        result = result.copy(fallRateSuppress = saved.fallRateSuppress)
    }
    return result
}

/** [this] with each alert's snooze as stored: a snooze is not a setting. */
internal fun List<CustomAlertConfig>.matchedTo(saved: List<CustomAlertConfig>): List<CustomAlertConfig> {
    val savedById = saved.associateBy { it.id }
    return map { alert -> savedById[alert.id]?.let { alert.copy(snoozedUntil = it.snoozedUntil) } ?: alert }
}

/**
 * What to store for the custom alerts: the list as stored now ([current]) with
 * the screen's edits since [saved] applied - each alert it changed, added or
 * removed - and the rest as [current] has it: a snooze taken meanwhile, an
 * alert added elsewhere.
 */
internal fun mergeCustomAlerts(
    saved: List<CustomAlertConfig>,
    draft: List<CustomAlertConfig>,
    current: List<CustomAlertConfig>,
): List<CustomAlertConfig> {
    val savedById = saved.associateBy { it.id }
    val currentById = current.associateBy { it.id }
    val draftIds = draft.mapTo(HashSet()) { it.id }
    val result = ArrayList<CustomAlertConfig>()
    draft.forEach { edited ->
        val before = savedById[edited.id]
        val now = currentById[edited.id]
        when {
            before == null -> result += edited
            edited == before -> if (now != null) result += now
            else -> result += edited.copy(snoozedUntil = now?.snoozedUntil ?: edited.snoozedUntil)
        }
    }
    current.forEach { alert ->
        if (alert.id !in savedById && alert.id !in draftIds) result += alert
    }
    return result
}

// ---- merging a part field by field -----------------------------------------------

/**
 * One alert's configuration merged three ways: [base] as stored when the draft
 * began, [draft] as on screen, [current] as stored now. A setting the draft
 * left as in [base] takes [current]; a setting the draft changed keeps the
 * draft, also when [current] changed it too. The active hours' start and end
 * each merge whole (hour and minute together).
 */
internal fun mergeFields(base: AlertConfig, draft: AlertConfig, current: AlertConfig): AlertConfig =
    mergeNamed(base, draft, current) { start, pick ->
        val activeStart = pick { it.activeStartHour to it.activeStartMinute }
        val activeEnd = pick { it.activeEndHour to it.activeEndMinute }
        start.copy(
            enabled = pick { it.enabled },
            threshold = pick { it.threshold },
            durationMinutes = pick { it.durationMinutes },
            forecastMinutes = pick { it.forecastMinutes },
            rearmMargin = pick { it.rearmMargin },
            rearmMinIntervalMinutes = pick { it.rearmMinIntervalMinutes },
            iobCoverageFactor = pick { it.iobCoverageFactor },
            fallRateSuppress = pick { it.fallRateSuppress },
            deltaThreshold = pick { it.deltaThreshold },
            deltaCount = pick { it.deltaCount },
            deltaBorder = pick { it.deltaBorder },
            deltaIntervalMinutes = pick { it.deltaIntervalMinutes },
            earlyTriggerEnabled = pick { it.earlyTriggerEnabled },
            deliveryMode = pick { it.deliveryMode },
            overrideDND = pick { it.overrideDND },
            soundEnabled = pick { it.soundEnabled },
            customSoundUri = pick { it.customSoundUri },
            vibrationEnabled = pick { it.vibrationEnabled },
            hapticProfile = pick { it.hapticProfile },
            flashEnabled = pick { it.flashEnabled },
            soundDelayEnabled = pick { it.soundDelayEnabled },
            soundDelaySeconds = pick { it.soundDelaySeconds },
            defaultSnoozeMinutes = pick { it.defaultSnoozeMinutes },
            alarmDurationSeconds = pick { it.alarmDurationSeconds },
            activeStartHour = activeStart.first,
            activeStartMinute = activeStart.second,
            activeEndHour = activeEnd.first,
            activeEndMinute = activeEnd.second,
            timeRangeEnabled = pick { it.timeRangeEnabled },
            retryEnabled = pick { it.retryEnabled },
            retryIntervalMinutes = pick { it.retryIntervalMinutes },
            retryCount = pick { it.retryCount },
            expiryWarningMinutes = pick { it.expiryWarningMinutes },
        )
    }

/** The settings for all alerts, merged as [mergeFields] merges an alert's. */
internal fun mergeFields(base: GlobalAlertSettings, draft: GlobalAlertSettings, current: GlobalAlertSettings) =
    mergeNamed(base, draft, current) { start, pick ->
        start.copy(
            sameDirectionSuppressionMinutes = pick { it.sameDirectionSuppressionMinutes },
            acknowledgedHighCoverage = pick { it.acknowledgedHighCoverage },
        )
    }

/** The quiet window's settings, merged as [mergeFields] merges an alert's. */
internal fun mergeFields(base: QuietWindowSettings, draft: QuietWindowSettings, current: QuietWindowSettings) =
    mergeNamed(base, draft, current) { start, pick ->
        start.copy(
            mode = pick { it.mode },
            breakthroughMinutes = pick { it.breakthroughMinutes },
            breakthroughScope = pick { it.breakthroughScope },
            defaultMinutes = pick { it.defaultMinutes },
        )
    }

/** Takes a setting from the draft when the draft changed it, else from what is stored now. */
internal class FieldPicker<T>(private val base: T, private val draft: T, private val current: T) {
    operator fun <V> invoke(read: (T) -> V): V = read(draft).let { if (it != read(base)) it else read(current) }
}

/**
 * Runs [merge], which copies [start] with each setting it names taken through
 * the picker. A setting it does not name (one added to the class later) cannot
 * be merged on its own: once the draft changed one, the merge starts from the
 * draft rather than [current], so the edit is kept whole instead of lost.
 */
private fun <T> mergeNamed(base: T, draft: T, current: T, merge: (start: T, pick: FieldPicker<T>) -> T): T {
    if (draft == base) return current
    if (current == base) return draft
    val unnamedEdited = merge(base, FieldPicker(base, draft, base)) != draft
    return merge(if (unnamedEdited) draft else current, FieldPicker(base, draft, current))
}

/**
 * Whether [this] differs from [saved] in anything a card shows under Advanced:
 * anything but the headline settings and the rows above Advanced. The difference
 * is read whole, so a setting added later counts without being listed.
 */
internal fun AlertConfig.differsUnderAdvanced(saved: AlertConfig): Boolean =
    saved.copy(
        enabled = enabled,
        threshold = threshold,
        durationMinutes = durationMinutes,
        forecastMinutes = forecastMinutes,
        expiryWarningMinutes = expiryWarningMinutes,
        deltaThreshold = deltaThreshold,
        deltaCount = deltaCount,
        deltaBorder = deltaBorder,
        soundEnabled = soundEnabled,
        vibrationEnabled = vibrationEnabled,
        flashEnabled = flashEnabled,
        customSoundUri = customSoundUri,
        overrideDND = overrideDND,
        timeRangeEnabled = timeRangeEnabled,
        activeStartHour = activeStartHour,
        activeStartMinute = activeStartMinute,
        activeEndHour = activeEndHour,
        activeEndMinute = activeEndMinute,
    ) != this

/** A custom alert as the shared alert body edits it. */
internal fun CustomAlertConfig.asAlertConfig(): AlertConfig = AlertConfig(
    type = if (type == CustomAlertType.HIGH) AlertType.HIGH else AlertType.LOW,
    enabled = enabled,
    threshold = threshold,
    soundEnabled = sound,
    customSoundUri = soundUri,
    vibrationEnabled = vibrate,
    flashEnabled = flash,
    hapticProfile = customHapticProfile(hapticProfile),
    alarmDurationSeconds = sanitizeAlertDurationSeconds(durationSeconds),
    deliveryMode = when (style.lowercase()) {
        "alarm", "system_alarm" -> AlertDeliveryMode.SYSTEM_ALARM
        "both" -> AlertDeliveryMode.BOTH
        "notification" -> AlertDeliveryMode.NOTIFICATION_ONLY
        else -> AlertDeliveryMode.SYSTEM_ALARM
    },
    overrideDND = overrideDnd,
    retryEnabled = retryEnabled,
    retryIntervalMinutes = retryIntervalMinutes,
    retryCount = retryCount,
    timeRangeEnabled = timeRangeEnabled,
    activeStartHour = startTimeMinutes / 60,
    activeStartMinute = startTimeMinutes % 60,
    activeEndHour = endTimeMinutes / 60,
    activeEndMinute = endTimeMinutes % 60
)

internal fun customHapticProfile(value: String): HapticProfile {
    return runCatching { HapticProfile.valueOf(value.uppercase()) }.getOrElse {
        when (value.lowercase()) {
            "soft", "low", "silent" -> HapticProfile.SOFT
            "steady", "medium" -> HapticProfile.STEADY
            "escalating", "ascending" -> HapticProfile.ESCALATING
            else -> HapticProfile.STRONG
        }
    }
}

// ---- the change summary --------------------------------------------------------

/** Whom a change is about. */
sealed interface AlertSettingSubject {
    data class Alert(val type: AlertType) : AlertSettingSubject
    data class Custom(val name: String, val type: CustomAlertType) : AlertSettingSubject
    data object AllAlerts : AlertSettingSubject
    data object QuietWindow : AlertSettingSubject
}

/** The settings a change summary names. */
enum class AlertSetting {
    ENABLED,
    NAME,
    THRESHOLD,
    ALERT_AFTER,
    LOOK_AHEAD,
    EXPIRY_WARNINGS,
    DELTA_CHANGE,
    DELTA_COUNT,
    DELTA_BORDER,
    SOUND,
    VIBRATION,
    FLASH,
    ALERT_SOUND,
    OVERRIDE_SILENT,
    ACTIVE_HOURS,
    ACTIVE_START,
    ACTIVE_END,
    HAPTICS,
    DELIVERY,
    ALARM_DURATION,
    SOUND_DELAY,
    SOUND_DELAY_SECONDS,
    RETRY,
    RETRY_INTERVAL,
    RETRY_COUNT,
    DEFAULT_SNOOZE,
    REARM_MARGIN,
    REARM_INTERVAL,
    IOB_COVERAGE,
    FALL_SUPPRESS,
    DELTA_INTERVAL,
    EARLY_TRIGGER,
    SAME_DIRECTION_QUIET_PERIOD,
    QUIET_MODE,
    QUIET_BREAKTHROUGH,
    QUIET_BREAKTHROUGH_SCOPE,
    QUIET_TILE_DEFAULT,
    ADDED,
    REMOVED,
    // A difference no entry above names: a setting added after this list.
    OTHER,
}

/** One changed setting: [before] is what is stored, [after] what Save would store. */
data class AlertSettingChange(
    val subject: AlertSettingSubject,
    val setting: AlertSetting,
    val before: Any? = null,
    val after: Any? = null,
)

/** At most [max] of these, and how many were left out. */
internal fun <T> List<T>.limitTo(max: Int): Pair<List<T>, Int> =
    if (size <= max) this to 0 else take(max) to (size - max)

private class Field<T>(val setting: AlertSetting, val read: (T) -> Any?)

private fun configField(setting: AlertSetting, read: (AlertConfig) -> Any?) = Field(setting, read)

// What the time chips show for an unset start or end.
private const val DEFAULT_ACTIVE_START_MINUTES = 22 * 60
private const val DEFAULT_ACTIVE_END_MINUTES = 8 * 60

private val CONFIG_FIELDS: List<Field<AlertConfig>> = listOf(
    configField(AlertSetting.ENABLED) { it.enabled },
    configField(AlertSetting.THRESHOLD) { it.threshold },
    configField(AlertSetting.ALERT_AFTER) { it.durationMinutes },
    configField(AlertSetting.LOOK_AHEAD) { it.forecastMinutes },
    configField(AlertSetting.EXPIRY_WARNINGS) { it.expiryWarningMinutes },
    configField(AlertSetting.DELTA_CHANGE) { it.deltaThreshold },
    configField(AlertSetting.DELTA_COUNT) { it.deltaCount },
    configField(AlertSetting.DELTA_BORDER) { it.deltaBorder },
    configField(AlertSetting.SOUND) { it.soundEnabled },
    configField(AlertSetting.VIBRATION) { it.vibrationEnabled },
    configField(AlertSetting.FLASH) { it.flashEnabled },
    configField(AlertSetting.ALERT_SOUND) { it.customSoundUri },
    configField(AlertSetting.OVERRIDE_SILENT) { it.overrideDND },
    configField(AlertSetting.ACTIVE_HOURS) { it.timeRangeEnabled },
    configField(AlertSetting.ACTIVE_START) {
        it.activeStartHour?.let { hour -> hour * 60 + (it.activeStartMinute ?: 0) } ?: DEFAULT_ACTIVE_START_MINUTES
    },
    configField(AlertSetting.ACTIVE_END) {
        it.activeEndHour?.let { hour -> hour * 60 + (it.activeEndMinute ?: 0) } ?: DEFAULT_ACTIVE_END_MINUTES
    },
    configField(AlertSetting.HAPTICS) { it.hapticProfile },
    configField(AlertSetting.DELIVERY) { it.deliveryMode },
    configField(AlertSetting.ALARM_DURATION) { it.alarmDurationSeconds },
    configField(AlertSetting.SOUND_DELAY) { it.soundDelayEnabled },
    configField(AlertSetting.SOUND_DELAY_SECONDS) { it.soundDelaySeconds },
    configField(AlertSetting.RETRY) { it.retryEnabled },
    configField(AlertSetting.RETRY_INTERVAL) { it.retryIntervalMinutes },
    configField(AlertSetting.RETRY_COUNT) { it.retryCount },
    configField(AlertSetting.DEFAULT_SNOOZE) { it.defaultSnoozeMinutes },
    configField(AlertSetting.REARM_MARGIN) { it.rearmMargin },
    configField(AlertSetting.REARM_INTERVAL) { it.rearmMinIntervalMinutes },
    configField(AlertSetting.IOB_COVERAGE) { it.iobCoverageFactor },
    configField(AlertSetting.FALL_SUPPRESS) { (it.fallRateSuppress ?: 0f) > 0f },
    configField(AlertSetting.DELTA_INTERVAL) { it.deltaIntervalMinutes },
    configField(AlertSetting.EARLY_TRIGGER) { it.earlyTriggerEnabled },
)

private val GLOBAL_FIELDS: List<Field<GlobalAlertSettings>> = listOf(
    Field(AlertSetting.SAME_DIRECTION_QUIET_PERIOD) { it.sameDirectionSuppressionMinutes },
)

private val QUIET_WINDOW_FIELDS: List<Field<QuietWindowSettings>> = listOf(
    Field(AlertSetting.QUIET_MODE) { it.mode },
    Field(AlertSetting.QUIET_BREAKTHROUGH) { it.breakthroughMinutes },
    Field(AlertSetting.QUIET_BREAKTHROUGH_SCOPE) { it.breakthroughScope },
    Field(AlertSetting.QUIET_TILE_DEFAULT) { it.defaultMinutes },
)

private fun <T> diff(
    subject: AlertSettingSubject,
    before: T,
    after: T,
    fields: List<Field<T>>,
): List<AlertSettingChange> {
    if (before == after) return emptyList()
    val changes = fields.mapNotNull { field ->
        val old = field.read(before)
        val new = field.read(after)
        if (old != new) AlertSettingChange(subject, field.setting, old, new) else null
    }
    return changes.ifEmpty { listOf(AlertSettingChange(subject, AlertSetting.OTHER)) }
}

private fun customAlertChanges(
    saved: List<CustomAlertConfig>,
    draft: List<CustomAlertConfig>,
): List<AlertSettingChange> = buildList {
    val savedById = saved.associateBy { it.id }
    val draftIds = draft.mapTo(HashSet()) { it.id }
    draft.forEach { after ->
        val subject = AlertSettingSubject.Custom(after.name, after.type)
        val before = savedById[after.id]
        when {
            before == null -> add(AlertSettingChange(subject, AlertSetting.ADDED))
            before != after -> {
                val named = if (before.name != after.name) {
                    listOf(AlertSettingChange(subject, AlertSetting.NAME, before.name, after.name))
                } else {
                    emptyList()
                }
                val settings = diff(subject, before.asAlertConfig(), after.asAlertConfig(), CONFIG_FIELDS)
                    .filterNot { named.isNotEmpty() && it.setting == AlertSetting.OTHER }
                addAll(named + settings)
                if (named.isEmpty() && settings.isEmpty()) add(AlertSettingChange(subject, AlertSetting.OTHER))
            }
        }
    }
    saved.forEach { before ->
        if (before.id !in draftIds) {
            add(AlertSettingChange(AlertSettingSubject.Custom(before.name, before.type), AlertSetting.REMOVED))
        }
    }
}

// ---- keeping a draft on disk ---------------------------------------------------

/**
 * [PendingAlertEdits] as strings for a preferences file, and back. The alert
 * parts go through Java serialization, which covers every field without a list
 * of them; a part this build cannot read back (written by another version of
 * the app) is dropped, and [decode] reports it. The bases go under the same
 * keys with [BASE_PREFIX]; without its base, a part counts whole.
 */
internal object PendingAlertEditsCodec {
    private const val CONFIG_PREFIX = "config_"
    private const val GLOBAL = "global"
    private const val QUIET_WINDOW = "quiet_window"
    private const val CUSTOM_ALERTS = "custom_alerts"
    private const val BASE_PREFIX = "base_"

    /** One set of parts: the edits, or their bases. */
    private class Parts(
        val configs: MutableMap<AlertType, AlertConfig> = LinkedHashMap(),
        var global: GlobalAlertSettings? = null,
        var quietWindow: QuietWindowSettings? = null,
        var customAlerts: List<CustomAlertConfig>? = null,
    )

    fun encode(edits: PendingAlertEdits): Map<String, String> = buildMap {
        putParts("", Parts(edits.configs.toMutableMap(), edits.global, edits.quietWindow, edits.customAlerts))
        putParts(
            BASE_PREFIX,
            Parts(edits.baseConfigs.toMutableMap(), edits.baseGlobal, edits.baseQuietWindow, edits.baseCustomAlerts),
        )
    }

    private fun MutableMap<String, String>.putParts(prefix: String, parts: Parts) {
        parts.configs.forEach { (type, config) -> put(prefix + CONFIG_PREFIX + type.id, serialize(config)) }
        parts.global?.let { put(prefix + GLOBAL, serialize(it)) }
        parts.quietWindow?.let { put(prefix + QUIET_WINDOW, serialize(it)) }
        parts.customAlerts?.let { alerts ->
            put(prefix + CUSTOM_ALERTS, JSONArray().apply { alerts.forEach { put(it.toJson()) } }.toString())
        }
    }

    fun decode(
        entries: Map<String, *>,
        onUnreadable: (key: String, error: Throwable) -> Unit = { _, _ -> },
    ): PendingAlertEdits {
        val edits = Parts()
        val bases = Parts()
        entries.forEach { (key, value) ->
            val text = value as? String ?: return@forEach
            val parts = if (key.startsWith(BASE_PREFIX)) bases else edits
            val name = key.removePrefix(BASE_PREFIX)
            try {
                when {
                    name.startsWith(CONFIG_PREFIX) -> {
                        val type = AlertType.fromId(name.removePrefix(CONFIG_PREFIX).toInt())
                        val config = deserialize(text) as AlertConfig
                        if (type != null && config.type == type) parts.configs[type] = config
                    }
                    name == GLOBAL -> parts.global = deserialize(text) as GlobalAlertSettings
                    name == QUIET_WINDOW -> parts.quietWindow = deserialize(text) as QuietWindowSettings
                    name == CUSTOM_ALERTS -> {
                        val array = JSONArray(text)
                        parts.customAlerts = (0 until array.length()).map { CustomAlertConfig.fromJson(array.getJSONObject(it)) }
                    }
                }
            } catch (t: Throwable) {
                onUnreadable(key, t)
            }
        }
        return PendingAlertEdits(
            configs = edits.configs,
            global = edits.global,
            quietWindow = edits.quietWindow,
            customAlerts = edits.customAlerts,
            baseConfigs = bases.configs,
            baseGlobal = bases.global,
            baseQuietWindow = bases.quietWindow,
            baseCustomAlerts = bases.customAlerts,
        )
    }

    private fun serialize(value: Serializable): String {
        val bytes = ByteArrayOutputStream()
        ObjectOutputStream(bytes).use { it.writeObject(value) }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    private fun deserialize(text: String): Any? =
        ObjectInputStream(ByteArrayInputStream(Base64.getDecoder().decode(text))).use { it.readObject() }
}
