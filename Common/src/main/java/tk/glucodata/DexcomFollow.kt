package tk.glucodata

/**
 * Which G7 the phone hands a watch that reads the sensor itself ("Direct
 * sensor on watch"), and when.
 *
 * Only the selected G7 used to be handed over. At a sensor change the old one
 * stays selected until it is removed, so the watch got the new one only then,
 * well after the phone had started reading it. So the newest G7 goes, as soon
 * as the phone can send its setup ([DexcomWatchSetup]): from its first reading
 * here, which fixes the sensor's start and the slots both devices count.
 */
internal object DexcomFollow {
    /**
     * A G7 this phone reads: its name, its start in seconds, and whether its
     * setup can go to a watch yet ([setupReady]: not before its first reading).
     */
    data class Record(val name: String, val start: Long, val setupReady: Boolean)

    enum class Decision {
        /** Hand it over now. */
        HAND,

        /** This watch has it already, with this start. */
        HANDED,

        /** The phone has no reading of it yet, so no setup to send. */
        NOT_READY,

        /** The watch was told to read another kind of sensor, and has never been handed a G7. */
        NOT_FOLLOWING,
    }

    /**
     * How far a handed record's start may move before the watch is handed it
     * again. Each device works a G7's start out from the sensor's own clock,
     * a second either way.
     */
    const val START_TOLERANCE_SEC = 15L

    private const val HANDED_START_SEPARATOR = '@'

    /** The G7 that started last: at a sensor change, the new one. */
    fun newest(records: Iterable<Record>): Record? = records.maxByOrNull { it.start }

    /** What is remembered for a watch once it was handed [record]. */
    fun handedValue(record: Record): String = record.name + HANDED_START_SEPARATOR + record.start

    /**
     * Whether a watch that was last handed [handed] (a [handedValue], or a bare
     * name as remembered before the start was) gets [newest] now.
     * [assignedIsDexcom] says whether the sensor the watch was told to read is
     * a G7; it is asked only for a watch never handed one.
     */
    fun decide(handed: String?, assignedIsDexcom: () -> Boolean, newest: Record): Decision {
        if (handed.isNullOrBlank() && !assignedIsDexcom()) return Decision.NOT_FOLLOWING
        if (isHanded(handed, newest)) return Decision.HANDED
        if (!newest.setupReady) return Decision.NOT_READY
        return Decision.HAND
    }

    /**
     * The G7 a watch is handed when "Direct sensor on watch" or automatic
     * switching is turned on: what [decide] hands a watch never handed one, from
     * the [active] records, while the phone's selected sensor is a G7
     * ([selectedIsDexcom]). Not the selected record itself: after a sensor change
     * that can be the old G7, ended, or forgotten on the watch, and the watch
     * would pair with it again. Null when there is none to hand yet; the newest
     * follows once the phone can send its setup.
     */
    fun onSwitchOn(active: Iterable<Record>, selectedIsDexcom: () -> Boolean): Record? {
        val newest = newest(active) ?: return null
        return newest.takeIf { decide(null, selectedIsDexcom, it) == Decision.HAND }
    }

    private fun isHanded(handed: String?, record: Record): Boolean {
        val value = handed?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val separator = value.lastIndexOf(HANDED_START_SEPARATOR)
        val name = if (separator < 0) value else value.substring(0, separator)
        if (!name.equals(record.name, ignoreCase = true)) return false
        // A bare name: handed before starts were remembered, keep it.
        if (separator < 0) return true
        val start = value.substring(separator + 1).toLongOrNull() ?: return false
        return kotlin.math.abs(start - record.start) <= START_TOLERANCE_SEC
    }
}
