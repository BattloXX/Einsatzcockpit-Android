package cloud.einsatzleiter.smsgatewayplugin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat

/** Lautloser Kanal: Ton und Vibration kommen ausschliesslich vom Alarm-Service. */
object EinsatzAlarmNotification {
    const val CHANNEL_ID = "einsatz_alarm_native"
    const val NOTIFICATION_ID = 7310

    fun create(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(CHANNEL_ID)
        if (channel != null && manager.isNotificationPolicyAccessGranted && !channel.canBypassDnd()) {
            manager.deleteNotificationChannel(CHANNEL_ID)
        }
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "Einsatzalarm (Vollbild)", NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setSound(null, null)
            enableVibration(false)
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
    }

    fun build(
        context: Context,
        alarm: Intent,
        fallback: Boolean = false,
        finished: Boolean = false,
    ): Notification {
        val preview = alarm.getIntExtra(EinsatzAlarmService.EXTRA_PREVIEW_SECONDS, 0) > 0
        val channelId = if (fallback) AlarmNotificationChannel.CHANNEL_ID else CHANNEL_ID
        if (fallback) AlarmNotificationChannel.create(context) else create(context)
        val title = alarm.getStringExtra(EinsatzAlarmService.EXTRA_TITLE).orEmpty().ifBlank { "Einsatz" }
        val activityIntent = Intent(context, AlarmActivity::class.java).apply {
            putExtras(alarm)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val content = PendingIntent.getActivity(
            context, NOTIFICATION_ID, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID + 1,
            Intent(activityIntent).setAction(AlarmActivity.ACTION_OPEN),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            context, NOTIFICATION_ID,
            Intent(context, EinsatzAlarmService::class.java).setAction(EinsatzAlarmService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_ec_live)
            .setContentTitle(if (finished) "Einsatzalarm: $title" else title)
            .setContentText(if (preview) "Vorschau Alarmlautstärke" else alarm.getStringExtra(EinsatzAlarmService.EXTRA_BODY).orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                if (preview) "Vorschau Alarmlautstärke" else alarm.getStringExtra(EinsatzAlarmService.EXTRA_BODY).orEmpty(),
            ))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(!finished)
            .setAutoCancel(finished)
            .setOnlyAlertOnce(true)
            .setContentIntent(content)
            .apply {
                if (finished) setSilent(true)
                if (fallback && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    @Suppress("DEPRECATION")
                    setSound(Uri.parse("android.resource://${context.packageName}/raw/einsatz_alarm"), AudioManager.STREAM_ALARM)
                    setVibrate(longArrayOf(0, 700, 300, 700))
                }
                if (!preview && !finished) setFullScreenIntent(content, true)
            }
            .addAction(0, "Quittieren", stop)
            .addAction(0, "Einsatz öffnen", open)
            .build()
    }

    fun absoluteUrl(context: Context, url: String): String {
        if (url.startsWith("https://") || url.startsWith("http://")) return url
        val baseUrl = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
            .getString(EinsatzLivePoller.PREF_BASE_URL, "https://einsatzcockpit.com")
            ?.trimEnd('/') ?: "https://einsatzcockpit.com"
        return "$baseUrl/${url.trimStart('/')}"
    }
}
