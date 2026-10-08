package tk.glucodata.widget

/**
 * When the widgets need drawing, without a timer of its own.
 *
 * Nobody sees a widget while the screen is off, so updates then only mark the
 * widgets dirty and screen-on draws them once. Screen-on also draws when the
 * value last shown as fresh has aged past [freshnessMillis] since: the loss
 * alarm that would have switched the widget to its stale look may have come
 * while the screen was off, or not at all (no sensor, alarm cancelled).
 *
 * Starts dirty: after a process start nothing is known about what the hosts
 * still show.
 */
class WidgetRefreshGate(private val freshnessMillis: Long) {
    private var dirty = true
    private var deferrals = 0L
    private var shownFreshReadingMillis = 0L

    /** An update came while nobody could see it. */
    @Synchronized
    fun deferWhileScreenOff() {
        dirty = true
        deferrals++
    }

    /** A render of every placed widget starts; pass the result to [renderedAll]. */
    @Synchronized
    fun beginRender(): Long = deferrals

    /**
     * Every placed widget was just drawn. [freshReadingMillis] is the time of
     * the reading they show as fresh, or 0 when they show it as stale or show none.
     * An update deferred while the render ran (its data was already loaded) keeps
     * the widgets dirty.
     */
    @Synchronized
    fun renderedAll(token: Long, freshReadingMillis: Long) {
        if (token == deferrals) dirty = false
        shownFreshReadingMillis = freshReadingMillis
    }

    @Synchronized
    fun needsRenderOnScreenOn(nowMillis: Long): Boolean {
        if (dirty) return true
        return shownFreshReadingMillis > 0L && nowMillis - shownFreshReadingMillis > freshnessMillis
    }

    @Synchronized
    fun isDirty(): Boolean = dirty
}
