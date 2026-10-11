package tk.glucodata

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DexcomFollowWiringTests {
    private val old = DexcomFollow.Record("1111122222333331", "0000003331", "DXCMAA", 1_790_000_000L)
    private val new = DexcomFollow.Record("4444455555666664", "0000006664", "DXCMBB", 1_790_864_000L)

    private class FakeAccess(
        var records: List<DexcomFollow.Record>,
        var unreachable: Boolean = false,
        var delivers: Boolean = true,
        val dexcomIds: Set<String> = emptySet(),
    ) : ManagedSensorHandoff.FollowAccess {
        val sent = ArrayList<Pair<String, String>>()

        override fun activeDexcomRecords() = records

        override fun isDexcom(sensorId: String?) = sensorId in dexcomIds

        override fun watchUnreachable() = unreachable

        override fun handOver(node: String, record: DexcomFollow.Record): Boolean {
            if (!delivers) return false
            sent += node to record.name
            return true
        }
    }

    private fun prefs(vararg entries: Pair<String, Any>) = FakePreferences().apply { values.putAll(entries) }

    @Test
    fun atASensorChangeEachDirectWatchIsHandedTheNewG7Once() {
        val prefs = prefs(
            "direct.watch" to true,
            "sensor.watch" to old.name,
            "dexcom.watch" to DexcomFollow.handedValue(old),
            "direct.other" to false,
        )
        val access = FakeAccess(listOf(old, new.copy(deviceName = "")))
        ManagedSensorHandoff.followNewestDexcom(prefs, access)
        assertTrue("not before the phone paired it", access.sent.isEmpty())

        access.records = listOf(old, new)
        ManagedSensorHandoff.followNewestDexcom(prefs, access)
        ManagedSensorHandoff.followNewestDexcom(prefs, access)
        assertEquals(listOf("watch" to new.name), access.sent)
        assertEquals(DexcomFollow.handedValue(new), prefs.values["dexcom.watch"])
    }

    @Test
    fun nothingIsRememberedForAHandoffThatDidNotArrive() {
        val prefs = prefs("direct.watch" to true, "dexcom.watch" to DexcomFollow.handedValue(old))
        val access = FakeAccess(listOf(old, new), unreachable = true)
        ManagedSensorHandoff.followNewestDexcom(prefs, access)
        access.unreachable = false
        access.delivers = false
        ManagedSensorHandoff.followNewestDexcom(prefs, access)
        assertEquals(DexcomFollow.handedValue(old), prefs.values["dexcom.watch"])

        access.delivers = true
        ManagedSensorHandoff.followNewestDexcom(prefs, access)
        assertEquals(listOf("watch" to new.name), access.sent)
    }

    @Test
    fun aWatchToldToReadAnotherSensorIsLeftAlone() {
        val prefs = prefs("direct.watch" to true, "sensor.watch" to "ABCDEFGHIJK")
        val access = FakeAccess(listOf(new))
        ManagedSensorHandoff.followNewestDexcom(prefs, access)
        assertTrue(access.sent.isEmpty())
        assertNull(prefs.values["dexcom.watch"])
    }

    @Test
    fun aWatchToldToReadAG7FollowsIt() {
        val prefs = prefs("direct.watch" to true, "sensor.watch" to old.name)
        val access = FakeAccess(listOf(old, new), dexcomIds = setOf(old.name))
        ManagedSensorHandoff.followNewestDexcom(prefs, access)
        assertEquals(listOf("watch" to new.name), access.sent)
    }

    /** In-memory SharedPreferences; an editor's changes land together, on apply or commit. */
    private class FakePreferences : SharedPreferences {
        val values = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(values)
        override fun getString(key: String?, defValue: String?) = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (values[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int) = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long) = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float) = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue
        override fun contains(key: String?) = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val puts = HashMap<String, Any?>()
            private val removes = HashSet<String>()
            private var clear = false
            private fun put(key: String?, value: Any?) = apply { puts[key!!] = value; removes.remove(key) }
            override fun putString(key: String?, value: String?) = put(key, value)
            override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values)
            override fun putInt(key: String?, value: Int) = put(key, value)
            override fun putLong(key: String?, value: Long) = put(key, value)
            override fun putFloat(key: String?, value: Float) = put(key, value)
            override fun putBoolean(key: String?, value: Boolean) = put(key, value)
            override fun remove(key: String?) = apply { removes += key!!; puts.remove(key) }
            override fun clear() = apply { clear = true }
            private fun land() {
                if (clear) values.clear()
                removes.forEach(values::remove)
                values.putAll(puts)
            }
            override fun commit(): Boolean {
                land()
                return true
            }
            override fun apply() = land()
        }
    }
}
