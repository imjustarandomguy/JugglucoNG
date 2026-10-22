package tk.glucodata.service

/**
 * When the floating pill is on screen: only while the screen is on, only once its
 * readings have been loaded since the screen came on, and only once it has composed the
 * reading resolved from them.
 *
 * A pill kept while the screen was off, hidden, went on recomposing for every reading
 * with no surface to draw on, and came back showing the value from before the screen
 * went off until a tap redrew it. Off screen it now has no window and does no work; back
 * on screen it gets a new window, and only after its readings are current again.
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

    // The revision of the reading resolved from the readings loaded since the screen came
    // on, or null until they are.
    private var handedRevision: Long? = null

    /** Whether the readings are in but the pill waits for its composition of them. */
    val awaitingComposition: Boolean
        get() = screenOn && handedRevision != null && !shown

    /**
     * Records the screen state. Returns true when the screen has just come on, so the
     * readings have to be loaded again; until they are, the pill stays off screen.
     */
    fun onScreen(on: Boolean): Boolean {
        if (on == screenOn) return false
        screenOn = on
        handedRevision = null
        shown = false
        return on
    }

    /**
     * The readings were loaded, and the reading resolved from them, of [revision], handed
     * to the pill. One that lands after the screen went off does not count.
     */
    fun onReadingsLoaded(revision: Long) {
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
     * screen as it is rather than stay off (the self-check then repairs it).
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
         * from the readings' loading: a frame or two at most; this is for one that never
         * comes.
         */
        const val COMPOSE_TIMEOUT_MS = 1_000L
    }
}
