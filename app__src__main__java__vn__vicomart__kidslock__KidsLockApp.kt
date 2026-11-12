package vn.vicomart.kidslock

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import vn.vicomart.kidslock.ui.MainActivity

class KidsLockApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }

    companion object {
        const val CH_PROTECTION = "protection"
        const val CH_ALERTS = "alerts"
        private const val ALERT_ID = 2001

        fun createChannels(ctx: Context) {
            val c = Lang.wrap(ctx)
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CH_PROTECTION,
                    c.getString(R.string.notif_channel_protection),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CH_ALERTS,
                    c.getString(R.string.notif_channel_alerts),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }

        fun openAppIntent(ctx: Context): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(
                ctx, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /** Shows an alert notification. Silently does nothing if notifications are not allowed. */
        fun notifyAlert(ctx: Context, titleRes: Int, textRes: Int) {
            val c = Lang.wrap(ctx)
            val n = NotificationCompat.Builder(ctx, CH_ALERTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(c.getString(titleRes))
                .setContentText(c.getString(textRes))
                .setStyle(NotificationCompat.BigTextStyle().bigText(c.getString(textRes)))
                .setContentIntent(openAppIntent(ctx))
                .setAutoCancel(true)
                .build()
            try {
                NotificationManagerCompat.from(ctx).notify(ALERT_ID, n)
            } catch (_: SecurityException) {
                // POST_NOTIFICATIONS not granted
            }
        }
    }
}
