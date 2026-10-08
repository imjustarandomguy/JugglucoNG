@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui.journal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.util.Calendar
import kotlin.math.abs
import tk.glucodata.R
import tk.glucodata.data.journal.JournalInsulinDosing
import tk.glucodata.journal.InsulinReminders

/**
 * The insulin library's dosing settings for one preset: the step the entry sheet's -/+ use, the
 * dose filled in when the insulin is chosen, and, for long-acting insulin (not counted toward
 * IOB), the times of day the dose is due.
 */
@Composable
internal fun JournalPresetDosingSection(
    doseStep: Float,
    defaultDoseText: String,
    reminderTimes: List<Int>,
    remindersEditable: Boolean,
    enabled: Boolean,
    onDoseStepChange: (Float) -> Unit,
    onDefaultDoseChange: (String) -> Unit,
    onReminderTimesChange: (List<Int>) -> Unit,
    modifier: Modifier = Modifier
) {
    var showTimePicker by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.journal_dose_step),
            style = MaterialTheme.typography.titleMedium
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            JournalInsulinDosing.STEP_CHOICES.forEach { step ->
                FilterChip(
                    selected = abs(doseStep - step) < 0.001f,
                    onClick = { onDoseStepChange(step) },
                    enabled = enabled,
                    label = { Text(stringResource(R.string.unit_insulin_value, formatFloatForEditor(step))) }
                )
            }
        }
        OutlinedTextField(
            value = defaultDoseText,
            onValueChange = { text ->
                onDefaultDoseChange(text.filter { it.isDigit() || it == '.' || it == ',' })
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            label = { Text(stringResource(R.string.journal_default_dose)) },
            suffix = { Text(stringResource(R.string.unit_insulin_short)) },
            supportingText = { Text(stringResource(R.string.journal_default_dose_desc)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done)
        )
        if (remindersEditable) {
            Text(
                text = stringResource(R.string.journal_reminders),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.journal_reminders_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (reminderTimes.isNotEmpty()) {
                reminderDeliveryWarning()?.let { warning ->
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                reminderTimes.forEach { minutes ->
                    val label = formatReminderTime(minutes)
                    val removeDescription = stringResource(R.string.journal_reminder_remove, label)
                    InputChip(
                        selected = false,
                        onClick = { onReminderTimesChange(reminderTimes - minutes) },
                        enabled = enabled,
                        label = { Text(text = label, fontWeight = FontWeight.SemiBold) },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = removeDescription,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                }
                AssistChip(
                    onClick = { showTimePicker = true },
                    enabled = enabled,
                    label = { Text(stringResource(R.string.journal_reminder_add)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }
        }
    }

    if (showTimePicker) {
        val context = LocalContext.current
        val timePickerState = rememberTimePickerState(
            initialHour = 21,
            initialMinute = 0,
            is24Hour = android.text.format.DateFormat.is24HourFormat(context)
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text(text = stringResource(R.string.journal_reminder_add)) },
            text = {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TimePicker(state = timePickerState)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onReminderTimesChange(
                        JournalInsulinDosing.normalizeReminderTimes(
                            reminderTimes + (timePickerState.hour * 60 + timePickerState.minute)
                        )
                    )
                    showTimePicker = false
                }) {
                    Text(text = stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text(text = stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * Why the reminders set here would not arrive as set, read again whenever the screen comes back
 * (from the system settings, say); null when nothing is in the way.
 */
@Composable
private fun reminderDeliveryWarning(): String? {
    val context = LocalContext.current
    var problem by remember { mutableStateOf(InsulinReminders.deliveryProblem(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        problem = InsulinReminders.deliveryProblem(context)
    }
    return when (problem) {
        InsulinReminders.DeliveryProblem.NOT_SHOWN -> stringResource(R.string.journal_reminders_not_shown)
        InsulinReminders.DeliveryProblem.LATE -> stringResource(R.string.journal_reminders_late)
        null -> null
    }
}

/** "21:00" or "9:00 PM", as the phone shows times. */
@Composable
private fun formatReminderTime(minutesOfDay: Int): String {
    val context = LocalContext.current
    val calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, minutesOfDay / 60)
        set(Calendar.MINUTE, minutesOfDay % 60)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return android.text.format.DateFormat.getTimeFormat(context).format(calendar.time)
}
