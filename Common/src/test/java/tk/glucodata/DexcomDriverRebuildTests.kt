package tk.glucodata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DexcomDriverRebuildTests {
    private fun needed(
        dexcomBuild: Boolean = true,
        isDexcomDriver: Boolean = false,
        isManagedDriver: Boolean = false,
        recordIsDexcom: Boolean = true,
    ) = DexcomDriverRebuild.needed(dexcomBuild, isDexcomDriver, isManagedDriver) { recordIsDexcom }

    @Test
    fun aDriverBuiltBeforeItsRecordBecameAG7sIsRebuilt() {
        assertTrue(needed())
    }

    @Test
    fun aDexcomDriverStays() {
        assertFalse(needed(isDexcomDriver = true))
    }

    @Test
    fun aManagedDriverAndAnyOtherRecordStay() {
        assertFalse(needed(isManagedDriver = true))
        assertFalse(needed(recordIsDexcom = false))
    }

    @Test
    fun aBuildWithoutDexcomSupportKeepsWhatItBuilt() {
        // Its own builder would make the same driver again, at every rebuild.
        assertFalse(needed(dexcomBuild = false))
    }

    @Test
    fun theRecordIsLookedUpOnlyForADriverThatMightNeedIt() {
        var asked = 0
        DexcomDriverRebuild.needed(true, true, false) { asked++; true }
        DexcomDriverRebuild.needed(true, false, true) { asked++; true }
        DexcomDriverRebuild.needed(false, false, false) { asked++; true }
        assertTrue(asked == 0)
        DexcomDriverRebuild.needed(true, false, false) { asked++; true }
        assertTrue(asked == 1)
    }
}
