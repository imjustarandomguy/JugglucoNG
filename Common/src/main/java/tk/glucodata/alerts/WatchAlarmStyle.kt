package tk.glucodata.alerts

import tk.glucodata.Applic
import tk.glucodata.Log

/**
 * The phone's "On the watch" setting, under "Where alarms ring": how the watch
 * rings the glucose alarms it rings. Global, not per alert type.
 *
 * It applies on the watch only, to the alarms [AlarmRouting.routes] covers, and
 * changes their sound and vibration only ([effective]). Whether the watch rings
 * at all is still [AlarmRouting]'s decision; the alarm screen and its
 * full-screen notification come as before, on their silent channel; the phone
 * is never affected.
 *
 * The watch reads it where an alarm's effects start (Notify.mksound), the one
 * place its first firing, its timed retries, the test button's alarm and a
 * quiet-window breakthrough all pass. It replaces the alert's own sound and
 * vibration switches there, and the quiet window is applied afterwards, on top
 * of the result:
 *
 * - a window that silences sound keeps the watch silent, whatever the style,
 *   until the alarm breaks through it;
 * - a notification-only window also stops the vibration, Vibrate only included;
 * - a breakthrough gives back what the style allows and nothing more, so
 *   Vibrate only still plays no sound and Screen only stays silent and still.
 *
 * Speech is sound: a style without sound does not speak the alarm either
 * ([speaksHere]).
 *
 * It reaches the watch on [AlertConfigSync]'s global line with the rest of
 * [GlobalAlertSettings]. A value this build cannot read, or none, is
 * [SAME_AS_PHONE].
 */
enum class WatchAlarmStyle {
    /** Each alert's own sound and vibration settings, as before the setting existed. The default. */
    SAME_AS_PHONE,

    /** Vibration and no sound, whatever the alert's own settings. */
    VIBRATE_ONLY,

    /** Sound and vibration, whatever the alert's own settings. */
    SOUND_AND_VIBRATION,

    /** Neither: the alarm screen alone. */
    SCREEN_ONLY;

    /** What the watch plays for an alarm. */
    data class Effects(val sound: Boolean, val vibrate: Boolean)

    companion object {
        private const val LOG_ID = "WatchAlarmStyle"

        /** The decision itself: [style] over the alert's own [soundEnabled] and [vibrationEnabled]. */
        @JvmStatic
        fun effective(style: WatchAlarmStyle, soundEnabled: Boolean, vibrationEnabled: Boolean): Effects =
            when (style) {
                SAME_AS_PHONE -> Effects(sound = soundEnabled, vibrate = vibrationEnabled)
                VIBRATE_ONLY -> Effects(sound = false, vibrate = true)
                SOUND_AND_VIBRATION -> Effects(sound = true, vibrate = true)
                SCREEN_ONLY -> Effects(sound = false, vibrate = false)
            }

        /**
         * Whether the setting applies to alarm [kind] on this kind of device: on
         * the watch, to the alarms "Where alarms ring" routes. Not to sensor
         * expiry, the hidden legacy types or an id this build does not know.
         */
        @JvmStatic
        fun appliesTo(onWatch: Boolean, kind: Int): Boolean {
            if (!onWatch) return false
            val type = AlertType.fromId(kind) ?: return false
            return AlarmRouting.routes(type)
        }

        /**
         * What alarm [kind] plays on this device, from its own [soundEnabled]
         * and [vibrationEnabled]: those unchanged wherever the setting does not
         * apply. Never throws: on any failure, the alert's own settings.
         */
        @JvmStatic
        fun effectsHere(kind: Int, soundEnabled: Boolean, vibrationEnabled: Boolean): Effects {
            val own = Effects(sound = soundEnabled, vibrate = vibrationEnabled)
            return try {
                if (!appliesTo(Applic.isWearable, kind)) {
                    own
                } else {
                    val style = AlertRepository.loadWatchAlarmStyle()
                    effective(style, soundEnabled, vibrationEnabled).also {
                        if (it != own) Log.i(LOG_ID, "kind=$kind $style: own $own, plays $it")
                    }
                }
            } catch (t: Throwable) {
                Log.stack(LOG_ID, "effectsHere $kind", t)
                own
            }
        }

        /** Whether [style] lets an alarm be spoken: speech is sound, so not where it never sounds. */
        @JvmStatic
        fun speaks(style: WatchAlarmStyle): Boolean =
            effective(style, soundEnabled = true, vibrationEnabled = true).sound

        /**
         * Whether alarm [kind] may be spoken on this device: not on a watch whose
         * style never sounds. Never throws: on any failure, as before.
         */
        @JvmStatic
        fun speaksHere(kind: Int): Boolean = try {
            !appliesTo(Applic.isWearable, kind) || speaks(AlertRepository.loadWatchAlarmStyle())
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "speaksHere $kind", t)
            true
        }
    }
}
