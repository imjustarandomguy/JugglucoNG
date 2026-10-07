package tk.glucodata

/**
 * Rate limit that keeps the last request. The first request runs at once; any
 * that arrive within [intervalMs] of the previous run are folded into a single
 * run at the end of the interval, so the newest state is always delivered.
 *
 * Decision logic only: the caller supplies a monotonic clock and does the
 * scheduling, which keeps the rule testable without Android.
 */
internal class TrailingThrottle(private val intervalMs: Long) {
    private var hasRun = false
    private var lastRunMs = 0L
    private var pending = false

    /**
     * Registers a request made at [nowMs]. Returns 0 when the caller should run
     * now, a positive delay after which it should call [deferredRunStarting] and
     * run, or [COVERED] when an already scheduled run will include this request.
     */
    @Synchronized
    fun request(nowMs: Long): Long {
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
    }
}
