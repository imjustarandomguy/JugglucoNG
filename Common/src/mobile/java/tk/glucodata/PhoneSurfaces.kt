package tk.glucodata

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * The phone's current reading, as every surface draws it ([CurrentReadingHub]).
 *
 * Hands each publish to the ongoing notification and its status-bar icon, which cannot
 * collect it themselves; the always-on display, the floating value and the dashboard
 * follow the hub directly. A new reading and the moment it goes stale reach them in the
 * same publish, so none of them keeps a staleness timer of its own.
 *
 * Installs the phone's clock ([PhoneReadingClock]) and reconciles it whenever the wall
 * clock may have moved under it: screen on, time or time-zone change, and process start
 * (installing the clock works freshness out again).
 */
object PhoneSurfaces : ReadingHub.Listener {
    private const val LOG_ID = "PhoneSurfaces"

    @Volatile
    private var started = false

    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                CurrentReadingHub.onClockChanged()
            } catch (t: Throwable) {
                Log.stack(LOG_ID, "clockReceiver", t)
            }
        }
    }

    /** At process start, once the natives are loaded: the phone's surfaces follow the hub. */
    @JvmStatic
    fun start(context: Context) {
        if (started) return
        started = true
        CurrentReadingHub.setScheduler(PhoneReadingClock)
        CurrentReadingHub.addListener(this)
        try {
            val filter = IntentFilter(Intent.ACTION_SCREEN_ON).apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            ContextCompat.registerReceiver(
                context.applicationContext,
                clockReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "registerReceiver", t)
        }
    }

    override fun onReading(reading: DisplayedReading) {
        Notify.onReadingPublished(reading)
    }
}
