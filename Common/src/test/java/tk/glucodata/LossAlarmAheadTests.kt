package tk.glucodata

import java.io.File
import org.junit.Assert.assertEquals
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
