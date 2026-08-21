package cloud.einsatzleiter.smsgatewayplugin

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Empfaengt FCM-Data-Nachrichten auch bei beendeter WebView. */
class EinsatzFirebaseMessagingService : FirebaseMessagingService() {
    companion object {
        private const val GENERIC_CHANNEL_ID = "ec_push"
        private const val GENERIC_NOTIFICATION_ID = 7305
        const val ALARM_FALLBACK_NOTIFICATION_ID = 7306
        private val ackClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val isAlarm = data["channel_id"] == "einsatz_alarm"
        if (isAlarm) {
            postGenericNotification(
                data["title"].orEmpty(),
                data["body"].orEmpty(),
                data["url"].orEmpty(),
                ALARM_FALLBACK_NOTIFICATION_ID,
            )
            data["delivery_id"]?.takeIf { it.isNotBlank() }?.let { sendPushAck(it) }
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
        if (!isAlarm) {
            postGenericNotification(
                data["title"].orEmpty(),
                data["body"].orEmpty(),
                data["url"].orEmpty(),
                GENERIC_NOTIFICATION_ID,
            )
        }
    }

    private fun postGenericNotification(title: String, body: String, url: String, notificationId: Int) {
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
                this, notificationId, it,
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
        manager.notify(notificationId, notification)
    }

    private fun sendPushAck(deliveryId: String) {
        try {
            val prefs = getSharedPreferences("CapacitorStorage", MODE_PRIVATE)
            val baseUrl = prefs.getString(EinsatzLivePoller.PREF_BASE_URL, null)?.trimEnd('/')
            val deviceToken = prefs.getString("el_device_token", null)?.takeIf { it.isNotBlank() }
            if (baseUrl.isNullOrBlank() || deviceToken == null) {
                SmsGatewayService.log("Push-Zustellbestaetigung nicht gesendet: Server-URL/Device-Token fehlt")
                return
            }
            val body = JSONObject()
                .put("delivery_id", deliveryId)
                .toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/api/v1/device/push-ack")
                .header("Authorization", "Bearer $deviceToken")
                .post(body)
                .build()
            ackClient.newCall(request).enqueue(object : Callback {
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
