package tk.glucodata.service

/**
 * When the floating pill is on screen: only while the screen is on, and only once its
 * readings have been loaded since the screen came on.
 *
 * A pill kept while the screen was off, hidden, went on recomposing for every reading
 * with no surface to draw on, and came back showing the value from before the screen
 * went off until a tap redrew it. Off screen it now has no window and does no work; back
 * on screen it gets a new window, and only after its readings are current again.
 */
internal class FloatingPillPresence {
    /** Whether the screen is on (interactive). */
    var screenOn = false
        private set

    private var loadedSinceScreenOn = false

    /** Whether the pill belongs on screen. */
    val shown: Boolean
        get() = screenOn && loadedSinceScreenOn

    /**
     * Records the screen state. Returns true when the screen has just come on, so the
     * readings have to be loaded again; until they are, the pill stays off screen.
     */
    fun onScreen(on: Boolean): Boolean {
        if (on == screenOn) return false
        screenOn = on
        loadedSinceScreenOn = false
        return on
    }

    /** The readings were loaded. One that lands after the screen went off does not count. */
    fun onReadingsLoaded() {
        if (screenOn) loadedSinceScreenOn = true
    }
}
