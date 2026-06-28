package cloud.einsatzleiter.smsgatewayplugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Startet SmsGatewayService / DeviceKeepaliveService nach einem Neustart und
 * öffnet anschließend die App automatisch, sofern ein Modus konfiguriert ist.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in listOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                "android.intent.action.LOCKED_BOOT_COMPLETED")) return

        val prefs = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
        val gwUrl       = prefs.getString("el_gateway_url",   null)
        val gwToken     = prefs.getString("el_gateway_token", null)
        val deviceToken = prefs.getString("el_device_token",  null)

        var shouldLaunch = false

        if (!gwUrl.isNullOrEmpty() && !gwToken.isNullOrEmpty()) {
            val serviceIntent = Intent(context, SmsGatewayService::class.java).apply {
                this.action = SmsGatewayService.ACTION_START
                putExtra(SmsGatewayService.EXTRA_URL,   gwUrl)
                putExtra(SmsGatewayService.EXTRA_TOKEN, gwToken)
            }
            context.startForegroundService(serviceIntent)
            shouldLaunch = true
        }

        if (!deviceToken.isNullOrEmpty()) {
            val keepaliveIntent = Intent(context, DeviceKeepaliveService::class.java).apply {
                this.action = DeviceKeepaliveService.ACTION_START
            }
            context.startForegroundService(keepaliveIntent)
            shouldLaunch = true
        }

        if (shouldLaunch) {
            context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                ?.let { context.startActivity(it) }
        }
    }
}
