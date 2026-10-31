package cloud.einsatzleiter.smsgatewayplugin

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.google.firebase.messaging.FirebaseMessaging

/** Capacitor-Plugin fuer den akustischen Einsatzalarm-Notification-Channel. */
@CapacitorPlugin(name = "AlarmChannel")
class AlarmChannelPlugin : Plugin() {
    override fun load() {
        super.load()
        FcmTokenRegistration.ensureCapabilitiesRegistered(context, "app_start")
    }

    private val notificationManager: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @PluginMethod
    fun getStatus(call: PluginCall) = call.resolve(status())

    @PluginMethod
    fun setEnabled(call: PluginCall) {
        val enabled = call.getBoolean("enabled") ?: return call.reject("enabled erforderlich")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (enabled) {
                AlarmNotificationChannel.create(context)
                EinsatzAlarmNotification.create(context)
            } else {
                EinsatzAlarmService.stop(context)
                notificationManager.deleteNotificationChannel(CHANNEL_ID)
                notificationManager.deleteNotificationChannel(EinsatzAlarmNotification.CHANNEL_ID)
            }
        }
        call.resolve(status())
    }

    @PluginMethod
    fun playTestNotification(call: PluginCall) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            call.reject("Benachrichtigungen sind nicht erlaubt")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
            call.reject("Alarmkanal ist nicht aktiviert")
            return
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle("Einsatzcockpit")
            .setContentText("🔊 Testton — so klingt ein Einsatzalarm")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(TEST_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            call.reject("POST_NOTIFICATIONS ist nicht erteilt")
            return
        }
        call.resolve()
    }

    @PluginMethod
    fun playLocalAlarm(call: PluginCall) {
        val started = EinsatzAlarmService.start(
            context, title = "🚒 Testalarm", body = "Lokaler Probealarm", url = "/", isTest = true,
        )
        call.resolve(JSObject().apply { put("started", started) })
    }

    @PluginMethod
    fun previewVolume(call: PluginCall) {
        val percent = call.getInt("percent") ?: return call.reject("percent erforderlich")
        if (percent !in 0..100) {
            call.reject("percent muss zwischen 0 und 100 liegen")
            return
        }
        val started = EinsatzAlarmService.start(
            context, title = "Vorschau Alarmlautstärke", body = "Vorschau Alarmlautstärke", url = "/",
            isTest = true, previewSeconds = 3, percent = percent,
        )
        call.resolve(JSObject().apply { put("started", started) })
    }

    @PluginMethod
    fun stopAlarm(call: PluginCall) {
        EinsatzAlarmService.stop(context)
        call.resolve()
    }

    @PluginMethod
    fun sendServerTestAlarm(call: PluginCall) {
        fun resolveError(message: String) {
            call.resolve(JSObject().apply {
                put("ok", false)
                put("nativeAlarm", false)
                put("error", message)
            })
        }
        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                FcmTokenRegistration.sendTestAlarm(context, token) { result ->
                    result.fold(onSuccess = { json ->
                        call.resolve(JSObject().apply {
                            put("ok", json.optBoolean("ok", true))
                            put("nativeAlarm", json.optBoolean("native_alarm", json.optBoolean("nativeAlarm", false)))
                            put("error", json.optString("error", ""))
                        })
                    }, onFailure = { error -> resolveError(error.message ?: "Server-Testalarm fehlgeschlagen") })
                }
            }.addOnFailureListener { error ->
                resolveError(error.message ?: "FCM-Token nicht verfuegbar")
            }
        } catch (e: Exception) {
            resolveError(e.message ?: "FCM-Token nicht verfuegbar")
        }
    }

    @PluginMethod
    fun openFullScreenIntentSettings(call: PluginCall) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        }
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            call.resolve()
        } catch (e: Exception) {
            call.reject("Konnte Vollbild-Einstellungen nicht oeffnen: ${e.message}")
        }
    }

    @PluginMethod
    fun openChannelSettings(call: PluginCall) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
        call.resolve()
    }

    @PluginMethod
    fun openDndAccessSettings(call: PluginCall) {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        call.resolve()
    }

    private fun status() = JSObject().apply {
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        if (supported) {
            reconcileBypassDnd()
            if (notificationManager.getNotificationChannel(EinsatzAlarmNotification.CHANNEL_ID) != null) {
                EinsatzAlarmNotification.create(context)
            }
        }
        val channel = if (supported) notificationManager.getNotificationChannel(CHANNEL_ID) else null
        put("supported", supported)
        put("enabled", channel != null)
        put("notificationsGranted", NotificationManagerCompat.from(context).areNotificationsEnabled())
        put("dndBypass", supported && channel?.canBypassDnd() == true)
        put("dndPermission", notificationManager.isNotificationPolicyAccessGranted)
        put("fullScreenIntentAllowed", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            notificationManager.canUseFullScreenIntent()
        } else true)
        put("batteryOptimizationIgnored", context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName))
        val audio = context.getSystemService(AudioManager::class.java)
        val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        put("alarmVolume", if (maxVolume > 0) audio.getStreamVolume(AudioManager.STREAM_ALARM) * 100 / maxVolume else 0)
        put("volumePercentSetting", context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
            .getString("el_alarm_volume_percent", "80") ?: "80")
        put("nativeChannelDndBypass", supported &&
            notificationManager.getNotificationChannel(EinsatzAlarmNotification.CHANNEL_ID)?.canBypassDnd() == true)
    }

    /**
     * Die Eigenschaften eines angelegten Notification-Channels werden vom System
     * beibehalten. Wird der DND-Zugriff erst danach gewaehrt, wird der beim Anlegen
     * angeforderte Bypass nicht nachtraeglich auf den bestehenden Channel uebernommen.
     * Den Channel deshalb mit der nun vorhandenen Berechtigung neu anlegen.
     */
    private fun reconcileBypassDnd() {
        val channel = notificationManager.getNotificationChannel(CHANNEL_ID) ?: return
        if (notificationManager.isNotificationPolicyAccessGranted && !channel.canBypassDnd()) {
            notificationManager.deleteNotificationChannel(CHANNEL_ID)
            AlarmNotificationChannel.create(context)
        }
    }

    companion object {
        private const val CHANNEL_ID = AlarmNotificationChannel.CHANNEL_ID
        private const val TEST_NOTIFICATION_ID = 42001
    }
}
