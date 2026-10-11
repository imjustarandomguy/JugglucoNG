package tk.glucodata

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import tk.glucodata.Log.doLog

/**
 * The phone's [ReadingClock] scheduler: one exact wake-up alarm at the moment the shown
 * reading goes stale, or is taken down.
 *
 * A delayed message stops while the phone sleeps, so a reading that stopped coming stayed
 * on the notification, the always-on display and the floating value as if current until
 * something else woke the phone. The alarm fires in Doze too; each new reading moves it
 * before it fires, so while readings keep arriving it never wakes the phone.
 *
 * Allow-while-idle alarms share a quota in Doze, and the loss-of-signal alarm
 * ([LossOfSensorAlarm]) also wakes the phone once a reading is missed. When it is due
 * within [SHARED_WAKE_MS] after the stale moment, its wake does both: its handler brings
 * the current reading up to date, and this clock only keeps a backstop behind it, which
 * that update moves on before it fires. Otherwise they are a minute or more apart, and
 * each wakes on its own.
 */
internal object PhoneReadingClock : ReadingScheduler {
    private const val LOG_ID = "PhoneReadingClock"
    const val ACTION_DEADLINE = "tk.glucodata.action.CURRENT_READING_DEADLINE"

    /** A loss alarm due this soon after the stale moment wakes for both. */
    const val SHARED_WAKE_MS = 10_000L

    /** Where the alarm is set: the platform's [AlarmManager], a fake in tests. */
    interface Alarms {
        fun setExact(atMillis: Long)

        fun cancel()
    }

    @Volatile
    internal var alarms: Alarms = SystemAlarms

    /** When the loss-of-signal alarm is due, 0 for none. */
    @Volatile
    internal var lossAlarmAt: () -> Long = { LossOfSensorAlarm.scheduledAtMillis() }

    override fun schedule(atMillis: Long, wake: Runnable) {
        val at = wakeAt(atMillis, runCatching { lossAlarmAt() }.getOrDefault(0L))
        alarms.setExact(at)
        if (doLog) Log.i(LOG_ID, "deadline $atMillis, wake at $at")
    }

    override fun cancel() {
        alarms.cancel()
    }

    /**
     * When to wake for a deadline at [deadlineMillis] while the loss alarm is due at
     * [lossAlarmMillis] (0 for none): at the deadline, or, when the loss alarm comes
     * at most [SHARED_WAKE_MS] after it, a backstop that long after the loss alarm.
     */
    @JvmStatic
    fun wakeAt(deadlineMillis: Long, lossAlarmMillis: Long): Long =
        if (lossAlarmMillis > 0L && lossAlarmMillis >= deadlineMillis &&
            lossAlarmMillis - deadlineMillis <= SHARED_WAKE_MS
        ) {
            lossAlarmMillis + SHARED_WAKE_MS
        } else {
            deadlineMillis
        }

    private object SystemAlarms : Alarms {
        override fun setExact(atMillis: Long) {
            try {
                val context = Applic.app
                val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val pending = deadlineIntent(context)
                try {
                    manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
                } catch (e: SecurityException) {
                    // Exact alarms denied: an inexact one still takes the value down, a little late.
                    manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
                }
            } catch (t: Throwable) {
                Log.stack(LOG_ID, "setExact", t)
            }
        }

        override fun cancel() {
            try {
                val context = Applic.app
                (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(deadlineIntent(context))
            } catch (t: Throwable) {
                Log.stack(LOG_ID, "cancel", t)
            }
        }

        private fun deadlineIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_DEADLINE).setClass(context, PhoneReadingClockReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}

/** The alarm of [PhoneReadingClock]. Declared in the manifest, never exported. */
class PhoneReadingClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PhoneReadingClock.ACTION_DEADLINE) return
        try {
            CurrentReadingHub.onDeadline()
        } catch (t: Throwable) {
            Log.stack("PhoneReadingClock", "onReceive", t)
        }
    }
}
