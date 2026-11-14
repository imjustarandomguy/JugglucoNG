package tk.glucodata

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.HealthActivityImportPolicy.ActivityContent
import tk.glucodata.HealthActivityImportPolicy.ExistingRow
import tk.glucodata.HealthActivityImportPolicy.ImportAction
import tk.glucodata.HealthActivityImportPolicy.ImportedRow
import tk.glucodata.HealthActivityImportPolicy.Interval
import tk.glucodata.HealthActivityImportPolicy.Memory
import tk.glucodata.HealthActivityImportPolicy.importAction

/**
 * The activity import writes a Health Connect record only when it is new or changed there: an
 * unchanged record is not rewritten (which would send it out again), a row the user edited keeps
 * the edit, and one the user deleted stays deleted.
 */
class HealthActivityImportTests {
    private val minute = 60_000L
    private val day = 24 * 60 * minute
    private val now = 1_786_794_604_000L

    private class MapStore : HealthActivityImportPolicy.Store {
        val longs = HashMap<String, Long>()
        val strings = HashMap<String, String>()
        override fun getLong(key: String): Long = longs[key] ?: 0L
        override fun putLong(key: String, value: Long) { longs[key] = value }
        override fun getString(key: String): String? = strings[key]
        override fun putString(key: String, value: String) { strings[key] = value }
    }

    private fun steps(count: Long, start: Long = now - 3 * 60 * minute) =
        ActivityContent.of(start, "Steps", "$count steps", count.toFloat(), 30, "moderate")

    /** The journal rows the import is concerned with, by name: what the import does with them. */
    private class Journal {
        val rows = HashMap<String, ExistingRow>()
        var writes = 0

        fun import(records: Map<String, ActivityContent>, memory: Memory, at: Long) {
            val before = memory.imported()
            for ((name, content) in records) {
                if (importAction(rows[name], content, name in before) == ImportAction.WRITE) {
                    rows[name] = ExistingRow(byImport = true, content = content)
                    writes++
                }
            }
            memory.remember(records.mapValues { at }, at)
            memory.lastRunMillis = at
        }
    }

    @Test
    fun anUnchangedRecordIsNotWrittenAgain() {
        val memory = Memory(MapStore())
        val journal = Journal()
        val records = mapOf("health_connect:steps:a" to steps(3_000), "health_connect:exercise:b" to steps(500))

        journal.import(records, memory, now)
        assertEquals(2, journal.writes)
        journal.import(records, memory, now + 20 * minute)
        journal.import(records, memory, now + 40 * minute)
        assertEquals(2, journal.writes)
    }

    @Test
    fun aRecordChangedInHealthConnectUpdatesItsRow() {
        val memory = Memory(MapStore())
        val journal = Journal()
        journal.import(mapOf("health_connect:steps:a" to steps(3_000), "health_connect:steps:c" to steps(800)), memory, now)

        journal.import(mapOf("health_connect:steps:a" to steps(3_400), "health_connect:steps:c" to steps(800)), memory, now + 20 * minute)

        assertEquals(3, journal.writes)
        assertEquals(steps(3_400), journal.rows.getValue("health_connect:steps:a").content)
    }

    @Test
    fun aRowTheUserEditedKeepsTheEditWhateverHealthConnectSays() {
        val edited = ExistingRow(byImport = false, content = steps(3_000).copy(title = "Walk to work"))

        assertEquals(ImportAction.EDITED_HERE, importAction(edited, steps(3_000), importedBefore = true))
        assertEquals(ImportAction.EDITED_HERE, importAction(edited, steps(3_400), importedBefore = true))
    }

    @Test
    fun aRowTheUserDeletedStaysDeleted() {
        val memory = Memory(MapStore())
        val journal = Journal()
        val records = mapOf("health_connect:steps:a" to steps(3_000))
        journal.import(records, memory, now)

        journal.rows.remove("health_connect:steps:a")
        journal.import(records, memory, now + 20 * minute)

        assertFalse(journal.rows.containsKey("health_connect:steps:a"))
        assertEquals(1, journal.writes)
        assertEquals(ImportAction.DELETED_HERE, importAction(null, steps(3_000), importedBefore = true))
        assertEquals(ImportAction.WRITE, importAction(null, steps(3_000), importedBefore = false))
    }

    @Test
    fun aRestartDoesNotImportAgainAtOnce() {
        val store = MapStore()
        Memory(store).lastRunMillis = now

        // A new process: nothing in memory but what the store kept.
        val afterRestart = Memory(store)
        assertEquals(now, afterRestart.lastRunMillis)
        assertFalse(HealthActivityImportPolicy.foregroundImportDue(now + 5 * minute, afterRestart.lastRunMillis))
        assertTrue(HealthActivityImportPolicy.foregroundImportDue(now + 15 * minute, afterRestart.lastRunMillis))
    }

    @Test
    fun whatWasImportedIsRememberedAcrossARestart() {
        val store = MapStore()
        Memory(store).remember(mapOf("health_connect:steps:a" to now, "health_connect:exercise:b" to now - day), now)

        assertEquals(
            mapOf("health_connect:steps:a" to now, "health_connect:exercise:b" to now - day),
            Memory(store).imported()
        )
    }

    @Test
    fun aRecordNoImportCanReadAnyMoreIsForgotten() {
        val memory = Memory(MapStore())
        memory.remember(mapOf("health_connect:steps:old" to now - 40 * day, "health_connect:steps:new" to now - 2 * day), now)

        assertEquals(setOf("health_connect:steps:new"), memory.imported().keys)
    }

    @Test
    fun contentIsComparedAsTheJournalStoresIt() {
        assertEquals(
            ActivityContent.of(now, "Run", null, null, 30, "light"),
            ActivityContent.of(now, " Run ", "  ", null, 30, "light")
        )
        assertEquals(
            ImportAction.UNCHANGED,
            importAction(
                ExistingRow(byImport = true, content = ActivityContent.of(now, "Run", "easy", null, 30, "light")),
                ActivityContent.of(now, "Run ", " easy", null, 30, "light"),
                importedBefore = true
            )
        )
    }

    /**
     * The journal behind [HealthActivityImportPolicy.Journal]: [writeWhere] and [deleteWhere] run
     * whole, as the database transaction does. [meanwhile] runs once, right after the import's
     * first plain read: what the user does in the journal while the import is under way.
     */
    private class TransactionalJournal : HealthActivityImportPolicy.Journal<ActivityContent> {
        data class Row(val id: Long, val byImport: Boolean, val content: ActivityContent, val updatedAt: Long)

        val rows = LinkedHashMap<String, Row>()
        var writes = 0
        var meanwhile: () -> Unit = {}
        private var clock = 0L
        private var nextId = 1L

        fun imported(name: String, content: ActivityContent) {
            rows[name] = Row(rows[name]?.id ?: nextId++, byImport = true, content = content, updatedAt = ++clock)
        }

        /** The user's edit: the row keeps its name and is the user's from then on. */
        fun userEdits(name: String, title: String) {
            val row = rows.getValue(name)
            rows[name] = row.copy(byImport = false, content = row.content.copy(title = title), updatedAt = ++clock)
        }

        private fun afterPlainRead() {
            val now = meanwhile
            meanwhile = {}
            now()
        }

        private fun Row.existing() = ExistingRow(byImport, content)

        private fun Map.Entry<String, Row>.asImportedRow() = value.let {
            ImportedRow(it.id, key, it.content.timestampMillis, it.content.durationMinutes, it.byImport, it.updatedAt)
        }

        override suspend fun rowsNamed(names: Collection<String>): Map<String, ExistingRow> =
            names.mapNotNull { name -> rows[name]?.let { name to it.existing() } }.toMap().also { afterPlainRead() }

        override suspend fun writeWhere(
            records: List<HealthActivityImportPolicy.Incoming<ActivityContent>>,
            writes: (HealthActivityImportPolicy.Incoming<ActivityContent>, ExistingRow?) -> Boolean,
        ) {
            for (record in records) {
                if (writes(record, rows[record.name]?.existing())) {
                    imported(record.name, record.record)
                    this.writes++
                }
            }
        }

        override suspend fun importedRows(): List<ImportedRow> =
            rows.entries.filter { it.value.byImport }.map { it.asImportedRow() }.also { afterPlainRead() }

        override suspend fun deleteWhere(rows: List<ImportedRow>, deletes: (ImportedRow, ImportedRow?) -> Boolean): Int =
            rows.count { read ->
                val now = this.rows.entries.firstOrNull { it.value.id == read.id }
                deletes(read, now?.asImportedRow()).also { if (it && now != null) this.rows.remove(now.key) }
            }
    }

    private fun incoming(vararg records: Pair<String, ActivityContent>) =
        records.map { (name, content) -> HealthActivityImportPolicy.Incoming(name, content, content) }

    @Test
    fun anEditMadeWhileTheImportRunsIsKept() = runBlocking {
        val journal = TransactionalJournal()
        journal.imported("health_connect:steps:a", steps(3_000))
        // Health Connect has more steps now, so the first read finds the record worth writing.
        journal.meanwhile = { journal.userEdits("health_connect:steps:a", "Walk to work") }

        val counts = HealthActivityImportPolicy.importRecords(
            journal, incoming("health_connect:steps:a" to steps(3_400)), setOf("health_connect:steps:a")
        )

        val row = journal.rows.getValue("health_connect:steps:a")
        assertFalse(row.byImport)
        assertEquals(steps(3_000).copy(title = "Walk to work"), row.content)
        assertEquals(0, journal.writes)
        assertEquals(mapOf(ImportAction.EDITED_HERE to 1), counts)
    }

    @Test
    fun aDeletionMadeWhileTheImportRunsStaysDeleted() = runBlocking {
        for (importedBefore in listOf(setOf("health_connect:steps:a"), emptySet())) {
            val journal = TransactionalJournal()
            journal.imported("health_connect:steps:a", steps(3_000))
            journal.meanwhile = { journal.rows.remove("health_connect:steps:a") }

            // Not yet in the list of imported records (an import before the list existed): the
            // row was there at the first read, so it was deleted here all the same.
            val counts = HealthActivityImportPolicy.importRecords(
                journal, incoming("health_connect:steps:a" to steps(3_400)), importedBefore
            )

            assertFalse(journal.rows.containsKey("health_connect:steps:a"))
            assertEquals(0, journal.writes)
            assertEquals(mapOf(ImportAction.DELETED_HERE to 1), counts)
        }
    }

    @Test
    fun aRecordChangedInHealthConnectIsStillWritten() = runBlocking {
        val journal = TransactionalJournal()
        journal.imported("health_connect:steps:a", steps(3_000))
        journal.imported("health_connect:steps:c", steps(800))
        // Something else edited meanwhile does not hold back the records it does not touch.
        journal.imported("health_connect:exercise:x", steps(500))
        journal.meanwhile = { journal.userEdits("health_connect:exercise:x", "Yoga") }

        val counts = HealthActivityImportPolicy.importRecords(
            journal,
            incoming(
                "health_connect:steps:a" to steps(3_400),
                "health_connect:steps:b" to steps(1_200),
                "health_connect:steps:c" to steps(800),
            ),
            setOf("health_connect:steps:a", "health_connect:steps:c", "health_connect:exercise:x"),
        )

        assertEquals(steps(3_400), journal.rows.getValue("health_connect:steps:a").content)
        assertEquals(steps(1_200), journal.rows.getValue("health_connect:steps:b").content)
        assertEquals(2, journal.writes)
        assertEquals(mapOf(ImportAction.WRITE to 2, ImportAction.UNCHANGED to 1), counts)
    }

    @Test
    fun theCleanUpLeavesARowEditedWhileItRuns() = runBlocking {
        val journal = TransactionalJournal()
        // Two step rows inside an exercise session: both double count it.
        journal.imported("health_connect:steps:a", steps(3_000))
        journal.imported("health_connect:steps:b", steps(2_000))
        journal.meanwhile = { journal.userEdits("health_connect:steps:a", "Walk to work") }
        val session = Interval(now - 4 * 60 * minute, now - 2 * 60 * minute)

        val removed = HealthActivityImportPolicy.removeStepsWithinSessions(journal, listOf(session), emptyMap())

        assertEquals(1, removed)
        assertFalse(journal.rows.containsKey("health_connect:steps:b"))
        val edited = journal.rows.getValue("health_connect:steps:a")
        assertFalse(edited.byImport)
        assertEquals("Walk to work", edited.content.title)
    }

    @Test
    fun theCleanUpLeavesARowThatChangedWhileItRuns() = runBlocking {
        val journal = TransactionalJournal()
        journal.imported("health_connect:steps:a", steps(3_000))
        journal.meanwhile = { journal.imported("health_connect:steps:a", steps(3_000, start = now - 3 * 60 * minute + minute)) }
        val session = Interval(now - 4 * 60 * minute, now - 2 * 60 * minute)

        assertEquals(0, HealthActivityImportPolicy.removeStepsWithinSessions(journal, listOf(session), emptyMap()))
        assertTrue(journal.rows.containsKey("health_connect:steps:a"))
        // As read, unchanged: the next run removes it.
        assertEquals(1, HealthActivityImportPolicy.removeStepsWithinSessions(journal, listOf(session), emptyMap()))
    }
}
