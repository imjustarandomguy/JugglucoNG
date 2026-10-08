package tk.glucodata.ui.alerts

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.alerts.AlertConfig
import tk.glucodata.alerts.AlertRepository
import tk.glucodata.alerts.AlertType
import tk.glucodata.alerts.CustomAlertConfig
import tk.glucodata.alerts.CustomAlertRepository
import tk.glucodata.alerts.GlobalAlertSettings
import tk.glucodata.alerts.QuietWindow

/**
 * Owns the alert screen's [AlertSettingsDraft] for the whole process rather than
 * for the screen: turning the phone rebuilds the navigation host here, and the
 * screen's own state with it, and a sub-screen or a notification's route takes
 * the screen away. Every edit is also written to a preferences file, because
 * this activity does not keep its state when Android stops the process
 * (stateNotNeeded): the next visit to the screen finds the draft there.
 *
 * Main thread only, like the screen.
 */
internal object AlertSettingsEditor {
    private const val LOG_ID = "AlertSettingsEditor"
    private const val PREFS_NAME = "tk.glucodata.alerts.draft"
    private const val KEY_UNIT_MMOL = "unit_mmol"

    var state by mutableStateOf<AlertSettingsDraft?>(null)
        private set

    // The glucose unit the draft's thresholds are in. A draft made in the other
    // unit means nothing in this one, and is dropped rather than laid over it.
    private var draftMmol: Boolean? = null

    private val prefs by lazy { Applic.app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    /** Reads what is stored, and lays the edits not saved yet over it. */
    fun refresh(isMmol: Boolean): AlertSettingsDraft {
        val stored = loadStored()
        val next = state?.takeIf { draftMmol == isMmol }?.rebase(stored, isMmol)
            ?: AlertSettingsDraft.restore(stored, readPending(isMmol), isMmol)
        draftMmol = isMmol
        set(next)
        return next
    }

    /** The quiet window's stored settings changed under the screen: a window started, say. */
    fun refreshQuietWindow(isMmol: Boolean) {
        val current = state ?: return
        val stored = current.saved.copy(quietWindow = loadQuietWindow())
        if (stored != current.saved) set(current.rebase(stored, isMmol))
    }

    fun edit(transform: (AlertSettingsDraft) -> AlertSettingsDraft) {
        val current = state ?: return
        set(transform(current))
    }

    fun save(context: Context) {
        val current = state ?: return
        set(current.saveTo(RepositoryStore(context.applicationContext)))
    }

    fun discard() = edit { it.discard() }

    private fun set(next: AlertSettingsDraft) {
        if (next == state) return
        state = next
        writePending(next.pendingEdits())
    }

    private fun loadStored(): AlertSettingsValues = AlertSettingsValues(
        configs = AlertType.settingsEntries.associateWith { AlertRepository.loadConfig(it) },
        global = AlertRepository.loadGlobalSettings(),
        quietWindow = loadQuietWindow(),
        customAlerts = CustomAlertRepository.getAll(),
    )

    private fun loadQuietWindow() = QuietWindowSettings(
        mode = QuietWindow.mode(),
        breakthroughMinutes = QuietWindow.breakthroughMinutes(),
        breakthroughScope = QuietWindow.breakthroughScope(),
        defaultMinutes = QuietWindow.defaultMinutes(),
    )

    private fun readPending(isMmol: Boolean): PendingAlertEdits {
        if (prefs.contains(KEY_UNIT_MMOL) && prefs.getBoolean(KEY_UNIT_MMOL, isMmol) != isMmol) {
            Log.i(LOG_ID, "dropping a draft made in the other glucose unit")
            return PendingAlertEdits()
        }
        return PendingAlertEditsCodec.decode(prefs.all) { key, error ->
            Log.stack(LOG_ID, "dropping unreadable draft entry $key", error)
        }
    }

    private fun writePending(edits: PendingAlertEdits) {
        runCatching {
            prefs.edit {
                clear()
                if (!edits.isEmpty) {
                    draftMmol?.let { putBoolean(KEY_UNIT_MMOL, it) }
                    PendingAlertEditsCodec.encode(edits).forEach { (key, value) -> putString(key, value) }
                }
            }
        }.onFailure { Log.stack(LOG_ID, "writePending", it) }
    }

    /** Save through the stores every other path uses, so their side effects (natives, watch) follow. */
    private class RepositoryStore(private val context: Context) : AlertSettingsStore {
        override fun saveConfig(config: AlertConfig) = AlertRepository.saveConfig(config)

        override fun saveGlobal(settings: GlobalAlertSettings) = AlertRepository.saveGlobalSettings(settings)

        override fun saveQuietWindow(saved: QuietWindowSettings, settings: QuietWindowSettings) {
            if (settings.breakthroughMinutes != saved.breakthroughMinutes) {
                QuietWindow.setBreakthroughMinutes(settings.breakthroughMinutes)
            }
            if (settings.breakthroughScope != saved.breakthroughScope) {
                QuietWindow.setBreakthroughScope(settings.breakthroughScope)
            }
            if (settings.defaultMinutes != saved.defaultMinutes) {
                QuietWindow.setDefaultMinutes(settings.defaultMinutes)
            }
            // Last: on a running window it also redraws the notification and the tile.
            if (settings.mode != saved.mode) {
                QuietWindow.setMode(context, settings.mode)
            }
        }

        override fun saveCustomAlerts(saved: List<CustomAlertConfig>, draft: List<CustomAlertConfig>) {
            CustomAlertRepository.saveAll(mergeCustomAlerts(saved, draft, CustomAlertRepository.getAll()))
        }

        override fun together(block: () -> Unit) = AlertRepository.saveTogether(block)
    }
}
