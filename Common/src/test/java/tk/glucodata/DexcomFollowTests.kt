package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DexcomFollowTests {
    private val old = DexcomFollow.Record(
        name = "1111122222333331",
        code = "0000003331",
        deviceName = "DXCMAA",
        start = 1_790_000_000L,
    )
    private val new = DexcomFollow.Record(
        name = "4444455555666664",
        code = "0000006664",
        deviceName = "DXCMBB",
        start = 1_790_864_000L,
    )

    private fun decide(
        handed: String?,
        newest: DexcomFollow.Record,
        assignedIsDexcom: Boolean = true,
    ) = DexcomFollow.decide(handed, { assignedIsDexcom }, newest)

    @Test
    fun theNewestG7IsTheOneThatStartedLast() {
        assertEquals(new, DexcomFollow.newest(listOf(old, new)))
        assertEquals(new, DexcomFollow.newest(listOf(new, old)))
        assertNull(DexcomFollow.newest(emptyList()))
    }

    @Test
    fun atASensorChangeTheNewG7GoesOnceThePhoneHasPairedIt() {
        // The old one stays selected and was handed when direct mode went on.
        val handedOld = DexcomFollow.handedValue(old)
        val scanned = new.copy(deviceName = "")
        assertEquals(DexcomFollow.Decision.NOT_PAIRED, decide(handedOld, DexcomFollow.newest(listOf(old, scanned))!!))
        assertEquals(DexcomFollow.Decision.HAND, decide(handedOld, DexcomFollow.newest(listOf(old, new))!!))
        assertEquals(DexcomFollow.Decision.HANDED, decide(DexcomFollow.handedValue(new), new))
    }

    @Test
    fun aG7HandedWithTheScansTimeGoesAgainWithTheSensorsStart() {
        // Until the phone first heard from it, the record started at the scan.
        val atScan = new.copy(start = new.start + 130L)
        assertEquals(DexcomFollow.Decision.HAND, decide(DexcomFollow.handedValue(atScan), new))
    }

    @Test
    fun aStartWorkedOutAgainAtEachWarmupSessionIsNotHandedAgain() {
        val handed = DexcomFollow.handedValue(new)
        assertEquals(DexcomFollow.Decision.HANDED, decide(handed, new.copy(start = new.start + 1L)))
        assertEquals(DexcomFollow.Decision.HANDED, decide(handed, new.copy(start = new.start - 1L)))
        assertEquals(
            DexcomFollow.Decision.HANDED,
            decide(handed, new.copy(start = new.start + DexcomFollow.START_TOLERANCE_SEC)),
        )
    }

    @Test
    fun aNameRememberedWithoutItsStartCountsAsHanded() {
        assertEquals(DexcomFollow.Decision.HANDED, decide(new.name, new))
        assertEquals(DexcomFollow.Decision.HAND, decide(old.name, new))
    }

    @Test
    fun aWatchToldToReadAnotherSensorIsNotHandedAG7() {
        assertEquals(DexcomFollow.Decision.NOT_FOLLOWING, decide(null, new, assignedIsDexcom = false))
        assertEquals(DexcomFollow.Decision.HAND, decide(null, new, assignedIsDexcom = true))
        // Once handed one, a watch follows the next.
        assertEquals(
            DexcomFollow.Decision.HAND,
            decide(DexcomFollow.handedValue(old), new, assignedIsDexcom = false),
        )
    }

    @Test
    fun switchingOnHandsTheNewestPairedG7NotTheSelectedOne() {
        // The old G7 still selected after a sensor change: the new one goes.
        assertEquals(new, DexcomFollow.onSwitchOn(listOf(old, new)) { true })
        // An ended record is not among the active ones: nothing is left to hand.
        assertNull(DexcomFollow.onSwitchOn(emptyList()) { true })
        // Not before the phone paired the newest, and not the old one meanwhile:
        // the follow hands the new one once it is paired.
        assertNull(DexcomFollow.onSwitchOn(listOf(old, new.copy(deviceName = ""))) { true })
        // Switched on while another kind of sensor is selected: no G7.
        assertNull(DexcomFollow.onSwitchOn(listOf(old, new)) { false })
    }

    @Test
    fun onlyAnExportableRecordIsAG7() {
        assertEquals(
            new,
            DexcomFollow.fromFields(arrayOf(new.name, new.code, new.deviceName, new.start.toString())),
        )
        assertEquals(
            new.copy(deviceName = ""),
            DexcomFollow.fromFields(arrayOf(new.name, new.code, "", new.start.toString())),
        )
        assertNull(DexcomFollow.fromFields(null))
        assertNull(DexcomFollow.fromFields(arrayOf(new.name, new.code, new.deviceName)))
        assertNull(DexcomFollow.fromFields(arrayOf(new.name, "", new.deviceName, new.start.toString())))
        assertNull(DexcomFollow.fromFields(arrayOf(new.name, new.code, new.deviceName, "0")))
        assertNull(DexcomFollow.fromFields(arrayOf("", new.code, new.deviceName, new.start.toString())))
    }
}
