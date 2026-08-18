package cloud.einsatzleiter.smsgatewayplugin

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** Empfaengt FCM-Data-Nachrichten auch bei beendeter WebView. */
class EinsatzFirebaseMessagingService : FirebaseMessagingService() {
    companion object {
        private const val GENERIC_CHANNEL_ID = "ec_push"
        private const val GENERIC_NOTIFICATION_ID = 7305
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val refresh = Intent(this, DeviceKeepaliveService::class.java).apply {
            action = DeviceKeepaliveService.ACTION_LIVE_REFRESH
        }
        startForegroundService(refresh)

        // Einsatzalarme werden nach dem sofortigen Poll als konsistente
        // "Laufender Einsatz"-Notification dargestellt. Andere Meldungen
        // brauchen weiterhin eine eigene, antippbare Systembenachrichtigung.
        if (data["channel_id"] != "einsatz_alarm") {
            postGenericNotification(
                data["title"].orEmpty(),
                data["body"].orEmpty(),
                data["url"].orEmpty(),
            )
        }
    }

    private fun postGenericNotification(title: String, body: String, url: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            GENERIC_CHANNEL_ID,
            "Einsatzcockpit-Meldungen",
            NotificationManager.IMPORTANCE_DEFAULT,
        ))
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EinsatzLiveNotifier.EXTRA_EC_URL, absoluteUrl(url))
        }
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this, GENERIC_NOTIFICATION_ID, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val notification = NotificationCompat.Builder(this, GENERIC_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ec_live)
            .setContentTitle(title.ifBlank { "Einsatzcockpit" })
            .setContentText(body)
            .setAutoCancel(true)
            .apply { pendingIntent?.let { setContentIntent(it) } }
            .build()
        manager.notify(GENERIC_NOTIFICATION_ID, notification)
    }

    private fun absoluteUrl(url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        val baseUrl = getSharedPreferences("CapacitorStorage", MODE_PRIVATE)
            .getString(EinsatzLivePoller.PREF_BASE_URL, "https://einsatzcockpit.com")
            ?.trimEnd('/') ?: "https://einsatzcockpit.com"
        return "$baseUrl/${url.trimStart('/')}"
    }
}
