package com.densityfold

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.view.Display
import androidx.core.app.NotificationCompat
import com.densityfold.data.DensityPreferences
import com.densityfold.engine.DisplayUtils
import com.densityfold.engine.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class FoldMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var displayManager: DisplayManager? = null
    private var lastWasExternal: Boolean? = null
    
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            checkAndApplyDensity()
        }
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onCreate() {
        super.onCreate()
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager?.registerDisplayListener(displayListener, null)
        startForegroundServiceNotification()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        checkAndApplyDensity()
        return START_STICKY
    }
    
    private fun startForegroundServiceNotification() {
        val channelId = "fold_monitor_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Монитор складывания", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }
        
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Density Fold")
            .setContentText("Мониторинг складывания активен")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
            
        startForeground(1, notification)
    }
    
    private fun checkAndApplyDensity() {
        scope.launch {
            try {
                val active = DisplayUtils.getActiveDisplay(applicationContext) ?: return@launch
                val isExternal = active.isExternal
                
                // Если состояние не изменилось, ничего не делаем
                if (lastWasExternal == isExternal) return@launch
                
                // ВАЖНО: Если Shizuku недоступен, НЕ обновляем lastWasExternal.
                // Это позволит сервису повторить попытку при следующем изменении дисплея,
                // когда Shizuku снова станет доступен.
                if (!ShizukuManager.isShizukuAvailable()) {
                    return@launch
                }
                
                val screenName = if (isExternal) "Внешний" else "Внутренний"
                val prefs = DensityPreferences(applicationContext)
                val targetDp = if (isExternal) prefs.getOuterDp() else prefs.getInnerDp()
                val targetDpi = DisplayUtils.dpToDpi(targetDp, active.shortPx) ?: return@launch
                
                val currentDpi = ShizukuManager.getCurrentDensity().getOrNull()
                
                // Байпасс: если DPI уже правильный, считаем это успехом и обновляем состояние
                if (currentDpi == targetDpi) {
                    lastWasExternal = isExternal
                    showNotification(screenName, targetDp, true, "Уже установлено")
                    return@launch
                }
                
                val result = ShizukuManager.setDensity(targetDpi)
                result.fold(
                    onSuccess = { 
                        // Обновляем состояние ТОЛЬКО после успешного применения
                        lastWasExternal = isExternal
                        showNotification(screenName, targetDp, true, null) 
                    },
                    onFailure = { 
                        // При ошибке НЕ обновляем lastWasExternal, чтобы можно было повторить
                        showNotification(screenName, targetDp, false, it.message) 
                    }
                )
            } catch (e: Exception) {
                // Тихо игнорируем ошибки в фоне, чтобы не крашить сервис
            }
        }
    }
    
    private fun showNotification(screenName: String, dp: Int, isSuccess: Boolean, errorMsg: String?) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "fold_monitor_channel"
        
        val title = if (isSuccess) "Density Fold: $screenName экран" else "Density Fold: Ошибка"
        val text = if (isSuccess) "Установлено $dp dp" else (errorMsg ?: "Неизвестная ошибка")
        
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()
        
        // Используем уникальный ID для каждого уведомления, чтобы они не затирали друг друга
        notificationManager.notify((System.currentTimeMillis() % 10000).toInt(), notification)
    }
    
    override fun onDestroy() {
        displayManager?.unregisterDisplayListener(displayListener)
        scope.cancel()
        super.onDestroy()
    }
}
