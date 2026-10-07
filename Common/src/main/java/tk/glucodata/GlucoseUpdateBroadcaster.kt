package tk.glucodata

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

object GlucoseUpdateBroadcaster {
    const val ACTION_GLUCOSE_UPDATE = "tk.glucodata.action.GLUCOSE_UPDATE"
    const val EXTRA_TICK_DELIVERED = "tk.glucodata.extra.TICK_DELIVERED"

    private const val LOG_ID = "GlucoseUpdateBroadcast"

    /**
     * Minimum spacing of updates. A request inside it is postponed to the end of
     * the interval, not dropped. One reading asks for several updates from
     * different threads, and the first can come before the reading is published:
     * the Room sync of a native sensor (HistorySync) can finish while
     * dowithglucose has not yet set the live value. Dropping the later requests
     * left the home-screen widget on the previous reading until the next one.
     */
    internal const val MIN_BROADCAST_INTERVAL_MS = 1_000L
    private val lock = Any()

    private val tickState = MutableStateFlow(0L)

    // Glance widget sessions observe this because update() recomposes the
    // captured content but does not re-run provideGlance.
    val tick: StateFlow<Long> get() = tickState

    // Outside the throttle both ways: a broadcast in the second before expiry (startup, a
    // settings change) must not swallow the one redraw that retires the number, and that
    // redraw must not hold back a fresh reading arriving right after it.
    private val freshnessDeadline = AlarmManager.OnAlarmListener {
        synchronized(lock) { pendingDeadlineMillis = 0L }
        deliver()
    }
    private var pendingDeadlineMillis = 0L

    /**
     * Redraws the lock-screen overlay and widgets once [readingTimeMillis] passes
     * [Notify.glucosetimeout], so they swap the number for "No new value since …" whether
     * or not the glucose notification is enabled (its own deadline only runs when it is).
     * Non-wakeup: fires on time while the device is awake, otherwise on the next wake.
     *
     * Called for every live reading and by each surface that draws a fresh number, so a
     * reading restored after a process restart (which drops the in-process alarm) still
     * expires. One alarm serves them all at the earliest deadline: readings differ per
     * sensor (switching the primary can draw an older one), and the redraw it triggers
     * re-arms whatever each surface still shows, so a too-early deadline costs one redraw.
     */
    @JvmStatic
    fun armFreshnessDeadline(readingTimeMillis: Long) {
        if (Applic.isWearable || readingTimeMillis <= 0L) return
        val alarmManager = Applic.app?.getSystemService(AlarmManager::class.java) ?: return
        // DisplayDataState counts the exact threshold as fresh; go one ms past it.
        val deadline = readingTimeMillis + Notify.glucosetimeout + 1L
        synchronized(lock) {
            if (pendingDeadlineMillis in 1..deadline) return
            try {
                alarmManager.cancel(freshnessDeadline)
                alarmManager.setExact(
                    AlarmManager.RTC,
                    deadline,
                    "tk.glucodata:glucose-freshness",
                    freshnessDeadline,
                    null
                )
                pendingDeadlineMillis = deadline
            } catch (th: Throwable) {
                Log.stack(LOG_ID, "armFreshnessDeadline", th)
            }
        }
    }

    @Volatile
    private var lastContext: Context? = null
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    // Real time, sleep included: uptime stops while the phone sleeps, so the
    // interval could still look open when the next reading arrives. The deferred
    // update itself rides the main looper; when the CPU suspends first it runs on
    // waking, and the screen being on, when a widget can be seen, keeps it awake.
    private val coalescer = Coalescer(
        intervalMs = MIN_BROADCAST_INTERVAL_MS,
        clock = SystemClock::elapsedRealtime,
        schedule = { delayMs, run -> handler.postDelayed(run, delayMs) },
        deliver = ::deliver
    )

    @JvmStatic
    fun hasActiveTickObservers(): Boolean {
        return tickState.subscriptionCount.value > 0
    }

    @JvmStatic
    @JvmOverloads
    fun send(context: Context? = null) {
        lastContext = (context?.applicationContext ?: Applic.app) ?: return
        coalescer.request()
    }

    private fun deliver() {
        val appContext = lastContext ?: Applic.app ?: return
        tickState.update { it + 1L }
        try {
            appContext.sendBroadcast(
                Intent(ACTION_GLUCOSE_UPDATE)
                    .setPackage(appContext.packageName)
                    .putExtra(EXTRA_TICK_DELIVERED, true)
            )
        } catch (th: Throwable) {
            Log.stack(LOG_ID, "send", th)
        }
    }

    /**
     * The rate limit without Android, so it can be tested. The first request
     * delivers at once; later ones within [intervalMs] fold into one delivery at
     * the end of the interval. Every request is followed by a delivery that
     * starts no earlier than the request, so the last update always shows the
     * newest state.
     */
    internal class Coalescer(
        intervalMs: Long,
        private val clock: () -> Long,
        private val schedule: (delayMs: Long, run: Runnable) -> Unit,
        private val deliver: () -> Unit
    ) {
        private val throttle = TrailingThrottle(intervalMs)
        private val deferredDelivery = Runnable {
            throttle.deferredRunStarting(clock())
            deliver()
        }

        fun request() {
            val delayMs = throttle.request(clock())
            when {
                delayMs == 0L -> deliver()
                delayMs > 0L -> schedule(delayMs, deferredDelivery)
                // Otherwise TrailingThrottle.COVERED: the scheduled delivery includes it.
            }
        }
    }
}
