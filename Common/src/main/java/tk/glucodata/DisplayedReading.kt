package tk.glucodata

/**
 * The current reading as every surface of this device shows it: one value, one arrow, one
 * freshness, published by [CurrentReadingHub].
 *
 * Surfaces render this and never resolve on their own. Each of them used to call
 * [CurrentDisplaySource] at its own moment, take its own rate and keep its own staleness
 * timer, so a watch face, a complication and the app could show two readings, two arrows
 * and two stale moments for one sensor.
 *
 * Immutable. [revision] grows with every publish; everything else is what is drawn, and
 * [sameDisplayAs] compares exactly that.
 */
data class DisplayedReading(
    val sensorId: String?,
    /** When the reading was taken; 0 with none. */
    val timeMillis: Long,
    /** [value] in mg/dL; NaN without a value. */
    val mgdl: Float,
    /** The primary lane in the display unit, as [CurrentDisplaySource] resolved it; NaN without a value. */
    val value: Float,
    /** [value] formatted; empty without a value. */
    val valueText: String,
    /** The second and third lanes of the sensor's view mode, when it shows them. */
    val secondaryText: String?,
    val tertiaryText: String?,
    val isMmol: Boolean,
    val viewMode: Int,
    /**
     * The display arrow's rate in mg/dL per minute, the one the dashboard draws; NaN
     * without one. The alert engine keeps its own rate and it is never drawn.
     */
    val rate: Float,
    /** [TrendArrowAngle.rotationDegrees] of [rate]; NaN without a rate. */
    val arrowDegrees: Float,
    val index: Int,
    val freshness: Freshness,
    /** The sensor's reading interval the deadlines below are counted from. */
    val intervalMillis: Long,
    /** When the next reading is due; 0 without a reading. */
    val nextExpectedAtMillis: Long,
    /** When the reading turns [Freshness.STALE]: the next one was due and did not come. */
    val staleAtMillis: Long,
    /** When the value is taken down altogether ([Freshness.NO_VALUE]). */
    val noValueAtMillis: Long,
    /**
     * When the newest reading was taken, which the deadlines above count from; 0 with none.
     * The same as [timeMillis] unless the display shows an earlier point, as smoothing that
     * collapses readings into chunks does.
     */
    val lastReadingMillis: Long,
    /**
     * The other sensors the device shows beside this one, in their order, each under the
     * same stale rule. Its moments wake the clock too and are published, so a surface that
     * shows those sensors redraws when one goes stale or is taken down.
     */
    val otherSensors: List<OtherSensor> = emptyList(),
    val origin: Origin,
    val reason: Reason,
    val revision: Long,
) {
    enum class Freshness {
        /** The newest reading, while the next one is not yet overdue. */
        FRESH,

        /** The next reading was due and did not come: the value is shown dimmed, without arrow. */
        STALE,

        /** Too old to show a value at all. */
        NO_VALUE,

        /** A sensor, but no reading from it yet. */
        AWAITING,

        /** No sensor to show. */
        NO_SENSOR,
    }

    /** One of [otherSensors]: its freshness, and when it goes stale and is taken down. */
    data class OtherSensor(
        val sensorId: String,
        val freshness: Freshness,
        val staleAtMillis: Long,
        val noValueAtMillis: Long,
    )

    /** Where the reading came from, as the path that accepted it said. */
    enum class Origin {
        /** Read by this device. */
        LOCAL,

        /** Received from the other device (phone or watch). */
        PEER,

        /** A late reading filled in behind newer ones. */
        BACKFILL,

        /** Followed from a remote source. */
        FOLLOWER,

        /** Found in storage, with no accepting path naming it. */
        STORED,
    }

    /** Why it was published. Only [NEW_READING] is a new reading. */
    enum class Reason {
        /** The first reading this process published. */
        STARTUP,

        /** A newer reading than the one shown before. */
        NEW_READING,

        /**
         * The same reading, with something drawn changed: stored data under it (a backfill, a
         * calibration), or the path that read it handing it over after its stored row.
         */
        BACKFILL,

        /** A deadline passed: the same reading went stale or was taken down. */
        STALE_TICK,

        /** A display setting changed: unit, view mode, sensor. */
        SETTINGS,
    }

    /** True while the value is shown, dimmed or not. */
    val showsValue: Boolean
        get() = (freshness == Freshness.FRESH || freshness == Freshness.STALE) && valueText.isNotBlank()

    /** True while the arrow is shown: only a fresh reading has a direction worth drawing. */
    val showsArrow: Boolean get() = freshness == Freshness.FRESH && rate.isFinite()

    /** The value is shown, but the next reading is overdue. */
    val isDimmed: Boolean get() = freshness == Freshness.STALE

    /** The text a surface shows: the value while it is shown, else [NO_VALUE_TEXT]. */
    val shownText: String get() = if (showsValue) valueText else NO_VALUE_TEXT

    /** The arrow a surface draws: [rate] while [showsArrow], else NaN. */
    val shownRate: Float get() = if (showsArrow) rate else Float.NaN

    /** True when [other] draws exactly the same: everything but the revision, reason and origin. */
    fun sameDisplayAs(other: DisplayedReading): Boolean =
        this == other.copy(origin = origin, reason = reason, revision = revision)

    companion object {
        /** What every surface shows where there is no value. */
        const val NO_VALUE_TEXT = "---"

        /** Before the first publish. */
        @JvmField
        val NONE = DisplayedReading(
            sensorId = null,
            timeMillis = 0L,
            mgdl = Float.NaN,
            value = Float.NaN,
            valueText = "",
            secondaryText = null,
            tertiaryText = null,
            isMmol = false,
            viewMode = 0,
            rate = Float.NaN,
            arrowDegrees = Float.NaN,
            index = 0,
            freshness = Freshness.AWAITING,
            intervalMillis = ReadingCadence.DEFAULT_INTERVAL_MS,
            nextExpectedAtMillis = 0L,
            staleAtMillis = 0L,
            noValueAtMillis = 0L,
            lastReadingMillis = 0L,
            origin = Origin.STORED,
            reason = Reason.STARTUP,
            revision = 0L,
        )
    }
}

/**
 * When a reading stops being current (owner's rule): it is stale once the next reading was
 * due and did not come, counted from the sensor's own interval, and its value is taken down
 * [NO_VALUE_AFTER_MS] after it was taken.
 *
 * A fixed 330 s let a one-minute sensor miss five readings and still look current, and put
 * a five-minute sensor's value down 30 s after its next reading was due.
 */
object ReadingCadence {
    /**
     * Slack after the due time before a reading counts as missed. Readings arrive a few
     * seconds after their slot (a session takes 1-3 s, a re-ask 1-2 s more, a background
     * connect up to ~13 s, the other device's push a few seconds after its session); a
     * shorter grace dimmed the value on ordinary jitter.
     */
    const val GRACE_MS = 20_000L

    /** After this the value is not shown at all. */
    const val NO_VALUE_AFTER_MS = 30L * 60_000L

    /** Used when neither the driver nor the readings tell. */
    const val DEFAULT_INTERVAL_MS = 5L * 60_000L

    /** Bounds of an interval measured from the readings. */
    const val MIN_INTERVAL_MS = 60_000L
    const val MAX_INTERVAL_MS = 15L * 60_000L

    /** Closer than this, two times are one reading stored twice, not a gap. */
    private const val SAME_READING_MS = 30_000L

    /** A dozen gaps pin the median; more history does not improve it. */
    private const val MEDIAN_GAPS = 12

    /**
     * The sensor's reading interval: [declaredMillis] when the driver states one, else the
     * median of the recent gaps between [readingTimes] (any order), bounded to
     * [MIN_INTERVAL_MS]..[MAX_INTERVAL_MS], else [DEFAULT_INTERVAL_MS]. Median, not mean:
     * one disconnect gap must not stretch it.
     */
    @JvmStatic
    fun intervalMillis(declaredMillis: Long, readingTimes: Collection<Long>): Long {
        if (declaredMillis > 0L) return declaredMillis
        return medianGapMillis(readingTimes)?.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
            ?: DEFAULT_INTERVAL_MS
    }

    internal fun medianGapMillis(readingTimes: Collection<Long>): Long? {
        val newestFirst = readingTimes.filter { it > 0L }.sortedDescending()
        val gaps = ArrayList<Long>(MEDIAN_GAPS)
        var later = newestFirst.firstOrNull() ?: return null
        for (index in 1 until newestFirst.size) {
            val gap = later - newestFirst[index]
            if (gap < SAME_READING_MS) continue
            gaps.add(gap)
            later = newestFirst[index]
            if (gaps.size >= MEDIAN_GAPS) break
        }
        if (gaps.isEmpty()) return null
        gaps.sort()
        return gaps[(gaps.size - 1) / 2]
    }

    /** When the reading after one taken at [timeMillis] is due. */
    @JvmStatic
    fun nextExpectedAt(timeMillis: Long, intervalMillis: Long): Long = timeMillis + intervalMillis

    /** When a reading taken at [timeMillis] turns stale. */
    @JvmStatic
    fun staleAt(timeMillis: Long, intervalMillis: Long): Long = timeMillis + intervalMillis + GRACE_MS

    /** When a reading taken at [timeMillis] is taken down. */
    @JvmStatic
    fun noValueAt(timeMillis: Long): Long = timeMillis + NO_VALUE_AFTER_MS

    /**
     * The freshness of the newest reading, taken at [timeMillis] (0 for none), at [nowMillis].
     * The kinds and their order are [DisplayDataState]'s, with STALE split at the value's
     * removal.
     */
    @JvmStatic
    fun freshness(
        sensorPresent: Boolean,
        timeMillis: Long,
        intervalMillis: Long,
        nowMillis: Long,
    ): DisplayedReading.Freshness = when {
        !sensorPresent -> DisplayedReading.Freshness.NO_SENSOR
        timeMillis <= 0L -> DisplayedReading.Freshness.AWAITING
        nowMillis >= noValueAt(timeMillis) -> DisplayedReading.Freshness.NO_VALUE
        nowMillis >= staleAt(timeMillis, intervalMillis) -> DisplayedReading.Freshness.STALE
        else -> DisplayedReading.Freshness.FRESH
    }
}
