package tk.glucodata.ui.journal

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import tk.glucodata.Log
import tk.glucodata.R
import tk.glucodata.data.journal.JournalEntryType

/**
 * "Log insulin" and "Log food" on a long press of the app icon. Dynamic rather than static
 * shortcuts: a static one names the package, and the debug and side-by-side builds have
 * another (".debug", ".dub"). Published at every process start, only when they changed (the
 * launcher rate-limits updates from the background), so a new language reaches them too.
 * With the journal turned off they are taken away at the next start.
 */
object JournalQuickEntryShortcuts {
    private const val LOG_ID = "JournalQuickEntryShortcuts"
    private const val INSULIN_ID = "journal_log_insulin"
    private const val FOOD_ID = "journal_log_food"

    /** Off the main thread: it runs at process start, and the shortcut calls are binder calls. */
    @JvmStatic
    fun publish(context: Context) {
        val app = context.applicationContext
        Thread({ publishNow(app) }, LOG_ID).start()
    }

    private fun publishNow(app: Context) {
        try {
            val journalEnabled = app.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
                .getBoolean("dashboard_journal_enabled", true)
            if (!journalEnabled) {
                ShortcutManagerCompat.removeDynamicShortcuts(app, listOf(INSULIN_ID, FOOD_ID))
                return
            }
            val wanted = listOf(
                shortcut(
                    app, INSULIN_ID, JournalEntryType.INSULIN,
                    R.string.journal_quick_log_insulin, R.drawable.ic_shortcut_journal_insulin, rank = 0
                ),
                shortcut(
                    app, FOOD_ID, JournalEntryType.CARBS,
                    R.string.journal_quick_log_food, R.drawable.ic_shortcut_journal_food, rank = 1
                )
            )
            val current = ShortcutManagerCompat.getDynamicShortcuts(app)
                .associate { it.id to it.shortLabel.toString() }
            if (wanted.all { current[it.id] == it.shortLabel.toString() }) return
            ShortcutManagerCompat.addDynamicShortcuts(app, wanted)
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "publish", t)
        }
    }

    private fun shortcut(
        context: Context,
        id: String,
        type: JournalEntryType,
        labelRes: Int,
        iconRes: Int,
        rank: Int
    ): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(context.getString(labelRes))
            .setIcon(IconCompat.createWithResource(context, iconRes))
            .setIntent(JournalQuickEntryActivity.intent(context, type))
            .setRank(rank)
            .build()
}
