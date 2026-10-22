package tk.glucodata.widget

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetOptionsStoreTests {
    private val prefs = FakePreferences()
    private val store = WidgetOptionsStore(prefs)

    @Test
    fun aWidgetPlacedBeforeTheseSettingsKeepsItsKindsLook() {
        // Nothing stored for ids that existed before the update: each kind's defaults.
        assertEquals(WidgetOptions.VALUE_DEFAULTS, store.load(7, WidgetKind.VALUE))
        assertEquals(WidgetOptions.CHART_DEFAULTS, store.load(8, WidgetKind.CHART))
        // The classic look: no card, white value coloured by range, arrow and time.
        with(WidgetOptions.VALUE_DEFAULTS) {
            assertEquals(WidgetBackground.NONE, background)
            assertTrue(rangeColors && showArrow && showTime)
            assertTrue(!showDelta && !showIob && !showChart)
        }
        with(WidgetOptions.CHART_DEFAULTS) {
            assertEquals(WidgetBackground.THEME, background)
            assertEquals(100, opacityPercent)
            assertTrue(showArrow && showDelta && showTime && showChart)
            assertEquals(3, chartHours)
        }
    }

    @Test
    fun settingsArePerWidget() {
        val changed = WidgetOptions.VALUE_DEFAULTS.copy(
            rangeColors = false, showDelta = true, showIob = true, textScale = 1.2f,
            background = WidgetBackground.LIGHT, opacityPercent = 40,
        )
        store.save(7, changed)
        assertEquals(changed, store.load(7, WidgetKind.VALUE))
        // Another widget of the same kind is untouched.
        assertEquals(WidgetOptions.VALUE_DEFAULTS, store.load(9, WidgetKind.VALUE))
    }

    @Test
    fun outOfRangeValuesAreClamped() {
        store.save(3, WidgetOptions.CHART_DEFAULTS.copy(textScale = 9f, chartHours = 5, opacityPercent = 140))
        val loaded = store.load(3, WidgetKind.CHART)
        assertEquals(WidgetOptions.MAX_TEXT_SCALE, loaded.textScale, 0f)
        assertEquals(WidgetOptions.DEFAULT_CHART_HOURS, loaded.chartHours)
        assertEquals(100, loaded.opacityPercent)
    }

    @Test
    fun deletingAWidgetLeavesNothingBehind() {
        store.save(4, WidgetOptions.CHART_DEFAULTS.copy(chartHours = 6))
        store.save(5, WidgetOptions.VALUE_DEFAULTS.copy(showIob = true))
        store.delete(intArrayOf(4))
        assertTrue(prefs.values.keys.none { it.startsWith("w4.") })
        assertEquals(WidgetOptions.CHART_DEFAULTS, store.load(4, WidgetKind.CHART))
        assertEquals(true, store.load(5, WidgetKind.VALUE).showIob)
        store.delete(intArrayOf(5))
        assertTrue(prefs.values.isEmpty())
    }

    @Test
    fun aRestoreMovesSettingsToTheNewIdsEvenWhenTheyOverlap() {
        val first = WidgetOptions.VALUE_DEFAULTS.copy(textScale = 0.9f)
        val second = WidgetOptions.CHART_DEFAULTS.copy(chartHours = 1)
        store.save(1, first)
        store.save(2, second)
        // Old 1 becomes 2, old 2 becomes 3.
        store.move(intArrayOf(1, 2), intArrayOf(2, 3))
        assertEquals(first, store.load(2, WidgetKind.VALUE))
        assertEquals(second, store.load(3, WidgetKind.CHART))
        assertEquals(WidgetOptions.VALUE_DEFAULTS, store.load(1, WidgetKind.VALUE))
    }

    @Test
    fun aRestoredWidgetWithNothingStoredStaysOnDefaults() {
        store.move(intArrayOf(11), intArrayOf(12))
        assertEquals(WidgetOptions.CHART_DEFAULTS, store.load(12, WidgetKind.CHART))
        assertTrue(prefs.values.isEmpty())
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
