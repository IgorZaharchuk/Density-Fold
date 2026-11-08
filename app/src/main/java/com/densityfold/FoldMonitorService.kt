package com.densityfold

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.ServiceInfo
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku

class FoldMonitorService : Service() {
    private val TAG = "DensityFold-Monitor"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var displayManager: DisplayManager? = null
    private var lastWasExternal: Boolean? = null
    
    private val dpiMutex = Mutex()
    @Volatile private var pendingRecheck = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            Log.d(TAG, "Обнаружено изменение дисплея (ID: $displayId)")
            scheduleDensityCheck()
        }
    }

    private val shizukuListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Получен Binder Shizuku, инициируем проверку DPI")
        scheduleDensityCheck()
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Сервис создан, регистрация слушателей")
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager?.registerDisplayListener(displayListener, null)
        Shizuku.addBinderReceivedListenerSticky(shizukuListener)
        startForegroundServiceNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Сервис запущен (startId: $startId)")
        scheduleDensityCheck()
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
            
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, notification)
            }
            Log.d(TAG, "Foreground уведомление установлено")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска Foreground сервиса: ${e.message}")
        }
    }

    private fun scheduleDensityCheck() {
        scope.launch {
            if (dpiMutex.isLocked) {
                pendingRecheck = true
                Log.d(TAG, "Операция DPI уже выполняется, запланирована повторная проверка")
                return@launch
            }

            dpiMutex.withLock {
                do {
                    pendingRecheck = false
                    Log.d(TAG, "Начало проверки и применения DPI")

                    val active = DisplayUtils.getActiveDisplay(applicationContext)
                    if (active == null) {
                        Log.e(TAG, "Не удалось определить активный дисплей")
                        break
                    }

                    val isExternal = active.isExternal

                    if (!ShizukuManager.isShizukuAvailable()) {
                        Log.w(TAG, "Shizuku недоступен, применение DPI отложено до восстановления")
                        break // lastWasExternal НЕ обновляется, гарантируя повторную попытку
                    }

                    val prefs = DensityPreferences(applicationContext)
                    val targetDp = if (isExternal) prefs.getOuterDp() else prefs.getInnerDp()
                    val targetDpi = DisplayUtils.dpToDpi(targetDp, active.shortPx)
                    
                    if (targetDpi == null) {
                        Log.e(TAG, "Некорректные параметры для расчёта DPI (dp=$targetDp, shortPx=${active.shortPx})")
                        break
                    }

                    val currentDpi = ShizukuManager.getCurrentDensity().getOrNull()
                    
                    if (currentDpi == targetDpi) {
                        lastWasExternal = isExternal
                        Log.d(TAG, "DPI уже установлен корректно ($targetDpi). Состояние зафиксировано.")
                        break
                    }

                    Log.d(TAG, "Применение DPI: $targetDpi для ${if (isExternal) "внешнего" else "внутреннего"} экрана")
                    val result = ShizukuManager.setDensity(targetDpi)
                    
                    result.fold(
                        onSuccess = {
                            lastWasExternal = isExternal
                            Log.d(TAG, "DPI успешно применён. Состояние зафиксировано.")
                        },
                        onFailure = {
                            Log.e(TAG, "Ошибка применения DPI: ${it.message}. Состояние НЕ зафиксировано, будет повторная попытка.")
                            // lastWasExternal НЕ обновляется
                        }
                    )
                } while (pendingRecheck)
                
                if (pendingRecheck) {
                    Log.d(TAG, "Завершена обработка с учётом отложенных изменений дисплея")
                }
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Сервис останавливается, очистка ресурсов")
        displayManager?.unregisterDisplayListener(displayListener)
        Shizuku.removeBinderReceivedListener(shizukuListener)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
