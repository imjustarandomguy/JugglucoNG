package tk.glucodata

/**
 * Which G7 the phone hands a watch that reads the sensor itself ("Direct
 * sensor on watch"), and when.
 *
 * Only the selected G7 used to be handed over. At a sensor change the old one
 * stays selected until it is removed, so the watch got the new one only then,
 * well after the phone had paired it, and by then a synced reading had made
 * the watch a plain record for it that its Dexcom driver could not use. So the
 * newest G7 goes, as soon as the phone has paired it, while its warmup still
 * keeps any reading from reaching the watch first.
 */
internal object DexcomFollow {
    /** A G7 record as Natives.dexHandoff exports it; [deviceName] is blank until the phone paired it. */
    data class Record(val name: String, val code: String, val deviceName: String, val start: Long)

    enum class Decision {
        /** Hand it over now. */
        HAND,

        /** This watch has it already, with this start. */
        HANDED,

        /** The phone has not paired it yet: the watch would pair alongside the phone's own pairing. */
        NOT_PAIRED,

        /** The watch was told to read another kind of sensor, and has never been handed a G7. */
        NOT_FOLLOWING,
    }

    /**
     * How far a handed record's start may move before the watch is handed it
     * again. Until the phone first hears from a new G7 its record starts at the
     * scan; from then on the start is worked out from the sensor's own clock
     * at every session of its warmup, a second either way. The watch keeps a
     * G7's start as handed, so the scan's time must not stay there.
     */
    const val START_TOLERANCE_SEC = 15L

    private const val HANDED_START_SEPARATOR = '@'

    /** [fields] as Natives.dexHandoff returns them, or null for anything but an exportable G7 record. */
    fun fromFields(fields: Array<out String?>?): Record? {
        if (fields == null || fields.size < 4) return null
        val name = fields[0]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val code = fields[1]?.takeIf { it.isNotEmpty() } ?: return null
        val start = fields[3]?.trim()?.toLongOrNull()?.takeIf { it > 0L } ?: return null
        return Record(name, code, fields[2]?.trim().orEmpty(), start)
    }

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
        if (newest.deviceName.isBlank()) return Decision.NOT_PAIRED
        return Decision.HAND
    }

    /**
     * The G7 a watch is handed when "Direct sensor on watch" or automatic
     * switching is turned on: what [decide] hands a watch never handed one, from
     * the [active] records, while the phone's selected sensor is a G7
     * ([selectedIsDexcom]). Not the selected record itself: after a sensor change
     * that can be the old G7, ended, or forgotten on the watch, and the watch
     * would pair with it again. Null when there is none to hand yet; the newest
     * follows once the phone has paired it.
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
