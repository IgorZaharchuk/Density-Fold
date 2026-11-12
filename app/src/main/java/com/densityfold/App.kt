package com.densityfold

import android.app.Application
import android.os.Process
import android.util.Log
import com.densityfold.backend.shizuku.ShizukuManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("DensityFold-Shizuku", "APP onCreate pid=${Process.myPid()}")
        ShizukuManager.start()
    }
}
