package tk.glucodata.ui.components

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import tk.glucodata.R
import tk.glucodata.alerts.AlertType
import tk.glucodata.alerts.SnoozeManager
import tk.glucodata.alerts.SnoozeTimeLeft

/** One snoozed alert on this device, as a screen lists it. */
data class ActiveSnoozeRow(
    val type: AlertType,
    val snoozeUntilMs: Long,
    val minutesLeft: Int,
)

/** This device's snoozes that have time left, soonest to end first. */
fun readActiveSnoozes(nowMs: Long = System.currentTimeMillis()): List<ActiveSnoozeRow> =
    runCatching { SnoozeManager.getAllActiveSnoozes() }
        .getOrDefault(emptyList())
        .mapNotNull { state ->
            val left = SnoozeTimeLeft.minutesLeft(state.snoozeUntilMillis, nowMs)
            if (left > 0) ActiveSnoozeRow(state.alertType, state.snoozeUntilMillis, left) else null
        }
        .sortedBy { it.snoozeUntilMs }

/**
 * The active snoozes, kept current while the screen is started: read again as the
 * soonest displayed minute changes, and at least every [SnoozeTimeLeft.IDLE_REFRESH_MS]
 * so one set elsewhere appears. Change [refreshKey] to read again at once (after a cancel).
 */
@Composable
fun rememberActiveSnoozes(refreshKey: Any? = Unit): List<ActiveSnoozeRow> {
    val lifecycleOwner = LocalLifecycleOwner.current
    var rows by remember { mutableStateOf(readActiveSnoozes()) }
    LaunchedEffect(lifecycleOwner, refreshKey) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val nowMs = System.currentTimeMillis()
                val current = readActiveSnoozes(nowMs)
                rows = current
                delay(SnoozeTimeLeft.refreshDelayMs(current.map { it.snoozeUntilMs }, nowMs))
            }
        }
    }
    return rows
}

/** "12 min", "1 h", "1 h 5 min". */
fun formatAlertMinutes(resources: Resources, minutes: Int): String {
    val (hours, rest) = SnoozeTimeLeft.split(minutes)
    return when {
        hours == 0 -> resources.getString(R.string.minutes_short_format, rest)
        rest == 0 -> resources.getString(R.string.alarm_duration_hours, hours)
        else -> resources.getString(R.string.alarm_duration_hours_minutes, hours, rest)
    }
}
