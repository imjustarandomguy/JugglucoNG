package tk.glucodata

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.DisplayedReading.Reason

/**
 * A change of unit, view mode or smoothing changes what every surface draws without a new
 * reading: each place it is made asks the current reading's hub to resolve again, so no
 * surface keeps the old unit or lanes until the next reading.
 */
class CurrentReadingSettingsTests {
    private val minute = 60_000L
    private val t0 = 1_700_000_000_000L
    private val sensor = "SENSOR-1"

    private fun reading(text: String, isMmol: Boolean = false, viewMode: Int = 0) = ReadingHub.Reading(
        sensorId = sensor,
        timeMillis = t0,
        value = text.toFloat(),
        valueText = text,
        secondaryText = null,
        tertiaryText = null,
        isMmol = isMmol,
        viewMode = viewMode,
        rate = 0.5f,
        index = 0,
    )

    private class NoScheduler : ReadingScheduler {
        override fun schedule(atMillis: Long, wake: Runnable) {}
        override fun cancel() {}
    }

    @Test
    fun aSettingIsPublishedAtOnceUnderItsReason() {
        var current = reading("120")
        val published = ArrayList<DisplayedReading>()
        val hub = ReadingHub(
            source = { ReadingHub.Inputs(sensor, current, emptyList(), 5 * minute) },
            worker = { it.run() },
            clock = ReadingClock(NoScheduler()),
            now = { t0 + minute },
        )
        hub.addListener { published += it }
        // Smoothing: the same reading, unit and lanes, another value.
        current = reading("118")
        hub.onDisplayInputsChanged(Reason.SETTINGS)
        assertEquals(Reason.SETTINGS, published.last().reason)
        assertEquals("118", published.last().shownText)
        // The unit and the view mode.
        current = reading("6.6", isMmol = true)
        hub.onDisplayInputsChanged(Reason.SETTINGS)
        assertEquals("6.6", published.last().shownText)
        current = reading("6.6", isMmol = true, viewMode = 1)
        hub.onDisplayInputsChanged(Reason.SETTINGS)
        assertEquals(1, published.last().viewMode)
        assertEquals(listOf(Reason.STARTUP, Reason.SETTINGS, Reason.SETTINGS, Reason.SETTINGS), published.map { it.reason })
    }

    private val root: File by lazy {
        generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "Common/src/main/java/tk/glucodata/Applic.java").exists() }
    }

    private fun source(path: String): String = File(root, path).readText().replace("\r\n", "\n")

    /** The body of the method declared by [signature] in [text]. */
    private fun body(text: String, signature: String): String {
        val start = text.indexOf(signature)
        check(start >= 0) { "$signature not found" }
        var depth = 0
        var index = text.indexOf('{', start)
        val open = index
        while (index < text.length) {
            when (text[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open, index + 1)
            }
            index++
        }
        error("unbalanced $signature")
    }

    /** [setter] asks the hub, as a setting, after [lastChange] is made. */
    private fun assertAsksAfter(path: String, signature: String, lastChange: String) {
        val setter = body(source(path), signature)
        val ask = setter.indexOf("CurrentReadingHub.onDisplayInputsChanged(")
        assertTrue("$signature does not ask the hub", ask >= 0)
        assertTrue("$signature asks as a setting", setter.indexOf("Reason.SETTINGS", ask) > ask)
        assertTrue("$signature asks after $lastChange", ask > setter.lastIndexOf(lastChange))
    }

    @Test
    fun everyUnitViewModeAndSmoothingChangeAsksTheHub() {
        assertAsksAfter("Common/src/main/java/tk/glucodata/Applic.java", "public void setunit(int unit)", "Notify.mkunitstr(")
        assertAsksAfter(
            "Common/src/main/java/tk/glucodata/CurrentDisplaySource.kt",
            "fun setViewModeForSensor(sensorName: String?, mode: Int)",
            "Natives.setViewMode(",
        )
        assertAsksAfter(
            "Common/src/main/java/tk/glucodata/bluediag.java",
            "public void onItemSelected(AdapterView<?> p, View v, int pos, long id)",
            "ManagedSensorViewModeStore.INSTANCE.write(",
        )
        assertAsksAfter(
            "Common/src/mobile/java/tk/glucodata/ui/viewmodel/SensorViewModel.kt",
            "fun setCalibrationMode(serial: String, mode: Int)",
            "Natives.setViewMode(",
        )
        assertAsksAfter(
            "Common/src/mobile/java/tk/glucodata/ui/viewmodel/DashboardViewModel.kt",
            "private fun refreshCurrentDisplayAfterSmoothingChange()",
            "refreshCurrentDisplaySnapshot()",
        )
        assertAsksAfter(
            "Common/src/main/java/tk/glucodata/WearPrefsSync.kt",
            "fun apply(context: Context?, data: ByteArray?): Int",
            "editor.apply()",
        )
    }
}
