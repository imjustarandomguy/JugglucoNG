package tk.glucodata.widget

import tk.glucodata.DisplayedReading

/**
 * When the widgets need drawing, without a timer of its own.
 *
 * Nobody sees a widget while the screen is off, so updates then only mark the
 * widgets dirty and screen-on draws them once. Their stale look needs no clock
 * either: CurrentReadingHub publishes the moment the reading goes stale or its
 * value is taken down, and that publish is an update like any other.
 *
 * Starts dirty: after a process start nothing is known about what the hosts
 * still show.
 */
class WidgetRefreshGate {
    private var dirty = true
    private var deferrals = 0L

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
     * Every placed widget was just drawn. An update deferred while the render ran
     * (its data was already loaded) keeps the widgets dirty.
     */
    @Synchronized
    fun renderedAll(token: Long) {
        if (token == deferrals) dirty = false
    }

    @Synchronized
    fun needsRenderOnScreenOn(): Boolean = dirty

    @Synchronized
    fun isDirty(): Boolean = dirty

    companion object {
        /**
         * Whether a deadline of the hub's [reading] (its stale moment, or the removal of
         * its value) has passed at [nowMillis] without the hub's republish. The hub's
         * clock may sleep with the phone (CurrentReadingHub.onClockChanged): the widgets
         * are then drawn from its republish, not from a reading already out of date.
         */
        fun hubIsBehind(reading: DisplayedReading, nowMillis: Long): Boolean {
            val deadline = when (reading.freshness) {
                DisplayedReading.Freshness.FRESH -> reading.staleAtMillis
                DisplayedReading.Freshness.STALE -> reading.noValueAtMillis
                else -> 0L
            }
            return deadline in 1L..nowMillis
        }
    }
}
