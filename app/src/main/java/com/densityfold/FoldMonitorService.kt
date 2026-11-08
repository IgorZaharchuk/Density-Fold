package com.densityfold

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class FoldMonitorService : Service() {
    private val TAG = "DensityFold-Monitor"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var displayManager: DisplayManager? = null
    private var lastWasExternal: Boolean? = null
    
    // Используем conflated channel для надёжного коалесцирования событий
    // CONFLATED гарантирует, что ни одно последнее событие не будет потеряно
    private val densityCheckChannel = Channel<Unit>(Channel.CONFLATED)
    
    // Worker coroutine, которая обрабатывает запросы последовательно
    private val densityCheckWorker = scope.launch {
        densityCheckChannel.receiveAsFlow().collect {
            processDensityCheck()
        }
    }

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
        startForegroundSafely()
    }

    private fun startForegroundSafely() {
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
            Log.d(TAG, "Foreground уведомление успешно установлено")
        } catch (e: Exception) {
            Log.e(TAG, "Критическая ошибка запуска Foreground сервиса: ${e.message}. Сервис будет остановлен.")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Сервис запущен (startId: $startId)")
        scheduleDensityCheck()
        return START_STICKY
    }

    private fun scheduleDensityCheck() {
        // Отправляем сигнал в conflated channel
        // Если канал уже содержит Unit, он будет заменён (конфлейшн)
        // Это гарантирует, что ни одно последнее событие не будет потеряно
        densityCheckChannel.trySend(Unit)
    }

    private suspend fun processDensityCheck() {
        try {
            Log.d(TAG, "Начало проверки и применения DPI")

            val active = DisplayUtils.getActiveDisplay(applicationContext)
            if (active == null) {
                Log.e(TAG, "Не удалось определить активный дисплей")
                return
            }

            val isExternal = active.isExternal

            if (!ShizukuManager.isShizukuAvailable()) {
                Log.w(TAG, "Shizuku недоступен. Состояние НЕ зафиксировано, ожидание восстановления.")
                return // lastWasExternal не обновляется
            }

            val prefs = DensityPreferences(applicationContext)
            val targetDp = if (isExternal) prefs.getOuterDp() else prefs.getInnerDp()
            val targetDpi = DisplayUtils.dpToDpi(targetDp, active.shortPx)
            
            if (targetDpi == null) {
                Log.e(TAG, "Некорректные параметры для расчёта DPI")
                return
            }

            val currentDpi = ShizukuManager.getCurrentDensity().getOrNull()
            
            if (currentDpi == targetDpi) {
                // ПЕРЕПРОВЕРЯЕМ дисплей после получения текущего DPI
                val recheckActive = DisplayUtils.getActiveDisplay(applicationContext)
                if (recheckActive != null && recheckActive.isExternal == isExternal) {
                    lastWasExternal = isExternal
                    Log.d(TAG, "DPI уже установлен корректно ($targetDpi). Состояние зафиксировано.")
                } else {
                    Log.d(TAG, "DPI правильный, но экран изменился. Планируем повторную проверку.")
                    scheduleDensityCheck()
                }
                return
            }

            Log.d(TAG, "Применение DPI: $targetDpi для ${if (isExternal) "внешнего" else "внутреннего"} экрана")
            val result = ShizukuManager.setDensity(targetDpi)
            
            // ПЕРЕПРОВЕРЯЕМ дисплей после применения DPI
            val recheckActive = DisplayUtils.getActiveDisplay(applicationContext)
            
            result.fold(
                onSuccess = { 
                    if (recheckActive != null && recheckActive.isExternal == isExternal) {
                        lastWasExternal = isExternal
                        Log.d(TAG, "DPI успешно применён. Состояние зафиксировано.")
                    } else {
                        Log.d(TAG, "DPI применён, но экран изменился во время операции. Планируем повторную проверку.")
                        scheduleDensityCheck()
                    }
                },
                onFailure = { 
                    Log.e(TAG, "Ошибка применения DPI: ${it.message}. Состояние НЕ зафиксировано, будет повторная попытка.")
                    // lastWasExternal не обновляется
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Необработанная ошибка в processDensityCheck: ${e.message}")
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
