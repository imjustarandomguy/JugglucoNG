package tk.glucodata

import java.io.File
import org.junit.Assert.assertFalse
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
}
