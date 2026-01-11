package org.godotengine.godot_gradle_build_environment

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "app_settings",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val KEY_CLEAR_CACHE_AFTER_BUILD = "clear_cache_after_build"
    }

    var clearCacheAfterBuild: Boolean
        get() = prefs.getBoolean(KEY_CLEAR_CACHE_AFTER_BUILD, false)
        set(value) {
            prefs.edit().putBoolean(KEY_CLEAR_CACHE_AFTER_BUILD, value).apply()
        }
}
