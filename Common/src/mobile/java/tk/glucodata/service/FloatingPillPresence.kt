package tk.glucodata.service

import tk.glucodata.DisplayedReading

/**
 * When the floating pill is on screen: only while the screen is on, only once the current
 * reading has been handed to it since the screen came on, and only once it has composed
 * that reading.
 *
 * A pill kept while the screen was off, hidden, went on recomposing for every reading
 * with no surface to draw on, and came back showing the value from before the screen
 * went off until a tap redrew it. Off screen it now has no window and does no work; back
 * on screen it gets a new window, and only after its reading is current again.
 *
 * Its composition outlives the window, and a new window's first frame draws what the
 * composition holds: the reading from before the screen went off, as long as the one
 * handed to it since is not composed. So the window waits for that composition, which
 * the pill reports ([onReadingComposed]), and its first frame shows the handed reading.
 * A pill that never composed (the service just started) has no older picture: its first
 * composition, made as its first window goes in, reads the handed reading itself.
 */
internal class FloatingPillPresence {
    /** Whether the screen is on (interactive). */
    var screenOn = false
        private set

    /** Whether the pill belongs on screen. Once on, it stays until the screen goes off. */
    var shown = false
        private set

    /**
     * The newest revision of the reading the pill has composed, or null before its first
     * composition. Kept while the screen is off: so is the composition.
     */
    var composedRevision: Long? = null
        private set

    // The revision of the reading handed to the pill since the screen came on, or null
    // until one is.
    private var handedRevision: Long? = null

    /** Whether the reading is handed but the pill waits for its composition of it. */
    val awaitingComposition: Boolean
        get() = screenOn && handedRevision != null && !shown

    /**
     * Records the screen state. Returns true when the screen has just come on, so the
     * current reading has to be handed again; until it is, the pill stays off screen.
     */
    fun onScreen(on: Boolean): Boolean {
        if (on == screenOn) return false
        screenOn = on
        handedRevision = null
        shown = false
        return on
    }

    /**
     * The current reading, of [revision], was handed to the pill. One handed after the
     * screen went off does not count.
     */
    fun onReadingHanded(revision: Long) {
        if (!screenOn) return
        handedRevision = revision
        update()
    }

    /** The pill composed, and applied, the reading of [revision]. */
    fun onReadingComposed(revision: Long) {
        composedRevision = maxOf(composedRevision ?: revision, revision)
        update()
    }

    /**
     * The pill did not compose the handed reading within [COMPOSE_TIMEOUT_MS]: it goes on
     * screen as it is rather than stay off.
     */
    fun onComposeTimedOut() {
        if (awaitingComposition) shown = true
    }

    private fun update() {
        if (!awaitingComposition) return
        val handed = handedRevision ?: return
        val composed = composedRevision
        if (composed == null || composed >= handed) shown = true
    }

    companion object {
        /**
         * How long the pill waits at screen on for its composition of the handed reading,
         * and before that for the hub's publish of a current one: a frame or two at most;
         * this is for one that never comes.
         */
        const val COMPOSE_TIMEOUT_MS = 1_000L

        /**
         * Whether, at screen on ([nowMillis]), the hub's [reading] is not yet the one to
         * hand: the hub has not published at all, or a deadline of the reading (its stale
         * moment, or the removal of its value) has passed without the hub's republish. Its
         * clock may sleep with the phone (CurrentReadingHub.onClockChanged); the pill then
         * waits for the republish rather than show for a moment a value already stale.
         */
        fun waitsForHub(reading: DisplayedReading, nowMillis: Long): Boolean {
            if (reading.revision <= 0L) return true
            val deadline = when (reading.freshness) {
                DisplayedReading.Freshness.FRESH -> reading.staleAtMillis
                DisplayedReading.Freshness.STALE -> reading.noValueAtMillis
                else -> 0L
            }
            return deadline in 1L..nowMillis
        }
    }
}
