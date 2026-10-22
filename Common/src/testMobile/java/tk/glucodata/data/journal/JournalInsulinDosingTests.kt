package tk.glucodata.data.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JournalInsulinDosingTests {
    private val plain: (Float) -> String = { value ->
        if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()
    }

    @Test
    fun aStepOnTheGridMovesAWholeStep() {
        assertEquals(7f, JournalInsulinDosing.stepped(6f, +1, 1f))
        assertEquals(5f, JournalInsulinDosing.stepped(6f, -1, 1f))
        assertEquals(6.5f, JournalInsulinDosing.stepped(6f, +1, 0.5f))
        assertEquals(8f, JournalInsulinDosing.stepped(6f, +1, 2f))
    }

    @Test
    fun aValueBetweenGridPointsMovesToTheNeighbourOnThatSide() {
        assertEquals(6f, JournalInsulinDosing.stepped(5.3f, +1, 1f))
        assertEquals(5f, JournalInsulinDosing.stepped(5.3f, -1, 1f))
        assertEquals(5.5f, JournalInsulinDosing.stepped(5.3f, +1, 0.5f))
        assertEquals(4f, JournalInsulinDosing.stepped(5f, -1, 2f))
        assertEquals(6f, JournalInsulinDosing.stepped(5f, +1, 2f))
    }

    @Test
    fun tenthStepsDoNotAccumulateFloatNoise() {
        var value: Float? = null
        repeat(3) { value = JournalInsulinDosing.stepped(value, +1, 0.1f) }
        assertEquals(0.3f, value!!, 0f)
        assertEquals(0.2f, JournalInsulinDosing.stepped(0.30000001f, -1, 0.1f), 0f)
    }

    @Test
    fun anEmptyAmountStartsFromZeroAndNeverGoesBelowIt() {
        assertEquals(1f, JournalInsulinDosing.stepped(null, +1, 1f))
        assertEquals(0f, JournalInsulinDosing.stepped(null, -1, 1f))
        assertEquals(0f, JournalInsulinDosing.stepped(0.4f, -1, 1f))
        assertEquals(0f, JournalInsulinDosing.stepped(0f, -1, 0.5f))
    }

    @Test
    fun anUnusableStepFallsBackToHalfUnits() {
        assertEquals(0.5f, JournalInsulinDosing.sanitizeStep(0f))
        assertEquals(0.5f, JournalInsulinDosing.sanitizeStep(-0.5f))
        assertEquals(0.5f, JournalInsulinDosing.sanitizeStep(Float.NaN))
        assertEquals(0.5f, JournalInsulinDosing.sanitizeStep(null))
        assertEquals(1f, JournalInsulinDosing.sanitizeStep(1f))
        assertEquals(6.5f, JournalInsulinDosing.stepped(6f, +1, 0f))
    }

    @Test
    fun roundsToTheNearestMultipleOfTheStep() {
        assertEquals(6f, JournalInsulinDosing.roundToStep(6.4f, 1f))
        assertEquals(6.5f, JournalInsulinDosing.roundToStep(6.4f, 0.5f))
        assertEquals(6f, JournalInsulinDosing.roundToStep(6.9f, 2f))
        assertEquals(6.4f, JournalInsulinDosing.roundToStep(6.42f, 0.1f))
    }

    @Test
    fun aDefaultDoseIsPositiveOrNothing() {
        assertNull(JournalInsulinDosing.sanitizeDefaultDose(0f))
        assertNull(JournalInsulinDosing.sanitizeDefaultDose(-2f))
        assertNull(JournalInsulinDosing.sanitizeDefaultDose(null))
        assertEquals(25f, JournalInsulinDosing.sanitizeDefaultDose(25f))
    }

    @Test
    fun choosingAnInsulinFillsItsDefaultIntoAnEmptyAmount() {
        assertEquals("25", JournalInsulinDosing.amountAfterPresetChange("", null, 25f, plain))
        assertEquals("25", JournalInsulinDosing.amountAfterPresetChange("  ", 6f, 25f, plain))
    }

    @Test
    fun choosingAnInsulinReplacesThePreviousInsulinsUntouchedDefault() {
        assertEquals("6", JournalInsulinDosing.amountAfterPresetChange("25", 25f, 6f, plain))
        assertEquals("6", JournalInsulinDosing.amountAfterPresetChange("25,0", 25f, 6f, plain))
    }

    @Test
    fun choosingAnInsulinKeepsAnAmountTheUserTyped() {
        assertEquals("8", JournalInsulinDosing.amountAfterPresetChange("8", 25f, 6f, plain))
        assertEquals("8", JournalInsulinDosing.amountAfterPresetChange("8", null, 6f, plain))
        assertEquals("8", JournalInsulinDosing.amountAfterPresetChange("8", null, null, plain))
    }

    @Test
    fun anInsulinWithoutDefaultClearsTheUntouchedDefaultOfTheOneBefore() {
        // 25 U of a basal must not quietly become 25 U of a rapid insulin.
        assertEquals("", JournalInsulinDosing.amountAfterPresetChange("25", 25f, null, plain))
        assertEquals("", JournalInsulinDosing.amountAfterPresetChange("", null, null, plain))
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
