package com.densityfold

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat

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

    fun build(
        message: String,
        controlsAvailable: Boolean,
        centerLabel: String,
        centerSubLabel: String? = null
    ): Notification {
        val views = RemoteViews(
            context.packageName,
            R.layout.notification_dpi_controls
        )

        views.setOnClickPendingIntent(
            R.id.notification_root,
            createOpenAppPendingIntent()
        )

        if (controlsAvailable) {
            views.setViewVisibility(R.id.notification_minus, android.view.View.VISIBLE)
            views.setViewVisibility(R.id.notification_profile, android.view.View.VISIBLE)
            views.setViewVisibility(R.id.notification_plus, android.view.View.VISIBLE)
            views.setViewVisibility(R.id.notification_unavailable, android.view.View.GONE)

            views.setTextViewText(R.id.notification_current_dpi, centerLabel)
            views.setViewVisibility(
                R.id.notification_profile_sub,
                if (centerSubLabel.isNullOrBlank()) android.view.View.GONE
                else android.view.View.VISIBLE
            )
            if (!centerSubLabel.isNullOrBlank()) {
                views.setTextViewText(R.id.notification_profile_sub, centerSubLabel)
            }

            views.setOnClickPendingIntent(
                R.id.notification_minus,
                createActionPendingIntent(
                    FoldMonitorService.ACTION_NOTIFICATION_MINUS,
                    101
                )
            )
            views.setOnClickPendingIntent(
                R.id.notification_profile,
                createActionPendingIntent(
                    FoldMonitorService.ACTION_NOTIFICATION_PROFILE,
                    102
                )
            )
            views.setOnClickPendingIntent(
                R.id.notification_plus,
                createActionPendingIntent(
                    FoldMonitorService.ACTION_NOTIFICATION_PLUS,
                    103
                )
            )
        } else {
            views.setViewVisibility(R.id.notification_minus, android.view.View.GONE)
            views.setViewVisibility(R.id.notification_profile, android.view.View.GONE)
            views.setViewVisibility(R.id.notification_plus, android.view.View.GONE)
            views.setViewVisibility(R.id.notification_unavailable, android.view.View.VISIBLE)
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(createOpenAppPendingIntent())
            .setCustomContentView(views)
            .setCustomBigContentView(views)
            .build()
    }

    fun update(
        message: String,
        controlsAvailable: Boolean,
        centerLabel: String,
        centerSubLabel: String? = null
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager

        manager.notify(
            NOTIFICATION_ID,
            build(message, controlsAvailable, centerLabel, centerSubLabel)
        )
    }

    private fun createActionPendingIntent(
        action: String,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(context, FoldMonitorService::class.java).apply {
            this.action = action
        }

        return PendingIntent.getService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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
