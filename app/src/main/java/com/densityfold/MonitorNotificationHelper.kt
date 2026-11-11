package com.densityfold

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.densityfold.engine.DisplayUtils

class MonitorNotificationHelper(private val context: Context) {
    companion object {
        private const val CHANNEL_ID = "fold_monitor_channel"
        private const val NOTIFICATION_ID = 1
    }

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Монитор складывания",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    fun build(message: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Density Fold")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(createOpenAppPendingIntent())
            .build()

    fun update(message: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        manager.notify(NOTIFICATION_ID, build(message))
    }

    fun formatDisplayStatus(
        isExternal: Boolean,
        shortPx: Int,
        dpi: Int
    ): String {
        val logicalWidthDp = DisplayUtils.dpiToDp(dpi, shortPx)
        val screenName = if (isExternal) "Внешний" else "Внутренний"
        return "$screenName экран · $dpi DPI · $logicalWidthDp dp"
    }

    private fun createOpenAppPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
