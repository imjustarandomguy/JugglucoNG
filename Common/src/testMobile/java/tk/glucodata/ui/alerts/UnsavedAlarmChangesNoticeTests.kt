package tk.glucodata.ui.alerts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the "Unsaved alarm changes" notice is posted as the app leaves the screen. */
class UnsavedAlarmChangesNoticeTests {

    @Test
    fun theDraftInMemoryDecidesOnceThePageMadeOne() {
        assertTrue(UnsavedAlarmChangesNotice.wanted(draftDirty = true, editsOnDisk = false))
        // Saved or discarded in this process: edits still on disk are no reason.
        assertFalse(UnsavedAlarmChangesNotice.wanted(draftDirty = false, editsOnDisk = true))
    }

    @Test
    fun afterTheProcessStoppedTheEditsKeptOnDiskDecide() {
        assertTrue(UnsavedAlarmChangesNotice.wanted(draftDirty = null, editsOnDisk = true))
        assertFalse(UnsavedAlarmChangesNotice.wanted(draftDirty = null, editsOnDisk = false))
    }
}
