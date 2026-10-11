package tk.glucodata

/**
 * Which device is reading a sensor itself, and since when.
 *
 * Phone and watch can both read one G7, each over its own channel, and a device
 * that misses readings has them synced from the other. Its chart, and every
 * "Connected", then look the same whether it reads the sensor or not. The claim
 * state that used to stand in for this never expires while both read: it said
 * "Watch connected to sensor" through three hours of the watch not reading at
 * all (2026-10-06). What cannot lie about it is when each device last took a
 * reading straight from the sensor, so that is all the screens go on.
 */

/** How long without a reading of its own before a device counts as not reading the sensor. */
const val DIRECT_READING_FRESH_MS = 15L * 60L * 1000L

/** What one device last read straight from the sensor, as known on this one. */
data class DirectReadingFact(
    /** False when nothing says: no report heard yet, or a build that does not report it. */
    val known: Boolean,
    /** When, on this device's clock; 0 when known but it never read the sensor itself. */
    val lastMs: Long,
) {
    fun isReading(nowMs: Long): Boolean =
        known && lastMs > 0L && nowMs - lastMs <= DIRECT_READING_FRESH_MS

    /** Known and with a time to it. */
    val timed: Boolean get() = known && lastMs > 0L

    companion object {
        @JvmField
        val UNKNOWN = DirectReadingFact(known = false, lastMs = 0L)

        @JvmStatic
        fun at(lastMs: Long) = DirectReadingFact(known = true, lastMs = lastMs.coerceAtLeast(0L))
    }
}

/** The sensor card's status for a sensor both devices read. */
enum class DirectReadingSummary { PHONE_AND_WATCH, PHONE, WATCH, NEITHER }

enum class DirectReadingLineKind {
    /** Reading the sensor; [DirectReadingLine.lastMs] is the newest reading. */
    READING,

    /** Not reading it, and neither is the other device. */
    NOT_READING,

    /** Not reading it; the other device is, and this one gets its values. */
    FROM_OTHER,

    /** Nothing heard from that device about it. */
    UNKNOWN,
}

/** One device's line; [lastMs] is 0 when there is no time to give. */
data class DirectReadingLine(val kind: DirectReadingLineKind, val lastMs: Long)

/** Everything the screens show about who reads one sensor. */
data class DirectReadingView(
    val phone: DirectReadingFact,
    val watch: DirectReadingFact,
    /** "Direct sensor on watch" is on for this sensor. */
    val directOnWatch: Boolean,
    /** The sensor serves both devices at once, each over its own channel (a G7). */
    val readsAlongside: Boolean,
    /** Since when the watch's fact has had no time to it, while direct is on; 0 when it has one. */
    val watchUntimedSinceMs: Long,
    val nowMs: Long,
) {
    val summary: DirectReadingSummary get() = DirectReadingStatus.summary(phone, watch, nowMs)
    val phoneLine: DirectReadingLine get() = DirectReadingStatus.line(phone, watch, nowMs)
    val watchLine: DirectReadingLine get() = DirectReadingStatus.line(watch, phone, nowMs)
    val watchAlert: Boolean
        get() = DirectReadingStatus.watchAlert(directOnWatch, watch, watchUntimedSinceMs, nowMs)
    val readByBoth: Boolean
        get() = DirectReadingStatus.readByBoth(readsAlongside, directOnWatch, watch, nowMs)
}

object DirectReadingStatus {
    /**
     * The least time between two reports sent only because there is a newer
     * reading to tell. A G7 reads every five minutes, so this lets each reading
     * through and nothing more; a one-minute sensor reports every few minutes,
     * well inside [DIRECT_READING_FRESH_MS].
     */
    const val REPORT_MIN_INTERVAL_MS = 4L * 60L * 1000L

    /**
     * Two readings of one sensor closer than this are the same reading. No
     * sensor reads more often than once a minute, and a G7 asked a moment
     * before its next measurement answers with the one before, stamped up to a
     * second off from when it was first read.
     */
    const val SAME_READING_MS = 30_000L

    /**
     * When a reading straight from the sensor counts as taken: at the reading's
     * own time, never ahead of this device's clock; now when it has no time.
     * The time it arrived would make a session that only got the reading
     * before look like a fresh one.
     */
    @JvmStatic
    fun directReadingTime(sampleMs: Long, nowMs: Long): Long =
        if (sampleMs > 0L) minOf(sampleMs, nowMs) else nowMs

    /**
     * A reading taken longer than this before it arrived is one the sensor had
     * kept, not a live one: a live reading arrives within seconds of being
     * taken, the reading before a G7's next measurement about five minutes on.
     */
    const val LATE_READING_MS = 60_000L

    /**
     * Whether a reading taken at [readingMs] and arriving at [nowMs] says this
     * device is reading the sensor now. Not when it is no newer than
     * [lastDirectMs], the newest this device read itself: the sensor served
     * that one again, or an older one. Nor when it arrived late and this device
     * already had it from the other one ([lastSyncedMs], the newest the other
     * filled in): a G7 asked a moment early answers with the reading before,
     * and that one may be the reading this device missed and was given.
     *
     * A live reading the other device happened to deliver a moment earlier
     * still counts: this device read it too.
     */
    @JvmStatic
    @JvmOverloads
    fun isNewDirectReading(
        readingMs: Long,
        nowMs: Long,
        lastDirectMs: Long,
        lastSyncedMs: Long = 0L,
    ): Boolean {
        if (readingMs <= 0L) return false
        if (lastDirectMs > 0L && readingMs - lastDirectMs < SAME_READING_MS) return false
        val late = nowMs - readingMs > LATE_READING_MS
        return !(late && lastSyncedMs > 0L && readingMs - lastSyncedMs < SAME_READING_MS)
    }

    @JvmStatic
    fun summary(phone: DirectReadingFact, watch: DirectReadingFact, nowMs: Long): DirectReadingSummary {
        val phoneReads = phone.isReading(nowMs)
        val watchReads = watch.isReading(nowMs)
        return when {
            phoneReads && watchReads -> DirectReadingSummary.PHONE_AND_WATCH
            phoneReads -> DirectReadingSummary.PHONE
            watchReads -> DirectReadingSummary.WATCH
            else -> DirectReadingSummary.NEITHER
        }
    }

    /** [self]'s line; [other] decides whether it is getting its values from there. */
    @JvmStatic
    fun line(self: DirectReadingFact, other: DirectReadingFact, nowMs: Long): DirectReadingLine = when {
        !self.known -> DirectReadingLine(DirectReadingLineKind.UNKNOWN, 0L)
        self.isReading(nowMs) -> DirectReadingLine(DirectReadingLineKind.READING, self.lastMs)
        other.isReading(nowMs) -> DirectReadingLine(DirectReadingLineKind.FROM_OTHER, self.lastMs)
        else -> DirectReadingLine(DirectReadingLineKind.NOT_READING, self.lastMs)
    }

    /**
     * Whether the watch line is shown as an error: the watch was told to read
     * the sensor and has not, for longer than [DIRECT_READING_FRESH_MS] — or
     * nothing has said when it last did for that long ([untimedSinceMs]).
     */
    @JvmStatic
    fun watchAlert(
        directOnWatch: Boolean,
        watch: DirectReadingFact,
        untimedSinceMs: Long,
        nowMs: Long,
    ): Boolean {
        if (!directOnWatch) return false
        if (watch.timed) return nowMs - watch.lastMs > DIRECT_READING_FRESH_MS
        return untimedSinceMs > 0L && nowMs - untimedSinceMs > DIRECT_READING_FRESH_MS
    }

    /**
     * Whether the screens should say who reads the sensor: it serves both
     * devices at once, and the watch was told to read it or still does.
     * Anything else keeps the status it always had.
     */
    @JvmStatic
    fun readByBoth(
        readsAlongside: Boolean,
        directOnWatch: Boolean,
        watch: DirectReadingFact,
        nowMs: Long,
    ): Boolean = readsAlongside && (directOnWatch || watch.isReading(nowMs))

    /**
     * The untimed clock for [watchAlert]: when the watch's fact first had no
     * time to it while direct was on. Restarts whenever direct is switched on,
     * so the watch gets its full window to make its first reading.
     */
    @JvmStatic
    fun untimedSince(
        directOnWatch: Boolean,
        watch: DirectReadingFact,
        previousSinceMs: Long?,
        nowMs: Long,
    ): Long? = when {
        !directOnWatch || watch.timed -> null
        else -> previousSinceMs ?: nowMs
    }

    // ------------------------------------------------------------------ wire

    /**
     * The age of a direct reading as it goes on the wire, or -1 for none.
     * An age rather than a time, so the two devices' clocks are never compared.
     */
    @JvmStatic
    fun wireAge(lastMs: Long, nowMs: Long): Long =
        if (lastMs <= 0L) -1L else (nowMs - lastMs).coerceAtLeast(0L)

    /**
     * The peer's direct reading on this device's clock. [ageMs] is null when the
     * report had no such field (an older build): that is unknown, not "never".
     */
    @JvmStatic
    fun fromWire(ageMs: Long?, receivedAtMs: Long): DirectReadingFact = when {
        ageMs == null -> DirectReadingFact.UNKNOWN
        ageMs < 0L -> DirectReadingFact.at(0L)
        else -> DirectReadingFact.at((receivedAtMs - ageMs).coerceAtLeast(1L))
    }

    /**
     * Whether a report is due only because there is a newer direct reading to
     * tell: while "Direct sensor on watch" is on, each device's newest reading
     * has to reach the other within a reading or two, or the other's screens
     * would call it not reading. Ownership changes and the heartbeat are sent
     * as before; this adds nothing when direct is off.
     *
     * A reading the other side does not know this device to be reading goes at
     * once: its first, or the first after a pause. The report that said it now
     * holds the sensor often went out a moment before that reading was noted,
     * and [REPORT_MIN_INTERVAL_MS] then held the news until the next reading,
     * so the phone said the watch was getting its values from the phone.
     */
    @JvmStatic
    fun reportDue(
        directOnWatch: Boolean,
        directReadingMs: Long,
        lastReportedDirectMs: Long,
        lastReportedAtMs: Long,
        nowMs: Long,
    ): Boolean {
        if (!directOnWatch || directReadingMs <= lastReportedDirectMs) return false
        val peerSeesItReading = lastReportedDirectMs > 0L &&
            nowMs - lastReportedDirectMs <= DIRECT_READING_FRESH_MS
        return !peerSeesItReading || nowMs - lastReportedAtMs >= REPORT_MIN_INTERVAL_MS
    }
}
