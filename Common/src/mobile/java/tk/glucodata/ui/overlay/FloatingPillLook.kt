package tk.glucodata.ui.overlay

import tk.glucodata.DisplayedReading
import tk.glucodata.Notify
import tk.glucodata.logic.TrendEngine
import tk.glucodata.ui.DisplayValues
import tk.glucodata.ui.PeerCurrentReading
import tk.glucodata.ui.SecondarySensorDisplay

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
        /**
         * [peer] fills the secondary slot when the reading has no secondary value of its
         * own: a peer sensor's reading, as [peerFor] chooses it.
         */
        fun of(reading: DisplayedReading, peer: PeerCurrentReading? = null): FloatingPillLook {
            val shows = reading.showsValue
            val secondary = reading.secondaryText?.takeIf { it.isNotEmpty() } ?: peer?.primaryStr
            return FloatingPillLook(
                revision = reading.revision,
                text = reading.shownText,
                secondaryText = secondary?.takeIf { shows && it.isNotEmpty() },
                value = reading.value.takeIf { shows && it.isFinite() },
                arrowRate = reading.shownRate,
                stale = reading.freshness != DisplayedReading.Freshness.FRESH,
            )
        }

        /**
         * The peer sensor whose reading fills the secondary slot of [reading] at [nowMillis],
         * by the dashboard's rule (SecondarySensorDisplay.fallback): while the value is shown
         * and has no second or third lane of its own, the first of [peers] (the sensors
         * selected beside it, in their order) with a fresh reading; null otherwise.
         */
        fun peerFor(
            reading: DisplayedReading,
            peers: List<PeerCurrentReading>,
            nowMillis: Long,
            freshnessWindowMillis: Long = Notify.glucosetimeout,
        ): PeerCurrentReading? {
            if (!reading.showsValue) return null
            val values = DisplayValues(
                primaryValue = reading.value,
                primaryStr = reading.valueText,
                secondaryStr = reading.secondaryText,
                tertiaryStr = reading.tertiaryText,
                fullFormatted = reading.valueText,
            )
            return SecondarySensorDisplay.fallback(values, reading.sensorId, peers, nowMillis, freshnessWindowMillis)
        }

        /** The trend arrow's input for [rate], as the alarm screen builds it from a rate. */
        fun trend(rate: Float): TrendEngine.TrendResult =
            TrendEngine.TrendResult(TrendEngine.stateFor(rate), rate, 0f, 1f, 0f)
    }
}
