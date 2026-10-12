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

    /**
     * A reading wakes the uploader for glucose only. It also gets treatments read back, but
     * through the treatments' own branch and only where that branch would run anyway: after the
     * Wi-Fi gate, outside the hold after a refusal, not on the heels of a failed glucose upload,
     * and never as a full pass. It is asked only on a pass nothing else raised treatments for:
     * Java takes a yes to mean the pass is the reading's alone and reads light, so a pass owed
     * a full read for another reason must not ask.
     */
    @Test
    fun aReadingRaisesATreatmentReadNotAFullPass() {
        val text = uploader()
        val thread = text.substring(text.indexOf("static void uploaderthread()"))
        val ask = thread.indexOf("treatmentReadDueViaJava(useV3)")
        assertTrue("the uploader thread does not ask whether a reading should read treatments", ask >= 0)
        assertTrue(
            "the question is asked before the network is",
            ask > thread.indexOf("if(!uploadNetworkAllowed())")
        )
        val start = thread.lastIndexOf("if(treatmentsdue", ask)
        assertTrue("the question is asked outside the treatment hold", start >= 0 && ask - start < 300)
        val condition = thread.substring(start, ask)
        for (part in listOf(
            "!glucosefailed",
            "(current&Backup::wakestream)",
            "!(current&(Backup::wakenums|Backup::wakeall|Backup::waketreatments))",
        )) {
            assertTrue("a reading raises the read without $part", condition.contains(part))
        }
        val raised = thread.substring(ask, thread.indexOf("}", ask))
        assertTrue("a reading raises the treatments' own wake", raised.contains("current|=Backup::waketreatments"))
        assertFalse("a reading raises a full pass", raised.contains("wakeall"))

        val post = File(moduleRoot, "src/main/java/tk/glucodata/NightPost.java").readText()
        assertTrue(
            "the Java side of treatmentReadDue(Z)Z is gone",
            Regex("""static\s+public\s+boolean\s+treatmentReadDue\(boolean\s+useV3\)""").containsMatchIn(post) &&
                text.contains("\"treatmentReadDue\",\"(Z)Z\""),
        )
    }

    /**
     * Treatments entered on the server were read only when something else raised a treatment
     * pass, so opening the app to look found nothing new. Coming to the front raises one, and
     * the treatments' own wake: a full "Send now" pass would also end the backoff after a
     * refusal.
     */
    @Test
    fun comingToTheFrontWakesATreatmentReadOnTheTreatmentsWake() {
        val activity = File(moduleRoot, "src/main/java/tk/glucodata/MainActivity.java").readText()
        val resume = activity.substring(activity.indexOf("protected void onResume()"), activity.indexOf("protected void onStart()"))
        assertTrue(
            "onResume does not call NightscoutUploadWake.afterAppToFront() for the phone",
            Regex("""!Applic\.isWearable\s*&&\s*Applic\.Nativesloaded\)\s*\{[^}]*NightscoutUploadWake\.afterAppToFront\(\)""")
                .containsMatchIn(resume),
        )

        val wake = File(moduleRoot, "src/main/java/tk/glucodata/NightscoutUploadWake.kt").readText()
        val start = wake.indexOf("fun afterAppToFront()")
        assertTrue("afterAppToFront() is not in NightscoutUploadWake any more", start >= 0)
        val body = wake.substring(start, wake.indexOf("@JvmStatic", start))
        assertTrue("afterAppToFront() no longer wakes the treatments", body.contains("Natives.waketreatments()"))
        assertFalse(
            "afterAppToFront() raises a full pass, which also drops the treatment backoff",
            Regex("""Natives\.wakeuploader(now)?\(\)""").containsMatchIn(body),
        )
        assertTrue(
            "afterAppToFront() wakes without 'Receive treatments' on",
            body.contains("getReceiveTreatments()"),
        )
        assertTrue("afterAppToFront() wakes without the uploader set up", body.contains("getuseuploader()"))
    }

    /**
     * The full read's five-minute floor held a dose entered just after a read for up to five
     * minutes, however often the app was opened. Coming to the front asks for a light read, and
     * asks before it wakes, or the pass the wake brings could run without seeing the request.
     */
    @Test
    fun comingToTheFrontAsksForALightReadBeforeItsWake() {
        val wake = File(moduleRoot, "src/main/java/tk/glucodata/NightscoutUploadWake.kt").readText()
        val start = wake.indexOf("fun afterAppToFront()")
        assertTrue("afterAppToFront() is not in NightscoutUploadWake any more", start >= 0)
        val body = wake.substring(start, wake.indexOf("@JvmStatic", start))
        val request = body.indexOf("JournalTreatmentUploadAccess.requestLightReceive()")
        assertTrue("afterAppToFront() no longer asks for a light read", request >= 0)
        assertTrue("the light read is asked for after the wake", request < body.indexOf("Natives.waketreatments()"))
        assertTrue(
            "the light read is asked for before 'Receive treatments' is checked",
            request > body.indexOf("getReceiveTreatments()"),
        )
    }
}
