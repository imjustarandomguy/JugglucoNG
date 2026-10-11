package tk.glucodata

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A G7 forgotten on the watch came back each time "Direct sensor on watch" was
 * switched on: the switch's handoff carried the phone's selected G7, ended or
 * not, and adopting it on the watch cleared the user's removal, so its driver
 * was rebuilt and paired the expired sensor again, a consent prompt about once
 * a minute. These are source scans: the handoff needs the Android runtime, and
 * the record the native library.
 */
class WearDexcomForgottenTests {
    @Test
    fun aRecordTheUserRemovedIsNotAdopted() {
        val adopt = after(source("Common/src/main/cpp/dexcom/java.cpp"), "fromjava(dexAdoptSensor)(")
        val guard = adopt.indexOf("wasRemovedByUser()")
        val shell = adopt.indexOf("ensureDirectStreamShell(")
        assertTrue("the removal is checked before the record is touched", guard in 0 until shell)
        assertTrue(adopt.substring(guard, shell).contains("return false;"))
    }

    @Test
    fun switchingOnHandsTheG7TheFollowRulePicks() {
        val payload = after(source("Common/src/main/java/tk/glucodata/ManagedSensorHandoff.kt"), "fun createOutgoingPayload(")
            .substringBefore("private fun buildPayload(")
        assertTrue(payload, payload.contains("switchOnDexcom()"))
        assertFalse("not the selected sensor's record", payload.contains("resolveMainSensor"))
        // What the phone remembers for that watch is the G7 it handed.
        val routing = after(source("Common/src/mobile/java/tk/glucodata/WearRoutingRequest.kt"), "fun record(")
            .substringBefore("fun revokeSensor(")
        assertTrue(routing, routing.contains("ManagedSensorHandoff.switchOnDexcom()"))
    }

    private fun after(text: String, marker: String): String {
        val at = text.indexOf(marker)
        assertTrue("missing: $marker", at >= 0)
        return text.substring(at + marker.length)
    }

    private fun source(path: String): String = File(repoRoot(), path).readText()

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "Common/src/main/cpp/g.cpp").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("repo root not found")
    }
}
