package com.manhwalens.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.manhwalens.app.util.ErrorLog
import com.manhwalens.app.util.SafeMode
import com.manhwalens.app.util.StartupGuard
import java.io.File

class ManhwaLensApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // VERY FIRST thing: install the global uncaught-exception handler before any
        // other initialization can crash. Best-effort — never lets a failure escape.
        try {
            ErrorLog.install(base)
        } catch (t: Throwable) {
            Log.e("ManhwaLens", "failed to install crash handler", t)
        }
        try {
            com.manhwalens.app.util.MainThread.init(
                (base.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            )
        } catch (t: Throwable) {
            Log.e("ManhwaLens", "MainThread init failed", t)
        }
        // Debug builds only: log (never crash on) threading/main-thread and VM strictness violations.
        if ((base.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            try {
                android.os.StrictMode.setThreadPolicy(
                    android.os.StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build()
                )
                android.os.StrictMode.setVmPolicy(
                    android.os.StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build()
                )
            } catch (t: Throwable) {
                Log.e("ManhwaLens", "StrictMode setup failed", t)
            }
        }
        // Crash-loop guard: count consecutive startups that never reached 5 s of
        // healthy runtime. Two in a row → Safe Mode (see SafeMode / MainActivity banner).
        try {
            val guard = StartupGuard(File(base.filesDir, StartupGuard.FILE_NAME))
            val consecutive = guard.registerStart()
            SafeMode.active = StartupGuard.isSafeMode(consecutive)
            Log.i("ManhwaLens", "startup #$consecutive${if (SafeMode.active) " — SAFE MODE (no services/live)" else ""}")
        } catch (t: Throwable) {
            SafeMode.active = false
            Log.e("ManhwaLens", "startup guard failed — continuing normally", t)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Idempotent re-install (no-op once attachBaseContext installed it).
        try {
            ErrorLog.install(this)
        } catch (t: Throwable) {
            Log.e("ManhwaLens", "crash handler re-install failed", t)
        }
        // Run the settings migration up front so a corrupt store never reaches the UI.
        try {
            com.manhwalens.app.data.SettingsPrefs(this)
        } catch (t: Throwable) {
            Log.e("ManhwaLens", "settings migration failed — defaults will be used", t)
        }
        try {
            createNotificationChannels()
        } catch (t: Throwable) {
            Log.e("ManhwaLens", "notification channels failed — capture FGS will re-create them", t)
        }
        // The process survived 5 s → the crash streak is over. Best-effort; fine to do in Safe Mode too.
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                StartupGuard(File(filesDir, StartupGuard.FILE_NAME)).markHealthy()
            } catch (t: Throwable) {
                Log.e("ManhwaLens", "healthy-marker failed", t)
            }
        }, 5_000L)
        // Track "any ManhwaLens activity started" so Live mode pauses on our own screens.
        try {
            com.manhwalens.app.util.AppVisibilityTracker.register(this)
        } catch (t: Throwable) {
            Log.e("ManhwaLens", "activity visibility tracker failed — Live will not auto-pause", t)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        // Channel reserved for the capture foreground service (M2) and any
        // persistent "ManhwaLens is active" indicator. LOW importance = silent.
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_STATUS) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_STATUS,
                    "ManhwaLens status",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Capture and overlay status while ManhwaLens is active"
                    setShowBadge(false)
                }
            )
        }
    }

    companion object {
        const val CHANNEL_STATUS = "manhwalens_status"
    }
}
