package com.example

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

class AdminApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        setupGlobalExceptionHandler()
        initFirebaseSafely()
    }

    private fun initFirebaseSafely() {
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setApplicationId("1:357805299753:android:0cafd3e3c6dc70244b2438")
                    .setApiKey("AIzaSyBR4eWR7ctDHOcgB0c7yWne3aBwpMyZRe0")
                    .setProjectId("prophecy-verse-483df")
                    .setGcmSenderId("357805299753")
                    .setStorageBucket("prophecy-verse-483df.firebasestorage.app")
                    .build()
                FirebaseApp.initializeApp(this, options)
                Log.i("AdminApplication", "Firebase initialized explicitly with production options")
            } else {
                Log.i("AdminApplication", "Firebase already initialized by FirebaseInitProvider")
            }
        } catch (t: Throwable) {
            Log.e("AdminApplication", "Firebase initialization encountered an exception: ${t.message}", t)
        }
    }

    private fun setupGlobalExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(
                "AdminApplication",
                "FATAL EXCEPTION in thread '${thread.name}': ${throwable.message}",
                throwable
            )
            try {
                getSharedPreferences("pv_crash_log", MODE_PRIVATE)
                    .edit()
                    .putString("last_crash", "${throwable::class.java.simpleName}: ${throwable.message}")
                    .putLong("last_crash_time", System.currentTimeMillis())
                    .apply()
            } catch (_: Throwable) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
