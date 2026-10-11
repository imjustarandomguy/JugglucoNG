package tk.glucodata.glucosecomplication

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import tk.glucodata.Applic
import tk.glucodata.CurrentReadingHub
import tk.glucodata.Log
import tk.glucodata.Log.doLog
import tk.glucodata.ReadingScheduler

/**
 * The watch's [tk.glucodata.ReadingClock]: one exact alarm at the moment the shown reading
 * goes stale, or is taken down.
 *
 * The sources declare no update period, so a complication only changes when the hub
 * publishes: once readings stopped, the last value stayed on the face as if it were
 * current. The alarm wakes the watch for that publish even with the screen off, where a
 * delayed message would wait for the next wake-up. Each new reading moves the alarm before
 * it fires, so while readings keep arriving it never wakes the watch.
 *
 * The wake goes through the process's [CurrentReadingHub], the one the receiver reaches
 * after a restart too.
 */
internal object ComplicationFreshness : ReadingScheduler {
    private const val LOG_ID = "ComplicationFreshness"
    const val ACTION_STALE = "tk.glucodata.glucosecomplication.ACTION_STALE"

    override fun schedule(atMillis: Long, wake: Runnable) {
        try {
            val context = Applic.app
            val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pending = staleIntent(context)
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
            } catch (e: SecurityException) {
                // Exact alarms denied: an inexact one still takes the value down, a little late.
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
            }
            if (doLog) Log.i(LOG_ID, "wake at $atMillis")
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "schedule", t)
        }
    }

    override fun cancel() {
        try {
            val context = Applic.app
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(staleIntent(context))
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "cancel", t)
        }
    }

    private fun staleIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_STALE).setClass(context, ComplicationFreshnessReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

/** The alarm of [ComplicationFreshness]. Declared in the manifest, never exported. */
class ComplicationFreshnessReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ComplicationFreshness.ACTION_STALE) return
        try {
            CurrentReadingHub.onDeadline()
        } catch (t: Throwable) {
            Log.stack("ComplicationFreshness", "onReceive", t)
        }
    }
}
