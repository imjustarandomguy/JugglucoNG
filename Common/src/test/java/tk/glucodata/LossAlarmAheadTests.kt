package tk.glucodata

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * While phone and watch both read a G7, a reading one of them missed is asked
 * of the other from the loss alarm. It went off at reading + 380 s, after the
 * watch's complication had said "No value" from reading + 330 s.
 */
class LossAlarmAheadTests {
    private val g7Showtime = 6L * 60L * 1000L

    @Test
    fun whileBothReadTheAlarmComesBeforeTheStalePoint() {
        val showtime = LossAlarmAhead.showtime(g7Showtime, bothRead = true)
        // LossOfSensorAlarm.setalarm adds 20 s; Notify.glucosetimeout is 330 s.
        assertTrue(showtime + 20_000L < staleAfterMs())
        // After a live reading of the next slot would have arrived.
        assertTrue(showtime + 20_000L >= 5L * 60L * 1000L + 15_000L)
    }

    @Test
    fun otherwiseTheSensorsOwnDelayStays() {
        assertEquals(g7Showtime, LossAlarmAhead.showtime(g7Showtime, bothRead = false))
    }

    @Test
    fun itIsNeverLaterThanTheSensorsOwn() {
        assertEquals(200_000L, LossAlarmAhead.showtime(200_000L, bothRead = true))
    }

    @Test
    fun theReadingPathArmsItSo() {
        val source = source("Common/src/main/java/tk/glucodata/SuperGattCallback.java")
        assertTrue(
            source.contains(
                "glucosealarms.setagealarm(timmsec,\n" +
                    "                LossAlarmAhead.showtime(showtime, SensorOwnershipRuntime.bothRead(SerialNumber)));",
            ),
        )
    }

    // Every opening of the app starts Bluetooth, which arms the alarm from now
    // for a sensor both devices read. That must not put off the alarm the last
    // reading armed: it is what asks the other device for a reading missed.

    private val reading = 1_000_000_000L

    /** Where a reading leaves the alarm while both read: LossOfSensorAlarm.setalarm adds 20 s. */
    private val armedByReading = reading + LossAlarmAhead.BOTH_READ_SHOWTIME_MS + 20_000L

    /** What the start of Bluetooth asks for at [now]: now + Notify.glucosetimeout, plus the 20 s. */
    private fun startWants(now: Long) = now + staleAfterMs() + 20_000L

    @Test
    fun openingTheAppKeepsTheAlarmTheReadingArmed() {
        for (openedAfter in listOf(1_000L, 30_000L, 200_000L, 319_000L)) {
            val now = reading + openedAfter
            assertTrue("opened at +$openedAfter", LossAlarmAhead.keepsArmed(armedByReading, startWants(now), now))
        }
    }

    @Test
    fun aLaterAlarmIsBroughtForward() {
        val now = reading
        assertFalse(LossAlarmAhead.keepsArmed(now + 900_000L, startWants(now), now))
    }

    @Test
    fun theSameMomentStays() {
        assertTrue(LossAlarmAhead.keepsArmed(startWants(reading), startWants(reading), reading))
    }

    @Test
    fun withNoneArmedOrOneThatWentOffItIsArmed() {
        val now = reading + 400_000L
        assertFalse(LossAlarmAhead.keepsArmed(0L, startWants(now), now))
        // Went off at reading + 320 s.
        assertFalse(LossAlarmAhead.keepsArmed(armedByReading, startWants(now), now))
        assertFalse(LossAlarmAhead.keepsArmed(now, startWants(now), now))
    }

    @Test
    fun onlyTheStartOfBluetoothKeepsTheEarlierAlarm() {
        val alarms = source("Common/src/main/java/tk/glucodata/SuperGlucoseAlarms.java")
        val setLoss = alarms.substringAfter("public  void setLossAlarm() {").substringBefore("public void setagealarm(")
        val bothRead = "else if(SensorOwnershipRuntime.anySetToReadAlongside()) {"
        assertTrue(
            setLoss.substringAfter(bothRead).contains(
                "LossOfSensorAlarm.setalarmNoLater(Applic.app, System.currentTimeMillis() + Notify.glucosetimeout);",
            ),
        )
        // The loss-of-signal alert arms it as it always did, and a reading
        // re-arms it whichever way it moves.
        assertTrue(setLoss.substringBefore(bothRead).contains("LossOfSensorAlarm.setalarm(Applic.app, nu + waitmmsec());"))
        assertTrue(alarms.substringAfter("public void setagealarm(").contains("LossOfSensorAlarm.setalarm(Applic.app, oldtime);"))

        val alarm = source("Common/src/main/java/tk/glucodata/LossOfSensorAlarm.java")
        // The moments it goes off are compared: setalarm remembers the time with its 20 s.
        assertTrue(alarm.contains("alarmtime+=20*1000;\n        mkintents(context);\n        scheduledAtMillis = alarmtime;"))
        assertTrue(alarm.contains("LossAlarmAhead.keepsArmed(armed, alarmtime + 20*1000, System.currentTimeMillis())"))
        // Cancelled, there is none to keep.
        assertTrue(alarm.substringAfter("static void cancelalarm() {").contains("scheduledAtMillis = 0L;"))
    }

    /** Notify.glucosetimeout, read from its source: Notify cannot be loaded here. */
    private fun staleAfterMs(): Long {
        val notify = source("Common/src/main/java/tk/glucodata/Notify.java")
        assertTrue(notify.contains("glucosetimeoutSEC = 30 * 11;"))
        return 30L * 11L * 1000L
    }

    private fun source(relative: String): String =
        File(repoRoot(), relative).readText().replace("\r\n", "\n")

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "Common/src/main/cpp/g.cpp").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("repo root not found")
    }
}
