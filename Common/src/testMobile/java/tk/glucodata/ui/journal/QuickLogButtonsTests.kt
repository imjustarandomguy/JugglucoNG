package tk.glucodata.ui.journal

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.QUICK_LOG_TYPE_FOOD
import tk.glucodata.QUICK_LOG_TYPE_INSULIN
import tk.glucodata.data.journal.JournalEntryType

class QuickLogButtonsTests {
    @Test
    fun theNotificationsLogInsulinAndLogFoodOpenTheSheetOnThoseTypes() {
        // Notify (src/main) cannot see JournalEntryType: it names the two types by their
        // storage values, which the sheet's activity reads back (typeOf).
        assertEquals(JournalEntryType.INSULIN.storageValue, QUICK_LOG_TYPE_INSULIN)
        assertEquals(JournalEntryType.CARBS.storageValue, QUICK_LOG_TYPE_FOOD)
    }
}
