package tk.glucodata.data.journal

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalSaveTests {
    private val dose = JournalEntryInput(
        timestamp = 1_000L,
        type = JournalEntryType.INSULIN,
        title = "Tresiba",
        amount = 25f,
        insulinPresetId = 2L
    )
    private val meal = JournalEntryInput(
        timestamp = 1_000L,
        type = JournalEntryType.CARBS,
        title = "Food",
        amount = 45f
    )

    /**
     * A stand-in for the repository's one-transaction write: rows written by a failed call are
     * rolled back, as Room's transaction does.
     */
    private class AllOrNothingStore(private val failAt: Int? = null) {
        val rows = mutableListOf<JournalEntryInput>()
        var calls = 0

        fun writeAll(inputs: List<JournalEntryInput>): List<Long> {
            calls++
            val pending = mutableListOf<JournalEntryInput>()
            inputs.forEachIndexed { index, input ->
                if (index == failAt) throw IOException("disk full")
                pending += input
            }
            val firstId = rows.size + 1L
            rows += pending
            return pending.indices.map { firstId + it }
        }
    }

    @Test
    fun aStoredSaveTakesDownItsRemindersAfterTheWriteAndHandsBackTheRows() = runBlocking {
        val store = AllOrNothingStore()
        val events = mutableListOf<String>()
        val outcome = JournalSave.commit(
            listOf(dose, meal),
            writeAll = { inputs -> store.writeAll(inputs).also { events += "write" } },
            afterCommit = { committed ->
                assertEquals(listOf(dose, meal), committed)
                events += "reminders"
            }
        )
        assertEquals(JournalSave.Outcome.Saved(listOf(1L, 2L)), outcome)
        assertEquals(listOf("write", "reminders"), events)
        // A dose and its meal go in one write, so they are stored together or not at all.
        assertEquals(1, store.calls)
    }

    @Test
    fun aFailedSaveStoresNothingAndLeavesTheReminder() = runBlocking {
        var remindersTakenDown = false
        val outcome = JournalSave.commit(
            listOf(dose),
            writeAll = { throw IOException("database closed") },
            afterCommit = { remindersTakenDown = true }
        )
        assertTrue(outcome is JournalSave.Outcome.Failed)
        assertTrue((outcome as JournalSave.Outcome.Failed).error is IOException)
        assertEquals(false, remindersTakenDown)
    }

    @Test
    fun aMealThatFailsAfterItsDoseLeavesNeitherStored() = runBlocking {
        val store = AllOrNothingStore(failAt = 1)
        var remindersTakenDown = false
        val outcome = JournalSave.commit(
            listOf(dose, meal),
            writeAll = store::writeAll,
            afterCommit = { remindersTakenDown = true }
        )
        assertTrue(outcome is JournalSave.Outcome.Failed)
        assertEquals(emptyList<JournalEntryInput>(), store.rows)
        assertEquals(false, remindersTakenDown)
    }

    @Test
    fun aRetryAfterAFailureStoresTheSameEntriesOnce() = runBlocking {
        val failing = AllOrNothingStore(failAt = 1)
        val first = JournalSave.commit(listOf(dose, meal), failing::writeAll) {}
        assertTrue(first is JournalSave.Outcome.Failed)
        val working = AllOrNothingStore()
        val retry = JournalSave.commit(listOf(dose, meal), working::writeAll) {}
        assertEquals(JournalSave.Outcome.Saved(listOf(1L, 2L)), retry)
        assertEquals(listOf(dose, meal), working.rows)
    }

    @Test
    fun aReminderThatCannotBeTakenDownDoesNotUndoAStoredSave() = runBlocking {
        val store = AllOrNothingStore()
        val outcome = JournalSave.commit(
            listOf(dose),
            writeAll = store::writeAll,
            afterCommit = { throw SecurityException("no notification access") }
        )
        // Undo takes back exactly the rows stored.
        assertEquals(JournalSave.Outcome.Saved(listOf(1L)), outcome)
        assertEquals(listOf(dose), store.rows)
    }
}
