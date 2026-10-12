package tk.glucodata

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * For a sensor phone and watch both read, a device that misses a reading asks
 * for it from its loss alarm. A reading missed
 * while a device was down had nothing left to ask for it: the phone rebooted on
 * 2026-10-09 and never asked the watch, and the rebooted watch showed "No
 * value" until its app was opened on 2026-10-10.
 *
 * Source checks, like GlucoseAlarmsAsymmetryTests: these paths run on the
 * ownership executor against Natives and the Data Layer.
 */
class WearStartupSyncTests {
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

    private val runtime get() = source("Common/src/main/java/tk/glucodata/SensorOwnershipRuntime.kt")

    private fun after(source: String, anchor: String): String {
        assertTrue("anchor not found: $anchor", source.contains(anchor))
        return source.substringAfter(anchor)
    }

    private fun body(source: String, signature: String): String =
        after(source, signature).substringBefore("\n    }\n")

    @Test
    fun aDeviceThatHasNotHeardThePeerSinceItStartedAsksItToAnswer() {
        val announce = body(runtime, "private fun announce(answering: Boolean = false)")
        assertTrue(announce, announce.contains("val askAnswer = !peerHeardSinceStart"))
        assertTrue(announce, announce.contains("DirectReadingStatus.wireAge(direct, now), askAnswer)"))
        // An answer goes out whatever changed.
        assertTrue(announce, announce.contains("if (!answering && previous == owns"))
    }

    @Test
    fun thePeerAnswersOnceForAllItsReports() {
        val onReport = body(runtime, "fun onPeerReport(data: ByteArray?)")
        assertTrue(onReport, onReport.contains("peerHeardSinceStart = true"))
        assertTrue(onReport, onReport.contains("report.answerRequested && receivedAt - lastAnsweredAtMs >= ANSWER_MIN_INTERVAL_MS"))
        assertTrue(onReport, onReport.contains("announce(answering = true)"))
    }

    @Test
    fun theFirstReportSinceStartingAsksForWhatWasMissedOnce() {
        val onReport = body(runtime, "fun onPeerReport(data: ByteArray?)")
        assertTrue(onReport, onReport.contains("val firstSinceStart = !peerHeardSinceStart"))
        assertTrue(onReport, after(onReport, "if (firstSinceStart) {").contains("requestMissedWhileDown()"))
        val ask = body(runtime, "private fun requestMissedWhileDown()")
        // Only for a sensor both read, and incremental: not the whole horizon.
        assertTrue(ask, ask.contains("sensors().firstOrNull { readsAlongside(it) } ?: return"))
        assertTrue(ask, ask.contains("WearSync2.requestSync()"))
        assertFalse(ask, ask.contains("deep"))
    }

    @Test
    fun itTakesOnlyTheSlotsItLacks() {
        val onChunk = body(source("Common/src/main/java/tk/glucodata/WearSync2.kt"), "fun onChunk(data: ByteArray?)")
        assertTrue(
            onChunk,
            onChunk.contains(
                "val missingOnly = SensorOwnershipRuntime.readsLocally(serial) ||\n" +
                    "                    SensorOwnershipRuntime.readsAlongside(serial)",
            ),
        )
        assertTrue(onChunk, after(onChunk, "val alreadyHave = if (missingOnly) {").trimStart()
            .startsWith("runCatching { Natives.streamSlotsFilled(times, serial) }"))
    }

    @Test
    fun theLossAlarmIsArmedAtStartForASensorBothReadWithoutTheAlert() {
        val alarms = source("Common/src/main/java/tk/glucodata/SuperGlucoseAlarms.java")
        val setLoss = after(alarms, "public  void setLossAlarm() {").substringBefore("public void setagealarm(")
        val otherwise = after(setLoss, "else if(SensorOwnershipRuntime.anySetToReadAlongside()) {")
        // Unless one is set to go off sooner (LossAlarmAheadTests).
        assertTrue(otherwise, otherwise.contains("LossOfSensorAlarm.setalarmNoLater(Applic.app, System.currentTimeMillis() + Notify.glucosetimeout);"))
        // It is the one the start of Bluetooth already arms.
        val bluetooth = source("Common/src/main/java/tk/glucodata/SensorBluetooth.java")
        assertTrue(after(bluetooth, "static void start(boolean usebluetooth) {").contains("SuperGattCallback.glucosealarms.setLossAlarm();"))
    }
}
