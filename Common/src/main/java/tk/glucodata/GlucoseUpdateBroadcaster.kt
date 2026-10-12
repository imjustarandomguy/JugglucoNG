package tk.glucodata

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

object GlucoseUpdateBroadcaster {
    const val ACTION_GLUCOSE_UPDATE = "tk.glucodata.action.GLUCOSE_UPDATE"

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

    /**
     * Called in-process on every delivered update, on the delivering thread. The
     * phone's home-screen widgets listen here rather than through a manifest
     * receiver: a broadcast per reading reached them even with none placed.
     */
    @Volatile
    private var listener: Runnable? = null

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
    fun setListener(listener: Runnable?) {
        this.listener = listener
    }

    @JvmStatic
    @JvmOverloads
    fun send(context: Context? = null) {
        val appContext = (context?.applicationContext ?: Applic.app) ?: return
        lastContext = appContext
        coalescer.request()
    }

    private fun deliver() {
        val appContext = lastContext ?: return
        try {
            listener?.run()
        } catch (th: Throwable) {
            Log.stack(LOG_ID, "listener", th)
        }
        try {
            appContext.sendBroadcast(
                Intent(ACTION_GLUCOSE_UPDATE)
                    .setPackage(appContext.packageName)
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
