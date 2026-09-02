package cloud.einsatzleiter.smsgatewayplugin

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/** Capacitor-Plugin fuer den akustischen Einsatzalarm-Notification-Channel. */
@CapacitorPlugin(name = "AlarmChannel")
class AlarmChannelPlugin : Plugin() {
    private val notificationManager: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @PluginMethod
    fun getStatus(call: PluginCall) = call.resolve(status())

    @PluginMethod
    fun setEnabled(call: PluginCall) {
        val enabled = call.getBoolean("enabled") ?: return call.reject("enabled erforderlich")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (enabled) AlarmNotificationChannel.create(context) else notificationManager.deleteNotificationChannel(CHANNEL_ID)
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
        val channel = if (supported) notificationManager.getNotificationChannel(CHANNEL_ID) else null
        put("supported", supported)
        put("enabled", channel != null)
        put("notificationsGranted", NotificationManagerCompat.from(context).areNotificationsEnabled())
        put("dndBypass", supported && channel?.canBypassDnd() == true)
        put("dndPermission", notificationManager.isNotificationPolicyAccessGranted)
    }

    companion object {
        private const val CHANNEL_ID = AlarmNotificationChannel.CHANNEL_ID
        private const val TEST_NOTIFICATION_ID = 42001
    }
}
