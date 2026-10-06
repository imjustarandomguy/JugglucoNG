package tk.glucodata

import android.content.Context
import android.content.SharedPreferences

/**
 * Where the glucose colour bands are cut: the target range and the very low and
 * very high thresholds, in one display unit.
 *
 * The phone keeps them in its native settings, where its range settings write
 * them. The watch has native settings of its own that nothing copies across, so
 * it cut its bands at its own defaults (target 3.6-9.0 mmol/L) whatever the
 * phone was set to, and a reading the phone showed as low could be in range on
 * the watch. The phone now sends its ranges with the colour scheme
 * ([GlucoseColorSync]); a device that has received them colours by them, any
 * other device by its own settings.
 */
object GlucoseRanges {
    /** The factor native settings convert between units with. */
    private const val MGDL_PER_MMOL = 18f

    private const val PREF_MMOL = "glucose_color_range_mmol"
    private const val PREF_TARGET_LOW = "glucose_color_range_target_low"
    private const val PREF_TARGET_HIGH = "glucose_color_range_target_high"
    private const val PREF_VERY_LOW = "glucose_color_range_very_low"
    private const val PREF_VERY_HIGH = "glucose_color_range_very_high"

    data class Ranges(
        val isMmol: Boolean,
        val targetLow: Float,
        val targetHigh: Float,
        val veryLow: Float,
        val veryHigh: Float,
    ) {
        /** True when every threshold is a real glucose value. */
        val isUsable: Boolean
            get() = listOf(targetLow, targetHigh, veryLow, veryHigh).all { it.isFinite() && it > 0f }

        /** The same ranges in mmol/L when [mmol], else in mg/dL. */
        fun inUnit(mmol: Boolean): Ranges {
            if (mmol == isMmol) return this
            fun convert(value: Float) = if (mmol) value / MGDL_PER_MMOL else value * MGDL_PER_MMOL
            return Ranges(mmol, convert(targetLow), convert(targetHigh), convert(veryLow), convert(veryHigh))
        }

        /** Each threshold that is not a glucose value replaced by its default. */
        fun orDefaults(): Ranges {
            fun pick(value: Float, fallback: Float) = value.takeIf { it.isFinite() && it > 0f } ?: fallback
            return Ranges(
                isMmol,
                pick(targetLow, GlucoseRangeColors.defaultLow(isMmol)),
                pick(targetHigh, GlucoseRangeColors.defaultHigh(isMmol)),
                pick(veryLow, GlucoseRangeColors.defaultVeryLow(isMmol)),
                pick(veryHigh, GlucoseRangeColors.defaultVeryHigh(isMmol)),
            )
        }
    }

    // The ranges received from the phone. Read from prefs on first use, so a
    // restarted watch keeps colouring by them before the phone is in reach.
    @Volatile private var received: Ranges? = null
    @Volatile private var loaded = false

    /** This device's own ranges, from its native settings; null when unreadable. */
    @JvmStatic
    fun local(): Ranges? = runCatching {
        Ranges(
            isMmol = Natives.getunit() == 1,
            targetLow = Natives.targetlow(),
            targetHigh = Natives.targethigh(),
            veryLow = Natives.alarmverylow(),
            veryHigh = Natives.alarmveryhigh(),
        )
    }.getOrNull()?.takeIf { it.isUsable }

    /**
     * The ranges to colour by, in mmol/L when [isMmol]: the phone's once this
     * device has received them, else its own. Values that cannot be read are
     * NaN; [Ranges.orDefaults] replaces them.
     */
    @JvmStatic
    fun current(isMmol: Boolean): Ranges =
        (received() ?: local())?.inUnit(isMmol)
            ?: Ranges(isMmol, Float.NaN, Float.NaN, Float.NaN, Float.NaN)

    private fun received(): Ranges? {
        if (!loaded) reload(Applic.app)
        return received
    }

    /** Re-reads the received ranges from this device's colour preferences. */
    @JvmStatic
    fun reload(context: Context?) {
        val prefs = context?.getSharedPreferences(GlucoseRangeColors.PREF_FILE, Context.MODE_PRIVATE) ?: return
        received = read(prefs)
        loaded = true
    }

    internal fun read(prefs: SharedPreferences): Ranges? {
        if (!prefs.contains(PREF_TARGET_LOW)) return null
        return Ranges(
            isMmol = prefs.getBoolean(PREF_MMOL, false),
            targetLow = prefs.getFloat(PREF_TARGET_LOW, Float.NaN),
            targetHigh = prefs.getFloat(PREF_TARGET_HIGH, Float.NaN),
            veryLow = prefs.getFloat(PREF_VERY_LOW, Float.NaN),
            veryHigh = prefs.getFloat(PREF_VERY_HIGH, Float.NaN),
        ).takeIf { it.isUsable }
    }

    /** Stores [ranges] as the received ones, or forgets them when null. */
    internal fun write(editor: SharedPreferences.Editor, ranges: Ranges?) {
        if (ranges == null) {
            listOf(PREF_MMOL, PREF_TARGET_LOW, PREF_TARGET_HIGH, PREF_VERY_LOW, PREF_VERY_HIGH)
                .forEach { editor.remove(it) }
            return
        }
        editor.putBoolean(PREF_MMOL, ranges.isMmol)
        editor.putFloat(PREF_TARGET_LOW, ranges.targetLow)
        editor.putFloat(PREF_TARGET_HIGH, ranges.targetHigh)
        editor.putFloat(PREF_VERY_LOW, ranges.veryLow)
        editor.putFloat(PREF_VERY_HIGH, ranges.veryHigh)
    }
}
