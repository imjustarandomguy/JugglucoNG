package tk.glucodata.alerts

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the alarm history is told about an alarm. The hooks sit in Notify, the state
 * tracker, the snooze manager and the alert runtime, none of which can run in a JVM test,
 * so their placement is pinned by reading the sources, as `WearJournalIdentityWiringTests`
 * does. What matters most: a test alarm must never be recorded as a real one.
 */
class AlarmHistoryWiringTests {

    private val moduleRoot = File("").absoluteFile.let { working ->
        generateSequence(working) { it.parentFile }
            .firstOrNull { File(it, "src/main/java/tk/glucodata/Notify.java").exists() }
            ?: working
    }

    private fun source(relative: String) = File(moduleRoot, relative).readText().replace("\r\n", "\n")

    @Test
    fun onlyARealFirstFiringIsRecorded() {
        val notify = source("src/main/java/tk/glucodata/Notify.java")
        // AlertStateTracker.onAlertTriggered returns false for a manual test (the
        // AlertTestValuePolicy alarms), so recording inside this block skips them.
        val block = notify.substringAfter("if (productionTrigger) {").substringBefore("}")
        assertTrue(
            "the firing must be recorded inside the productionTrigger block, never for a test alarm",
            block.contains("AlarmHistory.onFired(kind, glvalue)")
        )
        assertTrue(
            "onFired must appear exactly once in the glucose alarm path",
            Regex("""AlarmHistory\.onFired\(kind""").findAll(notify).count() == 1
        )
        // The test path for signal loss calls lossofsignalalarm directly; only the real
        // loss alarm records.
        val test = notify.substringAfter("public static void testTrigger(int kind)")
            .substringBefore("public static void triggerCustomAlert(")
        assertTrue("testTrigger must not record", !test.contains("AlarmHistory"))
        val loss = notify.substringAfter("public void lossalarm(long time)")
        assertTrue(loss.substringBefore("lossofsignalalarm(").contains("AlarmHistory.onFired(4, Float.NaN)"))
    }

    @Test
    fun aDismissalIsRecordedOnlyAfterTheTestCheck() {
        val tracker = source("src/main/java/tk/glucodata/alerts/AlertStateTracker.kt")
        val dismissed = tracker.substringAfter("fun onAlertDismissed(type: AlertType): Boolean {")
            .substringBefore("return true")
        val testCheck = dismissed.indexOf("manualTests.consumeAction(type)")
        val record = dismissed.indexOf("AlarmHistory.onDismissed(type.id)")
        assertTrue("onAlertDismissed must record the dismissal", record >= 0)
        assertTrue("a test alarm's dismissal returns before it is recorded", testCheck in 0 until record)
    }

    @Test
    fun snoozesAndClearsAreRecorded() {
        val snooze = source("src/main/java/tk/glucodata/alerts/SnoozeManager.kt")
            .substringAfter("fun snooze(alertType: AlertType")
            .substringBefore("fun isSnoozed(")
        assertTrue(snooze.contains("AlarmHistory.onSnoozed(alertType.id, durationMinutes)"))

        val runtime = source("src/main/java/tk/glucodata/alerts/AlertRuntimeManager.kt")
            .substringAfter("private fun clearRuntimeAlert(type: AlertType, reason: String) {")
            .substringBefore("\n    }")
        assertTrue(runtime.contains("AlarmHistory.onCleared(type.id, reason)"))
    }

    @Test
    fun thePhoneTakesWatchEventsAndTheWatchFlushesWhenItHearsThePhone() {
        val receiver = source("src/main/java/tk/glucodata/MessageReceiver.kt")
        val branch = receiver.substringAfter("WearMessagePath.SYNC2_ALARM_HISTORY ->").substringBefore("}")
        assertTrue(branch.contains("if (!isWearable)") && branch.contains("AlarmHistory.onPeerEvents(data)"))
        assertTrue(receiver.contains("if (isWearable) tk.glucodata.alerts.AlarmHistory.onPeerHeard()"))
    }
}
