package tk.glucodata.glucosecomplication

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
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

    /** At the latest this long after a wake, the watch may sleep again whatever is still drawing. */
    const val MAX_HOLD_MS = 5_000L

    /**
     * Hands a task on once the hub's worker, then the main thread (where
     * [tk.glucodata.WatchSurfaces] asks the complications to update), have done what was
     * asked of them before it. A fake in tests.
     */
    @Volatile
    internal var afterDrawn: (Runnable) -> Unit = { task ->
        CurrentReadingHub.afterPending { mainHandler.post(task) }
    }

    /** Runs a task [MAX_HOLD_MS] from now. A fake in tests. */
    @Volatile
    internal var backstop: (Runnable) -> Unit = { mainHandler.postDelayed(it, MAX_HOLD_MS) }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Runs [release] once the publish asked for just before has reached the complications,
     * or after [MAX_HOLD_MS] at the latest, and only once. The alarm keeps the watch awake
     * only until its receiver returns, while the hub publishes on its own thread: without a
     * hold the watch could sleep in between, its complications still showing the reading as
     * current.
     */
    fun untilDrawn(release: Runnable) {
        val once = AtomicBoolean()
        val done = Runnable {
            if (once.compareAndSet(false, true)) {
                try {
                    release.run()
                } catch (t: Throwable) {
                    Log.stack(LOG_ID, "release", t)
                }
            }
        }
        try {
            backstop(done)
            afterDrawn(done)
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "untilDrawn", t)
            done.run()
        }
    }
}

/**
 * The alarm of [ComplicationFreshness]. Declared in the manifest, never exported. The
 * broadcast stays pending, and so the watch awake, until the complications are asked to
 * show the reading it takes stale.
 */
class ComplicationFreshnessReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ComplicationFreshness.ACTION_STALE) return
        val pending = goAsync()
        try {
            CurrentReadingHub.onDeadline()
        } catch (t: Throwable) {
            Log.stack("ComplicationFreshness", "onReceive", t)
        }
        ComplicationFreshness.untilDrawn { pending.finish() }
    }
}
