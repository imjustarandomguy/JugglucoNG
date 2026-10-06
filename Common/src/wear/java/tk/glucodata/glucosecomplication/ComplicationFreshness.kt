package tk.glucodata.glucosecomplication

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.Log.doLog
import tk.glucodata.Notify

/**
 * Takes a complication's value down once it is too old to show.
 *
 * The sources declare no update period, so a complication only changed when
 * new data arrived: once readings stopped, the last value stayed on the face
 * as if it were current. Showing a reading now arms one alarm for the moment
 * it goes stale ([Notify.glucosetimeout], the threshold every surface uses),
 * and the redraw it triggers shows "no value". Each new reading moves that
 * alarm before it fires, so while readings keep arriving it never wakes the
 * watch.
 */
internal object ComplicationFreshness {
    private const val LOG_ID = "ComplicationFreshness"
    const val ACTION_STALE = "tk.glucodata.glucosecomplication.ACTION_STALE"

    private val deadline = FreshnessDeadline()

    fun onReadingShown(readingTimeMillis: Long) {
        val at = deadline.rearmAt(readingTimeMillis, Notify.glucosetimeout) ?: return
        try {
            val context = Applic.app
            val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pending = staleIntent(context)
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } catch (e: SecurityException) {
                // Exact alarms denied: an inexact one still takes the value down, a little late.
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
            if (doLog) Log.i(LOG_ID, "stale at $at")
        } catch (t: Throwable) {
            deadline.clear()
            Log.stack(LOG_ID, "onReadingShown", t)
        }
    }

    /** The alarm fired: whatever is shown again from here on needs its own. */
    fun onStale() {
        deadline.clear()
        WearComplicationValue.updateall()
    }

    private fun staleIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_STALE).setClass(context, ComplicationFreshnessReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

/**
 * One alarm per deadline: redrawing the same reading, which every complication
 * and the watch face do, costs nothing.
 */
internal class FreshnessDeadline {
    private var armedAtMillis = 0L

    /** When to arm for a reading taken at [readingTimeMillis], or null when that alarm is already set. */
    @Synchronized
    fun rearmAt(readingTimeMillis: Long, timeoutMillis: Long): Long? {
        if (readingTimeMillis <= 0L) return null
        val at = readingTimeMillis + timeoutMillis
        if (at == armedAtMillis) return null
        armedAtMillis = at
        return at
    }

    @Synchronized
    fun clear() {
        armedAtMillis = 0L
    }
}

/** The stale-reading alarm of [ComplicationFreshness]. Declared in the manifest, never exported. */
class ComplicationFreshnessReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ComplicationFreshness.ACTION_STALE) return
        try {
            ComplicationFreshness.onStale()
        } catch (t: Throwable) {
            Log.stack("ComplicationFreshness", "onReceive", t)
        }
    }
}
