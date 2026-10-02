package io.liriliri.eruda

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsActivity.applySavedTheme(this)
    }
}
