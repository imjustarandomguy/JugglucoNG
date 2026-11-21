package tk.glucodata

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watch's "Read on the watch" switch: off stops the watch's sensor Bluetooth
 * (keeping the pairing), on starts it, and both tell the phone, whose "Direct
 * sensor on watch" follows through the handler the phone registers at start.
 */
class WearReadOnWatchWiringTests {
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "Common/src/main/java/tk/glucodata/WearSensorClaim.kt").isFile) return dir
            dir = dir.parentFile
        }
        error("repository root not found")
    }

    private fun source(relative: String): String =
        File(repoRoot(), relative).readText().replace(Regex("\\s+"), " ")

    @Test
    fun thePhoneFollowsAStartOnTheWatch() {
        val specific = source("Common/src/mobile/java/tk/glucodata/Specific.java")
        assertTrue(specific.contains("WearSensorClaimStatus.setStartedOnWatchHandler(WatchInterop::onWatchStartedDirect);"))
        val interop = source("Common/src/mobile/java/tk/glucodata/WatchInterop.kt")
        assertTrue(interop.contains("if (WearRoutingRequest.directRequested(nodeId)) return"))
        assertTrue(interop.contains("applyStandaloneSensorMode(nodeId, isGalaxy, directOnWatch = true, enterOnWatch = enter)"))
    }

    @Test
    fun theSwitchMovesTheWatchRadioBothWays() {
        val claim = source("Common/src/main/java/tk/glucodata/WearSensorClaim.kt")
        assertTrue(claim.contains("publishState(stoppedOnWatch = true) setSensorBluetooth(false)"))
        assertTrue(claim.contains("publishState(startedOnWatch = true) setSensorBluetooth(true)"))
        // Automatic switching keeps the radio, as the phone's /bluetooth off does.
        assertTrue(claim.contains("if (!on && runCatching { AutoSensorSwitch.isEnabled() }.getOrDefault(false)) return"))
        val screen = source("Common/src/wear/java/tk/glucodata/ui/screens/SensorScreen.kt")
        assertTrue(screen.contains("if (on) tk.glucodata.WearSensorClaim.startOnWatch() else tk.glucodata.WearSensorClaim.stopOnWatch()"))
    }
}
