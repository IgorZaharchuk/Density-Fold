package com.densityfold

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.provider.Settings
import android.util.Log
import android.view.Display
import androidx.core.app.NotificationCompat
import com.densityfold.engine.DisplayUtils
import com.densityfold.engine.ShizukuCommandRunner
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var displayManager: DisplayManager? = null

    private val checkChannel = Channel<Unit>(Channel.CONFLATED)

    private var lastRequestedExternal: Boolean? = null
    private var actionInFlight = false
    private var retryPending = false
    private var retryJob: Job? = null
    private var actionTimeoutJob: Job? = null
    private var shizukuWatchdogJob: Job? = null
    private var lastRecoveryAttemptAt = 0L

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            Log.d(TAG, "Изменился активный дисплей ID=$displayId")
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

            actionTimeoutJob?.cancel()
            actionTimeoutJob = null
            actionInFlight = false

            val success = intent.getBooleanExtra(
                DensityActionService.EXTRA_SUCCESS,
                false
            )
            Log.i(TAG, "Получен результат действия от сервиса: success=$success")

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
                    updateMonitorNotification(
                        formatDisplayStatus(isExternal, shortPx, actualDpi)
                    )
                } else {
                    updateMonitorNotification("Мониторинг складывания активен")
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
                updateMonitorNotification("Shizuku недоступен · ожидание восстановления")
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
        startShizukuWatchdog()

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


    private fun startShizukuWatchdog() {
        shizukuWatchdogJob?.cancel()
        shizukuWatchdogJob = scope.launch {
            var previousState: String? = null
            var binderUnavailableSince = 0L

            while (true) {
                val mainBinder = ShizukuCommandRunner.mainBinderAvailable()
                val permission = ShizukuCommandRunner.permissionGranted()
                val userService = ShizukuCommandRunner.userServiceAvailable()

                val state = when {
                    !mainBinder -> "MAIN_BINDER_DEAD"
                    !permission -> "PERMISSION_DENIED"
                    !userService -> "USER_SERVICE_DISCONNECTED"
                    else -> "READY"
                }

                val now = SystemClock.elapsedRealtime()

                if (mainBinder) {
                    lastRecoveryAttemptAt = 0L
                    binderUnavailableSince = 0L
                } else if (binderUnavailableSince == 0L) {
                    binderUnavailableSince = now
                }

                val recoveryInterval = if (
                    binderUnavailableSince == 0L ||
                    now - binderUnavailableSince < 60_000L
                ) {
                    10_000L
                } else {
                    60_000L
                }

                if (
                    state == "MAIN_BINDER_DEAD" &&
                    !actionInFlight &&
                    (
                        lastRecoveryAttemptAt == 0L ||
                        now - lastRecoveryAttemptAt >= recoveryInterval
                    )
                ) {
                    launchRecoveryAction()
                }

                if (state != previousState) {
                    Log.i(TAG, "Состояние Shizuku: $state")

                    val message = when (state) {
                        "MAIN_BINDER_DEAD" ->
                            "Shizuku Binder недоступен · ожидание запуска"
                        "PERMISSION_DENIED" ->
                            "Shizuku запущен · нет разрешения"
                        "USER_SERVICE_DISCONNECTED" ->
                            "Shizuku Binder работает · восстановление UserService"
                        else -> {
                            val active = DisplayUtils.getActiveDisplay(applicationContext)
                            if (active != null && active.density > 0) {
                                formatDisplayStatus(
                                    active.isExternal,
                                    active.shortPx,
                                    active.density
                                )
                            } else {
                                "Мониторинг складывания активен"
                            }
                        }
                    }

                    updateMonitorNotification(message)
                    previousState = state
                }

                if (mainBinder && permission && !userService) {
                    val result = ShizukuCommandRunner.reconnect()
                    if (result.isSuccess) {
                        Log.i(TAG, "UserService восстановлен")
                    } else {
                        Log.w(
                            TAG,
                            "Не удалось восстановить UserService: " +
                                (result.exceptionOrNull()?.message ?: "неизвестная ошибка")
                        )
                    }
                }

                delay(3_000L)
            }
        }
    }

    private fun launchRecoveryAction(): Boolean {
        lastRecoveryAttemptAt = SystemClock.elapsedRealtime()
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SHIZUKU RECOVERY: нет SYSTEM_ALERT_WINDOW")
            updateMonitorNotification("Разрешите фоновый запуск для восстановления Shizuku")
            return false
        }

        val active = DisplayUtils.getActiveDisplay(applicationContext)
        if (active == null) {
            Log.w(TAG, "SHIZUKU RECOVERY: активный экран не определён")
            return false
        }

        val isExternal = active.isExternal
        val activityClass = if (isExternal) {
            ClosedActionActivity::class.java
        } else {
            OpenActionActivity::class.java
        }

        val screenName = if (isExternal) "Внешний" else "Внутренний"

        return try {
            val intent = Intent(this, activityClass).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                putExtra(DensityActionService.EXTRA_MONITOR_REQUEST, true)
            }

            lastRequestedExternal = isExternal
            retryPending = false
            actionInFlight = true

            Log.i(
                TAG,
                "SHIZUKU RECOVERY: запуск штатного действия $screenName"
            )

            startActivity(intent)

            actionTimeoutJob?.cancel()
            actionTimeoutJob = scope.launch {
                delay(20_000L)
                if (actionInFlight) {
                    actionInFlight = false
                    retryPending = true
                    Log.w(TAG, "SHIZUKU RECOVERY: тайм-аут штатного действия")
                    updateMonitorNotification(
                        "Нет ответа от Shizuku · повторная попытка"
                    )
                    scheduleRetry()
                }
            }

            true
        } catch (e: Exception) {
            actionInFlight = false
            retryPending = true
            Log.e(TAG, "SHIZUKU RECOVERY: ошибка запуска $screenName", e)
            false
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
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "Монитор складывания",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val active = DisplayUtils.getActiveDisplay(applicationContext)
        val initialMessage = if (active != null && active.density > 0) {
            formatDisplayStatus(active.isExternal, active.shortPx, active.density)
        } else {
            "Мониторинг складывания активен"
        }

        val notification = buildMonitorNotification(initialMessage)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    1,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(1, notification)
            }
            Log.d(TAG, "Foreground уведомление установлено")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска Foreground: ${e.message}", e)
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

    private fun formatDisplayStatus(
        isExternal: Boolean,
        shortPx: Int,
        dpi: Int
    ): String {
        val logicalWidthDp = DisplayUtils.dpiToDp(dpi, shortPx)
        val screenName = if (isExternal) "Внешний" else "Внутренний"
        return "$screenName экран · $dpi DPI · $logicalWidthDp dp"
    }

    private fun updateMonitorNotification(message: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1, buildMonitorNotification(message))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Сервис запущен startId=$startId")
        scheduleDensityCheck()
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

        Log.i(
            TAG,
            "Текущее состояние дисплея: ${if (isExternal) "Внешний" else "Внутренний"}, " +
                "${active.width}x${active.height}, DPI=${active.density}"
        )
        updateMonitorNotification(
            formatDisplayStatus(isExternal, active.shortPx, active.density)
        )

        if (actionInFlight) {
            Log.d(TAG, "Действие уже выполняется, событие объединено")
            return
        }

        if (!ShizukuCommandRunner.mainBinderAvailable()) {
            retryPending = true
            Log.i(
                TAG,
                "Главный Shizuku Binder недоступен: " +
                    "ожидание запуска штатного действия watchdog"
            )
            updateMonitorNotification(
                "Shizuku Binder недоступен · восстановление"
            )
            return
        }

        if (lastRequestedExternal == isExternal && !retryPending) {
            return
        }

        lastRequestedExternal = isExternal
        retryPending = false
        actionInFlight = true

        val actionName = if (isExternal) "Внешний" else "Внутренний"
        Log.i(TAG, "Запуск действия: $actionName")

        try {
            if (isExternal) {
                Log.i(TAG, "Прямой запуск сервиса: Внешний")
                DensityActionService.startClosed(this, fromMonitor = true)
            } else {
                Log.i(TAG, "Прямой запуск сервиса: Внутренний")
                DensityActionService.startOpen(this, fromMonitor = true)
            }

            actionTimeoutJob?.cancel()
            actionTimeoutJob = scope.launch {
                delay(20_000L)
                if (actionInFlight) {
                    actionInFlight = false
                    retryPending = true
                    Log.w(TAG, "Тайм-аут ожидания результата действия")
                    updateMonitorNotification("Нет ответа от сервиса · повторная попытка")
                    scheduleRetry()
                }
            }
        } catch (e: Exception) {
            actionTimeoutJob?.cancel()
            actionTimeoutJob = null
            actionInFlight = false
            retryPending = true
            Log.e(TAG, "Не удалось запустить сервис: ${e.message}", e)
            updateMonitorNotification("Не удалось запустить действие · повторная попытка")
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (retryJob?.isActive == true) return

        retryJob = scope.launch {
            delay(5_000L)
            if (retryPending && !actionInFlight) {
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
        shizukuWatchdogJob?.cancel()

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
