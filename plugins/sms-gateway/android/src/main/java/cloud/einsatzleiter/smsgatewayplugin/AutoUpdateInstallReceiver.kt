package cloud.einsatzleiter.smsgatewayplugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/** Receives the asynchronous result of PackageInstaller's committed update session. */
class AutoUpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            ?.takeIf { it.isNotBlank() }
        if (status == PackageInstaller.STATUS_SUCCESS) {
            AutoUpdateStatusStore.install(context, "Installation erfolgreich abgeschlossen")
        } else {
            val error = message ?: "PackageInstaller-Status $status"
            AutoUpdateStatusStore.install(context, "Installation fehlgeschlagen", error)
        }
    }
}
