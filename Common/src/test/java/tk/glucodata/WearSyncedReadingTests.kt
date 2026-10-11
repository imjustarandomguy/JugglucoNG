package tk.glucodata

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A reading the other device pushed was stored here and nothing else: the 15 s
 * alert check took it up only when the previous reading left the live slot,
 * half a minute later for a G7. Until then the screens resolved the previous
 * reading, the watch face was not redrawn and the loss alarm counted from the
 * previous reading. The newest synced reading now goes the way a reading taken
 * here goes, once and at once.
 *
 * The decision is pure; the wiring is checked in the source, like
 * WearSyncAlongsidePushTests: it runs on the sync executor against native
 * storage and the alarm runtime.
 */
class WearSyncedReadingTests {
    private val t0 = 1_791_000_000_000L
    // Notify.glucosetimeout.
    private val stale = 330_000L

    @Test
    fun theNewestReadingAPushBringsIsTheCurrentOne() {
        assertTrue(WearSync2.isCurrentPeerReading(t0, t0 - 300_000L, t0 + 5_000L, stale))
        // The other device's clock a little ahead.
        assertTrue(WearSync2.isCurrentPeerReading(t0, t0 - 300_000L, t0 - 2_000L, stale))
    }

    @Test
    fun aRepeatOrABackfillIsHistory() {
        // The tail again: this device has that reading already.
        assertFalse(WearSync2.isCurrentPeerReading(t0, t0, t0 + 50_000L, stale))
        // Filled in behind this device's own newest.
        assertFalse(WearSync2.isCurrentPeerReading(t0 - 300_000L, t0, t0 + 5_000L, stale))
        // A backfill of a sensor this device has no newer reading of: too old to be shown.
        assertFalse(WearSync2.isCurrentPeerReading(t0 - 3_600_000L, 0L, t0, stale))
        // Native storage could not say what it had.
        assertFalse(WearSync2.isCurrentPeerReading(t0, Long.MAX_VALUE, t0 + 5_000L, stale))
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

    private val sync get() = source("Common/src/main/java/tk/glucodata/WearSync2.kt")
    private val gatt get() = source("Common/src/main/java/tk/glucodata/SuperGattCallback.java")

    private fun after(source: String, anchor: String): String {
        assertTrue("anchor not found: $anchor", source.contains(anchor))
        return source.substringAfter(anchor)
    }

    private fun body(source: String, signature: String): String =
        after(source, signature).substringBefore("\n    }\n")

    @Test
    fun eitherDeviceHandlesTheNewestStoredReadingAsItsCurrentOne() {
        val onChunk = body(sync, "fun onChunk(data: ByteArray?)")
        // What this device had is taken before the chunk is stored.
        val before = onChunk.indexOf("val newestBeforeMs = runCatching { Natives.lastglucosetime() }")
        assertTrue(onChunk, before >= 0 && before < onChunk.indexOf("Natives.addGlucoseStreamBatchWithRawTemp("))
        val handled = after(onChunk, "// below, so nothing resolves the reading it replaces.\n                if (written > 0) {")
        assertTrue(
            handled,
            handled.contains(
                "isCurrentPeerReading(stamps[newest], newestBeforeMs, System.currentTimeMillis(), Notify.glucosetimeout)",
            ),
        )
        assertTrue(handled, handled.contains("handlePeerReading(serial, stamps[newest], values[newest], raws[newest])"))
        // It is the live reading before the data refresh asks anything to resolve one.
        assertTrue(handled, handled.indexOf("handlePeerReading(") < handled.indexOf("UiRefreshBus.requestDataRefresh("))
        assertTrue(
            body(sync, "private fun handlePeerReading(").contains("SuperGattCallback.processPeerReading("),
        )
    }

    @Test
    fun itGoesTheWayALocalReadingGoesButIsNotPushedBack() {
        val peer = body(gatt, "public static void processPeerReading(")
        assertTrue(
            peer,
            peer.contains(
                "dowithglucose(sensorSerial, mgdl, gl, rate, 0, timmsec, sensorStartMs(sensorSerial),\n" +
                    "                showtimeFor(sensorSerial), 0, reading, true);",
            ),
        )
        val dispatch = after(gatt, "LiveReadingLanes reading, boolean fromPeer) {")
            .substringBefore("\n    static void emitExchangeOutputs(")
        assertTrue(dispatch, dispatch.contains("if (!acceptRealtimeReading(SerialNumber, timmsec, fromPeer)) {"))
        // The loss alarm counts from the reading, by the rule a local reading follows.
        assertTrue(
            dispatch,
            dispatch.contains(
                "glucosealarms.setagealarm(timmsec,\n" +
                    "                LossAlarmAhead.showtime(showtime, SensorOwnershipRuntime.bothRead(SerialNumber)));",
            ),
        )
        // The watch face, the complications and the data refresh.
        assertTrue(dispatch, dispatch.contains("Applic.updatescreen();"))
        assertTrue(dispatch, dispatch.contains("if (refresh != null) refresh.updateAll();"))
        assertTrue(dispatch, dispatch.contains("sglucose.value, !fromPeer);"))
        // The loss alarm's delay is the driver's here, as for a reading read here.
        assertTrue(body(gatt, "private static long showtimeFor(String sensorSerial)").contains("return callback.showtime;"))
    }

    /**
     * The phone sent a reading the watch synced to its exchange outputs from the
     * sync, and its alert check then took the same reading through
     * dowithglucose: alarms, and every exchange output a second time.
     */
    @Test
    fun theAlertCheckTookASyncedReadingThroughTheReadingPathAgain() {
        val previous = t0 - 300_000L
        // The sync stored it in native, so the realtime order let the check's copy through.
        assertTrue(RealtimeReadingPolicy.shouldDispatch(t0, previous, t0))
        // The check hands on any reading newer than the last one it saw delivered that
        // is not the live callback's; only onNewReading moves that mark.
        val alerts = source("Common/src/main/java/tk/glucodata/alerts/AlertRuntimeManager.kt")
        val check = body(alerts, "private fun syncCurrentReadingLocked()")
        assertTrue(check, check.contains("if (latest.timeMillis <= lastDeliveredReadingTimeMs) {"))
        assertTrue(check, check.contains("if (latest.source == \"callback\") {"))
        assertTrue(check, check.contains("SuperGattCallback.processExternalCurrentReading("))
        val onNewReading = after(alerts, "reading: LiveReadingLanes,\n        rate: Float,")
            .substringBefore("private fun ensureTaskLocked()")
        assertTrue(onNewReading, onNewReading.contains("lastDeliveredReadingTimeMs = maxOf(lastDeliveredReadingTimeMs, readingTimeMs)"))
    }

    @Test
    fun aSyncedReadingNowGoesThroughTheReadingPathOnceOnThePhoneToo() {
        val onChunk = body(sync, "fun onChunk(data: ByteArray?)")
        // No exchange-only path beside it, and nothing restricted to the watch.
        assertFalse(onChunk, onChunk.contains("emitExchangeOutputs"))
        assertFalse(sync, sync.contains("SuperGattCallback.emitExchangeOutputs("))
        assertFalse(onChunk, onChunk.contains("if (written > 0 && Applic.isWearable) {"))
        // dowithglucose delivers it to the alert engine (the check skips it after
        // that) and makes it the live callback reading, then sends the outputs once.
        val dispatch = after(gatt, "LiveReadingLanes reading, boolean fromPeer) {")
            .substringBefore("\n    static void emitExchangeOutputs(")
        val delivered = dispatch.indexOf("AlertRuntimeManager.INSTANCE.onNewReading(SerialNumber,")
        val live = dispatch.indexOf("previousglucose = sglucose;")
        val outputs = dispatch.indexOf("emitExchangeOutputs(SerialNumber, gl, rate, alarm, timmsec")
        assertTrue(dispatch, delivered in 0 until outputs && live in 0 until outputs)
    }
}
