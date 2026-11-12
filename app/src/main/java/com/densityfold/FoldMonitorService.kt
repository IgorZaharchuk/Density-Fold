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
import android.provider.Settings
import android.util.Log
import android.view.Display
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
    private val notifications by lazy { MonitorNotificationHelper(this) }
    // Все поля состояния монитора обслуживаются последовательно на Main.
    // BroadcastReceiver и DisplayListener также вызываются на главном потоке.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var displayManager: DisplayManager? = null

    private val checkChannel = Channel<Unit>(Channel.CONFLATED)

    private var lastRequestedExternal: Boolean? = null
    private var actionInFlight = false
    private var activeRequestId: Long? = null
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
            actionInFlight = false

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
                notifications.update("Shizuku недоступен · ожидание восстановления")
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
                val runtimeState = ShizukuCommandRunner.currentState()
                val mainBinder = runtimeState.mainBinder
                val permission = runtimeState.permission
                val userService = runtimeState.userService
                val state = runtimeState.label

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
                                notifications.formatDisplayStatus(
                                    active.isExternal,
                                    active.shortPx,
                                    active.density
                                )
                            } else {
                                "Мониторинг складывания активен"
                            }
                        }
                    }

                    notifications.update(message)
                    previousState = state
                }

                if (mainBinder && permission && !userService) {
                    ShizukuCommandRunner.requestReconnect()
                }

                delay(3_000L)
            }
        }
    }

    private fun launchRecoveryAction(): Boolean {
        lastRecoveryAttemptAt = SystemClock.elapsedRealtime()
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SHIZUKU RECOVERY: нет SYSTEM_ALERT_WINDOW")
            notifications.update("Разрешите фоновый запуск для восстановления Shizuku")
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
        val requestId = SystemClock.elapsedRealtimeNanos()

        return try {
            val intent = Intent(this, activityClass).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                putExtra(DensityActionService.EXTRA_MONITOR_REQUEST, true)
                putExtra(DensityActionService.EXTRA_MONITOR_REQUEST_ID, requestId)
            }

            lastRequestedExternal = isExternal
            retryPending = false
            activeRequestId = requestId
            actionInFlight = true

            Log.i(
                TAG,
                "SHIZUKU RECOVERY: запуск штатного действия $screenName"
            )

            startActivity(intent)

            startActionTimeout(
                requestId = requestId,
                logMessage = "SHIZUKU RECOVERY: тайм-аут штатного действия",
                notificationMessage = "Нет ответа от Shizuku · повторная попытка"
            )

            true
        } catch (e: Exception) {
            if (activeRequestId == requestId) {
                activeRequestId = null
                actionInFlight = false
                retryPending = true
            }
            Log.e(TAG, "SHIZUKU RECOVERY: ошибка запуска $screenName", e)
            false
        }
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

        notifications.update(
            notifications.formatDisplayStatus(isExternal, active.shortPx, active.density)
        )

        if (actionInFlight) {
            return
        }

        if (!ShizukuCommandRunner.mainBinderAvailable()) {
            retryPending = true
            notifications.update(
                "Shizuku Binder недоступен · восстановление"
            )
            return
        }

        if (lastRequestedExternal == isExternal && !retryPending) {
            return
        }

        lastRequestedExternal = isExternal
        retryPending = false
        val requestId = SystemClock.elapsedRealtimeNanos()
        activeRequestId = requestId
        actionInFlight = true

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
                actionInFlight = false
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
            if (actionInFlight && activeRequestId == requestId) {
                activeRequestId = null
                actionInFlight = false
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
