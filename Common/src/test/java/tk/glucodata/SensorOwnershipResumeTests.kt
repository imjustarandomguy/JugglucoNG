package tk.glucodata

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * release() pauses every callback filed under any of the sensor's names and closes
 * its GATT. A paused callback never dials again by itself, so taking the sensor back
 * has to un-pause that same set: un-pausing only the first match left the others
 * paused until a restart. These are source scans because the runtime needs the
 * Android runtime and the native library to be constructed at all.
 */
class SensorOwnershipResumeTests {
    @Test
    fun releasePausesEveryCallbackOfTheSensor() {
        val release = body("private fun release(serial: String)")
        assertTrue(release, release.contains("releasedGatts(serial).forEach"))
        assertTrue(release, release.contains("setPause(true)"))
    }

    @Test
    fun takingTheSensorBackResumesEveryCallbackReleasePaused() {
        val resume = body("private fun resume(serial: String")
        assertTrue(resume, resume.contains("releasedGatts(serial)"))
        assertTrue(resume, resume.contains("setPause(false)") && resume.contains("connectDevice("))
        assertFalse("resume() must not stop at the first match", resume.contains("findGatt("))
    }

    @Test
    fun recoveringFromStaleWearRoutingResumesEveryCallbackToo() {
        val recovery = body("private fun recoverDisabledCompanionRouting()")
        assertTrue(recovery, recovery.contains("releasedGatts(serial).forEach"))
        assertFalse(recovery, recovery.contains("findGatt("))
    }

    private fun body(signature: String): String {
        val text = File(repoRoot(), "Common/src/main/java/tk/glucodata/SensorOwnershipRuntime.kt").readText()
        val start = text.indexOf(signature)
        assertTrue("SensorOwnershipRuntime must declare $signature", start >= 0)
        val open = text.indexOf('{', start)
        var end = open
        var depth = 0
        do {
            if (text[end] == '{') depth++ else if (text[end] == '}') depth--
            end++
        } while (depth > 0 && end < text.length)
        return text.substring(open + 1, end - 1)
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "Common/src/main/java/tk/glucodata/SensorOwnershipRuntime.kt").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("Common/src not found from ${System.getProperty("user.dir")}")
    }
}
