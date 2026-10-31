package tk.glucodata

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NumAlarm is what starts the service again when nothing else will: after a reboot, and after
 * an update of the app, which ends the process and its service. Android lets a receiver of
 * either broadcast start a foreground service from the background. Without the second, an
 * updated watch had its process back (a message from the phone starts it) but not its
 * service, and was frozen between readings until the app was opened.
 *
 * Both broadcasts reach NumAlarm only through its filter in the shared manifest, which both
 * variants merge.
 */
class ServiceRestartManifestTests {

    private val moduleRoot = File("").absoluteFile.let { working ->
        // The test runs from the module directory; walk up if it does not.
        generateSequence(working) { it.parentFile }
            .firstOrNull { File(it, "src/main/java/tk/glucodata/NumAlarm.java").exists() }
            ?: working
    }

    /** The actions the NumAlarm receiver's filter declares in the shared manifest. */
    private fun numAlarmActions(): List<String> {
        val text = File(moduleRoot, "src/main/AndroidManifest.xml").readText()
        val receiverAt = text.indexOf("android:name=\".NumAlarm\"")
        assertTrue("the shared manifest declares no NumAlarm receiver", receiverAt >= 0)
        val receiverEnd = text.indexOf("</receiver>", receiverAt)
        assertTrue("NumAlarm's receiver element is not closed", receiverEnd > receiverAt)
        return Regex("""<action android:name="([^"]+)"""")
            .findAll(text.substring(receiverAt, receiverEnd))
            .map { it.groupValues[1] }
            .toList()
    }

    @Test
    fun theServiceComesBackAfterAReboot() {
        assertTrue(numAlarmActions().contains("android.intent.action.BOOT_COMPLETED"))
    }

    @Test
    fun theServiceComesBackAfterAnUpdateOfTheApp() {
        assertTrue(
            "NumAlarm must receive MY_PACKAGE_REPLACED, or an updated app runs without its " +
                "service until it is opened",
            numAlarmActions().contains("android.intent.action.MY_PACKAGE_REPLACED"),
        )
    }
}
