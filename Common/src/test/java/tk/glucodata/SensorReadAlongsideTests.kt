package tk.glucodata

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * After every install or reboot the phone handed its G7 to the watch ("handing
 * … to the watch: standing down for 180s") and missed the next reading: the
 * first reconciliation ran before the G7's driver existed, and only the driver
 * could say the G7 is read alongside the watch. The record says it too.
 */
class SensorReadAlongsideTests {
    @Test
    fun theDriverDecidesWhileThereIsOne() {
        assertTrue(resolveReadsAlongside(true) { false })
        assertFalse(resolveReadsAlongside(false) { true })
    }

    @Test
    fun beforeItsDriverExistsTheRecordDecides() {
        assertTrue(resolveReadsAlongside(null) { true })
        assertFalse(resolveReadsAlongside(null) { false })
    }

    @Test
    fun theRecordIsNotAskedWhileThereIsADriver() {
        var asked = false
        resolveReadsAlongside(true) { asked = true; true }
        assertFalse(asked)
    }

    @Test
    fun noDeviceStandsDownFromASensorReadAlongsideBeforeItsDriverExists() {
        val reconcile = after(runtime(), "private fun reconcile()")
        val decision = reconcile.substringBefore("SensorOwnershipPolicy.shouldReadLocally(")
        assertTrue(decision, decision.contains("val shouldRead = if (readsAlongside(serial))"))
        assertFalse(decision, decision.contains("findGatt(serial)?.readsAlongside()"))
    }

    @Test
    fun theRecordThatReadsAlongsideIsADexcomOne() {
        val runtime = runtime()
        assertTrue(runtime.contains("Natives.dexHandoff(serial) != null"))
        // A driver reading alongside is a Dexcom one: the record check must match it.
        val dex = File(repoRoot(), "Common/src/dex/java/tk/glucodata/DexGattCallback.java").readText()
        assertTrue(after(dex, "public boolean readsAlongside()").substringBefore("}").contains("return true;"))
    }

    private fun after(source: String, marker: String): String {
        val at = source.indexOf(marker)
        assertTrue("missing: $marker", at >= 0)
        return source.substring(at + marker.length)
    }

    private fun runtime(): String =
        File(repoRoot(), "Common/src/main/java/tk/glucodata/SensorOwnershipRuntime.kt").readText()

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "Common/src/main/cpp/g.cpp").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("repo root not found")
    }
}
