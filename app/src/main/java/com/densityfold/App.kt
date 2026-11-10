package com.densityfold

import android.app.Application
import com.densityfold.engine.ShizukuCommandRunner

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        ShizukuCommandRunner.installLifecycleListeners()
    }
    companion object {
        lateinit var instance: App
            private set
    }
}
