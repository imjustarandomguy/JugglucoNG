package tk.glucodata.data.journal

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InsulinReminderPolicyTests {
    private val utc = TimeZone.getTimeZone("UTC")
    private val minute = 60_000L
    private val hour = 60 * minute
    private val tresibaId = 2L
    private val fiaspId = 1L

    /** 7 October 2026 at [h]:[m] UTC, shifted by [dayOffset] days. */
    private fun at(h: Int, m: Int = 0, dayOffset: Int = 0): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(2026, Calendar.OCTOBER, 7 + dayOffset, h, m)
        }.timeInMillis

    private fun minuteOfDay(h: Int, m: Int = 0) = h * 60 + m

    private fun basal(
        times: List<Int>,
        id: Long = tresibaId,
        name: String = "Tresiba",
        countsTowardIob: Boolean = false,
        isArchived: Boolean = false
    ) = JournalInsulinPreset(
        id = id,
        displayName = name,
        onsetMinutes = 60,
        durationMinutes = 42 * 60,
        accentColor = 0,
        curveJson = "",
        isBuiltIn = false,
        isArchived = isArchived,
        countsTowardIob = countsTowardIob,
        sortOrder = 0,
        useForCalculation = false,
        defaultDose = 25f,
        reminderTimes = times
    )

    private fun dose(
        timestamp: Long,
        presetId: Long? = tresibaId,
        title: String = "Tresiba",
        amount: Float? = 25f,
        source: JournalEntrySource = JournalEntrySource.MANUAL,
        type: JournalEntryType = JournalEntryType.INSULIN
    ) = JournalEntry(
        id = timestamp,
        timestamp = timestamp,
        sensorSerial = null,
        type = type,
        title = title,
        note = null,
        amount = amount,
        glucoseValueMgDl = null,
        durationMinutes = null,
        intensity = null,
        insulinPresetId = presetId,
        foodId = null,
        proteinGrams = null,
        fatGrams = null,
        source = source,
        sourceRecordId = null,
        createdAt = timestamp,
        updatedAt = timestamp
    )

    private fun notifiesAt(
        preset: JournalInsulinPreset,
        h: Int,
        m: Int = 0,
        entries: List<JournalEntry>,
        checkAt: Long = at(h, m)
    ) = InsulinReminderPolicy.shouldNotify(preset, minuteOfDay(h, m), at(h, m), checkAt, entries)

    // One dose a day

    @Test
    fun oneDoseADayIsLoggedFromTwelveHoursBefore() {
        val tresiba = basal(listOf(minuteOfDay(21)))
        assertEquals(12 * 60, InsulinReminderPolicy.windowMinutesBefore(minuteOfDay(21), tresiba.reminderTimes))
        assertEquals(at(9), InsulinReminderPolicy.windowStart(at(21), minuteOfDay(21), tresiba.reminderTimes))
    }

    @Test
    fun oneDoseADayNotLoggedNotifies() {
        val tresiba = basal(listOf(minuteOfDay(21)))
        assertTrue(notifiesAt(tresiba, 21, entries = emptyList()))
        // Yesterday's dose is yesterday's.
        assertTrue(notifiesAt(tresiba, 21, entries = listOf(dose(at(21, dayOffset = -1)))))
        // Just before the window opens: still the previous dose.
        assertTrue(notifiesAt(tresiba, 21, entries = listOf(dose(at(8, 59)))))
    }

    @Test
    fun oneDoseADayLoggedStaysQuiet() {
        val tresiba = basal(listOf(minuteOfDay(21)))
        assertFalse(notifiesAt(tresiba, 21, entries = listOf(dose(at(20, 55)))))
        assertFalse(notifiesAt(tresiba, 21, entries = listOf(dose(at(9)))))
    }

    @Test
    fun anotherInsulinOrAnEmptyDoseDoesNotCount() {
        val tresiba = basal(listOf(minuteOfDay(21)))
        assertTrue(notifiesAt(tresiba, 21, entries = listOf(dose(at(20), presetId = fiaspId, title = "Fiasp"))))
        assertTrue(notifiesAt(tresiba, 21, entries = listOf(dose(at(20), amount = 0f))))
        assertTrue(notifiesAt(tresiba, 21, entries = listOf(dose(at(20), amount = null))))
        assertTrue(notifiesAt(tresiba, 21, entries = listOf(dose(at(20), type = JournalEntryType.NOTE))))
    }

    // Two doses a day

    @Test
    fun twoDosesADaySplitTheDayAtTheMidpoints() {
        val times = listOf(minuteOfDay(8), minuteOfDay(20))
        assertEquals(6 * 60, InsulinReminderPolicy.windowMinutesBefore(minuteOfDay(20), times))
        assertEquals(6 * 60, InsulinReminderPolicy.windowMinutesBefore(minuteOfDay(8), times))
        assertEquals(at(14), InsulinReminderPolicy.windowStart(at(20), minuteOfDay(20), times))
        assertEquals(at(2), InsulinReminderPolicy.windowStart(at(8), minuteOfDay(8), times))
    }

    @Test
    fun theMorningDoseDoesNotAnswerTheEveningReminder() {
        val levemir = basal(listOf(minuteOfDay(8), minuteOfDay(20)))
        val morning = dose(at(8, 5))
        assertFalse(notifiesAt(levemir, 8, entries = listOf(morning)))
        assertTrue(notifiesAt(levemir, 20, entries = listOf(morning)))
        assertFalse(notifiesAt(levemir, 20, entries = listOf(morning, dose(at(19, 40)))))
    }

    @Test
    fun unevenTimesSplitAtTheirOwnMidpoints() {
        // 07:00 and 22:00: 15 h one way, 9 h the other.
        val times = listOf(minuteOfDay(7), minuteOfDay(22))
        assertEquals(at(14, 30), InsulinReminderPolicy.windowStart(at(22), minuteOfDay(22), times))
        assertEquals(at(2, 30), InsulinReminderPolicy.windowStart(at(7), minuteOfDay(7), times))
    }

    // Midnight

    @Test
    fun theWindowWrapsAcrossMidnight() {
        // 23:00 and 01:00: the 01:00 dose may come from midnight, the 23:00 one from noon.
        val preset = basal(listOf(minuteOfDay(23), minuteOfDay(1)))
        assertEquals(at(0), InsulinReminderPolicy.windowStart(at(1), minuteOfDay(1), preset.reminderTimes))
        assertEquals(at(12), InsulinReminderPolicy.windowStart(at(23), minuteOfDay(23), preset.reminderTimes))
        assertFalse(notifiesAt(preset, 1, entries = listOf(dose(at(0, 15)))))
        // 23:30 the evening before answered the 23:00 reminder, not this one.
        assertTrue(notifiesAt(preset, 1, entries = listOf(dose(at(23, 30, dayOffset = -1)))))
    }

    @Test
    fun aSingleTimeJustAfterMidnightLooksBackIntoYesterday() {
        val preset = basal(listOf(minuteOfDay(0, 30)))
        assertEquals(at(12, 30, dayOffset = -1), InsulinReminderPolicy.windowStart(at(0, 30), minuteOfDay(0, 30), preset.reminderTimes))
        assertFalse(notifiesAt(preset, 0, 30, entries = listOf(dose(at(22, 0, dayOffset = -1)))))
        assertTrue(notifiesAt(preset, 0, 30, entries = listOf(dose(at(12, 0, dayOffset = -1)))))
    }

    @Test
    fun theNextTimeOfDayRollsOverMidnight() {
        assertEquals(at(0, 30, dayOffset = 1), InsulinReminderPolicy.nextOccurrence(at(23, 30), minuteOfDay(0, 30), utc))
        assertEquals(at(23, 45), InsulinReminderPolicy.nextOccurrence(at(23, 30), minuteOfDay(23, 45), utc))
        // At the very time, the next one is tomorrow's: an alarm never rings twice.
        assertEquals(at(21, dayOffset = 1), InsulinReminderPolicy.nextOccurrence(at(21), minuteOfDay(21), utc))
    }

    @Test
    fun aTimeSkippedByDaylightSavingComesRightAfterTheGap() {
        val toronto = TimeZone.getTimeZone("America/Toronto")
        // 8 March 2026: clocks go from 02:00 to 03:00.
        val evening = Calendar.getInstance(toronto).apply { clear(); set(2026, Calendar.MARCH, 7, 22, 0) }.timeInMillis
        val next = InsulinReminderPolicy.nextOccurrence(evening, minuteOfDay(2, 30), toronto)
        val local = Calendar.getInstance(toronto).apply { timeInMillis = next }
        assertEquals(8, local.get(Calendar.DAY_OF_MONTH))
        assertEquals(3, local.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, local.get(Calendar.MINUTE))
    }

    // Early dose

    @Test
    fun anEarlyDoseCountsOnceInsideTheWindow() {
        val tresiba = basal(listOf(minuteOfDay(21)))
        // Taken at 17:30 with dinner, three and a half hours early.
        assertFalse(notifiesAt(tresiba, 21, entries = listOf(dose(at(17, 30)))))
        // Logged a few minutes ahead of the reminder by a device whose clock runs fast.
        assertFalse(notifiesAt(tresiba, 21, entries = listOf(dose(at(21, 10)))))
    }

    // Received from elsewhere

    @Test
    fun aDoseFromNightscoutCounts() {
        val tresiba = basal(listOf(minuteOfDay(21)))
        assertFalse(
            notifiesAt(tresiba, 21, entries = listOf(dose(at(20, 40), source = JournalEntrySource.NIGHTSCOUT)))
        )
        assertFalse(notifiesAt(tresiba, 21, entries = listOf(dose(at(20, 40), source = JournalEntrySource.AAPS))))
        assertFalse(notifiesAt(tresiba, 21, entries = listOf(dose(at(20, 40), source = JournalEntrySource.CLONE))))
    }

    @Test
    fun aDoseFromNightscoutFiledUnderAnotherInsulinCountsByName() {
        val tresiba = basal(listOf(minuteOfDay(21)))
        // The import filed "Tresiba" under the first rapid insulin; its name is still Tresiba.
        val misfiled = dose(at(20, 40), presetId = fiaspId, title = "tresiba ", source = JournalEntrySource.NIGHTSCOUT)
        assertFalse(notifiesAt(tresiba, 21, entries = listOf(misfiled)))
        val unfiled = dose(at(20, 40), presetId = null, title = "Tresiba", source = JournalEntrySource.NIGHTSCOUT)
        assertFalse(notifiesAt(tresiba, 21, entries = listOf(unfiled)))
        val otherName = dose(at(20, 40), presetId = null, title = "Insulin", source = JournalEntrySource.NIGHTSCOUT)
        assertTrue(notifiesAt(tresiba, 21, entries = listOf(otherName)))
    }

    // Snooze

    @Test
    fun aSnoozeEndsHalfAnHourLater() {
        assertEquals(at(21, 30), InsulinReminderPolicy.snoozeUntil(at(21)))
    }

    @Test
    fun aDoseLoggedDuringTheSnoozeSilencesIt() {
        val tresiba = basal(listOf(minuteOfDay(21)))
        val snoozeEnd = InsulinReminderPolicy.snoozeUntil(at(21))
        assertTrue(notifiesAt(tresiba, 21, entries = emptyList(), checkAt = snoozeEnd))
        assertFalse(notifiesAt(tresiba, 21, entries = listOf(dose(at(21, 10))), checkAt = snoozeEnd))
    }

    @Test
    fun aSnoozedReminderKeepsItsOwnWindow() {
        val tresiba = basal(listOf(minuteOfDay(21)))
        val snoozeEnd = InsulinReminderPolicy.snoozeUntil(at(21))
        // The window still opens at 09:00, not half an hour later...
        assertFalse(notifiesAt(tresiba, 21, entries = listOf(dose(at(9, 10))), checkAt = snoozeEnd))
        // ...nor earlier.
        assertTrue(notifiesAt(tresiba, 21, entries = listOf(dose(at(8, 50))), checkAt = snoozeEnd))
    }

    // One-tap Log

    @Test
    fun aDoseAlreadyInTheWindowIsNotLoggedAgain() {
        val tresiba = basal(listOf(minuteOfDay(21)))
        assertNull(InsulinReminderPolicy.loggedDose(tresiba, minuteOfDay(21), at(21), at(21, 5), emptyList()))
        // The first tap's dose, or one logged from the app meanwhile, is found; the latest wins.
        val first = dose(at(21, 5))
        val earlier = dose(at(19))
        assertEquals(
            first,
            InsulinReminderPolicy.loggedDose(tresiba, minuteOfDay(21), at(21), at(21, 6), listOf(earlier, first))
        )
        // Yesterday's dose does not stop today's.
        assertNull(
            InsulinReminderPolicy.loggedDose(
                tresiba, minuteOfDay(21), at(21), at(21, 5), listOf(dose(at(21, dayOffset = -1)))
            )
        )
    }

    // Which presets, which alarms

    @Test
    fun onlyLongActingPresetsInUseRemind() {
        val times = listOf(minuteOfDay(21))
        assertTrue(InsulinReminderPolicy.remindsFor(basal(times)))
        assertFalse(InsulinReminderPolicy.remindsFor(basal(emptyList())))
        assertFalse(InsulinReminderPolicy.remindsFor(basal(times, countsTowardIob = true)))
        assertFalse(InsulinReminderPolicy.remindsFor(basal(times, isArchived = true)))
        assertTrue(notifiesAt(basal(times), 21, entries = emptyList()))
        assertFalse(notifiesAt(basal(times, isArchived = true), 21, entries = emptyList()))
        // A time no longer in the preset says nothing.
        assertFalse(notifiesAt(basal(listOf(minuteOfDay(22))), 21, entries = emptyList()))
    }

    @Test
    fun oneSlotPerReminderTime() {
        val presets = listOf(
            basal(listOf(minuteOfDay(20), minuteOfDay(8))),
            basal(listOf(minuteOfDay(21)), id = 3L, isArchived = true),
            basal(listOf(minuteOfDay(7)), id = fiaspId, countsTowardIob = true)
        )
        assertEquals(
            listOf(
                InsulinReminderPolicy.Slot(tresibaId, minuteOfDay(8)),
                InsulinReminderPolicy.Slot(tresibaId, minuteOfDay(20))
            ),
            InsulinReminderPolicy.slots(presets)
        )
    }

    @Test
    fun slotKeysRoundTrip() {
        val slot = InsulinReminderPolicy.Slot(tresibaId, minuteOfDay(21))
        assertEquals("2:1260", slot.key)
        assertEquals(slot, InsulinReminderPolicy.Slot.fromKey(slot.key))
        assertNull(InsulinReminderPolicy.Slot.fromKey("2:1440"))
        assertNull(InsulinReminderPolicy.Slot.fromKey("x:60"))
        assertNull(InsulinReminderPolicy.Slot.fromKey("2"))
    }

    @Test
    fun aLateAlarmIsDropped() {
        assertTrue(InsulinReminderPolicy.firesInTime(at(21), at(21, 0)))
        assertTrue(InsulinReminderPolicy.firesInTime(at(21), at(22, 0)))
        assertFalse(InsulinReminderPolicy.firesInTime(at(21), at(22, 1)))
    }
}
