package tk.glucodata.ui.alerts

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tk.glucodata.AlertDeliveryPolicy
import tk.glucodata.R
import tk.glucodata.alerts.AlertDeliveryMode
import tk.glucodata.alerts.AlertType
import tk.glucodata.alerts.CustomAlertType
import tk.glucodata.alerts.HapticProfile

/** How many summary lines the leave dialog lists before "and N more". */
private const val SUMMARY_LINES = 6

/**
 * The mark beside a control whose value differs from what is saved: a dot and
 * "Changed", in the tertiary colour so it reads apart from the primary values.
 */
@Composable
internal fun ChangedMarker(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.tertiary
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.alert_settings_changed),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1
        )
    }
}

/** [label] with a [ChangedMarker] after it while [changed]; the label wraps, the mark stays whole. */
@Composable
internal fun LabelWithChange(
    changed: Boolean,
    modifier: Modifier = Modifier,
    label: @Composable (Modifier) -> Unit
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        label(Modifier.weight(1f, fill = false))
        if (changed) ChangedMarker(Modifier.padding(start = 8.dp))
    }
}

/**
 * The bar under the alert settings while the draft differs from what is saved:
 * how many changes, Discard, and Save - the page's one primary action. It sits
 * above the system navigation bar; the page pads its list by the bar's height.
 */
@Composable
internal fun UnsavedChangesBar(
    changeCount: Int,
    onDiscard: () -> Unit,
    onSave: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = pluralStringResource(R.plurals.alert_settings_unsaved_changes, changeCount, changeCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = onDiscard) {
                Text(stringResource(R.string.alert_settings_discard))
            }
            Button(onClick = onSave) {
                Text(stringResource(R.string.save))
            }
        }
    }
}

/** "Save your changes?" on the way out, with what would be saved. */
@Composable
internal fun UnsavedChangesDialog(
    changes: List<AlertSettingChange>,
    isMmol: Boolean,
    // The Δ readout's interval, which a delta alert without its own window follows.
    deltaDisplayIntervalMinutes: Int,
    onKeepEditing: () -> Unit,
    onDiscard: () -> Unit,
    onSave: () -> Unit
) {
    val context = LocalContext.current
    val text = AlertChangeText.of(context, isMmol, deltaDisplayIntervalMinutes)
    val (shown, more) = changes.limitTo(SUMMARY_LINES)
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text(stringResource(R.string.alert_settings_leave_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                shown.forEach { change ->
                    Text(
                        text = "• " + text.line(change),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (more > 0) {
                    Text(
                        text = pluralStringResource(R.plurals.alert_settings_more_changes, more, more),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onSave) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onKeepEditing) {
                    Text(stringResource(R.string.alert_settings_keep_editing))
                }
                TextButton(
                    onClick = onDiscard,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.alert_settings_discard))
                }
            }
        }
    )
}

/**
 * One line per [AlertSettingChange]: "Low · Threshold: 3.9 → 4.2". Its strings
 * come through [text], so a test can supply them.
 */
internal class AlertChangeText(
    private val isMmol: Boolean,
    private val text: (Int, Array<out Any>) -> String,
    private val soundName: (String?, Int) -> String,
    // The Δ readout's interval, which a delta alert without its own window follows.
    private val deltaDisplayIntervalMinutes: Int,
) {
    companion object {
        fun of(context: Context, isMmol: Boolean, deltaDisplayIntervalMinutes: Int) = AlertChangeText(
            isMmol = isMmol,
            text = { id, args -> context.getString(id, *args) },
            soundName = { uri, typeId -> getSoundDisplayText(uri, typeId) },
            deltaDisplayIntervalMinutes = deltaDisplayIntervalMinutes
        )
    }

    private fun s(id: Int, vararg args: Any): String = text(id, args)

    fun line(change: AlertSettingChange): String {
        val subject = subjectName(change.subject)
        val label = label(change)
        return when {
            change.setting == AlertSetting.ADDED || change.setting == AlertSetting.REMOVED ||
                change.setting == AlertSetting.OTHER ->
                s(R.string.alert_settings_change_subject, subject, label ?: "")
            label == null -> s(
                R.string.alert_settings_change_line,
                subject,
                value(change, change.before),
                value(change, change.after)
            )
            else -> s(
                R.string.alert_settings_change_subject,
                subject,
                s(R.string.alert_settings_change_line, label, value(change, change.before), value(change, change.after))
            )
        }
    }

    private fun subjectName(subject: AlertSettingSubject): String = when (subject) {
        is AlertSettingSubject.Alert -> s(subject.type.nameResId)
        is AlertSettingSubject.Custom -> subject.name.ifEmpty {
            s(if (subject.type == CustomAlertType.HIGH) R.string.high_alert else R.string.low_alert)
        }
        AlertSettingSubject.AllAlerts -> s(R.string.master_alert_control)
        AlertSettingSubject.QuietWindow -> s(R.string.quiet_window_title)
    }

    // null: the line is "subject: before → after", the subject being the setting.
    private fun label(change: AlertSettingChange): String? = when (change.setting) {
        AlertSetting.ENABLED, AlertSetting.QUIET_MODE -> null
        AlertSetting.NAME -> s(R.string.alert_name)
        AlertSetting.THRESHOLD -> s(R.string.threshold_label)
        AlertSetting.ALERT_AFTER -> s(R.string.alert_after)
        AlertSetting.LOOK_AHEAD -> s(R.string.look_ahead)
        AlertSetting.EXPIRY_WARNINGS -> s(R.string.sensor_expiry_warnings_title)
        AlertSetting.DELTA_CHANGE -> s(R.string.delta_change_label, deltaDisplayIntervalMinutes)
        AlertSetting.DELTA_COUNT -> s(R.string.delta_count_label)
        AlertSetting.DELTA_BORDER -> s(
            if ((change.subject as? AlertSettingSubject.Alert)?.type == AlertType.FALLING_FAST) {
                R.string.delta_border_below_label
            } else {
                R.string.delta_border_above_label
            }
        )
        AlertSetting.SOUND -> s(R.string.soundname)
        AlertSetting.VIBRATION -> s(R.string.vibrationname)
        AlertSetting.FLASH -> s(R.string.flash)
        AlertSetting.ALERT_SOUND -> s(R.string.alert_sound)
        AlertSetting.OVERRIDE_SILENT -> s(R.string.override_silent_mode)
        AlertSetting.ACTIVE_HOURS -> s(R.string.active_time_range_title)
        AlertSetting.ACTIVE_START -> s(R.string.alert_settings_change_subject, s(R.string.active_time_range_title), s(R.string.start))
        AlertSetting.ACTIVE_END -> s(R.string.alert_settings_change_subject, s(R.string.active_time_range_title), s(R.string.end))
        AlertSetting.HAPTICS -> s(R.string.haptics)
        AlertSetting.DELIVERY -> s(R.string.alert_settings_delivery)
        AlertSetting.ALARM_DURATION -> s(R.string.duration_label)
        AlertSetting.SOUND_DELAY -> s(R.string.sound_delay_title)
        AlertSetting.SOUND_DELAY_SECONDS -> s(R.string.sound_delay_label)
        AlertSetting.RETRY -> s(R.string.retry_if_no_reaction)
        AlertSetting.RETRY_INTERVAL -> s(R.string.retry_every)
        AlertSetting.RETRY_COUNT -> s(R.string.max_retries)
        AlertSetting.DEFAULT_SNOOZE -> s(R.string.default_snooze)
        AlertSetting.REARM_MARGIN -> s(R.string.rearm_margin_label)
        AlertSetting.REARM_INTERVAL -> s(R.string.rearm_min_interval_label)
        AlertSetting.IOB_COVERAGE -> s(R.string.pre_high_iob_coverage_label)
        AlertSetting.FALL_SUPPRESS -> s(R.string.persistent_high_fall_suppress_label)
        AlertSetting.DELTA_INTERVAL -> s(R.string.delta_alarm_interval_label)
        AlertSetting.EARLY_TRIGGER -> s(R.string.delta_early_trigger_label)
        AlertSetting.SAME_DIRECTION_QUIET_PERIOD -> s(R.string.same_direction_suppression_title)
        AlertSetting.QUIET_BREAKTHROUGH -> s(R.string.quiet_window_breakthrough_title)
        AlertSetting.QUIET_BREAKTHROUGH_SCOPE -> s(R.string.quiet_window_breakthrough_scope_title)
        AlertSetting.QUIET_TILE_DEFAULT -> s(R.string.quiet_window_tile_default_title)
        AlertSetting.ADDED -> s(R.string.alert_settings_added)
        AlertSetting.REMOVED -> s(R.string.alert_settings_removed)
        AlertSetting.OTHER -> s(R.string.alert_settings_other_settings)
    }

    private fun value(change: AlertSettingChange, value: Any?): String {
        if (value == null) {
            return when (change.setting) {
                AlertSetting.ALERT_SOUND -> soundName(null, alertTypeId(change))
                AlertSetting.DELTA_INTERVAL -> s(R.string.delta_alarm_interval_follow, deltaDisplayIntervalMinutes)
                else -> "–"
            }
        }
        return when (change.setting) {
            AlertSetting.ENABLED -> s(if (value == true) R.string.enabled_status else R.string.disabled_status)
            AlertSetting.THRESHOLD, AlertSetting.DELTA_CHANGE, AlertSetting.DELTA_BORDER,
            AlertSetting.REARM_MARGIN -> glucose(value as Float)
            AlertSetting.ALERT_AFTER, AlertSetting.LOOK_AHEAD, AlertSetting.DEFAULT_SNOOZE,
            AlertSetting.QUIET_BREAKTHROUGH -> minutes(value as Int)
            AlertSetting.RETRY_INTERVAL -> (value as Int).let {
                if (it <= 0) s(R.string.retry_constantly) else minutes(it)
            }
            AlertSetting.REARM_INTERVAL, AlertSetting.SAME_DIRECTION_QUIET_PERIOD -> (value as Int).let {
                if (it <= 0) s(R.string.off) else minutes(it)
            }
            AlertSetting.QUIET_TILE_DEFAULT -> (value as Int).let {
                if (it % 60 == 0) s(R.string.hours_short, it / 60) else minutes(it)
            }
            AlertSetting.RETRY_COUNT -> (value as Int).let {
                if (it == 0) s(R.string.retry_forever) else it.toString()
            }
            AlertSetting.ALARM_DURATION, AlertSetting.SOUND_DELAY_SECONDS -> "$value ${s(R.string.sec)}"
            AlertSetting.ALERT_SOUND -> soundName(value as String, alertTypeId(change))
            AlertSetting.EXPIRY_WARNINGS -> (value as Set<*>).filterIsInstance<Int>()
                .sortedDescending()
                .joinToString(", ") { if (it >= 1440) s(R.string.days_short, it / 1440) else s(R.string.hours_short, it / 60) }
                .ifEmpty { s(R.string.off) }
            AlertSetting.ACTIVE_START, AlertSetting.ACTIVE_END -> (value as Int).let {
                String.format(java.util.Locale.ROOT, "%02d:%02d", it / 60, it % 60)
            }
            AlertSetting.HAPTICS -> s(
                when (value as HapticProfile) {
                    HapticProfile.SOFT -> R.string.haptic_profile_soft
                    HapticProfile.STEADY -> R.string.haptic_profile_steady
                    HapticProfile.STRONG -> R.string.haptic_profile_strong
                    HapticProfile.ESCALATING -> R.string.haptic_profile_escalating
                }
            )
            AlertSetting.DELIVERY -> s(
                when (value as AlertDeliveryMode) {
                    AlertDeliveryMode.NOTIFICATION_ONLY -> R.string.alert_delivery_notification
                    AlertDeliveryMode.SYSTEM_ALARM -> R.string.alert_delivery_alarm
                    AlertDeliveryMode.BOTH -> R.string.alert_delivery_both
                }
            )
            AlertSetting.IOB_COVERAGE -> ((value as Float) * 100f).toInt().let {
                if (it == 0) s(R.string.off) else "$it%"
            }
            AlertSetting.DELTA_INTERVAL -> when (value as Int) {
                1 -> s(R.string.delta_interval_1min)
                5 -> s(R.string.delta_interval_5min)
                else -> minutes(value)
            }
            AlertSetting.QUIET_MODE -> s(
                if (value == AlertDeliveryPolicy.QUIET_NOTIFICATION_ONLY) R.string.quiet_window_mode_notification_only
                else R.string.quiet_window_mode_vibrate_only
            )
            AlertSetting.QUIET_BREAKTHROUGH_SCOPE -> s(
                if (value == AlertDeliveryPolicy.BREAKTHROUGH_VERY_ONLY) R.string.quiet_window_breakthrough_scope_very
                else R.string.quiet_window_breakthrough_scope_all
            )
            else -> when (value) {
                is Boolean -> s(if (value) R.string.alert_settings_on else R.string.off)
                else -> value.toString()
            }
        }
    }

    private fun alertTypeId(change: AlertSettingChange): Int = when (val subject = change.subject) {
        is AlertSettingSubject.Alert -> subject.type.id
        is AlertSettingSubject.Custom -> if (subject.type == CustomAlertType.HIGH) AlertType.HIGH.id else AlertType.LOW.id
        else -> 0
    }

    private fun minutes(value: Int): String = s(R.string.minutes_short_format, value)

    private fun glucose(value: Float): String =
        if (isMmol) String.format(java.util.Locale.getDefault(), "%.1f", value)
        else String.format(java.util.Locale.getDefault(), "%.0f", value)
}
