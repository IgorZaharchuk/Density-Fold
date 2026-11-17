package com.densityfold

import android.app.Application
import android.os.Build
import java.io.File

class App : Application() {

    private fun isMainProcess(): Boolean {
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            File("/proc/self/cmdline").readText()
                .trimEnd('\u0000')
                .trim()
        }
        return processName == packageName
    }

    override fun onCreate() {
        super.onCreate()
        if (!isMainProcess()) return
    }
}
