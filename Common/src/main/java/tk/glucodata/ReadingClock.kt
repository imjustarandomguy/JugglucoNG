package tk.glucodata

import android.os.Handler
import android.os.Looper

/**
 * Wakes the device's [CurrentReadingHub] once, at a wall-clock moment.
 *
 * Each platform brings its own: the watch an exact alarm (its complications must go stale
 * on time with the screen off), the phone a [Handler] reconciled at screen on. Tests use a
 * fake one.
 */
interface ReadingScheduler {
    /** Runs [wake] at [atMillis] (wall clock), replacing any wake scheduled before. */
    fun schedule(atMillis: Long, wake: Runnable)

    /** Drops the scheduled wake, if any. */
    fun cancel()
}

/**
 * The one clock of a device's current reading: a single wake at the next moment the shown
 * reading changes by itself (it goes stale, or its value is taken down), and none while it
 * cannot change any more. Every surface used to keep a staleness timer of its own, with
 * three different deadlines between them.
 *
 * Arming is idempotent: redrawing the same reading costs nothing, and each new reading
 * moves the wake before it fires, so while readings keep arriving it never wakes the device.
 */
class ReadingClock(@Volatile var scheduler: ReadingScheduler) {
    private var armedAtMillis = 0L

    /**
     * The next moment [reading] changes by itself, or 0 when it cannot. A deadline already
     * past (the device slept through it) is still returned, and wakes at once.
     */
    fun nextDeadline(reading: DisplayedReading): Long {
        if (reading.timeMillis <= 0L) return 0L
        return when (reading.freshness) {
            DisplayedReading.Freshness.FRESH -> reading.staleAtMillis
            DisplayedReading.Freshness.STALE -> reading.noValueAtMillis
            else -> 0L
        }
    }

    /** Schedules [wake] for [reading]'s next deadline, unless it is already scheduled. */
    @Synchronized
    fun arm(reading: DisplayedReading, wake: Runnable) {
        val at = nextDeadline(reading)
        if (at == armedAtMillis) return
        armedAtMillis = at
        if (at > 0L) scheduler.schedule(at, wake) else scheduler.cancel()
    }

    /** The wake fired (or was lost): whatever is computed next arms again. */
    @Synchronized
    fun disarm() {
        armedAtMillis = 0L
    }
}

/**
 * A [ReadingScheduler] on the main looper. Its delay stops while the device sleeps, so a
 * caller relying on it reconciles when the screen comes on ([CurrentReadingHub.onClockChanged]).
 */
class HandlerReadingScheduler : ReadingScheduler {
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var scheduled: Runnable? = null

    @Synchronized
    override fun schedule(atMillis: Long, wake: Runnable) {
        scheduled?.let(handler::removeCallbacks)
        scheduled = wake
        handler.postDelayed(wake, (atMillis - System.currentTimeMillis()).coerceAtLeast(0L))
    }

    @Synchronized
    override fun cancel() {
        scheduled?.let(handler::removeCallbacks)
        scheduled = null
    }
}
