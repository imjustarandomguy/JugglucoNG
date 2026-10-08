package tk.glucodata

/**
 * The decisions behind the Health Connect glucose export, kept apart from the
 * androidx calls so they can be tested on the JVM.
 */
object HealthConnectExportPolicy {
    /** BloodGlucoseRecord refuses anything above 50 mmol/L, and one refusal fails the whole batch. */
    const val MAX_MGDL = 900

    /**
     * Every record carries the same version: a record sent again (after a cursor rewind or a
     * reset) replaces the copy Health Connect already holds under its clientRecordId.
     */
    const val RECORD_VERSION = 1L

    /** streamfromSensorptr packs time (seconds) | mg/dL << 32 | next position << 48. */
    @JvmStatic
    fun time(packed: Long): Long = packed and 0xFFFFFFFFL

    @JvmStatic
    fun mgdL(packed: Long): Int = ((packed ushr 32) and 0xFFFF).toInt()

    @JvmStatic
    fun nextPos(packed: Long): Int = ((packed ushr 48) and 0xFFFF).toInt()

    /**
     * Whether streamfromSensorptr found nothing from [pos] on. It returns a 0 time then,
     * which used to be exported as a 1970 reading of 0 mg/dL.
     */
    @JvmStatic
    fun searchEnded(packed: Long, pos: Int): Boolean = time(packed) == 0L || nextPos(packed) <= pos

    @JvmStatic
    fun isExportable(timeSec: Long, mgdL: Int): Boolean = timeSec > 0L && mgdL in 1..MAX_MGDL

    /** One reading of one sensor, whichever slot or export run it comes from. */
    @JvmStatic
    fun clientRecordId(serial: String, timeSec: Long): String = "ng:$serial:$timeSec"
}

/**
 * When a Health Connect feature may bring up the permission dialog. A reading never asks:
 * the dialog comes up when the user turns a switch on, or once per process while a switch
 * is on and its permission is missing.
 */
object HealthConnectPermissionPolicy {
    @JvmStatic
    fun shouldRequest(
        switchOn: Boolean,
        granted: Boolean,
        userTurnedOn: Boolean,
        askedThisProcess: Boolean,
    ): Boolean = switchOn && !granted && (userTurnedOn || !askedThisProcess)
}

/** The activity import's decisions: when it runs and which steps it keeps. */
object HealthActivityImportPolicy {
    const val FOREGROUND_INTERVAL_MILLIS = 15L * 60L * 1000L
    const val MIN_STEPS = 250L
    const val SOURCE_PREFIX = "health_connect:"
    const val STEPS_PREFIX = "health_connect:steps:"

    /** Guards against a page token that never runs out. */
    const val MAX_PAGES = 100

    data class Interval(val startMillis: Long, val endMillis: Long)

    /** A journal row the import wrote earlier. */
    data class ImportedRow(
        val id: Long,
        val sourceRecordId: String?,
        val startMillis: Long,
        val durationMinutes: Int?,
    )

    /** At most once per [FOREGROUND_INTERVAL_MILLIS]; [lastRunMillis] is 0 before the first run. */
    @JvmStatic
    fun foregroundImportDue(nowMillis: Long, lastRunMillis: Long): Boolean =
        lastRunMillis <= 0L || nowMillis < lastRunMillis || nowMillis - lastRunMillis >= FOREGROUND_INTERVAL_MILLIS

    /** Half-open: a step record that ends when a session starts does not overlap it. */
    fun overlaps(a: Interval, b: Interval): Boolean = a.startMillis < b.endMillis && b.startMillis < a.endMillis

    fun overlapsAny(interval: Interval, sessions: Collection<Interval>): Boolean = sessions.any { overlaps(interval, it) }

    /** Steps counted during an exercise session are the session's: importing both counts the activity twice. */
    fun importsSteps(count: Long, interval: Interval, sessions: Collection<Interval>): Boolean =
        count >= MIN_STEPS && !overlapsAny(interval, sessions)

    /**
     * The step rows an earlier import wrote that overlap an exercise session, so the double
     * count they make goes. Only rows named health_connect:steps: are ever chosen. A row's
     * interval is its Health Connect record's when that is still in [knownStepIntervals]
     * (by sourceRecordId), else what the row kept: its start and whole minutes.
     */
    fun stepRowsToRemove(
        rows: List<ImportedRow>,
        sessions: Collection<Interval>,
        knownStepIntervals: Map<String, Interval>,
    ): List<Long> = rows.filter { row ->
        val name = row.sourceRecordId ?: return@filter false
        if (!name.startsWith(STEPS_PREFIX)) return@filter false
        val interval = knownStepIntervals[name] ?: Interval(
            row.startMillis,
            row.startMillis + (row.durationMinutes ?: 1).coerceAtLeast(1) * 60_000L,
        )
        overlapsAny(interval, sessions)
    }.map { it.id }

    /** What an activity row says, as far as the import writes it. */
    data class ActivityContent(
        val timestampMillis: Long,
        val title: String,
        val note: String?,
        val amount: Float?,
        val durationMinutes: Int?,
        val intensity: String?,
    ) {
        companion object {
            /** As the journal stores it: title and note trimmed, a blank note none. */
            fun of(
                timestampMillis: Long,
                title: String,
                note: String?,
                amount: Float?,
                durationMinutes: Int?,
                intensity: String?,
            ) = ActivityContent(
                timestampMillis,
                title.trim(),
                note?.trim()?.takeIf { it.isNotEmpty() },
                amount,
                durationMinutes,
                intensity,
            )
        }
    }

    /** The row a record was imported to: [byImport] while it is still the import's, not the user's edit. */
    data class ExistingRow(val byImport: Boolean, val content: ActivityContent)

    enum class ImportAction {
        /** New, or changed in Health Connect since it was imported: written. */
        WRITE,
        /** Says what the row says already: not written again. */
        UNCHANGED,
        /** The user edited the row: the edit stays. */
        EDITED_HERE,
        /** The user deleted the row: it stays deleted. */
        DELETED_HERE,
    }

    /**
     * What becomes of one Health Connect record. Writing a record unchanged is not free: the
     * write marks the row changed, which sends it to Nightscout and LibreView again, and every
     * import reads two weeks of records. A row the user edited is theirs; one they deleted
     * ([importedBefore], but [existing] gone) is not brought back.
     */
    fun importAction(existing: ExistingRow?, incoming: ActivityContent, importedBefore: Boolean): ImportAction = when {
        existing == null -> if (importedBefore) ImportAction.DELETED_HERE else ImportAction.WRITE
        !existing.byImport -> ImportAction.EDITED_HERE
        existing.content == incoming -> ImportAction.UNCHANGED
        else -> ImportAction.WRITE
    }

    /** What the import keeps between runs, and between process starts. */
    interface Store {
        fun getLong(key: String): Long
        fun putLong(key: String, value: Long)
        fun getString(key: String): String?
        fun putString(key: String, value: String)
    }

    /**
     * When the import last finished, and the records it has imported: a record imported before
     * whose row is gone was deleted here. A record is remembered while an import can still read
     * it (the longest import reaches back 30 days), so the list stays bounded.
     */
    class Memory(private val store: Store) {
        var lastRunMillis: Long
            get() = store.getLong(LAST_RUN_KEY)
            set(value) = store.putLong(LAST_RUN_KEY, value)

        /** Each record imported, by its row's name, with when the record ended. */
        fun imported(): Map<String, Long> {
            val text = store.getString(IMPORTED_KEY) ?: return emptyMap()
            val imported = HashMap<String, Long>()
            for (line in text.lineSequence()) {
                val tab = line.lastIndexOf('\t')
                if (tab <= 0) continue
                val endMillis = line.substring(tab + 1).toLongOrNull() ?: continue
                imported[line.substring(0, tab)] = endMillis
            }
            return imported
        }

        /** Adds [records] (name to end time) and forgets those no import can read any more. */
        fun remember(records: Map<String, Long>, nowMillis: Long) {
            val kept = (imported() + records).filterValues { it >= nowMillis - REMEMBER_MILLIS }
            store.putString(IMPORTED_KEY, kept.entries.joinToString("\n") { (name, end) -> "$name\t$end" })
        }

        private companion object {
            const val LAST_RUN_KEY = "health_connect_activity_last_import"
            const val IMPORTED_KEY = "health_connect_activity_imported"
            const val REMEMBER_MILLIS = 31L * 24 * 60 * 60 * 1000
        }
    }

    /** Reads every page: [read] gets the page token (null first) and returns the page and the next token. */
    suspend fun <T> readAllPages(read: suspend (pageToken: String?) -> Pair<List<T>, String?>): List<T> {
        val all = ArrayList<T>()
        val seen = HashSet<String>()
        var token: String? = null
        repeat(MAX_PAGES) {
            val (records, next) = read(token)
            all += records
            if (next.isNullOrEmpty() || !seen.add(next)) return all
            token = next
        }
        return all
    }
}
