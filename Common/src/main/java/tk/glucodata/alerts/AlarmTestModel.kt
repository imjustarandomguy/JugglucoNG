package tk.glucodata.alerts

import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * The test button of an alert, as phone and watch share it. The runtime is
 * [AlarmTestSync]; everything here is pure, so the rules can be tested.
 */

/** Where a test started from the phone's alert settings rings. */
data class AlarmTestTargets(val phone: Boolean, val watch: Boolean)

/**
 * A test follows "Where alarms ring" ([AlarmRouting]) as the alarm itself would:
 *
 * | mode                 | phone                                   | watch                    |
 * |----------------------|-----------------------------------------|--------------------------|
 * | BOTH                 | rings                                   | rings                    |
 * | WATCH_WHEN_CONNECTED | rings only if the watch is out of reach, | rings when the phone     |
 * |                      | charging, or the phone cannot tell      | does not                 |
 * | PHONE_ONLY           | rings                                   | silent                   |
 *
 * Unlike a real alarm in WATCH_WHEN_CONNECTED, a test never rings on both: it shows
 * the one device that would carry the alarm. An alert that does not follow the setting
 * (sensor expiry) rings on both, as its real alarm does; a legacy-only one, which the
 * watch never rings, on the phone only.
 */
object AlarmTestRouting {
    @JvmStatic
    fun targets(
        type: AlertType,
        mode: AlarmRoutingMode,
        watchReachable: Boolean?,
        watchCharging: Boolean?,
    ): AlarmTestTargets {
        if (AlertType.isLegacyOnlyId(type.id)) return AlarmTestTargets(phone = true, watch = false)
        if (!AlarmRouting.routes(type)) return AlarmTestTargets(phone = true, watch = true)
        return when (mode) {
            AlarmRoutingMode.BOTH -> AlarmTestTargets(phone = true, watch = true)
            AlarmRoutingMode.PHONE_ONLY -> AlarmTestTargets(phone = true, watch = false)
            AlarmRoutingMode.WATCH_WHEN_CONNECTED -> {
                val phone = AlarmRouting.shouldRing(onWatch = false, mode, watchReachable, watchCharging)
                AlarmTestTargets(phone = phone, watch = !phone)
            }
        }
    }
}

/**
 * `/sync2/alarmtest`: one test alarm started on the phone ([OP_START], phone to watch), or
 * stopped on either device ([OP_STOP], both ways). [testId] names the test, so a stop
 * reaches only the test it was made for and never a later one, or a real alarm.
 *
 * Layout, big-endian: format (1), op (1), alert type id (1), test id (8). A later format
 * may append fields; a build that does not know an op ignores the message. Builds before
 * this one do not know the path and drop it (MessageReceiver logs it once).
 */
object AlarmTestCodec {
    const val FORMAT = 1
    const val OP_START = 1
    const val OP_STOP = 2
    private const val SIZE = 1 + 1 + 1 + 8

    data class Message(val op: Int, val typeId: Int, val testId: Long)

    @JvmStatic
    fun encode(op: Int, typeId: Int, testId: Long): ByteArray {
        require(op == OP_START || op == OP_STOP) { "op $op" }
        require(typeId in 0..255) { "typeId $typeId" }
        return ByteBuffer.allocate(SIZE).order(ByteOrder.BIG_ENDIAN)
            .put(FORMAT.toByte())
            .put(op.toByte())
            .put(typeId.toByte())
            .putLong(testId)
            .array()
    }

    /** The message in [data], or null when it is not one this build can act on. Never throws. */
    @JvmStatic
    fun decode(data: ByteArray?): Message? {
        if (data == null || data.size < SIZE) return null
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val format = buffer.get().toInt() and 0xff
        if (format < FORMAT) return null
        val op = buffer.get().toInt() and 0xff
        if (op != OP_START && op != OP_STOP) return null
        val typeId = buffer.get().toInt() and 0xff
        return Message(op, typeId, buffer.long)
    }
}

/**
 * The test alarms on this device, one per alert type: which test it is, and whether the
 * other device rings it too (so a stop here is worth telling it). Thread-safe.
 */
class AlarmTestRuns {
    data class Run(val testId: Long, val shared: Boolean)

    private val runs = HashMap<Int, Run>()

    /** Test [testId] of [typeId] rings here; [shared] when it rings on the other device too. */
    @Synchronized
    fun started(typeId: Int, testId: Long, shared: Boolean) {
        runs[typeId] = Run(testId, shared)
    }

    /** Test [testId] did not reach the other device: a stop here has no one to tell. */
    @Synchronized
    fun notShared(typeId: Int, testId: Long) {
        val run = runs[typeId] ?: return
        if (run.testId == testId) runs[typeId] = run.copy(shared = false)
    }

    /** A person stopped [typeId]'s test here: the run to stop on the other device, if any. */
    @Synchronized
    fun stoppedHere(typeId: Int): Run? = runs.remove(typeId)?.takeIf { it.shared }

    /**
     * The other device stopped test [testId] of [typeId]: true, and the run ends, when that
     * test is the one here. A stop for an older test, or for none, leaves this one alone.
     */
    @Synchronized
    fun stoppedThere(typeId: Int, testId: Long): Boolean {
        val run = runs[typeId] ?: return false
        if (run.testId != testId) return false
        runs.remove(typeId)
        return true
    }

    @Synchronized
    fun current(typeId: Int): Run? = runs[typeId]
}
