package tk.glucodata

/**
 * How long after a reading the loss alarm goes off ([SuperGlucoseAlarms.setagealarm]).
 *
 * While phone and watch both read a sensor, the watch pushes the phone none of
 * its readings, the phone pushes the watch only those the watch has not said it
 * read itself, and a device that missed one asks the other for it from this alarm.
 * At the sensor's own loss point (a G7: reading + 380 s) that came after the
 * screens had called the value stale (reading + 330 s, Notify.glucosetimeout),
 * so a watch that missed a reading showed "No value" for nearly a minute while
 * the phone had it. While both read, the alarm goes off ahead of the stale
 * point instead. A reading that arrives in time re-arms it, as before, so it
 * only ever goes off for a reading that was missed.
 */
object LossAlarmAhead {
    /**
     * The loss alarm's delay after a reading while both devices read the
     * sensor. LossOfSensorAlarm adds 20 s, which puts it ten seconds before
     * the stale point; a live reading of a five-minute sensor has arrived by
     * then, a few seconds after it was taken.
     */
    const val BOTH_READ_SHOWTIME_MS = 300_000L

    /** The delay to arm the loss alarm with: [showtimeMs], the sensor's own, or less while [bothRead]. */
    @JvmStatic
    fun showtime(showtimeMs: Long, bothRead: Boolean): Long =
        if (bothRead) minOf(showtimeMs, BOTH_READ_SHOWTIME_MS) else showtimeMs
}
