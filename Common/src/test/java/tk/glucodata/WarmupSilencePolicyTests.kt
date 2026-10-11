package tk.glucodata

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A new Dexcom stays silent for most of its 30-minute warmup. Every reading of another sensor
 * runs SensorBluetooth.othersworking, which reconnected it once its warmup frame was 12 minutes
 * old and recorded each of those reconnects as "Loss of signal": the status line and the error
 * history of a sensor that was doing exactly what it should.
 */
class WarmupSilencePolicyTests {
    private val start = 1_791_662_047_000L
    private val minute = 60_000L
    private val warmup = 30 * minute

    @Test
    fun aSessionWithoutReadingIsWarmingUpUntilItsFirstSlotsAfterWarmup() {
        assertTrue(WarmupSilencePolicy.awaitingFirstReading(start, warmup, 0L, start))
        assertTrue(WarmupSilencePolicy.awaitingFirstReading(start, warmup, 0L, start + 14 * minute))
        assertTrue(WarmupSilencePolicy.awaitingFirstReading(start, warmup, 0L, start + 45 * minute - 1))
        assertFalse(WarmupSilencePolicy.awaitingFirstReading(start, warmup, 0L, start + 45 * minute))
    }

    @Test
    fun aReadingEndsTheWarmupAtOnce() {
        assertFalse(WarmupSilencePolicy.awaitingFirstReading(start, warmup, start + 28 * minute, start + 40 * minute))
    }

    @Test
    fun anUnknownOrFutureStartIsNotAWarmup() {
        assertFalse(WarmupSilencePolicy.awaitingFirstReading(0L, warmup, 0L, start))
        assertFalse(WarmupSilencePolicy.awaitingFirstReading(start, warmup, 0L, start - 1))
        assertFalse(WarmupSilencePolicy.awaitingFirstReading(start, 0L, 0L, start))
    }

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val file = File(dir, "Common/$relative").takeIf { it.isFile } ?: File(dir, relative)
            if (file.isFile) return file.readText().replace(Regex("\\s+"), " ")
            dir = dir.parentFile
        }
        throw AssertionError("$relative not found from ${System.getProperty("user.dir")}")
    }

    @Test
    fun reconnectRecordsALossOnlyOutsideTheWarmup() {
        val text = source("src/main/java/tk/glucodata/SuperGattCallback.java")
        val reconnect = text.substring(text.indexOf("public boolean reconnect(long now)"), text.indexOf("void setConStatus("))
        assertTrue(
            "reconnect must not record a warming sensor's silence as a loss",
            reconnect.contains("if (!awaitingFirstReading(System.currentTimeMillis())) { noteLossOfSignal(now); }"),
        )
        assertTrue("a warming sensor is still reconnected", reconnect.contains("return connectDevice(0);"))
    }

    @Test
    fun dexcomAnswersFromItsWarmupAndFirstReading() {
        val text = source("src/dex/java/tk/glucodata/DexGattCallback.java")
        val override = text.substring(text.indexOf("protected boolean awaitingFirstReading(long nowMs)"))
        assertTrue(
            "Dexcom must take its warmup from the session start and its own readings",
            override.substring(0, override.indexOf('}')).contains(
                "return WarmupSilencePolicy.awaitingFirstReading(sensorstartmsec, DEXCOM_WARMUP_MSEC, charcha[0], nowMs);"
            ),
        )
    }
}
