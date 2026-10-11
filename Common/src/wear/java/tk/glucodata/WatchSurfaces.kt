package tk.glucodata

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import tk.glucodata.glucosecomplication.ComplicationFreshness
import tk.glucodata.glucosecomplication.WearComplicationValue

/**
 * Hands the watch's current reading to the surfaces that cannot collect it themselves:
 * the complications and the ongoing activity. The watch face and the app's screens listen
 * to [CurrentReadingHub] directly.
 *
 * A publish reaches them at once, so each shows a new reading, and its stale moment, when
 * the others do. Only repeats are folded: a backfill republishes the same reading with a
 * new arrow chunk by chunk, and stored history (what the chart complications draw)
 * changes without any publish at all; both wait for a run of their own at most every
 * [FOLD_INTERVAL_MS].
 */
object WatchSurfaces : ReadingHub.Listener {
    private const val FOLD_INTERVAL_MS = 20_000L

    /** Lets the publish a data change leads to arrive first and cover the fold. */
    private const val FOLD_DELAY_MS = 1_000L

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val throttle = TrailingThrottle(FOLD_INTERVAL_MS)
    private var foldPending = false

    private val deferredRun = Runnable {
        throttle.deferredRunStarting(SystemClock.elapsedRealtime())
        updateComplications()
    }

    private val foldedRequest = Runnable {
        foldPending = false
        request(TrailingThrottle.NO_KEY)
    }

    /** At process start, once the natives are loaded: the watch's surfaces follow the hub. */
    @JvmStatic
    fun start() {
        // The selection's primary, as the phone shows first; native's main sensor before any selection.
        CurrentReadingHub.setDisplaySensor {
            tk.glucodata.ui.WearSensorSelection.resolve() ?: SensorIdentity.resolveMainSensor()
        }
        // The sensors shown beside it (the multi-sensor complications, the hero's chips): their
        // stale moments wake the same clock and are published, so they need no clock of their own.
        CurrentReadingHub.setOtherSensors { tk.glucodata.ui.WearSensorSelection.selected() }
        CurrentReadingHub.setScheduler(ComplicationFreshness)
        CurrentReadingHub.addListener(this)
    }

    override fun onReading(reading: DisplayedReading) {
        handler.post {
            Notify.scheduleOngoingStatusRefresh()
            if (isRepeat(reading.reason)) {
                fold()
            } else {
                // The revision only grows: a new reading, or the same one gone stale, runs now.
                handler.removeCallbacks(foldedRequest)
                foldPending = false
                request(reading.revision)
            }
        }
    }

    /** Stored readings changed, the current one perhaps not ([GlucoseValueRefresh.onStoredDataChanged]). */
    fun onStoredDataChanged() {
        handler.post { fold() }
    }

    /** A publish that only refines the reading already shown. */
    internal fun isRepeat(reason: DisplayedReading.Reason): Boolean = reason == DisplayedReading.Reason.BACKFILL

    private fun fold() {
        if (foldPending) return
        foldPending = true
        handler.postDelayed(foldedRequest, FOLD_DELAY_MS)
    }

    private fun request(key: Long) {
        // Real time, sleep included: uptime stops while the watch sleeps, so the
        // interval could still look open when the next reading arrives minutes later.
        val delayMs = throttle.request(SystemClock.elapsedRealtime(), key)
        when {
            delayMs == 0L -> {
                handler.removeCallbacks(deferredRun)
                updateComplications()
            }
            delayMs > 0L -> handler.postDelayed(deferredRun, delayMs)
        }
    }

    private fun updateComplications() {
        try {
            WearComplicationValue.updateall()
        } catch (t: Throwable) {
            Log.stack("WatchSurfaces", "updateComplications", t)
        }
    }
}
