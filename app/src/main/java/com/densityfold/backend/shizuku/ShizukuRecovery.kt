package com.densityfold.backend.shizuku

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.densityfold.ClosedActionActivity
import com.densityfold.DensityActionService
import com.densityfold.OpenActionActivity
import com.densityfold.engine.DisplayUtils

/**
 * Восстанавливает основной Shizuku Binder через штатную Action Activity.
 * Вызывается только ShizukuMonitor, пока Shizuku backend нужен приложению.
 */
object ShizukuRecovery {

    private const val TAG = "DensityFold-ShizukuRecovery"
    private const val FAST_INTERVAL_MS = 10_000L
    private const val MEDIUM_INTERVAL_MS = 30_000L
    private const val SLOW_INTERVAL_MS = 120_000L

    private const val FAST_STAGE_MS = 2 * 60_000L
    private const val MEDIUM_STAGE_MS = 10 * 60_000L
    private const val ACTION_TIMEOUT_MS = 20_000L

    @Volatile
    private var appContext: Context? = null

    private var binderUnavailableSince = 0L
    private var lastRecoveryAttemptAt = 0L
    private var activeRequestId = 0L
    private var activeRequestStartedAt = 0L

    private val completionReceiver = object : BroadcastReceiver() {
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

            synchronized(this@ShizukuRecovery) {
                if (requestId != 0L && requestId == activeRequestId) {
                    Log.i(TAG, "Recovery action завершено")
                    activeRequestId = 0L
                    activeRequestStartedAt = 0L
                }
            }
        }
    }

    @Synchronized
    fun initialize(context: Context) {
        if (appContext != null) return

        val app = context.applicationContext
        appContext = app

        val filter = IntentFilter(DensityActionService.ACTION_COMPLETED)
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(
                completionReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            app.registerReceiver(completionReceiver, filter)
        }

        Log.i(TAG, "Shizuku recovery инициализирован")
    }

    @Synchronized
    fun onRuntimeState(state: ShizukuRuntimeState) {
        val now = SystemClock.elapsedRealtime()

        if (state.mainBinder) {
            binderUnavailableSince = 0L
            lastRecoveryAttemptAt = 0L
            return
        }

        if (state.label != "MAIN_BINDER_DEAD") return

        if (binderUnavailableSince == 0L) {
            binderUnavailableSince = now
        }

        if (activeRequestId != 0L) {
            if (now - activeRequestStartedAt < ACTION_TIMEOUT_MS) return

            Log.w(TAG, "Recovery action timeout")
            activeRequestId = 0L
            activeRequestStartedAt = 0L
        }

        val unavailableDuration = now - binderUnavailableSince
        val interval = when {
            unavailableDuration < FAST_STAGE_MS -> FAST_INTERVAL_MS
            unavailableDuration < MEDIUM_STAGE_MS -> MEDIUM_INTERVAL_MS
            else -> SLOW_INTERVAL_MS
        }

        if (
            lastRecoveryAttemptAt != 0L &&
            now - lastRecoveryAttemptAt < interval
        ) return

        lastRecoveryAttemptAt = now
        Log.i(
            TAG,
            "Recovery cadence: unavailable=${unavailableDuration / 1000}s " +
                "interval=${interval / 1000}s"
        )
        launchRecoveryActivity()
    }

    private fun launchRecoveryActivity() {
        val context = appContext ?: run {
            Log.e(TAG, "Recovery невозможен: Context не инициализирован")
            return
        }

        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "SHIZUKU RECOVERY: нет SYSTEM_ALERT_WINDOW")
            return
        }

        val active = DisplayUtils.getActiveDisplay(context)
        if (active == null) {
            Log.w(TAG, "SHIZUKU RECOVERY: активный экран не определён")
            return
        }

        val isExternal = active.isExternal
        val activityClass = if (isExternal) {
            ClosedActionActivity::class.java
        } else {
            OpenActionActivity::class.java
        }

        val screenName = if (isExternal) "Внешний" else "Внутренний"
        val requestId = SystemClock.elapsedRealtimeNanos()

        synchronized(this) {
            activeRequestId = requestId
            activeRequestStartedAt = SystemClock.elapsedRealtime()
        }

        try {
            val intent = Intent(context, activityClass).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                putExtra(DensityActionService.EXTRA_MONITOR_REQUEST, true)
                putExtra(DensityActionService.EXTRA_MONITOR_REQUEST_ID, requestId)
            }

            Log.w(TAG, "SHIZUKU RECOVERY: запуск штатного действия $screenName")
            context.startActivity(intent)
        } catch (e: Exception) {
            synchronized(this) {
                if (activeRequestId == requestId) {
                    activeRequestId = 0L
                    activeRequestStartedAt = 0L
                }
            }
            Log.e(TAG, "SHIZUKU RECOVERY: ошибка запуска Activity", e)
        }
    }

    @Synchronized
    fun reset() {
        binderUnavailableSince = 0L
        lastRecoveryAttemptAt = 0L
        activeRequestId = 0L
        activeRequestStartedAt = 0L
    }
}
