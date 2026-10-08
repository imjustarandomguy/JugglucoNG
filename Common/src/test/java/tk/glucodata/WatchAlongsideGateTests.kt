package tk.glucodata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchAlongsideGateTests {

    private fun staysOff(
        isWearable: Boolean = true,
        directRequested: Boolean = false,
        readsAlongside: Boolean = true,
        phoneReadsIt: Boolean = true,
    ) = resolveWatchStaysOffAlongside(isWearable, directRequested, readsAlongside, phoneReadsIt)

    @Test
    fun watchKeepsOffASensorThePhoneReadsUnlessToldToReadIt() {
        assertTrue(staysOff())
        assertFalse(staysOff(directRequested = true))
    }

    @Test
    fun watchWithoutAReadingPhoneReadsTheSensorItself() {
        // A watch on its own, or one whose phone does not read this sensor.
        assertFalse(staysOff(phoneReadsIt = false))
    }

    @Test
    fun onlyASensorReadAlongsideIsGated() {
        assertFalse(staysOff(readsAlongside = false))
    }

    @Test
    fun thePhoneIsNeverGated() {
        assertFalse(staysOff(isWearable = false))
    }
}
