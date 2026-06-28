package cloud.einsatzleiter.smsgatewayplugin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Foreground-Service für den Einheit-Gerät-Modus.
 * Hält den App-Prozess am Leben und die CPU wach, damit der WebView-JS-Thread
 * nicht von Android beendet oder eingefroren wird – auch wenn der Bildschirm aus ist.
 * Der Service wird über DeviceKeepalivePlugin gestartet, bevor index.html zur
 * Remote-PWA weiterleitet, und durch BootReceiver nach einem Geräte-Neustart
 * neu gestartet, sofern ein Device-Token gespeichert ist.
 */
class DeviceKeepaliveService : Service() {

    companion object {
        const val ACTION_START = "cloud.einsatzleiter.smsgatewayplugin.KEEPALIVE_START"
        const val ACTION_STOP  = "cloud.einsatzleiter.smsgatewayplugin.KEEPALIVE_STOP"

        private const val NOTIF_CHANNEL_ID = "ec_device"
        private const val NOTIF_ID         = 7302
        private const val WAKE_LOCK_TAG    = "einsatzcockpit:device"
    }

    private var wakeLock: PowerManager.WakeLock? = null

    private val notificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            releaseWakeLock()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification())
        acquireWakeLock()
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(PowerManager::class.java))
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .also {
                it.setReferenceCounted(false)
                it.acquire(24 * 60 * 60 * 1000L)
            }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            NOTIF_CHANNEL_ID,
            "Einsatzcockpit",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Hält die Einsatzcockpit-App im Hintergrund aktiv"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val tapIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP) }
        val tapPending = if (tapIntent != null)
            PendingIntent.getActivity(this, 0, tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        else null
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Einsatzcockpit")
            .setContentText("App läuft im Hintergrund")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply { tapPending?.let { setContentIntent(it) } }
            .build()
    }
}
