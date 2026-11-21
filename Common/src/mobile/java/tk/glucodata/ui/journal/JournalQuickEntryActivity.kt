package tk.glucodata.ui.journal

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch
import tk.glucodata.Log
import tk.glucodata.QUICK_ENTRY_EXTRA_TYPE
import tk.glucodata.R
import tk.glucodata.RestrictedMode
import tk.glucodata.data.journal.JournalEntryInput
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalSave
import tk.glucodata.ui.JugglucoTheme
import tk.glucodata.ui.restricted.rememberRestrictedJournalGuard

/**
 * The entry sheet on its own, over whatever is on screen: what the floating glucose's buttons,
 * the glucose notification's Log insulin and Log food, and the basal reminder open. A translucent
 * window with nothing of its own but the sheet (and the sheet's scrim, which dims the app below),
 * closed when dismissed or saved.
 *
 * Over the lock screen it shows nothing until the keyguard is dismissed: a dose is written to
 * the journal and uploaded, so it takes the user's credentials, as any app would. It never
 * shows on top of the keyguard (no showWhenLocked); if the phone locks while it is open, it goes
 * behind the keyguard with everything else.
 */
class JournalQuickEntryActivity : ComponentActivity() {
    private var request by mutableStateOf<QuickEntryRequest?>(null)
    private var unlocked by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (refusedWhileRestricted()) return
        WindowCompat.setDecorFitsSystemWindows(window, false)
        request = QuickEntryRequest.from(intent)
        setContent {
            JugglucoTheme {
                // JugglucoTheme paints the bars in its background colour; they belong to the
                // app below this window, so they stay as they were.
                SideEffect {
                    @Suppress("DEPRECATION")
                    window.statusBarColor = AndroidColor.TRANSPARENT
                    @Suppress("DEPRECATION")
                    window.navigationBarColor = AndroidColor.TRANSPARENT
                }
                val current = request
                if (unlocked && current != null) {
                    // A new request starts over: a save still running for the old one finishes
                    // in QuickEntryWrites, but its result no longer reaches this screen.
                    key(current) {
                        QuickEntryContent(
                            request = current,
                            onFinish = ::finish
                        )
                    }
                }
            }
        }
        whenUnlocked { unlocked = true }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (refusedWhileRestricted()) return
        // Opened again from somewhere else while open: start over on what was asked this time.
        request = QuickEntryRequest.from(intent)
    }

    /**
     * Restricted mode without journal entries: says that full mode is in Settings, and closes.
     * The ways in are hidden then where they can be (the notification's and the floating
     * glucose's buttons, the reminder's Log); a notification posted before still comes here.
     */
    private fun refusedWhileRestricted(): Boolean {
        if (RestrictedMode.current().canEditJournal) return false
        Toast.makeText(this, R.string.restricted_needs_full_mode, Toast.LENGTH_LONG).show()
        finish()
        return true
    }

    /**
     * Shows the sheet once the keyguard is out of the way: at once when the phone is unlocked;
     * after the user unlocks it otherwise (the bouncer asks for the credentials of a secure
     * lock). Backing out of the bouncer closes this.
     */
    private fun whenUnlocked(show: () -> Unit) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isKeyguardLocked) {
            show()
            return
        }
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = show()
            override fun onDismissCancelled() = finish()
            override fun onDismissError() {
                Log.i(LOG_ID, "keyguard dismiss failed")
                finish()
            }
        })
    }

    companion object {
        private const val LOG_ID = "JournalQuickEntry"
        // Also set by the glucose notification's Log insulin and Log food (src/main).
        private const val EXTRA_TYPE = QUICK_ENTRY_EXTRA_TYPE
        private const val EXTRA_INSULIN_PRESET_ID = "tk.glucodata.journal.quick_entry.INSULIN_PRESET_ID"
        private const val EXTRA_INSULIN_AMOUNT = "tk.glucodata.journal.quick_entry.INSULIN_AMOUNT"

        /**
         * Opens the sheet on [type] (defaults to insulin when null); [insulinPresetId] picks
         * the insulin it starts on, and [insulinAmount] the dose filled in.
         */
        @JvmStatic
        @JvmOverloads
        fun intent(
            context: Context,
            type: JournalEntryType? = null,
            insulinPresetId: Long? = null,
            insulinAmount: Float? = null
        ): Intent =
            Intent(context, JournalQuickEntryActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                type?.let { putExtra(EXTRA_TYPE, it.storageValue) }
                insulinPresetId?.let { putExtra(EXTRA_INSULIN_PRESET_ID, it) }
                insulinAmount?.let { putExtra(EXTRA_INSULIN_AMOUNT, it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        /** From a service or any other context: the floating glucose's buttons. */
        @JvmStatic
        fun start(context: Context, type: JournalEntryType?) {
            try {
                context.startActivity(intent(context, type))
            } catch (t: Throwable) {
                Log.stack(LOG_ID, "start", t)
            }
        }

        internal fun typeOf(intent: Intent?): JournalEntryType? =
            intent?.getStringExtra(EXTRA_TYPE)?.let { stored ->
                JournalEntryType.journalRowTypes.firstOrNull { it.storageValue == stored }
            }

        internal fun insulinPresetIdOf(intent: Intent?): Long? =
            intent?.takeIf { it.hasExtra(EXTRA_INSULIN_PRESET_ID) }
                ?.getLongExtra(EXTRA_INSULIN_PRESET_ID, -1L)
                ?.takeIf { it >= 0L }

        internal fun insulinAmountOf(intent: Intent?): Float? =
            intent?.takeIf { it.hasExtra(EXTRA_INSULIN_AMOUNT) }
                ?.getFloatExtra(EXTRA_INSULIN_AMOUNT, 0f)
                ?.takeIf { it.isFinite() && it > 0f }
    }
}

@Composable
private fun QuickEntryContent(
    request: QuickEntryRequest,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val data by produceState<QuickEntryData?>(null, request) {
        value = try {
            loadQuickEntryData(context.applicationContext)
        } catch (t: Throwable) {
            Log.stack("JournalQuickEntry", "load", t)
            onFinish()
            null
        }
    }
    var saving by remember(request) { mutableStateOf(false) }
    var saveFailed by remember(request) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val journalGuard = rememberRestrictedJournalGuard()
    val loaded = data ?: return

    // The sheet stays, Save disabled, until the entries are stored. A failed save keeps the
    // sheet and what was typed, with a line saying so.
    fun store(inputs: List<JournalEntryInput>) {
        saving = true
        saveFailed = false
        val pending = QuickEntryWrites.save(context.applicationContext, inputs)
        scope.launch {
            when (pending.await()) {
                is JournalSave.Outcome.Saved -> onFinish()
                is JournalSave.Outcome.Failed -> saveFailed = true
            }
            saving = false
        }
    }

    // Restricted mode: as in the app, a save that would move a calibration point needs full mode.
    fun save(inputs: List<JournalEntryInput>) {
        if (inputs.isEmpty() || saving) return
        journalGuard.save(null, inputs.map { it.type }) { store(inputs) }
    }

    JournalEntrySheet(
        unit = loaded.unit,
        selectedTimestamp = request.openedAt,
        insulinPresets = loaded.insulinPresets,
        foods = loaded.foods,
        foodLibraryEnabled = loaded.foodLibraryEnabled,
        foodOnlineEnabled = loaded.foodOnlineEnabled,
        foodMacrosEnabled = loaded.foodMacrosEnabled,
        doseJournalEntries = loaded.entries,
        doseProfile = loaded.doseProfile,
        initialType = request.type,
        onDismiss = onFinish,
        onSave = { input -> save(listOf(input)) },
        onSaveEntries = { inputs -> save(inputs) },
        sensorSerialProvider = { loaded.sensorSerial },
        initialInsulinPresetId = request.insulinPresetId,
        initialInsulinAmount = request.insulinAmount,
        saving = saving,
        saveError = if (saveFailed) stringResource(R.string.journal_save_failed_kept) else null
    )
}
