package tk.glucodata.ui.journal

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.SensorIdentity
import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalEntryInput
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalFood
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.data.journal.JournalRepository
import tk.glucodata.data.journal.JournalSave
import tk.glucodata.data.prediction.DoseTarget
import tk.glucodata.data.prediction.PredictionModelProfileStore
import tk.glucodata.journal.InsulinReminders

/*
 * What the standalone entry sheet ([JournalQuickEntryActivity]) reads and writes outside of
 * Compose. Kept out of the composable files (ArchitectureGateTests: no SharedPreferences or
 * Applic in them).
 */

private const val PREFS_NAME = "tk.glucodata_preferences"

/**
 * What the standalone sheet opens on; [openedAt] is also the time a new entry starts at, and
 * [insulinAmount] the dose a new insulin entry is filled in with (a reminder's, to confirm).
 */
internal data class QuickEntryRequest(
    val type: JournalEntryType,
    val insulinPresetId: Long?,
    val openedAt: Long,
    val insulinAmount: Float? = null
) {
    companion object {
        fun from(intent: Intent?): QuickEntryRequest {
            val presetId = JournalQuickEntryActivity.insulinPresetIdOf(intent)
            val type = JournalQuickEntryActivity.typeOf(intent) ?: JournalEntryType.INSULIN
            return QuickEntryRequest(
                type,
                presetId,
                System.currentTimeMillis(),
                JournalQuickEntryActivity.insulinAmountOf(intent)
            )
        }
    }
}

/** What the dashboard hands its sheet, read once from Room and the settings. */
internal class QuickEntryData(
    val unit: String,
    val insulinPresets: List<JournalInsulinPreset>,
    val foods: List<JournalFood>,
    val foodLibraryEnabled: Boolean,
    val foodOnlineEnabled: Boolean,
    val foodMacrosEnabled: Boolean,
    val entries: List<JournalEntry>,
    val doseProfile: JournalDoseProfile,
    val sensorSerial: String?
)

// The dashboard's settings (DashboardViewModel keeps its keys private).
private const val JOURNAL_ENABLED_KEY = "dashboard_journal_enabled"
private const val JOURNAL_DOSE_CALCULATOR_KEY = "dashboard_journal_dose_calculator_enabled"
private const val JOURNAL_FOOD_MACROS_KEY = "dashboard_journal_food_macros_enabled"
private const val JOURNAL_FOOD_LIBRARY_KEY = "dashboard_journal_food_library_enabled"
private const val JOURNAL_FOOD_ONLINE_KEY = "dashboard_journal_food_online_enabled"
private const val PREDICTION_DOSE_TARGET_KEY = "dashboard_prediction_dose_target_mgdl"

/** Enough for the dose calculator's insulin on board and recent habits. */
private const val QUICK_ENTRY_HISTORY_MS = 48L * 60 * 60 * 1000
private const val QUICK_ENTRY_AHEAD_MS = 60L * 60 * 1000

internal suspend fun loadQuickEntryData(context: Context): QuickEntryData = withContext(Dispatchers.IO) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val repository = JournalRepository()
    repository.ensureDefaultInsulinPresets()
    val foodLibrary = prefs.getBoolean(JOURNAL_FOOD_LIBRARY_KEY, true)
    if (foodLibrary) repository.ensureDefaultFoods()
    val now = System.currentTimeMillis()
    val foodMacros = prefs.getBoolean(JOURNAL_FOOD_MACROS_KEY, false)
    val modelProfile = PredictionModelProfileStore.load(prefs)
    val firstBlock = modelProfile.blocks.first()
    QuickEntryData(
        unit = if (Applic.unit == 1) "mmol/L" else "mg/dL",
        // A new entry is offered the insulins in use, as on the dashboard.
        insulinPresets = repository.observeInsulinPresets().first().filter { !it.isArchived },
        foods = if (foodLibrary) repository.observeFoods().first() else emptyList(),
        foodLibraryEnabled = foodLibrary,
        foodOnlineEnabled = prefs.getBoolean(JOURNAL_FOOD_ONLINE_KEY, true),
        foodMacrosEnabled = foodMacros,
        entries = repository.getEntriesBetweenSnapshot(now - QUICK_ENTRY_HISTORY_MS, now + QUICK_ENTRY_AHEAD_MS),
        doseProfile = JournalDoseProfile(
            enabled = prefs.getBoolean(JOURNAL_ENABLED_KEY, true) &&
                prefs.getBoolean(JOURNAL_DOSE_CALCULATOR_KEY, false),
            carbRatioGramsPerUnit = firstBlock.carbRatioGramsPerUnit,
            insulinSensitivityMgDlPerUnit = firstBlock.insulinSensitivityMgDlPerUnit,
            foodMacrosEnabled = foodMacros,
            modelProfile = modelProfile,
            targetMgDl = prefs.getFloat(PREDICTION_DOSE_TARGET_KEY, DoseTarget.DEFAULT_MGDL)
                .coerceIn(DoseTarget.MIN_MGDL, DoseTarget.MAX_MGDL)
        ),
        sensorSerial = SensorIdentity.resolveMainSensor()?.takeIf { it.isNotBlank() }
    )
}

/**
 * The standalone sheet's writes. They outlive its window: a save started just before it closes
 * still completes. They go through the repository, so Nightscout and the other uploads see them
 * as any entry.
 */
internal object QuickEntryWrites {
    private const val LOG_ID = "JournalQuickEntry"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Saves [inputs] together (all or none) and, once they are stored, takes down the reminders
     * of the doses among them. The result says which it was; it never fails itself.
     */
    fun save(context: Context, inputs: List<JournalEntryInput>): Deferred<JournalSave.Outcome> =
        scope.async {
            JournalSave.commit(inputs, JournalRepository()::upsertEntries) { committed ->
                // "Tresiba not logged" is answered, whichever way the dose came.
                InsulinReminders.onEntriesSaved(context, committed)
            }.also { outcome ->
                if (outcome is JournalSave.Outcome.Failed) Log.stack(LOG_ID, "save", outcome.error)
            }
        }
}
