package com.densityfold

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.Display
import com.densityfold.engine.DisplayUtils
import com.densityfold.data.DensityPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class FoldMonitorService : Service() {
    private val TAG = "DensityFold-Monitor"
    private val notifications by lazy { MonitorNotificationHelper(this) }
    // Все поля состояния монитора обслуживаются последовательно на Main.
    // BroadcastReceiver и DisplayListener также вызываются на главном потоке.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var displayManager: DisplayManager? = null

    private val checkChannel = Channel<Unit>(Channel.CONFLATED)

    private var lastRequestedExternal: Boolean? = null
    private var activeRequestId: Long? = null
    private var retryPending = false
    private var retryJob: Job? = null
    private var actionTimeoutJob: Job? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            scheduleDensityCheck()
        }
    }

    private val actionCompletedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DensityActionService.ACTION_COMPLETED) return
            if (!intent.getBooleanExtra(
                    DensityActionService.EXTRA_MONITOR_REQUEST,
                    false
                )
            ) return

            val requestId = intent.getLongExtra(
                DensityActionService.EXTRA_MONITOR_REQUEST_ID,
                0L
            )
            if (requestId == 0L || activeRequestId != requestId) {
                Log.d(TAG, "Игнорируется устаревший ответ монитора: id=$requestId")
                return
            }

            actionTimeoutJob?.cancel()
            actionTimeoutJob = null
            activeRequestId = null

            val success = intent.getBooleanExtra(
                DensityActionService.EXTRA_SUCCESS,
                false
            )

            if (success) {
                retryPending = false
                retryJob?.cancel()

                val isExternal = intent.getBooleanExtra(
                    DensityActionService.EXTRA_IS_EXTERNAL,
                    false
                )
                val shortPx = intent.getIntExtra(
                    DensityActionService.EXTRA_SHORT_PX,
                    0
                )
                val actualDpi = intent.getIntExtra(
                    DensityActionService.EXTRA_ACTUAL_DPI,
                    0
                )

                if (shortPx > 0 && actualDpi > 0) {
                    notifications.update(
                        notifications.formatDisplayStatus(isExternal, shortPx, actualDpi)
                    )
                } else {
                    notifications.update("Мониторинг складывания активен")
                }

                Log.i(TAG, "Действие подтверждено монитором")

                val active = DisplayUtils.getActiveDisplay(applicationContext)
                if (active == null || active.isExternal != isExternal) {
                    Log.i(TAG, "Экран изменился во время выполнения, повторная проверка")
                    scheduleDensityCheck()
                }
            } else {
                retryPending = true
                val error = intent.getStringExtra(DensityActionService.EXTRA_ERROR)
                Log.w(TAG, "Действие не выполнено: ${error ?: "неизвестная ошибка"}")
                notifications.update("Не удалось выполнить действие · повторная попытка")
                scheduleRetry()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        Log.i(TAG, "MONITOR onCreate")
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager?.registerDisplayListener(displayListener, null)

        val filter = IntentFilter(DensityActionService.ACTION_COMPLETED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(actionCompletedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(actionCompletedReceiver, filter)
        }

        startForegroundSafely()

        scope.launch {
            for (event in checkChannel) {
                try {
                    performDensityCheck()
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка обработки смены экрана: ${e.message}", e)
                }
            }
        }
    }


    private fun beginRequest(isExternal: Boolean): Long {
        val requestId = SystemClock.elapsedRealtimeNanos()
        lastRequestedExternal = isExternal
        retryPending = false
        activeRequestId = requestId
        return requestId
    }

    private fun startForegroundSafely() {
        notifications.ensureChannel()

        val active = DisplayUtils.getActiveDisplay(applicationContext)
        val initialMessage = if (active != null && active.density > 0) {
            notifications.formatDisplayStatus(
                active.isExternal,
                active.shortPx,
                active.density
            )
        } else {
            "Мониторинг складывания активен"
        }

        try {
            val notification = notifications.build(initialMessage)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    1,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска Foreground: ${e.message}", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            try {
                val settings = DensityPreferences(applicationContext).getSettings()
                if (!settings.foldMonitorEnabled) {
                    Log.i(TAG, "Мониторинг отключён в настройках; остановка сервиса")
                    stopSelf(startId)
                    return@launch
                }
scheduleDensityCheck()
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось проверить настройки монитора", e)
                stopSelf(startId)
            }
        }
        return START_STICKY
    }

    private fun scheduleDensityCheck() {
        checkChannel.trySend(Unit)
    }

    private suspend fun performDensityCheck() {
        val active = DisplayUtils.getActiveDisplay(applicationContext)
        if (active == null) {
            Log.w(TAG, "Активный дисплей не определён")
            return
        }

        val isExternal = active.isExternal

        notifications.update(
            notifications.formatDisplayStatus(isExternal, active.shortPx, active.density)
        )

        if (activeRequestId != null) {
            return
        }

if (lastRequestedExternal == isExternal && !retryPending) {
            return
        }

        val requestId = beginRequest(isExternal)

        val actionName = if (isExternal) "Внешний" else "Внутренний"
        Log.i(TAG, "Запуск действия: $actionName")

        try {
            if (isExternal) {
                DensityActionService.startClosed(
                    this,
                    fromMonitor = true,
                    requestId = requestId
                )
            } else {
                DensityActionService.startOpen(
                    this,
                    fromMonitor = true,
                    requestId = requestId
                )
            }

            startActionTimeout(
                requestId = requestId,
                logMessage = "Тайм-аут ожидания результата действия",
                notificationMessage = "Нет ответа от сервиса · повторная попытка"
            )
        } catch (e: Exception) {
            if (activeRequestId == requestId) {
                actionTimeoutJob?.cancel()
                actionTimeoutJob = null
                activeRequestId = null
                retryPending = true
            }
            Log.e(TAG, "Не удалось запустить сервис: ${e.message}", e)
            notifications.update("Не удалось запустить действие · повторная попытка")
            scheduleRetry()
        }
    }

    private fun startActionTimeout(
        requestId: Long,
        logMessage: String,
        notificationMessage: String
    ) {
        actionTimeoutJob?.cancel()
        actionTimeoutJob = scope.launch {
            delay(20_000L)
            if (activeRequestId == requestId) {
                activeRequestId = null
                retryPending = true
                Log.w(TAG, logMessage)
                notifications.update(notificationMessage)
                scheduleRetry()
            }
        }
    }

    private fun scheduleRetry() {
        if (retryJob?.isActive == true) return

        retryJob = scope.launch {
            delay(5_000L)
            if (retryPending && activeRequestId == null) {
                Log.i(TAG, "Повторный запуск действия для текущего экрана")
                scheduleDensityCheck()
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Монитор останавливается")
        displayManager?.unregisterDisplayListener(displayListener)
        retryJob?.cancel()
        actionTimeoutJob?.cancel()

        try {
            unregisterReceiver(actionCompletedReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось снять receiver: ${e.message}")
        }

        checkChannel.close()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
