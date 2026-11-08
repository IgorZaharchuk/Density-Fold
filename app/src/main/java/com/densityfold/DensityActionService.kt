package com.densityfold

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
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
        val screenName = if (isClosed) "Внешний" else "Внутренний"

        scope.launch {
            try {
                if (!ShizukuManager.isShizukuAvailable()) {
                    showNotification(applicationContext, screenName, 0, false, "Shizuku не активен")
                    return@launch
                }

                val prefs = DensityPreferences(applicationContext)
                val targetDpi = if (isClosed) prefs.getOuterDpi() else prefs.getInnerDpi()

                val result = ShizukuManager.setDensity(targetDpi)
                
                result.fold(
                    onSuccess = { showNotification(applicationContext, screenName, targetDpi, true, null) },
                    onFailure = { showNotification(applicationContext, screenName, targetDpi, false, it.message) }
                )
            } catch (e: Exception) {
                showNotification(applicationContext, screenName, 0, false, e.message)
            } finally {
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    private fun showNotification(context: Context, screenName: String, dpi: Int, isSuccess: Boolean, errorMsg: String?) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "density_actions_channel"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Действия Density Fold", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }
        
        val title = if (isSuccess) "Density Fold: Успешно" else "Density Fold: Ошибка"
        val text = if (isSuccess) "$screenName экран: установлено $dpi dpi" else (errorMsg ?: "Неизвестная ошибка")
        
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()
            
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_OPEN = "com.densityfold.ACTION_OPEN"
        const val ACTION_CLOSED = "com.densityfold.ACTION_CLOSED"

        fun startOpen(context: Context) {
            val intent = Intent(context, DensityActionService::class.java).apply { action = ACTION_OPEN }
            context.startService(intent)
        }

        fun startClosed(context: Context) {
            val intent = Intent(context, DensityActionService::class.java).apply { action = ACTION_CLOSED }
            context.startService(intent)
        }
    }
}
