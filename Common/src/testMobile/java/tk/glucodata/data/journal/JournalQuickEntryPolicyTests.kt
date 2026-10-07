package tk.glucodata.data.journal

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalQuickEntryPolicyTests {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour
    private val now = 1_700_000_000_000L
    private val fiasp = 1L
    private val tresiba = 2L

    private fun row(amount: Float, ago: Long, presetId: Long? = fiasp) =
        JournalAmountRow(amount = amount, timestamp = now - ago, insulinPresetId = presetId)

    private fun recentInsulin(rows: List<JournalAmountRow>, presetId: Long? = fiasp) =
        JournalQuickEntryPolicy.recentAmounts(rows, JournalEntryType.INSULIN, presetId, now)

    @Test
    fun mostUsedComeFirst() {
        val rows = listOf(
            row(4f, 1 * hour),
            row(6f, 2 * hour), row(6f, 3 * hour), row(6f, 4 * hour),
            row(8f, 5 * hour), row(8f, 6 * hour)
        )
        assertEquals(listOf(6f, 8f, 4f), recentInsulin(rows))
    }

    @Test
    fun aTieGoesToTheMostRecentlyUsed() {
        val rows = listOf(
            row(5f, 10 * hour), row(5f, 30 * hour),
            row(7f, 2 * hour), row(7f, 40 * hour)
        )
        assertEquals(listOf(7f, 5f), recentInsulin(rows))
    }

    @Test
    fun onlyTheLastSevenDaysCount() {
        val rows = listOf(
            row(6f, 6 * day + 23 * hour),
            row(12f, 7 * day + 1 * minute), row(12f, 8 * day), row(12f, 9 * day),
            // Logged ahead of now: not "recent" yet.
            row(9f, -10 * minute)
        )
        assertEquals(listOf(6f), recentInsulin(rows))
    }

    @Test
    fun atMostFiveValues() {
        val rows = (1..9).map { units -> row(units.toFloat(), units * hour) }
        val chips = recentInsulin(rows)
        assertEquals(5, chips.size)
        assertEquals(listOf(1f, 2f, 3f, 4f, 5f), chips)
    }

    @Test
    fun insulinChipsAreThoseOfTheChosenInsulinOnly() {
        val rows = listOf(row(6f, hour, fiasp), row(25f, 2 * hour, tresiba), row(25f, day, tresiba))
        assertEquals(listOf(6f), recentInsulin(rows, fiasp))
        assertEquals(listOf(25f), recentInsulin(rows, tresiba))
        assertEquals(emptyList<Float>(), recentInsulin(rows, presetId = null))
    }

    @Test
    fun foodChipsAreCarbGramsWhateverTheInsulinColumnSays() {
        val rows = listOf(row(45f, hour, null), row(45f, 2 * hour, null), row(20f, 3 * hour, 7L))
        assertEquals(
            listOf(45f, 20f),
            JournalQuickEntryPolicy.recentAmounts(rows, JournalEntryType.CARBS, insulinPresetId = fiasp, nowMillis = now)
        )
    }

    @Test
    fun everySourceCountsTheSame() {
        // The query reads every row of the type in the window: typed here, from the watch,
        // Nightscout, AAPS or Clone alike. Nothing in a row says where it came from, so a dose
        // received from AAPS ranks exactly like one typed in.
        val typedHere = row(6f, 1 * hour)
        val fromAaps = row(6f, 2 * hour)
        val fromNightscout = row(4f, 3 * hour)
        assertEquals(listOf(6f, 4f), recentInsulin(listOf(fromNightscout, fromAaps, typedHere)))
    }

    @Test
    fun amountsDifferingOnlyByFloatNoiseAreOneValue() {
        val rows = listOf(row(5.9999995f, hour), row(6f, 2 * hour), row(6.0000005f, 3 * hour), row(4f, 30 * minute))
        assertEquals(listOf(6f, 4f), recentInsulin(rows))
    }

    @Test
    fun emptyAndNonPositiveAmountsAreNoChips() {
        val rows = listOf(row(0f, hour), row(-2f, hour), row(Float.NaN, hour))
        assertEquals(emptyList<Float>(), recentInsulin(rows))
    }

    private fun entry(
        id: Long,
        type: JournalEntryType,
        ago: Long,
        amount: Float?,
        presetId: Long? = null,
        durationMinutes: Int? = null,
        foodId: Long? = null,
        source: JournalEntrySource = JournalEntrySource.MANUAL,
        protein: Float? = null,
        fat: Float? = null
    ) = JournalEntry(
        id = id,
        timestamp = now - ago,
        sensorSerial = null,
        type = type,
        title = "",
        note = null,
        amount = amount,
        glucoseValueMgDl = null,
        durationMinutes = durationMinutes,
        intensity = null,
        insulinPresetId = presetId,
        foodId = foodId,
        proteinGrams = protein,
        fatGrams = fat,
        source = source,
        sourceRecordId = null,
        createdAt = now,
        updatedAt = now
    )

    @Test
    fun lastDoseShowsOnlyWhileTheDoseIsActive() {
        val dose = entry(1, JournalEntryType.INSULIN, 80 * minute, 6f, fiasp)
        assertSame(dose, JournalQuickEntryPolicy.lastActiveInsulin(listOf(dose), fiasp, 300, now))
        // Exactly its duration ago: no longer active.
        assertNull(JournalQuickEntryPolicy.lastActiveInsulin(listOf(dose), fiasp, 80, now))
        assertSame(dose, JournalQuickEntryPolicy.lastActiveInsulin(listOf(dose), fiasp, 81, now))
    }

    @Test
    fun lastDoseIsTheLatestOfTheChosenInsulinOnly() {
        val older = entry(1, JournalEntryType.INSULIN, 3 * hour, 4f, fiasp)
        val latest = entry(2, JournalEntryType.INSULIN, 1 * hour, 6f, fiasp)
        val basal = entry(3, JournalEntryType.INSULIN, 10 * minute, 25f, tresiba)
        val entries = listOf(older, basal, latest)
        assertSame(latest, JournalQuickEntryPolicy.lastActiveInsulin(entries, fiasp, 300, now))
        assertSame(basal, JournalQuickEntryPolicy.lastActiveInsulin(entries, tresiba, 2520, now))
    }

    @Test
    fun anOlderDoseDoesNotStandInForAnExpiredLatestOne() {
        // The line is about the last dose; once that one is spent there is nothing to show.
        val latest = entry(2, JournalEntryType.INSULIN, 5 * hour, 6f, fiasp)
        assertNull(JournalQuickEntryPolicy.lastActiveInsulin(listOf(latest), fiasp, 240, now))
    }

    @Test
    fun theEntryBeingEditedIsNotItsOwnLastDose() {
        val earlier = entry(1, JournalEntryType.INSULIN, 2 * hour, 4f, fiasp)
        val edited = entry(2, JournalEntryType.INSULIN, 30 * minute, 6f, fiasp)
        assertSame(
            earlier,
            JournalQuickEntryPolicy.lastActiveInsulin(listOf(earlier, edited), fiasp, 300, now, excludeEntryId = 2)
        )
    }

    @Test
    fun aDoseLoggedAheadOfNowOrWithoutAmountIsNotTheLastDose() {
        val future = entry(1, JournalEntryType.INSULIN, -15 * minute, 6f, fiasp)
        val empty = entry(2, JournalEntryType.INSULIN, 10 * minute, null, fiasp)
        assertNull(JournalQuickEntryPolicy.lastActiveInsulin(listOf(future, empty), fiasp, 300, now))
        assertNull(JournalQuickEntryPolicy.lastActiveInsulin(listOf(future), presetId = null, durationMinutes = 300, nowMillis = now))
    }

    @Test
    fun lastMealShowsWithinItsOwnAbsorptionTime() {
        val meal = entry(1, JournalEntryType.CARBS, 70 * minute, 45f, durationMinutes = 90)
        assertSame(meal, JournalQuickEntryPolicy.lastActiveMeal(listOf(meal), emptyMap(), now))
        val digested = entry(2, JournalEntryType.CARBS, 100 * minute, 45f, durationMinutes = 90)
        assertNull(JournalQuickEntryPolicy.lastActiveMeal(listOf(digested), emptyMap(), now))
    }

    @Test
    fun aMealWithoutDurationUsesItsFoodThenTheDefaultFormula() {
        val pizza = JournalFood(
            id = 9, displayName = "Pizza", carbsGrams = 60f, proteinGrams = 25f, fatGrams = 30f,
            absorptionMinutes = 330, accentColor = 0, isBuiltIn = true, isArchived = false, sortOrder = 0
        )
        val fromLibrary = entry(1, JournalEntryType.CARBS, 4 * hour, 60f, foodId = 9)
        assertSame(fromLibrary, JournalQuickEntryPolicy.lastActiveMeal(listOf(fromLibrary), mapOf(9L to pizza), now))
        assertEquals(330, JournalQuickEntryPolicy.mealAbsorptionMinutes(fromLibrary, pizza))

        // 30 g, no macros: 60 + 2 * 30 = 120 min, as the Nightscout upload estimates it.
        val plain = entry(2, JournalEntryType.CARBS, 0, 30f)
        assertEquals(120, JournalQuickEntryPolicy.mealAbsorptionMinutes(plain, null))
        assertEquals(62, JournalQuickEntryPolicy.mealAbsorptionMinutes(entry(3, JournalEntryType.CARBS, 0, 1f), null))
        // Protein and fat lengthen it (1.5 and 2.5 min per gram), up to six hours.
        assertEquals(
            60 + 60 + 15 + 25,
            JournalQuickEntryPolicy.mealAbsorptionMinutes(entry(6, JournalEntryType.CARBS, 0, 30f, protein = 10f, fat = 10f), null)
        )
        assertEquals(360, JournalQuickEntryPolicy.mealAbsorptionMinutes(entry(4, JournalEntryType.CARBS, 0, 200f), null))
        val stillDigesting = entry(5, JournalEntryType.CARBS, 110 * minute, 30f)
        assertSame(stillDigesting, JournalQuickEntryPolicy.lastActiveMeal(listOf(stillDigesting), emptyMap(), now))
    }

    @Test
    fun lastMealIgnoresOtherTypesAndTheEditedEntry() {
        val dose = entry(1, JournalEntryType.INSULIN, 5 * minute, 6f, fiasp)
        val meal = entry(2, JournalEntryType.CARBS, 20 * minute, 30f, durationMinutes = 90)
        assertSame(meal, JournalQuickEntryPolicy.lastActiveMeal(listOf(dose, meal), emptyMap(), now))
        assertNull(JournalQuickEntryPolicy.lastActiveMeal(listOf(dose, meal), emptyMap(), now, excludeEntryId = 2))
    }

    private val zone: TimeZone = TimeZone.getTimeZone("America/Toronto")

    private fun at(year: Int, month: Int, dayOfMonth: Int, hourOfDay: Int, minuteOfHour: Int): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, dayOfMonth, hourOfDay, minuteOfHour)
        }.timeInMillis

    @Test
    fun todayReadsAsTimeAgo() {
        val nowAt = at(2026, 10, 7, 14, 0)
        assertEquals(
            JournalQuickEntryPolicy.Elapsed.Ago(80),
            JournalQuickEntryPolicy.elapsed(at(2026, 10, 7, 12, 40), nowAt, zone)
        )
        assertEquals(
            JournalQuickEntryPolicy.Elapsed.Ago(0),
            JournalQuickEntryPolicy.elapsed(nowAt - 20_000L, nowAt, zone)
        )
    }

    @Test
    fun underTwelveHoursReadsAsTimeAgoAcrossMidnight() {
        // Just before midnight, minutes ago: "30 min ago", not "yesterday 23:50".
        assertEquals(
            JournalQuickEntryPolicy.Elapsed.Ago(30),
            JournalQuickEntryPolicy.elapsed(at(2026, 10, 6, 23, 50), at(2026, 10, 7, 0, 20), zone)
        )
        // Last evening's dose in the morning: "9 h 52 min ago".
        assertEquals(
            JournalQuickEntryPolicy.Elapsed.Ago(9 * 60L + 52),
            JournalQuickEntryPolicy.elapsed(at(2026, 10, 6, 21, 38), at(2026, 10, 7, 7, 30), zone)
        )
        // A minute short of the limit.
        assertEquals(
            JournalQuickEntryPolicy.Elapsed.Ago(11 * 60L + 59),
            JournalQuickEntryPolicy.elapsed(at(2026, 10, 6, 20, 1), at(2026, 10, 7, 8, 0), zone)
        )
    }

    @Test
    fun twelveHoursOrMoreOnTheDayBeforeReadsAsYesterdayAtItsTime() {
        val dose = at(2026, 10, 6, 21, 38)
        assertEquals(
            JournalQuickEntryPolicy.Elapsed.Yesterday(dose),
            JournalQuickEntryPolicy.elapsed(dose, at(2026, 10, 7, 10, 0), zone)
        )
        // Exactly twelve hours.
        val evening = at(2026, 10, 6, 20, 0)
        assertEquals(
            JournalQuickEntryPolicy.Elapsed.Yesterday(evening),
            JournalQuickEntryPolicy.elapsed(evening, at(2026, 10, 7, 8, 0), zone)
        )
    }

    @Test
    fun twelveHoursOrMoreEarlierTodayReadsAsTimeAgo() {
        assertEquals(
            JournalQuickEntryPolicy.Elapsed.Ago(12 * 60L + 30),
            JournalQuickEntryPolicy.elapsed(at(2026, 10, 7, 0, 30), at(2026, 10, 7, 13, 0), zone)
        )
    }

    @Test
    fun yesterdayFollowsTheCalendarAcrossADaylightSavingChange() {
        // 2026-11-01 is the autumn change in Toronto: that day has 25 hours.
        val dose = at(2026, 10, 31, 22, 0)
        assertTrue(JournalQuickEntryPolicy.elapsed(dose, at(2026, 11, 1, 23, 0), zone) is JournalQuickEntryPolicy.Elapsed.Yesterday)
    }

    @Test
    fun twoDaysAgoReadsAsTimeAgoInDaysAndHours() {
        val nowAt = at(2026, 10, 7, 8, 0)
        val elapsed = JournalQuickEntryPolicy.elapsed(at(2026, 10, 5, 5, 0), nowAt, zone)
        assertEquals(JournalQuickEntryPolicy.Elapsed.Ago(51 * 60L), elapsed)
        assertEquals(Triple(2L, 3L, 0L), JournalQuickEntryPolicy.splitMinutes(51 * 60L))
        assertEquals(Triple(0L, 1L, 20L), JournalQuickEntryPolicy.splitMinutes(80L))
        assertEquals(Triple(0L, 0L, 0L), JournalQuickEntryPolicy.splitMinutes(-5L))
    }

    @Test
    fun undoOfAnEditWritesTheOwnRowBackWithItsSourceAndIdentity() {
        val before = entry(7, JournalEntryType.INSULIN, hour, 4f, fiasp, source = JournalEntrySource.PEN)
            .copy(sourceRecordId = "pen:abc", originSource = JournalEntrySource.PEN, note = "before lunch")
        val input = JournalQuickEntryPolicy.restoreInput(before)
        assertEquals(7L, input.id)
        assertEquals(before.timestamp, input.timestamp)
        assertEquals(4f, input.amount)
        assertEquals(fiasp, input.insulinPresetId)
        assertEquals("before lunch", input.note)
        assertEquals(JournalEntrySource.PEN, input.source)
        assertEquals("pen:abc", input.sourceRecordId)
        assertEquals(JournalEntrySource.PEN, input.originSource)
    }

    @Test
    fun undoOfAnEditOfAMirroredRowIsAUserEditOfIt() {
        // The repository keeps a mirrored row's stored source and record id; the restore is
        // written as the user's edit, exactly like the edit it takes back.
        val before = entry(8, JournalEntryType.CARBS, hour, 45f, source = JournalEntrySource.NIGHTSCOUT)
            .copy(sourceRecordId = "ns:123")
        val input = JournalQuickEntryPolicy.restoreInput(before)
        assertEquals(JournalEntrySource.MANUAL, input.source)
        assertNull(input.sourceRecordId)
        assertEquals(45f, input.amount)
        assertEquals(8L, input.id)
    }
}
