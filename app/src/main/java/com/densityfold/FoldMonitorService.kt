package com.densityfold

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.util.Log
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
    private val TAG = "DensityFold-Monitor"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var displayManager: DisplayManager? = null
    private var lastWasExternal: Boolean? = null
    
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            Log.d(TAG, "Display changed: $displayId")
            checkAndApplyDensity()
        }
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onCreate() {
        super.onCreate()
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager?.registerDisplayListener(displayListener, null)
        Log.d(TAG, "FoldMonitorService created, display listener registered")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "FoldMonitorService started")
        checkAndApplyDensity()
        return START_STICKY
    }
    
    private fun checkAndApplyDensity() {
        scope.launch {
            try {
                val active = DisplayUtils.getActiveDisplay(applicationContext) ?: return@launch
                val isExternal = active.isExternal
                
                // Проверяем, изменился ли тип экрана
                if (lastWasExternal == isExternal) return@launch
                lastWasExternal = isExternal
                
                val screenName = if (isExternal) "Внешний" else "Внутренний"
                Log.d(TAG, "Screen changed to: $screenName")
                
                if (!ShizukuManager.isShizukuAvailable()) {
                    Log.d(TAG, "Shizuku not available")
                    return@launch
                }
                
                val prefs = DensityPreferences(applicationContext)
                val targetDp = if (isExternal) prefs.getOuterDp() else prefs.getInnerDp()
                val targetDpi = DisplayUtils.dpToDpi(targetDp, active.shortPx) ?: return@launch
                
                // Байпасс: проверяем текущий DPI
                val currentDpi = ShizukuManager.getCurrentDensity().getOrNull()
                if (currentDpi == targetDpi) {
                    Log.d(TAG, "Bypass: DPI already correct ($targetDpi)")
                    return@launch
                }
                
                Log.d(TAG, "Applying DPI: $targetDpi for $screenName screen")
                val result = ShizukuManager.setDensity(targetDpi)
                result.fold(
                    onSuccess = { showNotification(screenName, targetDp, true, null) },
                    onFailure = { showNotification(screenName, targetDp, false, it.message) }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error in checkAndApplyDensity", e)
            }
        }
    }
    
    private fun showNotification(screenName: String, dp: Int, isSuccess: Boolean, errorMsg: String?) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "fold_monitor_channel"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Монитор складывания", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }
        
        val title = if (isSuccess) "Density Fold: $screenName экран" else "Density Fold: Ошибка"
        val text = if (isSuccess) "Установлено $dp dp" else (errorMsg ?: "Неизвестная ошибка")
        
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
    
    override fun onDestroy() {
        displayManager?.unregisterDisplayListener(displayListener)
        scope.cancel()
        super.onDestroy()
    }
}
