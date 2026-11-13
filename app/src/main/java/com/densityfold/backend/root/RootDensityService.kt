package com.densityfold.backend.root

import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.topjohnwu.superuser.ipc.RootService

class RootDensityService : RootService() {

    private val binder = object : IRootDensityService.Stub() {
        override fun getProcessUid(): Int = Process.myUid()

        override fun getCurrentDensity(displayId: Int): Int =
            WindowManagerDensity.get(displayId)

        override fun setDensity(displayId: Int, dpi: Int): Int =
            WindowManagerDensity.set(displayId, dpi)
    }

    override fun onBind(intent: Intent): IBinder = binder
}
