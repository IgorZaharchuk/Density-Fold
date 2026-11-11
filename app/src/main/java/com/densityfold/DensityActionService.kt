package com.densityfold

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.densityfold.engine.DensityApplyResult
import com.densityfold.engine.DensityExecutor
import com.densityfold.engine.DensityProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DensityActionService : Service() {
    private val TAG = "DensityFold-Service"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: run {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val profile = when (action) {
            ACTION_OPEN -> DensityProfile.INNER
            ACTION_CLOSED -> DensityProfile.OUTER
            else -> {
                Log.w(TAG, "Unknown action: $action")
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }

        val fromMonitor = intent.getBooleanExtra(EXTRA_MONITOR_REQUEST, false)

        scope.launch {
            var succeeded = false
            var applied: DensityApplyResult? = null
            var failureMessage: String? = null
            var cancelled = false

            try {

                val value = DensityExecutor.apply(applicationContext, profile).getOrThrow()
                succeeded = true
                applied = value

                val screenName = if (value.isExternal) "Внешний" else "Внутренний"
                Log.i(
                    TAG,
                    "Action confirmed: screen=$screenName " +
                        "target=${value.targetDpi} actual=${value.actualDpi}"
                )

                if (!fromMonitor) {
                    showNotification(
                        applicationContext,
                        screenName,
                        value.targetDpi,
                        true,
                        null
                    )
                }
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } catch (e: Exception) {
                failureMessage = reportFailure(e, profile, fromMonitor)
            } finally {
                if (!cancelled) {
                    sendCompletionBroadcast(
                        fromMonitor = fromMonitor,
                        success = succeeded,
                        applied = applied,
                        error = failureMessage,
                        expectedExternal = profile == DensityProfile.OUTER
                    )
                }
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    private fun reportFailure(
        error: Throwable,
        profile: DensityProfile,
        fromMonitor: Boolean
    ): String {
        val message = error.message ?: "Не удалось применить DPI"
        Log.e(TAG, "Density action failed: $message", error)

        if (!fromMonitor) {
            showNotification(
                applicationContext,
                if (profile == DensityProfile.OUTER) "Внешний" else "Внутренний",
                0,
                false,
                message
            )
        }

        return message
    }

    private fun sendCompletionBroadcast(
        fromMonitor: Boolean,
        success: Boolean,
        applied: DensityApplyResult?,
        error: String?,
        expectedExternal: Boolean
    ) {
        val intent = Intent(ACTION_COMPLETED).apply {
            setPackage(packageName)
            putExtra(EXTRA_MONITOR_REQUEST, fromMonitor)
            putExtra(EXTRA_SUCCESS, success)
            putExtra(EXTRA_IS_EXTERNAL, applied?.isExternal ?: expectedExternal)
            putExtra(EXTRA_SHORT_PX, applied?.shortPx ?: 0)
            putExtra(EXTRA_ACTUAL_DPI, applied?.actualDpi ?: 0)
            putExtra(EXTRA_ERROR, error)
        }
        sendBroadcast(intent)
    }

    private fun showNotification(
        context: Context,
        screenName: String,
        dpi: Int,
        isSuccess: Boolean,
        errorMsg: String?
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "density_actions_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "Действия Density Fold",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val title = if (isSuccess) "Density Fold: Успешно" else "Density Fold: Ошибка"
        val text = if (isSuccess) {
            "$screenName экран: установлено $dpi DPI"
        } else {
            errorMsg ?: "Не удалось применить DPI"
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()

        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_OPEN = "com.densityfold.ACTION_OPEN"
        const val ACTION_CLOSED = "com.densityfold.ACTION_CLOSED"
        const val ACTION_COMPLETED = "com.densityfold.ACTION_COMPLETED"

        const val EXTRA_MONITOR_REQUEST = "com.densityfold.EXTRA_MONITOR_REQUEST"
        const val EXTRA_SUCCESS = "com.densityfold.EXTRA_SUCCESS"
        const val EXTRA_IS_EXTERNAL = "com.densityfold.EXTRA_IS_EXTERNAL"
        const val EXTRA_SHORT_PX = "com.densityfold.EXTRA_SHORT_PX"
        const val EXTRA_ACTUAL_DPI = "com.densityfold.EXTRA_ACTUAL_DPI"
        const val EXTRA_ERROR = "com.densityfold.EXTRA_ERROR"

        fun startOpen(context: Context, fromMonitor: Boolean = false) {
            context.startService(
                Intent(context, DensityActionService::class.java).apply {
                    action = ACTION_OPEN
                    putExtra(EXTRA_MONITOR_REQUEST, fromMonitor)
                }
            )
        }

        fun startClosed(context: Context, fromMonitor: Boolean = false) {
            context.startService(
                Intent(context, DensityActionService::class.java).apply {
                    action = ACTION_CLOSED
                    putExtra(EXTRA_MONITOR_REQUEST, fromMonitor)
                }
            )
        }
    }
}
