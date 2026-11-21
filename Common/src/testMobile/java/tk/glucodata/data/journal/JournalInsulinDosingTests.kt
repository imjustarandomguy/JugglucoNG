package tk.glucodata.data.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JournalInsulinDosingTests {
    @Test
    fun anUnusableStepIsNoStep() {
        assertNull(JournalInsulinDosing.sanitizeStep(0f))
        assertNull(JournalInsulinDosing.sanitizeStep(-0.5f))
        assertNull(JournalInsulinDosing.sanitizeStep(Float.NaN))
        assertNull(JournalInsulinDosing.sanitizeStep(20f))
        assertNull(JournalInsulinDosing.sanitizeStep(null))
        assertEquals(1f, JournalInsulinDosing.sanitizeStep(1f))
    }

    @Test
    fun noStepIsStoredAsZero() {
        assertEquals(0f, JournalInsulinDosing.storedStep(null))
        assertEquals(0f, JournalInsulinDosing.storedStep(-1f))
        assertEquals(0.5f, JournalInsulinDosing.storedStep(0.5f))
        assertNull(JournalInsulinDosing.sanitizeStep(JournalInsulinDosing.storedStep(null)))
    }

    @Test
    fun aDefaultDoseIsPositiveOrNothing() {
        assertNull(JournalInsulinDosing.sanitizeDefaultDose(0f))
        assertNull(JournalInsulinDosing.sanitizeDefaultDose(-2f))
        assertNull(JournalInsulinDosing.sanitizeDefaultDose(null))
        assertEquals(25f, JournalInsulinDosing.sanitizeDefaultDose(25f))
    }

    @Test
    fun reminderTimesRoundTripSortedAndWithoutDuplicates() {
        val stored = JournalInsulinDosing.encodeReminderTimes(listOf(1290, 480, 1290, 1440, -5))
        assertEquals("480,1290", stored)
        assertEquals(listOf(480, 1290), JournalInsulinDosing.decodeReminderTimes(stored))
        assertEquals(emptyList<Int>(), JournalInsulinDosing.decodeReminderTimes(""))
        assertEquals(emptyList<Int>(), JournalInsulinDosing.decodeReminderTimes(null))
        assertEquals(listOf(60), JournalInsulinDosing.decodeReminderTimes(" 60 ,x,"))
    }

    @Test
    fun onlyLongActingInsulinKeepsReminders() {
        assertEquals(listOf(1260), JournalInsulinDosing.reminderTimesFor(countsTowardIob = false, listOf(1260)))
        assertEquals(emptyList<Int>(), JournalInsulinDosing.reminderTimesFor(countsTowardIob = true, listOf(1260)))
    }
}
