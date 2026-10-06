package tk.glucodata

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Facts about the native Nightscout uploader that are wiring rather than arithmetic, checked in
 * its source. There is no C++ test target that Gradle runs (see [NightscoutPayloadWriterShapeTests]),
 * so this is the weak kind of test: it does not run the uploader, it only keeps a removed or
 * rewired path from coming back unnoticed.
 */
class NightscoutUploaderWiringTests {

    private val moduleRoot = File("").absoluteFile.let { working ->
        generateSequence(working) { it.parentFile }
            .firstOrNull { File(it, "src/main/cpp/net/watchserver/uploader.cpp").exists() }
            ?: working
    }

    private fun uploader() = File(moduleRoot, "src/main/cpp/net/watchserver/uploader.cpp").readText()
    private fun backupjava() = File(moduleRoot, "src/main/cpp/backupjava.cpp").readText()

    /** The body of the JNI function `fromjava(name)`, up to the next one. */
    private fun jniFunction(text: String, name: String): String {
        val start = text.indexOf("fromjava($name)")
        assertTrue("fromjava($name) is not in the file any more, so this test is vacuous", start >= 0)
        val end = text.indexOf("fromjava(", start + 1).takeIf { it > 0 } ?: text.length
        return text.substring(start, end)
    }

    /**
     * A 404 from v1 used to turn the v3 setting on for good. It is the user's setting, and the
     * answer may not even come from Nightscout (another device at a LAN address, away from home).
     */
    @Test
    fun theUploaderNeverChangesTheConfiguredApiVersion() {
        assertFalse(
            "uploader.cpp writes settings->data()->nightscoutV3",
            Regex("""nightscoutV3\s*=(?!=)""").containsMatchIn(uploader()),
        )
    }

    /**
     * A server reachable only at home refuses everything away from it, and the treatment backoff
     * then grows to four hours. Coming back to a network has to end it, or treatments wait out
     * those hours with the server in reach.
     */
    @Test
    fun aNetworkChangeEndsTheTreatmentBackoff() {
        for (callback in listOf("networkpresent", "networkhandover")) {
            val body = jniFunction(backupjava(), callback)
            assertEquals(
                "$callback wakes the uploader once, ending its backoff",
                1,
                Regex("""\bwakeuploadernow\(\);""").findAll(body).count(),
            )
            assertFalse(
                "$callback also wakes the uploader the old way",
                Regex("""\bwakeuploader\(\);""").containsMatchIn(body),
            )
        }
    }

    /** steady_clock stops while the phone sleeps, so a 15-minute hold lasted hours. */
    @Test
    fun theTreatmentBackoffRunsOnTheClockThatCountsSleep() {
        val text = uploader()
        assertFalse("uploader.cpp measures time on steady_clock again", text.contains("steady_clock::"))
        assertTrue(
            "the treatment hold is set from elapsedRealtimeMilliseconds()",
            Regex("""treatmentnextattemptms\s*=\s*nowms\s*\+""").containsMatchIn(text) &&
                Regex("""nowms\s*=\s*elapsedRealtimeMilliseconds\(\)""").containsMatchIn(text),
        )
    }

    /**
     * "Upload only on Wi-Fi" is a promise that nothing goes out over mobile data, so the
     * pass asks before its first request of any kind: glucose, treatments, device status.
     */
    @Test
    fun aPassAsksForItsNetworkBeforeSendingAnything() {
        val text = uploader()
        val thread = text.substring(text.indexOf("static void uploaderthread()"))
        val gate = thread.indexOf("if(!uploadNetworkAllowed())")
        assertTrue("the uploader thread no longer asks uploadNetworkAllowed()", gate >= 0)
        for (request in listOf("uploadCGM3(", "uploadCGM(", "uploadJournalTreatmentsViaJava(", "uploadDeviceStatus();")) {
            val at = thread.indexOf(request)
            assertTrue("$request is not in the uploader thread any more, so this test is vacuous", at >= 0)
            assertTrue("$request comes before the network is asked", at > gate)
        }
    }
}
