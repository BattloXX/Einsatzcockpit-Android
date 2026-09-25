package cloud.einsatzleiter.smsgatewayplugin

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/** Receives the asynchronous result of PackageInstaller's committed update session. */
class AutoUpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            ?.takeIf { it.isNotBlank() }
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                AutoUpdateStatusStore.install(context, "Installation erfolgreich abgeschlossen")
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Ein sideloaded App darf ein eigenes Update nicht auf jedem Gerät ohne
                // Rueckfrage installieren. PackageInstaller liefert in diesem Fall den
                // System-Dialog als Intent. Ihn nicht zu starten fuehrte bisher zu der
                // irrefuehrenden Meldung "Installation fehlgeschlagen".
                val confirmation = confirmationIntent(intent)
                if (confirmation == null) {
                    AutoUpdateStatusStore.install(
                        context,
                        "Installation benötigt Bestätigung",
                        message ?: "Installationsdialog konnte nicht geöffnet werden",
                    )
                    return
                }
                try {
                    confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    showConfirmationNotification(context, confirmation)
                    AutoUpdateStatusStore.install(
                        context,
                        "Installation wartet auf Bestätigung",
                        "Die Benachrichtigung \"Update installieren\" antippen",
                    )
                } catch (error: Exception) {
                    AutoUpdateStatusStore.install(
                        context,
                        "Installation benötigt Bestätigung",
                        error.message ?: message ?: "Installationsdialog konnte nicht geöffnet werden",
                    )
                }
            }
            else -> {
                val error = message ?: "PackageInstaller-Status $status"
                AutoUpdateStatusStore.install(context, "Installation fehlgeschlagen", error)
            }
        }
    }

    private fun showConfirmationNotification(context: Context, confirmation: Intent) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "App-Updates",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Bestätigung für Einsatzcockpit-Updates"
                },
            )
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            UPDATE_NOTIFICATION_ID,
            confirmation,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle("Einsatzcockpit-Update bereit")
            .setContentText("Antippen, um die Installation zu bestätigen")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(UPDATE_NOTIFICATION_ID, notification)
    }

    private fun confirmationIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    private companion object {
        const val CHANNEL_ID = "ec_auto_update"
        const val UPDATE_NOTIFICATION_ID = 4102
    }
}
