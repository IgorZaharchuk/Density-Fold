package com.densityfold

import android.app.Application
import android.os.Process
import android.util.Log
import com.densityfold.engine.ShizukuCommandRunner

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("DensityFold-Shizuku", "APP onCreate pid=${Process.myPid()}")
        ShizukuCommandRunner.installLifecycleListeners()
    }
}
