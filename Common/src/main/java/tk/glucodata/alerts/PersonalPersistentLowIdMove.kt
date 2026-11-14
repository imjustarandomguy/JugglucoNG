package tk.glucodata.alerts

import android.content.SharedPreferences

/**
 * Personal fork only. Persistent low had alert id 14 in this fork until upstream gave 14 to
 * Signal restored (#853); it is 15 now. The settings saved under 14 on this device are moved
 * to 15 once, before anything reads them, so Signal restored starts from its defaults and
 * Persistent low keeps the owner's settings. A key already saved under 15 is kept.
 */
internal object PersonalPersistentLowIdMove {
    private const val DONE_KEY = "personal_persistent_low_moved_to_15"
    private const val OLD_PREFIX = "alert_14_"
    private const val NEW_PREFIX = "alert_15_"

    fun moveOnce(prefs: SharedPreferences): SharedPreferences {
        if (prefs.getBoolean(DONE_KEY, false)) return prefs
        val edit = prefs.edit()
        prefs.all.filterKeys { it.startsWith(OLD_PREFIX) }.forEach { (key, value) ->
            val newKey = NEW_PREFIX + key.removePrefix(OLD_PREFIX)
            if (!prefs.contains(newKey)) put(edit, newKey, value)
            edit.remove(key)
        }
        edit.putBoolean(DONE_KEY, true).commit()
        return prefs
    }

    @Suppress("UNCHECKED_CAST")
    private fun put(edit: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is String -> edit.putString(key, value)
            is Set<*> -> edit.putStringSet(key, value as Set<String>)
        }
    }
}
