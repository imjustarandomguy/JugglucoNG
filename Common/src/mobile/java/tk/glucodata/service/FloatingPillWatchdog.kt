package tk.glucodata.service

/**
 * The visible pill's check on itself.
 *
 * A reading reaches the pill through a chain of links: the store, the readings the
 * service follows from it, the live reading the shown value is resolved with, the
 * reading handed to the pill, and the frame the pill draws. A link that misses an
 * event leaves the pill on an old value until something else moves it; a tap did,
 * because it makes the pill's recomposer run a frame (which applies pending state
 * changes) and its window redraw. Each link is checked here against the one before
 * it, and only the first one found behind is repaired:
 *
 *  - the store holds a later reading than the followed readings: [Action.RELOAD] them;
 *  - the live reading changed since the shown one was resolved (a new one arrived,
 *    the old one expired, or the main sensor changed): [Action.RESOLVE] it again;
 *  - the pill drew another reading than it was handed: [Action.REDRAW] it as a tap
 *    would, and if that is not enough, [Action.REATTACH] it in a new window.
 *
 * A reload and each redraw step are tried once per reading, so a link that cannot
 * catch up (a stored reading the display leaves out, say) costs one attempt, not one
 * per check.
 */
internal class FloatingPillWatchdog {
    enum class Action { NONE, RELOAD, RESOLVE, REDRAW, REATTACH }

    /** What the shown reading was resolved with: a change in either means resolving again. */
    data class ResolveInputs(val sensorId: String?, val liveTime: Long)

    private var reloadedFor = NOT_TRIED
    private var redrawnFor = NOT_TRIED
    private var reattachedFor = NOT_TRIED

    /**
     * @param storedNewest time of the newest stored reading, read from the store for this check.
     * @param loadedNewest time of the newest reading the followed readings hold.
     * @param inputsNow the main sensor and live reading time now.
     * @param inputsResolved the same, as they were when the shown reading was resolved;
     *   null before the first resolution.
     * @param handed the revision of the reading handed to the pill.
     * @param drawn the revision of the reading the pill last drew.
     */
    fun check(
        storedNewest: Long,
        loadedNewest: Long,
        inputsNow: ResolveInputs,
        inputsResolved: ResolveInputs?,
        handed: Long,
        drawn: Long,
    ): Action {
        if (isLaterReading(storedNewest, loadedNewest) && reloadedFor != storedNewest) {
            reloadedFor = storedNewest
            return Action.RELOAD
        }
        if (inputsResolved != null && inputsNow != inputsResolved) return Action.RESOLVE
        if (drawn == handed) return Action.NONE
        if (redrawnFor != handed) {
            redrawnFor = handed
            return Action.REDRAW
        }
        if (reattachedFor != handed) {
            reattachedFor = handed
            return Action.REATTACH
        }
        return Action.NONE
    }

    companion object {
        /**
         * Two times this close are the same reading as two sources stamp it (live and
         * stored times differ by up to a minute); CurrentDisplaySource matches them so too.
         */
        const val SAME_READING_WINDOW_MS = 60_000L

        /** How often the visible pill checks itself when nothing else asks. */
        const val CHECK_INTERVAL_MS = 30_000L

        /**
         * How long after a new reading was handed to the pill (or after a repair) it is
         * checked: ample for a recomposition and a frame, short enough to be "at once".
         */
        const val VERIFY_DELAY_MS = 3_000L

        private const val NOT_TRIED = Long.MIN_VALUE

        /** Whether [later] is a later reading than the one taken at [than]. */
        fun isLaterReading(later: Long, than: Long): Boolean =
            later > 0L && later - than > SAME_READING_WINDOW_MS

        /**
         * Whether the live source still holds an older reading than the newest stored one.
         * A reading stored without going through the live path (the watch's gap fill)
         * leaves the previous live reading current until it expires, minutes later, and the
         * current value resolves to it until then.
         */
        fun storeIsAheadOfLive(liveTime: Long, storedNewest: Long): Boolean =
            liveTime > 0L && isLaterReading(storedNewest, liveTime)
    }
}
