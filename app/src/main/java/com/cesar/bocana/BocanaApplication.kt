package com.cesar.bocana

import android.app.Application
import com.cesar.bocana.ui.theme.ThemeManager

class BocanaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeManager.applySavedMode(this)
    }
}
