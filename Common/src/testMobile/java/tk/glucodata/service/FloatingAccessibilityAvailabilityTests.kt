package tk.glucodata.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingAccessibilityAvailabilityTests {
    @Test
    fun offeredOnlyWithFloatingGlucoseOnAndBothOptions() {
        for (enabled in listOf(false, true)) {
            for (tap in listOf(false, true)) {
                for (above in listOf(false, true)) {
                    assertEquals(
                        "enabled=$enabled tap=$tap above=$above",
                        enabled && tap && above,
                        FloatingAccessibilityAvailability.wanted(enabled, tap, above),
                    )
                }
            }
        }
    }

    @Test
    fun switchingFloatingGlucoseOffWithdrawsTheServiceWhateverItsOptions() {
        assertFalse(FloatingAccessibilityAvailability.wanted(enabled = false, tapShowsDetails = true, aboveStatusBar = true))
    }

    @Test
    fun restrictedSettingsAreExplainedFromAndroid13ForAppsNotFromAStore() {
        assertTrue(FloatingAccessibilityAvailability.mayBeRestricted(33, "com.google.android.packageinstaller"))
        assertTrue(FloatingAccessibilityAvailability.mayBeRestricted(36, null))
        assertFalse(FloatingAccessibilityAvailability.mayBeRestricted(32, "com.google.android.packageinstaller"))
        assertFalse(FloatingAccessibilityAvailability.mayBeRestricted(36, "com.android.vending"))
    }
}
