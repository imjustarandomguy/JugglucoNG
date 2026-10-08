@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui.journal

import android.content.Context
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SheetState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.R
import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalEntryInput
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.data.journal.JournalQuickEntryPolicy
import tk.glucodata.data.journal.JournalRepository
import tk.glucodata.data.journal.JournalSave
import tk.glucodata.ui.components.CompactSheetDragHandle
import tk.glucodata.ui.components.StableModalBottomSheet
import tk.glucodata.ui.util.GlucoseFormatter
import tk.glucodata.ui.viewmodel.DashboardViewModel

/**
 * The entry sheet around [content], the form and its Save button below it. One height for every
 * type, so switching tabs never moves the sheet: the natural height of the tallest of
 * [sizingForms] (every type's form as it opens, laid out unseen and with nothing running), at
 * most the space there is. The form's list takes what Save leaves and scrolls what does not fit;
 * on a shorter type, Save stays at the bottom with room above it.
 *
 * While the sheet is open the height only grows: [sizingForms] measured taller later (the
 * insulins or foods changed) raise it, shorter ones do not lower it.
 */
@Composable
internal fun JournalEntrySheetFrame(
    onDismiss: () -> Unit,
    sheetState: SheetState,
    sizingForms: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    // A sheet that wraps its content, not a share of the screen: a height modifier here would
    // also be the height Material takes for the whole window, and would set the sheet at its top.
    StableModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { CompactSheetDragHandle() },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        val tallest = remember { TallestHeight() }
        SubcomposeLayout(modifier = Modifier.fillMaxWidth()) { constraints ->
            // As tall as they like, whatever the keyboard leaves: the forms measured stay the
            // same compositions with the keyboard up or down, and their heights do not change.
            val natural = Constraints.fitPrioritizingWidth(
                minWidth = constraints.minWidth,
                maxWidth = constraints.maxWidth,
                minHeight = 0,
                maxHeight = SIZING_MAX_HEIGHT_PX
            )
            subcompose(JournalEntrySheetSlot.SIZING) {
                // Measured, never placed: not drawn, not focusable, nothing for TalkBack.
                Box(modifier = Modifier.clearAndSetSemantics { }) { sizingForms() }
            }.forEach { tallest.offer(it.measure(natural).height) }
            val height = tallest.height.coerceIn(constraints.minHeight, constraints.maxHeight)
            val placeables = subcompose(JournalEntrySheetSlot.CONTENT) {
                Column(modifier = Modifier.fillMaxWidth(), content = content)
            }.map { it.measure(constraints.copy(minHeight = height, maxHeight = height)) }
            layout(placeables.maxOfOrNull { it.width } ?: constraints.minWidth, height) {
                placeables.forEach { it.place(0, 0) }
            }
        }
    }
}

private enum class JournalEntrySheetSlot { SIZING, CONTENT }

/** The tallest height measured so far: the sheet's, capped by the space there is. */
private class TallestHeight {
    var height = 0
        private set

    fun offer(measured: Int) {
        if (measured > height) height = measured
    }
}

/** Taller than any screen; a bound to measure by, as a lazy list cannot be measured unbounded. */
private const val SIZING_MAX_HEIGHT_PX = 100_000

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

/** "2 h 10 min ago" or "just now" under 12 hours, even across midnight; else "yesterday 21:38". */
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
 * Saves what the entry sheet hands back, all of it or none, then shows "Saved 6 U Fiasp" with
 * Undo in [snackbarHostState] for a few seconds, once it is stored. Undo deletes the rows the
 * save stored, or writes an edited entry back as it was ([previous] is the entry before the
 * edit, null for a new one); both go through the repository, so Nightscout and the other uploads
 * see a user delete or edit. A save that fails says so ("Not saved: 6 U Fiasp") and offers Try
 * again with the same entries. The dashboard, the journal and the history save this way; the
 * sheet opened from outside the app stays open until its save is stored
 * (JournalQuickEntryActivity).
 */
@Composable
internal fun rememberJournalSaveWithUndo(
    viewModel: DashboardViewModel,
    snackbarHostState: SnackbarHostState,
    presetsById: Map<Long, JournalInsulinPreset>,
    unit: String
): (inputs: List<JournalEntryInput>, previous: JournalEntry?) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val undoLabel = stringResource(R.string.undo)
    val retryLabel = stringResource(R.string.journal_save_try_again)
    val currentPresetsById by rememberUpdatedState(presetsById)
    val currentUnit by rememberUpdatedState(unit)
    return remember(viewModel, snackbarHostState, context, scope, undoLabel, retryLabel) {
        object : (List<JournalEntryInput>, JournalEntry?) -> Unit {
            override fun invoke(inputs: List<JournalEntryInput>, previous: JournalEntry?) {
                val summary = journalSavedSummary(context, inputs, currentPresetsById, currentUnit)
                viewModel.saveJournalEntries(inputs) { outcome ->
                    scope.launch {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        when (outcome) {
                            is JournalSave.Outcome.Saved -> {
                                val result = snackbarHostState.showSnackbar(
                                    message = context.getString(R.string.journal_saved_entry, summary),
                                    actionLabel = undoLabel,
                                    duration = SnackbarDuration.Short
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    viewModel.undoJournalSave(outcome.ids, previous)
                                }
                            }
                            is JournalSave.Outcome.Failed -> {
                                val result = snackbarHostState.showSnackbar(
                                    message = context.getString(R.string.journal_save_failed, summary),
                                    actionLabel = retryLabel,
                                    duration = SnackbarDuration.Long
                                )
                                if (result == SnackbarResult.ActionPerformed) invoke(inputs, previous)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The journal's +, on the dashboard and the journal screen: one tap opens the entry sheet, with
 * no type menu in between. An overlay: place it last in the Box it covers, with
 * Modifier.matchParentSize(). The button sits at the bottom end; [snackbarHostState]'s bar, for
 * a screen without a Scaffold to show it, appears just above the button.
 */
@Composable
internal fun JournalQuickEntryFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null
) {
    val view = LocalView.current
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            horizontalAlignment = Alignment.End
        ) {
            snackbarHostState?.let { SnackbarHost(hostState = it, modifier = Modifier.fillMaxWidth()) }
            FloatingActionButton(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onClick()
                },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(20.dp),
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp),
                modifier = Modifier.padding(end = 20.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.additem),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
