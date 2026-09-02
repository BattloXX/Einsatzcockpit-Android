package cloud.einsatzleiter.smsgatewayplugin

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

/** Empfaengt FCM-Data-Nachrichten auch bei beendeter WebView. */
class EinsatzFirebaseMessagingService : FirebaseMessagingService() {
    companion object {
        private const val GENERIC_CHANNEL_ID = "ec_push"
        private const val GENERIC_NOTIFICATION_ID = 7305
        const val ALARM_FALLBACK_NOTIFICATION_ID = 7306
    }

    override fun onNewToken(token: String) {
        FcmTokenRegistration.post(this, token) { result ->
            result.exceptionOrNull()?.let {
                SmsGatewayService.log("FCM-Token-Aktualisierung fehlgeschlagen: ${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val isAlarm = data["channel_id"] == "einsatz_alarm"
        val isSilentWake = data["silent"] == "1"
        data["delivery_id"]?.takeIf { it.isNotBlank() }?.let { sendPushAck(it) }

        if (isSilentWake) {
            SmsGatewayService.log("FCM empfangen: silent (Poller + Ack, keine eigene Anzeige)")
        } else if (isAlarm) {
            SmsGatewayService.log("FCM empfangen: display (Alarm-Fallback)")
            postGenericNotification(
                data["title"].orEmpty(),
                data["body"].orEmpty(),
                data["url"].orEmpty(),
                ALARM_FALLBACK_NOTIFICATION_ID,
                AlarmNotificationChannel.CHANNEL_ID,
            )
        } else {
            SmsGatewayService.log("FCM empfangen: generic")
        }

        val refresh = Intent(this, DeviceKeepaliveService::class.java).apply {
            action = DeviceKeepaliveService.ACTION_LIVE_REFRESH
        }
        try {
            startForegroundService(refresh)
        } catch (e: SecurityException) {
            SmsGatewayService.log("Live-Aktualisierung nach FCM nicht startbar: ${e.javaClass.simpleName}")
        } catch (e: IllegalStateException) {
            // Schließt ForegroundServiceStartNotAllowedException ab Android 12 ein.
            // Die Fallback-Notification bleibt sichtbar.
            SmsGatewayService.log("Live-Aktualisierung nach FCM nicht startbar: ${e.javaClass.simpleName}")
        }

        // Einsatzalarme werden nach dem sofortigen Poll als konsistente
        // "Laufender Einsatz"-Notification dargestellt. Andere Meldungen
        // brauchen weiterhin eine eigene, antippbare Systembenachrichtigung.
        if (!isSilentWake && !isAlarm) {
            postGenericNotification(
                data["title"].orEmpty(),
                data["body"].orEmpty(),
                data["url"].orEmpty(),
                GENERIC_NOTIFICATION_ID,
            )
        }
    }

    private fun postGenericNotification(
        title: String,
        body: String,
        url: String,
        notificationId: Int,
        channelId: String = GENERIC_CHANNEL_ID,
    ) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            SmsGatewayService.log("FCM-Anzeige nicht moeglich: Benachrichtigungen sind nicht erlaubt")
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        if (channelId == AlarmNotificationChannel.CHANNEL_ID) {
            AlarmNotificationChannel.create(this)
        } else {
            manager.createNotificationChannel(NotificationChannel(
                GENERIC_CHANNEL_ID,
                "Einsatzcockpit-Meldungen",
                NotificationManager.IMPORTANCE_DEFAULT,
            ))
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EinsatzLiveNotifier.EXTRA_EC_URL, absoluteUrl(url))
        }
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this, notificationId, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_ec_live)
            .setContentTitle(title.ifBlank { "Einsatzcockpit" })
            .setContentText(body)
            .setAutoCancel(true)
            .apply { pendingIntent?.let { setContentIntent(it) } }
            .build()
        try {
            manager.notify(notificationId, notification)
        } catch (e: SecurityException) {
            SmsGatewayService.log("FCM-Anzeige nicht moeglich: POST_NOTIFICATIONS fehlt")
        }
    }

    private fun sendPushAck(deliveryId: String) {
        try {
            val prefs = getSharedPreferences("CapacitorStorage", MODE_PRIVATE)
            val baseUrl = prefs.getString(EinsatzLivePoller.PREF_BASE_URL, null)?.trimEnd('/')
            if (baseUrl.isNullOrBlank()) {
                SmsGatewayService.log("Push-Zustellbestaetigung nicht gesendet: Server-URL fehlt")
                return
            }
            val authHeader = FcmTokenRegistration.getAuthHeader(this, baseUrl)
            if (authHeader == null) {
                SmsGatewayService.log(
                    "Push-Zustellbestaetigung nicht gesendet: Keine Anmeldung gefunden",
                )
                return
            }
            val body = JSONObject()
                .put("delivery_id", deliveryId)
                .toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/api/v1/device/push-ack")
                .header(authHeader.first, authHeader.second)
                .post(body)
                .build()
            FcmTokenRegistration.httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    SmsGatewayService.log(
                        "Push-Zustellbestaetigung fehlgeschlagen: ${e.javaClass.simpleName}",
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            SmsGatewayService.log(
                                "Push-Zustellbestaetigung fehlgeschlagen (HTTP ${it.code})",
                            )
                        }
                    }
                }
            })
        } catch (e: Exception) {
            SmsGatewayService.log(
                "Push-Zustellbestaetigung nicht startbar: ${e.javaClass.simpleName}",
            )
        }
    }

    private fun absoluteUrl(url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        val baseUrl = getSharedPreferences("CapacitorStorage", MODE_PRIVATE)
            .getString(EinsatzLivePoller.PREF_BASE_URL, "https://einsatzcockpit.com")
            ?.trimEnd('/') ?: "https://einsatzcockpit.com"
        return "$baseUrl/${url.trimStart('/')}"
    }
}
