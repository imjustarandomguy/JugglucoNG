package tk.glucodata.ui.journal

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import tk.glucodata.Log
import tk.glucodata.R
import tk.glucodata.data.journal.JournalEntryType

/**
 * The "Log insulin" quick-settings tile: one tap closes the shade and opens the entry sheet on
 * insulin, over whatever is on screen. An action, not a toggle, so it has no state to show.
 * From the lock screen the user unlocks first (unlockAndRun): the sheet writes to the journal.
 */
class JournalQuickEntryTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_journal_quick_insulin)
        tile.label = getString(R.string.journal_quick_log_insulin)
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun { openSheet() }
        } else {
            openSheet()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openSheet() {
        val intent = JournalQuickEntryActivity.intent(this, JournalEntryType.INSULIN)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "openSheet", t)
        }
    }

    private companion object {
        const val LOG_ID = "JournalQuickEntryTile"
    }
}
