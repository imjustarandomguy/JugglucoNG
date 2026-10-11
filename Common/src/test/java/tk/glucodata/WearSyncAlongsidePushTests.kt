package tk.glucodata

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * While phone and watch both read a G7, the phone left out its push of every
 * reading. A watch that held the sensor without reading it (its bond lost,
 * 2026-10-11) then got each value only from its loss alarm, 320 s late. The
 * phone now leaves a push out only for a reading the watch said it read itself
 * ([DirectReadingStatus.phonePushesWhileBothRead]).
 *
 * Source checks, like WearStartupSyncTests: the push runs on the sync executor
 * against Room and the Data Layer.
 */
class WearSyncAlongsidePushTests {
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

    private val sync get() = source("Common/src/main/java/tk/glucodata/WearSync2.kt")

    private fun after(source: String, anchor: String): String {
        assertTrue("anchor not found: $anchor", source.contains(anchor))
        return source.substringAfter(anchor)
    }

    private fun body(source: String, signature: String): String =
        after(source, signature).substringBefore("\n    }\n")

    @Test
    fun whileBothReadOnlyTheWatchLeavesItsRoutinePushOut() {
        val serve = body(sync, "private fun serveSince(fromSec: Long, routine: Boolean = false)")
        assertTrue(serve, serve.contains("val bothRead = routine && SensorOwnershipRuntime.bothRead(serial)"))
        assertTrue(serve, serve.contains("if (bothRead && Applic.isWearable) return@forEach"))
        assertTrue(serve, serve.contains("serveSensorSince(serial, fromSec, unlessWatchReadIt = bothRead)"))
        assertFalse(serve, serve.contains("if (routine && SensorOwnershipRuntime.bothRead(serial)) return@forEach"))
    }

    @Test
    fun thePhoneLeavesItOutOnlyForTheNewestReadingTheWatchReadItself() {
        val serve = body(
            sync,
            "private fun serveSensorSince(serial: String, fromSec: Long, unlessWatchReadIt: Boolean = false)",
        )
        val decide = after(serve, "if (unlessWatchReadIt) {")
        assertTrue(decide, decide.contains("val newestMs = points.lastOrNull()?.timestamp ?: 0L"))
        assertTrue(decide, decide.contains("val watch = SensorOwnershipRuntime.peerDirectReading(serial)"))
        assertTrue(decide, decide.contains("DirectReadingStatus.phonePushesWhileBothRead(watch, newestMs)"))
        // Decided before anything goes out: the calibration travels with a push only.
        val leftOut = serve.indexOf("if (!push) return")
        assertTrue(serve, leftOut >= 0 && leftOut < serve.indexOf("sendCalibration(serial)"))
    }

    @Test
    fun theWatchsWordIsItsOwnNewestReadingNotThatItHoldsTheSensor() {
        val runtime = source("Common/src/main/java/tk/glucodata/SensorOwnershipRuntime.kt")
        val peer = body(runtime, "fun peerDirectReading(serial: String?): DirectReadingFact")
        assertTrue(peer, peer.contains("peerReportFor(target)?.directReading ?: DirectReadingFact.UNKNOWN"))
        assertFalse(peer, peer.contains("owns"))
    }

    @Test
    fun aReadingTheDriverHandsOnIsStillPushed() {
        val gatt = source("Common/src/main/java/tk/glucodata/SuperGattCallback.java")
        assertTrue(gatt.contains("if (pushToPeer)\n            WearSync2.pushTail(SerialNumber, timmsec);"))
        assertTrue(after(sync, "fun pushTail(serial: String?, readingMs: Long) {").contains("serveSince(tailStartSec(), routine = true)"))
    }

    @Test
    fun thePushCarriesTheReadingItFollows() {
        val gatt = source("Common/src/main/java/tk/glucodata/SuperGattCallback.java")
        val accepted = after(gatt, "markLocalReadingAccepted(timmsec);\n            // The push that follows")
            .substringBefore("dowithglucose(")
        assertTrue(
            accepted,
            accepted.contains("WearSync2.noteLocalReading(SerialNumber, nativeReadingTimeMs(SerialNumber, timmsec), autoMgdl, rawMgdl);"),
        )
        val serve = body(
            sync,
            "private fun serveSensorSince(serial: String, fromSec: Long, unlessWatchReadIt: Boolean = false)",
        )
        // Merged before the newest is taken, so "the watch read it" is decided on this reading.
        val merged = serve.indexOf("val points = WearPushGate.withReading(stored, takenReading(serial), fromSec * 1000L)")
        assertTrue(serve, merged >= 0 && merged < serve.indexOf("val newestMs = points.lastOrNull()?.timestamp ?: 0L"))
        // The gate decides before anything is queued: no more dropped pushes for newer readings.
        val push = body(sync, "fun pushTail(serial: String?, readingMs: Long)")
        assertTrue(push, push.contains("pushGate.shouldPush(sensorKey(serial).orEmpty(), readingMs, now)"))
        assertFalse(push, push.contains("compareAndSet"))
    }
}
