package tk.glucodata.alerts

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.random.Random
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.MessageSender
import tk.glucodata.Notify
import tk.glucodata.SensorOwnershipRuntime
import tk.glucodata.WearMessagePath

/**
 * The test button of an alert, sent where "Where alarms ring" sends the alarm.
 *
 * The test in the phone's alert settings used to ring on the phone only, whatever the
 * setting. Now it rings where the alarm would ([AlarmTestRouting]): on both, on the watch
 * alone, or on the phone alone. A test for the watch goes as `/sync2/alarmtest`
 * ([AlarmTestCodec]); the watch runs it through its own test path
 * ([Notify.testTriggerHere]), as a manual test, so it records no alarm history, arms no
 * SMS watchdog and starts no retries there either. A test meant for the watch alone that
 * does not reach it rings on the phone instead, as the alarm would.
 *
 * Stopping a test (dismiss or snooze) on one device stops it on the other. That does not
 * go through the shared dismissal of [AlarmSilenceSync]: a test's dismissal is not one,
 * must not acknowledge a real alarm of the same type, and must leave nothing behind. The
 * stop names its test, so it never reaches a later test or a real alarm.
 *
 * Older builds do not know the path and drop it; WearProtocol.VERSION is unchanged.
 */
object AlarmTestSync {
    private const val LOG_ID = "AlarmTestSync"

    private val runs = AlarmTestRuns()

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, LOG_ID).apply { isDaemon = true }
    }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * The phone's alert settings asked for a test of [kind] (Notify.testTrigger). Sends it
     * to the watch when it rings there, and answers whether it rings on this device.
     */
    @JvmStatic
    fun startTest(kind: Int): Boolean {
        if (Applic.isWearable || Applic.app == null) return true
        val type = AlertType.fromId(kind) ?: return true
        val targets = try {
            AlarmTestRouting.targets(
                type,
                AlertRepository.loadAlarmRouting(),
                AlarmRouting.watchReachableFromPhone(),
                SensorOwnershipRuntime.peerCharging(),
            )
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "startTest ${type.name}", t)
            AlarmTestTargets(phone = true, watch = false)
        }
        val testId = Random.nextLong().takeIf { it != 0L } ?: 1L
        if (targets.phone) runs.started(type.id, testId, shared = targets.watch)
        Log.i(
            LOG_ID,
            "test ${type.name}: phone=${targets.phone} watch=${targets.watch} (${AlarmRouting.describeInputs()})"
        )
        if (targets.watch) {
            send(AlarmTestCodec.OP_START, type, testId, ringHereIfUndelivered = !targets.phone)
        }
        return targets.phone
    }

    /**
     * A person dismissed or snoozed the test alarm of [type] here ([AlertStateTracker]):
     * it stops on the other device too, if it rings there. Any thread; never blocks.
     */
    @JvmStatic
    fun onLocalTestStopped(type: AlertType) {
        val run = runs.stoppedHere(type.id) ?: return
        send(AlarmTestCodec.OP_STOP, type, run.testId)
    }

    /** A `/sync2/alarmtest` message. Never throws. */
    @JvmStatic
    fun onPeerMessage(data: ByteArray?) {
        if (Applic.app == null) return
        val message = AlarmTestCodec.decode(data)
        if (message == null) {
            Log.w(LOG_ID, "ignoring an unreadable test message (${data?.size ?: 0} bytes)")
            return
        }
        // The alarm paths this reaches run on the main thread, as when a person acts.
        mainHandler.post {
            try {
                apply(message)
            } catch (t: Throwable) {
                Log.stack(LOG_ID, "apply", t)
            }
        }
    }

    private fun apply(message: AlarmTestCodec.Message) {
        val type = AlertType.fromId(message.typeId) ?: return
        when (message.op) {
            AlarmTestCodec.OP_START -> {
                // Only the phone has a test button; a test reaching it is not for it.
                if (!Applic.isWearable) return
                runs.started(type.id, message.testId, shared = true)
                Log.i(LOG_ID, "test ${type.name} from the phone")
                Notify.testTriggerHere(type.id)
            }
            AlarmTestCodec.OP_STOP -> {
                if (!runs.stoppedThere(type.id, message.testId)) return
                // A real alarm of the type took over since, or the test was answered here.
                if (!AlertStateTracker.endManualTestFromPeer(type)) return
                stopHere(type)
                Log.i(LOG_ID, "test ${type.name} stopped on the other device")
            }
        }
    }

    /** The test's sound, vibration, alarm screen and notification go; nothing else. */
    private fun stopHere(type: AlertType) {
        Notify.cancelQueuedAlarmActivityLaunch(type.id, null, "peer-test-stop")
        val current = Notify.resolveAlertKind(-1)
        if (current == type.id) Notify.stopalarm()
        // The alarm notification is the test's unless another alarm came up since.
        if (current == type.id || current < 0) Notify.cancelAlertNotification()
        Notify.cancelAlarmScreenNotification(type.id)
        AlarmSilenceSync.closeAlarmScreens(type.id)
    }

    private fun send(op: Int, type: AlertType, testId: Long, ringHereIfUndelivered: Boolean = false) {
        if (Applic.app == null) return
        try {
            executor.execute {
                val delivered = try {
                    // Blocks until the Data Layer took it for every node in reach; false when none is.
                    MessageSender.sendSyncMessageAwait(
                        WearMessagePath.SYNC2_ALARM_TEST,
                        AlarmTestCodec.encode(op, type.id, testId),
                    )
                } catch (t: Throwable) {
                    Log.stack(LOG_ID, "send", t)
                    false
                }
                if (delivered || op != AlarmTestCodec.OP_START) {
                    if (!delivered) Log.i(LOG_ID, "test stop of ${type.name} not delivered")
                    return@execute
                }
                if (ringHereIfUndelivered) {
                    // The watch was in reach a moment ago. As for the alarm itself, the
                    // phone rings in its place.
                    Log.i(LOG_ID, "test ${type.name} did not reach the watch; ringing here")
                    runs.started(type.id, testId, shared = false)
                    Notify.testTriggerHere(type.id)
                } else {
                    Log.i(LOG_ID, "test ${type.name} did not reach the watch")
                    runs.notShared(type.id, testId)
                }
            }
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "send", t)
        }
    }
}
