@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui.alerts

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import java.util.Date
import java.util.Locale
import tk.glucodata.R
import tk.glucodata.alerts.AlarmDevice
import tk.glucodata.alerts.AlarmEvent
import tk.glucodata.alerts.AlarmHistory
import tk.glucodata.alerts.AlarmHistoryPolicy
import tk.glucodata.alerts.AlarmOutcome
import tk.glucodata.alerts.AlertType
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.cardShape
import tk.glucodata.ui.components.formatAlertMinutes

/**
 * The alarms of the last 30 days, this phone's and its watch's, newest first and
 * grouped by day. One row each: time, alert, value, device, how it ended.
 */
@Composable
fun AlarmHistoryScreen(navController: NavController) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { AlarmHistory.ensureLoaded() }
    val stored by AlarmHistory.events.collectAsStateWithLifecycle()
    // The time rules (30 days, the open timeout) applied for display, so a list read
    // long after the last alarm still shows what the next write would keep.
    val days = remember(stored) {
        AlarmHistoryPolicy.groupByDay(
            AlarmHistoryPolicy.prune(stored, System.currentTimeMillis(), Int.MAX_VALUE)
        )
    }
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.alarm_history)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        if (days.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.alarm_history_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            days.forEach { (dayStartMs, events) ->
                item(key = "day-$dayStartMs") {
                    Text(
                        text = DateUtils.formatDateTime(
                            context,
                            dayStartMs,
                            DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or
                                DateUtils.FORMAT_ABBREV_WEEKDAY
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
                    )
                }
                itemsIndexed(events, key = { _, event -> event.id }) { index, event ->
                    AlarmHistoryRow(
                        event = event,
                        time = timeFormat.format(Date(event.firedAtMs)),
                        position = rowPosition(index, events.size)
                    )
                }
            }
        }
    }
}

private fun rowPosition(index: Int, count: Int): CardPosition = when {
    count <= 1 -> CardPosition.SINGLE
    index == 0 -> CardPosition.TOP
    index == count - 1 -> CardPosition.BOTTOM
    else -> CardPosition.MIDDLE
}

@Composable
private fun AlarmHistoryRow(event: AlarmEvent, time: String, position: CardPosition) {
    val resources = LocalContext.current.resources
    val typeLabel = AlertType.fromId(event.alertTypeId)?.let { stringResource(it.nameResId) }
        ?: stringResource(R.string.alarm_type_unknown, event.alertTypeId)
    val device = stringResource(
        if (event.device == AlarmDevice.WATCH) R.string.alarm_device_watch else R.string.alarm_device_phone
    )
    val outcome = when (event.outcome) {
        AlarmOutcome.ACTIVE -> stringResource(R.string.alarm_outcome_active)
        AlarmOutcome.DISMISSED -> stringResource(R.string.alarm_outcome_dismissed)
        AlarmOutcome.SNOOZED -> stringResource(
            R.string.alarm_outcome_snoozed,
            formatAlertMinutes(resources, event.snoozeMinutes)
        )
        AlarmOutcome.RECOVERED -> stringResource(R.string.alarm_outcome_recovered)
        AlarmOutcome.ENDED -> stringResource(R.string.alarm_outcome_ended)
    }
    val details = listOfNotNull(formatValue(event, resources), device, outcome).joinToString(" · ")

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape(position),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "$time · $typeLabel",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = details,
                style = MaterialTheme.typography.bodyMedium,
                color = if (event.isOpen) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

/** "3.4 mmol/L" or "62 mg/dL", in the unit the alarm showed; null when it had no value. */
private fun formatValue(event: AlarmEvent, resources: android.content.res.Resources): String? {
    if (!event.value.isFinite()) return null
    return if (event.unit == 1) {
        String.format(Locale.getDefault(), "%.1f %s", event.value, resources.getString(R.string.unit_mmol))
    } else {
        String.format(Locale.getDefault(), "%.0f %s", event.value, resources.getString(R.string.unit_mg))
    }
}
