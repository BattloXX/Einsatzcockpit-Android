package cloud.einsatzleiter.smsgatewayplugin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

internal object SmsGatewayNotification {
    private const val NOTIF_CHANNEL_ID = "ec_sms_gateway"

    fun createChannel(notificationManager: NotificationManager) {
        val ch = NotificationChannel(
            NOTIF_CHANNEL_ID,
            "SMS-Gateway",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Einsatzcockpit SMS-Gateway-Status"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(ch)
    }

    fun build(context: Context, status: String): Notification {
        val tapIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP) }
        val tapPending = if (tapIntent != null)
            PendingIntent.getActivity(context, 0, tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        else null
        return NotificationCompat.Builder(context, NOTIF_CHANNEL_ID)
            .setContentTitle("SMS-Gateway aktiv")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply { tapPending?.let { setContentIntent(it) } }
            .build()
    }

}
