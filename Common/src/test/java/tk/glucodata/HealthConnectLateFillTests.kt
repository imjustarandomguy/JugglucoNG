package tk.glucodata

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A reading written into a poll slot the Health Connect export has already passed (a Dexcom
 * backfill, a gap filled in from the watch, a refill after a rebase) moves the export cursor
 * back to it, and an export in flight does not move it forward again over that slot.
 * Source checks, like HealthConnectNativeRecordTests, plus the cursor rule itself compiled
 * on the host, like NativeMeasuredHistoryTests (requires a host C++ compiler).
 */
class HealthConnectLateFillTests {
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "Common/src/main/cpp/g.cpp").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("repo root not found")
    }

    private fun source(relative: String): String = File(repoRoot(), relative).readText()

    private fun flattened(relative: String): String = source(relative).replace(Regex("\\s+"), " ")

    private fun between(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("$from not found", start >= 0)
        val end = text.indexOf(to, start)
        assertTrue("$to not found after $from", end > start)
        return text.substring(start, end)
    }

    @Test
    fun everyPollWriterReportsTheSlotBeforeItWritesIt() {
        val hpp = flattened("Common/src/main/cpp/SensorGlucoseData.hpp")
        val writers = listOf(
            Triple("int savepollallIDsonly(", "int savepollallIDsonlyQuiet(", "polls[id] = {"),
            Triple("int savepollallIDsonlyQuiet(", "bool savepollallIDs(", "polls[id] = {"),
            Triple("bool saveStreamAgain(", "bool saveglucosedata(", "polls[index] = {"),
            Triple("bool saveglucosedata(", "bool hasStreamID(", "streamscans[count] = {"),
        )
        for ((from, to, write) in writers) {
            val body = between(hpp, from, to)
            val hook = body.indexOf("healthConnectSlotFilled(")
            val written = body.indexOf(write)
            assertTrue("$from must call healthConnectSlotFilled", hook >= 0)
            assertTrue("$from must report the slot while it still holds what was there", hook < written)
        }
        // Any other write of a reading into a poll slot has to go through the hook too: the
        // only unhooked ones are the empty placeholders savepollallIDsonly lays down.
        val writes = Regex("\\bpolls\\[(\\w+)\\] = \\{([^}]*)\\}").findAll(hpp).toList()
        assertTrue(writes.size >= 5)
        for (write in writes) {
            val (slot, value) = write.destructured
            if (value.trim() == "timiter, count, 0, 0, 0") continue
            val before = hpp.substring(maxOf(0, write.range.first - 120), write.range.first)
            assertTrue("polls[$slot] write without healthConnectSlotFilled", before.contains("healthConnectSlotFilled($slot, glu);"))
        }
    }

    @Test
    fun anExportInFlightDoesNotWriteOverARewind() {
        val jni = flattened("Common/src/main/cpp/g.cpp")
        val written = between(jni, "fromjava(healthConnectWritten)(", "fromjava(getSensorStartmsec)")
        assertTrue(written.contains("jlong sensorptr, jint from, jint pos"))
        assertTrue(
            "the cursor moves on only from where the batch started",
            written.contains("uint16_t expected = static_cast<uint16_t>(from);") &&
                written.contains("__atomic_compare_exchange_n(&info->healthconnectiter, &expected,"),
        )
        val health = flattened("Common/src/mobile/java/tk/glucodata/HealthConnection.kt")
        assertTrue(health.contains("if (!Natives.healthConnectWritten(sensorptr, start, start + take)) {"))
        val natives = flattened("Common/src/main/java/tk/glucodata/Natives.java")
        assertTrue(natives.contains("public static native boolean healthConnectWritten(long sensorptr, int from, int pos);"))
    }

    @Test
    fun cursorRuleMovesBackOnlyForAReadingInAnEmptySlotBelowIt() {
        val hpp = source("Common/src/main/cpp/SensorGlucoseData.hpp")
        val rule = between(hpp, "static constexpr int healthConnectCursorAfterFill(", "void healthConnectSlotFilled(")
        val test = """
            struct Rule {
            $rule
            };
            constexpr int after(int cursor, int slot, bool empty, int glu) {
              return Rule::healthConnectCursorAfterFill(cursor, slot, empty, glu);
            }
            static_assert(after(100, 40, true, 120) == 40, "a backfill below the cursor rewinds it");
            static_assert(after(100, 0, true, 120) == 0, "down to slot 0");
            static_assert(after(100, 40, false, 120) == 100, "rewriting a reading is no late fill");
            static_assert(after(100, 40, true, 0) == 100, "an empty placeholder is no reading");
            static_assert(after(100, 100, true, 120) == 100, "at the cursor it is sent anyway");
            static_assert(after(100, 140, true, 120) == 100, "above the cursor it is sent anyway");
            static_assert(after(100, -1, true, 120) == 100, "no slot");
            static_assert(after(0, 0, true, 120) == 0, "a cursor at 0 starts from pollstart");
            int main() { return 0; }
        """.trimIndent()
        val directory = Files.createTempDirectory("healthconnect-cursor-test").toFile()
        try {
            val file = File(directory, "test.cpp")
            file.writeText(test)
            val log = File(directory, "output.log")
            val command = listOf(System.getenv("CXX") ?: "c++", "-std=c++17", "-fsyntax-only", file.path)
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start()
            val completed = process.waitFor(60, TimeUnit.SECONDS)
            if (!completed) process.destroyForcibly()
            assertTrue("Timed out: $command", completed)
            assertEquals(log.readText(), 0, process.exitValue())
        } finally {
            directory.deleteRecursively()
        }
    }
}
