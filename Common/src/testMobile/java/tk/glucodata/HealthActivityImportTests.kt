package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.HealthActivityImportPolicy.ActivityContent
import tk.glucodata.HealthActivityImportPolicy.ExistingRow
import tk.glucodata.HealthActivityImportPolicy.ImportAction
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
}
