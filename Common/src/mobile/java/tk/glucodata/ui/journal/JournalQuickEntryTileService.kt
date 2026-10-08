package tk.glucodata.ui.journal

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import tk.glucodata.Log
import tk.glucodata.R
import tk.glucodata.data.journal.JournalEntryType

/**
 * The "Log insulin" quick-settings tile. Its class name is what the system knows a tile added
 * to the shade by, so it stays as it was before the food tile came.
 */
class JournalQuickEntryTileService : JournalQuickEntryTile(
    type = JournalEntryType.INSULIN,
    iconRes = R.drawable.ic_journal_quick_insulin,
    labelRes = R.string.journal_quick_log_insulin
)

/** The "Log food" quick-settings tile. */
class JournalQuickEntryFoodTileService : JournalQuickEntryTile(
    type = JournalEntryType.CARBS,
    iconRes = R.drawable.ic_journal_quick_food,
    labelRes = R.string.journal_quick_log_food
)

/**
 * A quick-settings tile that opens the entry sheet on [type]: one tap closes the shade and opens
 * the sheet over whatever is on screen. An action, not a toggle, so it has no state to show.
 * From the lock screen the user unlocks first (unlockAndRun): the sheet writes to the journal.
 * Each tile is its own service (one per tile in the manifest), with this as their code.
 */
abstract class JournalQuickEntryTile(
    private val type: JournalEntryType,
    @DrawableRes private val iconRes: Int,
    @StringRes private val labelRes: Int
) : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, iconRes)
        tile.label = getString(labelRes)
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
        val intent = JournalQuickEntryActivity.intent(this, type)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(
                        this,
                        // One request code per tile: intents that differ only in their extras
                        // would otherwise be one PendingIntent.
                        type.ordinal,
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
