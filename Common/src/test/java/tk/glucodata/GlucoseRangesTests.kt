package tk.glucodata

import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the watch cuts its colour bands. It used its own native settings
 * (target 3.6-9.0 mmol/L unless set there) whatever the phone was set to; it
 * now takes the phone's ranges, which arrive with the colour scheme and are
 * kept across a restart.
 */
class GlucoseRangesTests {
    private val phone = GlucoseRanges.Ranges(true, 3.9f, 10.0f, 3.0f, 13.9f)

    // The watch's native defaults: tlow 648, thigh 1620, very low 550 and very
    // high 2520 (tenths of mg/dL), shown in mmol/L.
    private val watchDefaults = GlucoseRanges.Ranges(true, 3.6f, 9.0f, 550f / 180f, 14.0f)

    @After
    fun forgetReceived() {
        GlucoseRanges.reload(PrefsContext(FakePreferences()))
    }

    private fun band(value: Float, ranges: GlucoseRanges.Ranges) = GlucoseValueTone.heroTone(
        value, isDark = true, isMmol = ranges.isMmol,
        targetLow = ranges.targetLow, targetHigh = ranges.targetHigh,
        veryLowThreshold = ranges.veryLow, veryHighThreshold = ranges.veryHigh,
    )?.band

    @Test
    fun theWatchCutsItsBandsWhereThePhoneDoes() {
        // With its own defaults the watch called 3.7 in range and 9.5 high,
        // where the phone shows a low and an in-range reading.
        assertNull(band(3.7f, watchDefaults))
        assertEquals(GlucoseValueTone.Band.HIGH, band(9.5f, watchDefaults))

        val prefs = FakePreferences()
        prefs.edit().also { GlucoseRanges.write(it, phone) }.apply()
        GlucoseRanges.reload(PrefsContext(prefs))

        val onWatch = GlucoseRanges.current(isMmol = true)
        assertEquals(phone, onWatch)
        assertEquals(GlucoseValueTone.Band.LOW, band(3.7f, onWatch))
        assertNull(band(9.5f, onWatch))
    }

    @Test
    fun receivedRangesSurviveARestart() {
        val prefs = FakePreferences()
        prefs.edit().also { GlucoseRanges.write(it, phone) }.apply()
        assertEquals(phone, GlucoseRanges.read(prefs))

        // A new process reads them back before the phone is in reach.
        GlucoseRanges.reload(PrefsContext(FakePreferences()))
        GlucoseRanges.reload(PrefsContext(prefs))
        assertEquals(phone, GlucoseRanges.current(isMmol = true))
    }

    @Test
    fun aSchemeWithoutRangesForgetsThem() {
        val prefs = FakePreferences()
        prefs.edit().also { GlucoseRanges.write(it, phone) }.apply()
        prefs.edit().also { GlucoseRanges.write(it, null) }.apply()
        assertNull(GlucoseRanges.read(prefs))
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun aWatchInTheOtherUnitConvertsTheWayNativeSettingsDo() {
        assertSame(phone, phone.inUnit(mmol = true))

        val mgdl = phone.inUnit(mmol = false)
        assertFalse(mgdl.isMmol)
        assertEquals(3.9f * 18f, mgdl.targetLow, 0f)
        assertEquals(180f, mgdl.targetHigh, 0f)
        assertEquals(54f, mgdl.veryLow, 0f)
        assertEquals(13.9f * 18f, mgdl.veryHigh, 0f)

        val back = GlucoseRanges.Ranges(false, 70f, 180f, 54f, 250f).inUnit(mmol = true)
        assertEquals(70f / 18f, back.targetLow, 0f)
        assertEquals(10f, back.targetHigh, 0f)
        assertEquals(3f, back.veryLow, 0f)
        assertEquals(250f / 18f, back.veryHigh, 0f)
    }

    @Test
    fun unusableThresholdsAreRefusedOrDefaulted() {
        assertTrue(phone.isUsable)
        val partial = GlucoseRanges.Ranges(true, Float.NaN, 10f, 0f, 13.9f)
        assertFalse(partial.isUsable)

        val filled = partial.orDefaults()
        assertEquals(GlucoseRangeColors.DEFAULT_LOW_MMOL, filled.targetLow, 0f)
        assertEquals(10f, filled.targetHigh, 0f)
        assertEquals(GlucoseRangeColors.DEFAULT_VERY_LOW_MMOL, filled.veryLow, 0f)
        assertEquals(13.9f, filled.veryHigh, 0f)

        val prefs = FakePreferences()
        prefs.edit().also { GlucoseRanges.write(it, partial) }.apply()
        assertNull(GlucoseRanges.read(prefs))
    }

    private class PrefsContext(private val prefs: SharedPreferences) : ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    /** In-memory SharedPreferences; an editor's changes land together on apply or commit. */
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
