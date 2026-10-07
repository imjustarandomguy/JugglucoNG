@file:JvmName("QuickLogButtons")

package tk.glucodata

import android.content.Context

/*
 * "Quick log buttons" (notification settings): "Log insulin" and "Log food" on the ongoing
 * glucose notification, "+ Insulin" and "+ Food" on the floating glucose's details. On unless
 * turned off, and only while the journal is on. The quick-settings tiles and the app icon's
 * shortcuts do not follow it. Here rather than with the phone's journal code because the
 * notification (Notify, in src/main) asks too.
 */

private const val PREFS_NAME = "tk.glucodata_preferences"
private const val JOURNAL_ENABLED_KEY = "dashboard_journal_enabled"
private const val QUICK_LOG_BUTTONS_KEY = "notification_quick_log_buttons"

/**
 * The extra the phone's quick entry sheet (ui.journal.JournalQuickEntryActivity) reads its entry
 * type from, a JournalEntryType storage value; the notification, which cannot see that class,
 * sets it with [QUICK_LOG_TYPE_INSULIN] or [QUICK_LOG_TYPE_FOOD].
 */
const val QUICK_ENTRY_EXTRA_TYPE = "tk.glucodata.journal.quick_entry.TYPE"

/** JournalEntryType.INSULIN's storage value. */
const val QUICK_LOG_TYPE_INSULIN = "insulin"

/** JournalEntryType.CARBS's storage value: food. */
const val QUICK_LOG_TYPE_FOOD = "carbs"

/**
 * Whether the quick log buttons are offered: the journal is on (journalQuickEntryEnabled) and
 * so is the setting. The notification's actions and the floating glucose's buttons ask this.
 */
fun quickLogButtonsEnabled(context: Context): Boolean {
    val prefs = prefs(context)
    return prefs.getBoolean(JOURNAL_ENABLED_KEY, true) && prefs.getBoolean(QUICK_LOG_BUTTONS_KEY, true)
}

/** The setting alone, whatever the journal: what its switch shows. */
fun quickLogButtonsSetting(context: Context): Boolean = prefs(context).getBoolean(QUICK_LOG_BUTTONS_KEY, true)

/** Turns the setting on or off; the caller refreshes the notification. */
fun setQuickLogButtonsSetting(context: Context, enabled: Boolean) {
    prefs(context).edit().putBoolean(QUICK_LOG_BUTTONS_KEY, enabled).apply()
}

private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
