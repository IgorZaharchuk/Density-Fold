package com.densityfold

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.densityfold.data.DensityPreferences
import com.densityfold.engine.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DensityActionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: run {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val isClosed = action == ACTION_CLOSED

        scope.launch {
            try {
                if (!ShizukuManager.isShizukuAvailable()) return@launch

                val prefs = DensityPreferences(applicationContext)
                val targetDpi = if (isClosed) prefs.getOuterDpi() else prefs.getInnerDpi()

                ShizukuManager.setDensity(targetDpi)
            } catch (_: Exception) {
                // Тихо игнорируем ошибки в фоне
            } finally {
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_OPEN = "com.densityfold.ACTION_OPEN"
        const val ACTION_CLOSED = "com.densityfold.ACTION_CLOSED"

        fun startOpen(context: Context) {
            val intent = Intent(context, DensityActionService::class.java).apply {
                action = ACTION_OPEN
            }
            context.startService(intent)
        }

        fun startClosed(context: Context) {
            val intent = Intent(context, DensityActionService::class.java).apply {
                action = ACTION_CLOSED
            }
            context.startService(intent)
        }
    }
}
