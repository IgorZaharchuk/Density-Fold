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
import com.densityfold.engine.DensityExecutor
import com.densityfold.engine.DensityProfile
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
            Log.d(TAG, "onStartCommand: null action, stopping")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        Log.d(TAG, "onStartCommand: action=$action, startId=$startId")
        val isClosed = action == ACTION_CLOSED
        val screenName = if (isClosed) "Внешний" else "Внутренний"

        val profile = when (action) {
            ACTION_OPEN -> DensityProfile.INNER
            ACTION_CLOSED -> DensityProfile.OUTER
            else -> {
                Log.w(TAG, "Unknown action: $action")
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }

        scope.launch {
            try {
                Log.i(TAG, "Executing $profile through DensityExecutor")

                val result = DensityExecutor.apply(
                    applicationContext,
                    profile
                )

                result.fold(
                    onSuccess = { applied ->
                        val screenName = if (applied.isExternal) "Внешний" else "Внутренний"
                        val logicalDp = applied.targetDp

                        Log.i(
                            TAG,
                            "Action confirmed: screen=$screenName " +
                                "target=${applied.targetDpi} actual=${applied.actualDpi}"
                        )

                        showNotification(
                            applicationContext,
                            screenName,
                            logicalDp,
                            true,
                            null
                        )
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Density action failed: ${error.message}", error)

                        showNotification(
                            applicationContext,
                            if (profile == DensityProfile.OUTER) "Внешний" else "Внутренний",
                            0,
                            false,
                            error.message ?: "Не удалось применить DPI"
                        )
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Exception in service: ${e.message}", e)
                showNotification(
                    applicationContext,
                    if (profile == DensityProfile.OUTER) "Внешний" else "Внутренний",
                    0,
                    false,
                    e.message ?: "Неизвестная ошибка"
                )
            } finally {
                sendCompletionBroadcast()
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    private fun sendCompletionBroadcast() {
        val intent = Intent(ACTION_COMPLETED).apply { setPackage(packageName) }
        sendBroadcast(intent)
    }

    private fun showNotification(context: Context, screenName: String, dp: Int, isSuccess: Boolean, errorMsg: String?) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "density_actions_channel"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Действия Density Fold", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }
        
        val title = if (isSuccess) "Density Fold: Успешно" else "Density Fold: Ошибка"
        val text = if (isSuccess) "$screenName экран: установлено $dp dp" else (errorMsg ?: "Неизвестная ошибка")
        
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()
            
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_OPEN = "com.densityfold.ACTION_OPEN"
        const val ACTION_CLOSED = "com.densityfold.ACTION_CLOSED"
        const val ACTION_COMPLETED = "com.densityfold.ACTION_COMPLETED"

        fun startOpen(context: Context) {
            context.startService(Intent(context, DensityActionService::class.java).apply { action = ACTION_OPEN })
        }

        fun startClosed(context: Context) {
            context.startService(Intent(context, DensityActionService::class.java).apply { action = ACTION_CLOSED })
        }
    }
}
