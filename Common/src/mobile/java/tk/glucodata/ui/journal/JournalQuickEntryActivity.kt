package tk.glucodata.ui.journal

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay
import tk.glucodata.Log
import tk.glucodata.R
import tk.glucodata.data.journal.JournalEntryInput
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.ui.JugglucoTheme

/**
 * The entry sheet on its own, over whatever is on screen: what the floating glucose's buttons,
 * the "Log insulin" tile, the glucose notification's Log action, the app icon's shortcuts and
 * the basal reminder open. A translucent window with nothing of its own but the sheet, closed
 * with it; after a save, an undo bar for a few seconds at the bottom, the rest of the screen
 * already given back to the app below.
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
                    QuickEntryContent(
                        request = current,
                        onFinish = ::finish,
                        onSaved = ::releaseScreen
                    )
                }
            }
        }
        whenUnlocked { unlocked = true }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Opened again from somewhere else while open: start over on what was asked this time.
        holdScreen()
        request = QuickEntryRequest.from(intent)
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

    /**
     * After a save only the undo bar is left: the window shrinks to it at the bottom, and touches
     * elsewhere go to the app below instead of being held for the few seconds the bar shows.
     */
    private fun releaseScreen() {
        window.setGravity(Gravity.BOTTOM)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
    }

    private fun holdScreen() {
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
    }

    companion object {
        private const val LOG_ID = "JournalQuickEntry"
        private const val EXTRA_TYPE = "tk.glucodata.journal.quick_entry.TYPE"
        private const val EXTRA_INSULIN_PRESET_ID = "tk.glucodata.journal.quick_entry.INSULIN_PRESET_ID"

        /**
         * Opens the sheet on [type], or on the type last added when null; [insulinPresetId]
         * picks the insulin it starts on.
         */
        @JvmStatic
        @JvmOverloads
        fun intent(context: Context, type: JournalEntryType? = null, insulinPresetId: Long? = null): Intent =
            Intent(context, JournalQuickEntryActivity::class.java).apply {
                // Launcher shortcuts need an action; the other ways in do not mind one.
                action = Intent.ACTION_VIEW
                type?.let { putExtra(EXTRA_TYPE, it.storageValue) }
                insulinPresetId?.let { putExtra(EXTRA_INSULIN_PRESET_ID, it) }
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
                JournalEntryType.entries.firstOrNull { it.storageValue == stored }
            }

        internal fun insulinPresetIdOf(intent: Intent?): Long? =
            intent?.takeIf { it.hasExtra(EXTRA_INSULIN_PRESET_ID) }
                ?.getLongExtra(EXTRA_INSULIN_PRESET_ID, -1L)
                ?.takeIf { it >= 0L }
    }
}

private const val UNDO_SHOWN_MS = 4_000L

@Composable
private fun QuickEntryContent(
    request: QuickEntryRequest,
    onFinish: () -> Unit,
    onSaved: () -> Unit
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
    var saved by remember(request) { mutableStateOf<SavedEntries?>(null) }
    val loaded = data ?: return

    fun save(inputs: List<JournalEntryInput>) {
        if (inputs.isEmpty()) return
        saved = SavedEntries(
            message = context.getString(
                R.string.journal_saved_entry,
                journalSavedSummary(context, inputs, loaded.presetsById, loaded.unit)
            ),
            ids = QuickEntryWrites.save(context.applicationContext, inputs)
        )
        onSaved()
    }

    val result = saved
    if (result == null) {
        JournalEntrySheet(
            unit = loaded.unit,
            selectedTimestamp = request.openedAt,
            insulinPresets = loaded.insulinPresets,
            foods = loaded.foods,
            foodMacrosEnabled = loaded.foodMacrosEnabled,
            doseJournalEntries = loaded.entries,
            doseProfile = loaded.doseProfile,
            initialType = request.type,
            onDismiss = onFinish,
            onSave = { input -> save(listOf(input)) },
            onSaveEntries = { inputs -> save(inputs) },
            onSaveFood = QuickEntryWrites::saveFood,
            sensorSerialProvider = { loaded.sensorSerial },
            initialInsulinPresetId = request.insulinPresetId
        )
    } else {
        QuickEntryUndoBar(
            saved = result,
            onUndo = {
                QuickEntryWrites.undo(result.ids)
                onFinish()
            },
            onTimeout = onFinish
        )
    }
}

/** "Saved 6 U Fiasp", Undo: the dashboard's undo bar, alone at the bottom of the screen. */
@Composable
private fun QuickEntryUndoBar(
    saved: SavedEntries,
    onUndo: () -> Unit,
    onTimeout: () -> Unit
) {
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(saved) {
        delay(
            accessibility?.calculateRecommendedTimeoutMillis(
                UNDO_SHOWN_MS,
                containsIcons = false,
                containsText = true,
                containsControls = true
            ) ?: UNDO_SHOWN_MS
        )
        onTimeout()
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(12.dp)
    ) {
        Snackbar(
            action = {
                TextButton(onClick = onUndo) { Text(stringResource(R.string.undo)) }
            }
        ) {
            Text(saved.message)
        }
    }
}
