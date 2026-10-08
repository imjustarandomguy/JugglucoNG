package tk.glucodata

/**
 * Rate limit that keeps the last request. The first request runs at once; any
 * that arrive within [intervalMs] of the previous run are folded into a single
 * run at the end of the interval, so the newest state is always delivered.
 *
 * A request may name the state it carries by a key that only grows (a reading
 * time). A key newer than every one seen runs at once, whatever the interval:
 * only repeats of the same state are held back.
 *
 * Decision logic only: the caller supplies a monotonic clock and does the
 * scheduling, which keeps the rule testable without Android.
 */
internal class TrailingThrottle(private val intervalMs: Long) {
    private var hasRun = false
    private var lastRunMs = 0L
    private var pending = false
    private var newestKey = NO_KEY

    /**
     * Registers a request made at [nowMs] for the state named [key]. Returns 0
     * when the caller should run now (and drop a run it has scheduled), a
     * positive delay after which it should call [deferredRunStarting] and run,
     * or [COVERED] when an already scheduled run will include this request.
     */
    @Synchronized
    fun request(nowMs: Long, key: Long = NO_KEY): Long {
        if (key > newestKey) {
            newestKey = key
            pending = false
            hasRun = true
            lastRunMs = nowMs
            return 0L
        }
        if (pending) return COVERED
        val waitMs = if (hasRun) lastRunMs + intervalMs - nowMs else 0L
        if (waitMs <= 0L) {
            hasRun = true
            lastRunMs = nowMs
            return 0L
        }
        pending = true
        return waitMs
    }

    /** Marks the scheduled run as started; requests from here on need a run of their own. */
    @Synchronized
    fun deferredRunStarting(nowMs: Long) {
        pending = false
        lastRunMs = nowMs
    }

    companion object {
        /** A run is already scheduled and will include this request. */
        const val COVERED = -1L

        /** The request names no state: a repeat of whatever ran last. */
        const val NO_KEY = Long.MIN_VALUE
    }
}
