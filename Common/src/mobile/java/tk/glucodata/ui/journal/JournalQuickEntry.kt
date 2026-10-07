@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui.journal

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tk.glucodata.R
import tk.glucodata.data.journal.JournalEntryInput
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.data.journal.JournalQuickEntryPolicy
import tk.glucodata.data.journal.JournalRepository
import tk.glucodata.ui.util.GlucoseFormatter

/**
 * The entry sheet's height, as a share of the space it opens in, the same for every type: about
 * what the tallest type (insulin with its dose calculator) needs on a phone, so switching tabs
 * never resizes it. A type that needs less leaves room; one that needs more scrolls.
 */
internal const val JOURNAL_ENTRY_SHEET_HEIGHT_FRACTION = 0.85f

private val quickEntryRepository by lazy { JournalRepository() }

/**
 * The recent-value chips of one type (and, for insulin, one insulin), from the journal in Room:
 * the query runs on Room's executor and the ranking off the main thread.
 */
internal suspend fun loadRecentJournalAmounts(
    type: JournalEntryType,
    insulinPresetId: Long?,
    nowMillis: Long
): List<Float> {
    if (type != JournalEntryType.INSULIN && type != JournalEntryType.CARBS) return emptyList()
    if (type == JournalEntryType.INSULIN && insulinPresetId == null) return emptyList()
    val rows = quickEntryRepository.amountsBetween(
        type,
        nowMillis - JournalQuickEntryPolicy.RECENT_WINDOW_MILLIS,
        nowMillis
    )
    return withContext(Dispatchers.Default) {
        JournalQuickEntryPolicy.recentAmounts(rows, type, insulinPresetId, nowMillis)
    }
}

/** The five entry types as tabs, in the order of [JournalEntryType]. */
@Composable
internal fun JournalEntryTypeTabs(
    selectedType: JournalEntryType,
    onTypeSelected: (JournalEntryType) -> Unit,
    modifier: Modifier = Modifier
) {
    val types = JournalEntryType.entries
    PrimaryTabRow(
        selectedTabIndex = types.indexOf(selectedType).coerceAtLeast(0),
        modifier = modifier.fillMaxWidth(),
        containerColor = Color.Transparent
    ) {
        types.forEach { type ->
            Tab(
                selected = type == selectedType,
                onClick = { if (type != selectedType) onTypeSelected(type) },
                icon = {
                    Icon(
                        imageVector = type.journalActionIcon(),
                        contentDescription = null,
                        tint = journalTypeColor(type)
                    )
                },
                text = {
                    Text(
                        text = type.journalActionLabel(),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                selectedContentColor = MaterialTheme.colorScheme.onSurface,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Tapping a chip fills the amount; it does not save. */
@Composable
internal fun JournalRecentAmountChips(
    amounts: List<Float>,
    currentAmountText: String,
    valueFormatRes: Int,
    onAmountPicked: (Float) -> Unit
) {
    val current = currentAmountText.trim().replace(',', '.').toFloatOrNull()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        amounts.forEach { amount ->
            FilterChip(
                selected = current != null && kotlin.math.abs(current - amount) < 0.001f,
                onClick = { onAmountPicked(amount) },
                label = { Text(text = stringResource(valueFormatRes, formatFloatForEditor(amount))) }
            )
        }
    }
}

/** "Last Fiasp: 6 U · 1 h 20 min ago", under the choice it is about. */
@Composable
internal fun JournalContextLine(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = Icons.Default.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** "1 h 20 min ago", "just now", or "yesterday 21:38". */
@Composable
internal fun journalElapsedText(timestampMillis: Long, nowMillis: Long): String {
    val context = LocalContext.current
    return when (val elapsed = JournalQuickEntryPolicy.elapsed(timestampMillis, nowMillis)) {
        is JournalQuickEntryPolicy.Elapsed.Yesterday -> stringResource(
            R.string.journal_time_yesterday,
            android.text.format.DateFormat.getTimeFormat(context).format(Date(elapsed.timestampMillis))
        )
        is JournalQuickEntryPolicy.Elapsed.Ago -> if (elapsed.minutes < 1) {
            stringResource(R.string.journal_time_just_now)
        } else {
            stringResource(R.string.journal_time_ago, formatJournalDuration(context, elapsed.minutes))
        }
    }
}

private fun formatJournalDuration(context: Context, minutes: Long): String {
    val (days, hours, mins) = JournalQuickEntryPolicy.splitMinutes(minutes)
    return when {
        days > 0 && hours > 0 -> context.getString(R.string.journal_duration_days_hours, days.toInt(), hours.toInt())
        days > 0 -> context.getString(R.string.journal_duration_days, days.toInt())
        hours > 0 && mins > 0 -> context.getString(R.string.journal_duration_hours_minutes, hours.toInt(), mins.toInt())
        hours > 0 -> context.getString(R.string.journal_duration_hours, hours.toInt())
        else -> context.getString(R.string.minutes_short_format, mins.toInt())
    }
}

/**
 * What a save wrote, for the undo bar: "6 U Fiasp", "45 g", "102 mg/dL", "Walk 30 min", a
 * note's title; a dose saved with its meal reads "6 U Fiasp + 45 g".
 */
internal fun journalSavedSummary(
    context: Context,
    inputs: List<JournalEntryInput>,
    presetsById: Map<Long, JournalInsulinPreset>,
    unit: String
): String = inputs.joinToString(" + ") { input ->
    val amount = input.amount?.let(::formatFloatForEditor)
    when (input.type) {
        JournalEntryType.INSULIN -> listOfNotNull(
            amount?.let { context.getString(R.string.unit_insulin_value, it) },
            input.insulinPresetId?.let(presetsById::get)?.displayName ?: input.title.takeIf { it.isNotBlank() }
        ).joinToString(" ")

        JournalEntryType.CARBS -> listOfNotNull(
            amount?.let { context.getString(R.string.unit_carbs_value, it) },
            input.title.takeIf { it.isNotBlank() && it != context.getString(R.string.journal_type_food) }
        ).joinToString(" ")

        JournalEntryType.FINGERSTICK -> input.glucoseValueMgDl?.let { mgdl ->
            val isMmol = GlucoseFormatter.isMmol(unit)
            val value = GlucoseFormatter.displayFromMgDl(mgdl, isMmol)
            val text = if (isMmol) {
                DecimalFormat("0.0", DecimalFormatSymbols(Locale.getDefault())).format(value)
            } else {
                formatFloatForEditor(value)
            }
            "$text $unit"
        } ?: input.title

        JournalEntryType.ACTIVITY -> listOfNotNull(
            input.title.takeIf { it.isNotBlank() },
            input.durationMinutes?.let { context.getString(R.string.minutes_short_format, it) }
        ).joinToString(" ")

        JournalEntryType.NOTE -> input.title.take(30)
    }
}

/**
 * The dashboard's +: one tap opens the entry sheet, with no type menu in between. It is the
 * journal screen's button, kept closed, so both look and sit the same.
 */
@Composable
internal fun JournalQuickEntryFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    JournalExpandableFab(
        expanded = false,
        onExpandedChange = { expand -> if (expand) onClick() },
        onTypeSelected = {},
        modifier = modifier
    )
}
