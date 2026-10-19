package io.liriliri.eruda

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * Application com rastreio de foreground. O McpPairing usa isto para
 * escolher o popup de pairing certo (dialog in-app quando o app está na
 * frente, overlay quando está atrás) sem depender de "start activity
 * from background", que o Android bloqueia.
 */
class App : Application(), Application.ActivityLifecycleCallbacks {

    private var startedCount = 0
    private var current = WeakReference<Activity>(null)

    val isForeground: Boolean get() = startedCount > 0

    fun currentActivity(): Activity? = current.get()?.takeIf { !it.isFinishing }

    override fun onCreate() {
        super.onCreate()
        instance = this
        SettingsActivity.applySavedTheme(this)
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {
        startedCount++
        current = WeakReference(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        current = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {
        if (startedCount > 0) startedCount--
        if (current.get() === activity) current = WeakReference(null)
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {
        if (current.get() === activity) current = WeakReference(null)
    }

    companion object {
        @Volatile
        var instance: App? = null
            private set

        fun foreground(): Boolean = instance?.isForeground == true

        fun activity(): Activity? = instance?.currentActivity()

        fun context(): android.content.Context? = instance
    }
}
