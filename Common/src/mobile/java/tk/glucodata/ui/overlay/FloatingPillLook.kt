package tk.glucodata.ui.overlay

import tk.glucodata.DisplayedReading
import tk.glucodata.logic.TrendEngine

/**
 * What the pill, upright or as an island, draws for the device's current reading, the one
 * CurrentReadingHub publishes: it renders that reading and decides nothing about it.
 * Pure, see the tests.
 *
 * The pill used to resolve its own reading, regress its own arrow over the readings it
 * followed and keep its own freshness clock, so it could show another value, arrow or
 * stale moment than the notification beside it.
 */
internal data class FloatingPillLook(
    /** The hub's revision of the reading drawn. */
    val revision: Long,
    /** The value, or [DisplayedReading.NO_VALUE_TEXT] without one. */
    val text: String,
    val secondaryText: String?,
    /** The value in the display unit, for its range colour; null without one. */
    val value: Float?,
    /** The arrow's rate in mg/dL per minute; NaN when no arrow is drawn. */
    val arrowRate: Float,
    /**
     * Not current: the next reading was due and did not come, or there is no value. A
     * value is then dimmed, without range colour and without arrow, and the next-reading
     * ring is late.
     */
    val stale: Boolean,
) {
    val hasValue: Boolean get() = value != null

    val hasArrow: Boolean get() = arrowRate.isFinite()

    /** Opacity of the value and its secondary value. */
    val valueAlpha: Float get() = FloatingStaleValue.alpha(hasValue, stale)

    companion object {
        fun of(reading: DisplayedReading): FloatingPillLook {
            val shows = reading.showsValue
            return FloatingPillLook(
                revision = reading.revision,
                text = reading.shownText,
                secondaryText = reading.secondaryText?.takeIf { shows && it.isNotEmpty() },
                value = reading.value.takeIf { shows && it.isFinite() },
                arrowRate = reading.shownRate,
                stale = reading.freshness != DisplayedReading.Freshness.FRESH,
            )
        }

        /** The trend arrow's input for [rate], as the alarm screen builds it from a rate. */
        fun trend(rate: Float): TrendEngine.TrendResult =
            TrendEngine.TrendResult(TrendEngine.stateFor(rate), rate, 0f, 1f, 0f)
    }
}
