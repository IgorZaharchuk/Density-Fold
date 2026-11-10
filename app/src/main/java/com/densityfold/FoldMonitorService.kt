package com.densityfold

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import com.densityfold.engine.ShizukuCommandRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class FoldMonitorService : Service() {
    private val TAG = "DensityFold-Monitor"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var displayManager: DisplayManager? = null
    private var lastWasExternal: Boolean? = null
    
    private val checkChannel = Channel<Unit>(Channel.CONFLATED)

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            Log.d(TAG, "Обнаружено изменение дисплея (ID: $displayId)")
            scheduleDensityCheck()
        }
    }

    private val shizukuDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Binder Shizuku отключён. Автоприменение приостановлено.")
        updateMonitorNotification("Shizuku недоступен")
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Сервис создан, регистрация слушателей")
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager?.registerDisplayListener(displayListener, null)
        Shizuku.addBinderDeadListener(shizukuDeadListener)
        startForegroundSafely()
        
        scope.launch {
            for (event in checkChannel) {
                try {
                    performDensityCheck()
                } catch (e: Exception) {
                    Log.e(TAG, "Исключение при обработке события DPI: ${e.message}", e)
                }
            }
        }

        // Резервная проверка Shizuku: обнаруживает восстановление Binder
        // даже если OnBinderReceivedListener не обновил уведомление.
        scope.launch {
            var wasAvailable = ShizukuManager.isShizukuAvailable()
            while (true) {
                delay(3000)
                val isAvailable = ShizukuManager.isShizukuAvailable()

                if (!isAvailable) {
                    if (wasAvailable) {
                        Log.i(TAG, "Доступность Shizuku изменилась: true -> false")
                        updateMonitorNotification("Shizuku недоступен")
                    }
                    wasAvailable = false
                    continue
                }

                if (!wasAvailable) {
                    Log.i(TAG, "Shizuku доступен, восстанавливаем UserService")
                    val result = ShizukuCommandRunner.reconnect()
                    if (result.isSuccess) {
                        wasAvailable = true
                        Log.i(TAG, "UserService восстановлен")
                        updateMonitorNotification("Мониторинг складывания активен")
                        scheduleDensityCheck()
                    } else {
                        Log.w(TAG, "Не удалось восстановить UserService: ${result.exceptionOrNull()?.message}")
                        updateMonitorNotification("Shizuku доступен, подключение демона…")
                    }
                }
            }
        }
    }

    private fun createOpenAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun startForegroundSafely() {
        val channelId = "fold_monitor_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Монитор складывания", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }
        
        val active = DisplayUtils.getActiveDisplay(applicationContext)
        val initialMessage = if (active != null && active.density > 0) {
            formatDisplayStatus(active.isExternal, active.shortPx, active.density)
        } else if (ShizukuManager.isShizukuAvailable()) {
            "Мониторинг складывания активен"
        } else {
            "Shizuku недоступен"
        }
        val notification = buildMonitorNotification(initialMessage)
            
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

    private fun buildMonitorNotification(message: String): android.app.Notification {
        return NotificationCompat.Builder(this, "fold_monitor_channel")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Density Fold")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(createOpenAppPendingIntent())
            .build()
    }

    private fun formatDisplayStatus(isExternal: Boolean, shortPx: Int, dpi: Int): String {
        val logicalWidthDp = DisplayUtils.dpiToDp(dpi, shortPx)
        val screenName = if (isExternal) "Внешний" else "Внутренний"
        return "$screenName экран · $dpi DPI · $logicalWidthDp dp"
    }

    private fun updateMonitorNotification(message: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1, buildMonitorNotification(message))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Сервис запущен (startId: $startId)")
        scheduleDensityCheck()
        return START_STICKY
    }

    private fun scheduleDensityCheck() {
        checkChannel.trySend(Unit)
    }

    private suspend fun performDensityCheck() {
        Log.d(TAG, "Начало проверки и применения DPI")

        val active = DisplayUtils.getActiveDisplay(applicationContext)
        if (active == null) {
            Log.e(TAG, "Не удалось определить активный дисплей. Ожидание следующего события.")
            return
        }

        val isExternal = active.isExternal

        if (!ShizukuManager.isShizukuAvailable()) {
            Log.w(TAG, "Shizuku недоступен. Состояние НЕ зафиксировано, ожидание восстановления.")
            updateMonitorNotification("Shizuku недоступен")
            return
        }

        val prefs = DensityPreferences(applicationContext)
        val targetDp = if (isExternal) prefs.getOuterDp() else prefs.getInnerDp()
        val targetDpi = DisplayUtils.dpToDpi(targetDp, active.shortPx)
        
        if (targetDpi == null) {
            Log.e(TAG, "Некорректные параметры для расчёта DPI. Ожидание следующего события.")
            return
        }

        val currentDpiResult = ShizukuManager.getCurrentDensity()
        if (currentDpiResult.isFailure) {
            Log.w(TAG, "Не удалось прочитать текущий DPI: ${currentDpiResult.exceptionOrNull()?.message}")
            if (!ShizukuManager.isShizukuAvailable()) {
                updateMonitorNotification("Shizuku недоступен")
            }
            return
        }
        val currentDpi = currentDpiResult.getOrNull() ?: return

        if (currentDpi == targetDpi) {
            updateMonitorNotification(
                formatDisplayStatus(isExternal, active.shortPx, currentDpi)
            )
            // DPI уже правильный — но нужно подтвердить, что дисплей не сменился
            val currentActive = DisplayUtils.getActiveDisplay(applicationContext)
            if (currentActive != null && currentActive.isExternal == isExternal) {
                lastWasExternal = isExternal
                Log.d(TAG, "DPI уже установлен корректно ($targetDpi). Состояние зафиксировано.")
            } else if (currentActive == null) {
                Log.w(TAG, "DPI уже правильный, но не удалось определить текущий дисплей. Состояние НЕ зафиксировано.")
            } else {
                Log.w(TAG, "Экран сменился во время проверки DPI. Состояние НЕ зафиксировано, запланирована повторная проверка.")
                scheduleDensityCheck()
            }
            return
        }

        Log.d(TAG, "Применение DPI: $targetDpi для ${if (isExternal) "внешнего" else "внутреннего"} экрана")
        val result = ShizukuManager.setDensity(targetDpi)
        
        result.fold(
            onSuccess = { 
                // ПРИОРИТЕТ 1: Проверяем, не сменился ли экран во время применения DPI
                val currentActive = DisplayUtils.getActiveDisplay(applicationContext)
                if (currentActive == null) {
                    Log.w(TAG, "Не удалось подтвердить применение DPI: не удалось определить текущий дисплей. Состояние НЕ зафиксировано.")
                    return@fold
                }
                if (currentActive.isExternal != isExternal) {
                    Log.w(TAG, "Экран сменился во время применения DPI. Состояние НЕ зафиксировано, запланирована повторная проверка.")
                    scheduleDensityCheck()
                    return@fold
                }
                
                // ПРИОРИТЕТ 2: Экран не сменился — проверяем фактический DPI
                val actualDpi = ShizukuManager.getCurrentDensity().getOrNull()
                if (actualDpi == null) {
                    Log.w(TAG, "Не удалось подтвердить применение DPI: не удалось получить текущий DPI. Состояние НЕ зафиксировано.")
                } else if (actualDpi == targetDpi) {
                    updateMonitorNotification(
                        formatDisplayStatus(currentActive.isExternal, currentActive.shortPx, actualDpi)
                    )
                    lastWasExternal = isExternal
                    Log.d(TAG, "DPI успешно применён и подтверждён ($targetDpi). Состояние зафиксировано.")
                } else {
                    Log.w(TAG, "Не удалось подтвердить применение DPI: целевой=$targetDpi, фактический=$actualDpi. Состояние НЕ зафиксировано.")
                }
            },
            onFailure = {
                Log.e(TAG, "Ошибка применения DPI: ${it.message}. Состояние НЕ зафиксировано.")
                if (!ShizukuManager.isShizukuAvailable()) {
                    updateMonitorNotification("Shizuku недоступен")
                }
            }
        )
    }

    override fun onDestroy() {
        Log.d(TAG, "Сервис останавливается, очистка ресурсов")
        displayManager?.unregisterDisplayListener(displayListener)
        Shizuku.removeBinderDeadListener(shizukuDeadListener)
        checkChannel.close()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
