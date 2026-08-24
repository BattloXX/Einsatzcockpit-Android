package cloud.einsatzleiter.smsgatewayplugin

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build

/** Erstellt den Einsatzalarm-Kanal immer mit der vollstaendigen Alarm-Konfiguration. */
object AlarmNotificationChannel {
    const val CHANNEL_ID = "einsatz_alarm"

    fun create(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Einsatzalarm (übersteuert Lautlos)",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Akustischer Alarm bei neuen Einsätzen, auch wenn das Gerät auf Vibration oder lautlos steht."
                setSound(
                    Uri.parse("android.resource://${context.packageName}/raw/einsatz_alarm"),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
                enableLights(true)
                setBypassDnd(true)
            },
        )
    }
}
