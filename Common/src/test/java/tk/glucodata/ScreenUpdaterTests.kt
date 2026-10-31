package tk.glucodata

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watch face registers its redraw with [Applic.setscreenupdater] once, when
 * it is created, and takes it back when it is destroyed. [Applic.updatescreen]
 * cleared the list after running it, so only the first reading after the face
 * came up redrew it; every later one waited for the face's own minute frame.
 *
 * Source checks: Applic cannot be loaded without an Android application.
 */
class ScreenUpdaterTests {
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

    private fun body(source: String, signature: String): String {
        assertTrue("not found: $signature", source.contains(signature))
        return source.substringAfter(signature).substringBefore("\n    }\n")
    }

    @Test
    fun everyReadingRunsTheUpdatersAndKeepsThem() {
        val applic = source("Common/src/main/java/tk/glucodata/Applic.java")
        val update = body(applic, "static void updatescreen() {")
        assertTrue(update, update.contains("for (var el : updaters)\n            el.run();"))
        assertFalse(update, update.contains("updaters.clear()"))
        // Run on the reading threads while the face's main thread adds and removes.
        assertTrue(
            applic.contains(
                "static private final java.util.List<Runnable> updaters = " +
                    "new java.util.concurrent.CopyOnWriteArrayList<>();",
            ),
        )
    }
}
