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
import com.densityfold.data.DensityPreferences
import com.densityfold.engine.DisplayUtils
import com.densityfold.engine.ShizukuManager
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

        scope.launch {
            try {
                val readiness = ShizukuManager.awaitShizukuReady(10_000L)

                if (readiness.isFailure) {
                    val error = readiness.exceptionOrNull()
                    Log.w(TAG, "Shizuku readiness failed: ${error?.message}")

                    showNotification(
                        applicationContext,
                        screenName,
                        0,
                        false,
                        error?.message ?: "Не удалось подключиться к Shizuku"
                    )
                    return@launch
                }

                Log.i(TAG, "Shizuku ready, executing action: $action")

                val prefs = DensityPreferences(applicationContext)
                val targetDp = if (isClosed) prefs.getOuterDp() else prefs.getInnerDp()
                
                val shortPx = if (isClosed) DisplayUtils.getOuterShortPx(applicationContext) 
                              else DisplayUtils.getInnerShortPx(applicationContext)
                
                val targetDpi = DisplayUtils.dpToDpi(targetDp, shortPx)
                if (targetDpi == null) {
                    Log.d(TAG, "Invalid targetDpi calculation")
                    showNotification(applicationContext, screenName, 0, false, "Некорректные параметры")
                    return@launch
                }

                val currentDpiResult = ShizukuManager.getCurrentDensity()
                if (currentDpiResult.isFailure) {
                    val error = currentDpiResult.exceptionOrNull()
                    Log.w(TAG, "Cannot read current DPI: ${error?.message}")
                    showNotification(
                        applicationContext,
                        screenName,
                        targetDp,
                        false,
                        error?.message ?: "Не удалось проверить подключение Shizuku"
                    )
                    return@launch
                }
                val currentDpi = currentDpiResult.getOrNull()
                if (currentDpi == targetDpi) {
                    Log.d(TAG, "Bypass: currentDpi ($currentDpi) == targetDpi ($targetDpi)")
                    return@launch
                }

                Log.d(TAG, "Executing wm density $targetDpi")
                val result = ShizukuManager.setDensity(targetDpi)
                result.fold(
                    onSuccess = { 
                        Log.d(TAG, "wm density success")
                        showNotification(applicationContext, screenName, targetDp, true, null) 
                    },
                    onFailure = { 
                        Log.d(TAG, "wm density failed: ${it.message}")
                        showNotification(applicationContext, screenName, targetDp, false, it.message) 
                    }
                )
            } catch (e: Exception) {
                Log.d(TAG, "Exception in service: ${e.message}")
                showNotification(applicationContext, screenName, 0, false, e.message ?: "Неизвестная ошибка")
            } finally {
                Log.d(TAG, "finally block: sending ACTION_COMPLETED")
                sendCompletionBroadcast()
                Log.d(TAG, "finally block: calling stopSelf($startId)")
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
