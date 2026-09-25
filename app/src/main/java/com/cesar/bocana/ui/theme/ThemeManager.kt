package com.cesar.bocana.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate

object ThemeManager {
    private const val PREFS = "bocana_appearance"
    private const val KEY_MODE = "theme_mode"

    enum class Mode { SYSTEM, LIGHT, DARK }

    fun getMode(context: Context): Mode {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, Mode.SYSTEM.name)
        return runCatching { Mode.valueOf(value ?: Mode.SYSTEM.name) }
            .getOrDefault(Mode.SYSTEM)
    }

    fun applySavedMode(context: Context) {
        AppCompatDelegate.setDefaultNightMode(toDelegateMode(getMode(context)))
    }

    fun setMode(context: Context, mode: Mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode.name).apply()
        AppCompatDelegate.setDefaultNightMode(toDelegateMode(mode))
    }

    fun isNightActive(context: Context): Boolean {
        val mask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mask == Configuration.UI_MODE_NIGHT_YES
    }

    private fun toDelegateMode(mode: Mode): Int = when (mode) {
        Mode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        Mode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        Mode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
    }
}
