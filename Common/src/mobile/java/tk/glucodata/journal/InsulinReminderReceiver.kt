package tk.glucodata.journal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The basal reminder's own broadcasts: its alarms and its notification actions. Never exported;
 * every PendingIntent names it explicitly.
 */
class InsulinReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        InsulinReminders.handleAsync(context, intent) { pending.finish() }
    }
}

/**
 * What clears or shifts the reminder alarms: a reboot clears them, an update of the app clears
 * them, and a new time or time zone moves what "21:00" means. Each sets them all again.
 */
class InsulinReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                val pending = goAsync()
                InsulinReminders.rescheduleAsync(context) { pending.finish() }
            }
        }
    }
}
