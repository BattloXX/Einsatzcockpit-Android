package cloud.einsatzleiter.smsgatewayplugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Startet das dauerhaft konfigurierte SMS-Gateway nach einem Neustart.
 * Der Einsatz-Livestatus wird bedarfsgesteuert durch App-Start oder FCM geweckt.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in listOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED)) return

        if (action == Intent.ACTION_MY_PACKAGE_REPLACED || action == Intent.ACTION_BOOT_COMPLETED) {
            try {
                FcmTokenRegistration.ensureCapabilitiesRegistered(context, action)
            } catch (_: Throwable) {
                // Firebase kann auf diesem Geraet unkonfiguriert sein.
            }
        }

        val prefs = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
        ObjektOfflineSyncWorker.schedule(context)
        if (context.resources.getBoolean(R.bool.auto_update_enabled)) {
            AutoUpdateWorker.schedule(context)
        }
        val gwUrl       = prefs.getString("el_gateway_url",   null)
        val gwToken     = prefs.getString("el_gateway_token", null)
        var shouldLaunch = false

        if (!gwUrl.isNullOrEmpty() && !gwToken.isNullOrEmpty()) {
            val serviceIntent = Intent(context, SmsGatewayService::class.java).apply {
                this.action = SmsGatewayService.ACTION_START
                putExtra(SmsGatewayService.EXTRA_URL,   gwUrl)
                putExtra(SmsGatewayService.EXTRA_TOKEN, gwToken)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                SmsGatewayService.log("Gateway-Start nach Boot/Update fehlgeschlagen (${e.javaClass.simpleName})")
            }
            shouldLaunch = true
        }

        if (shouldLaunch) {
            // Android 10+ kann den Activity-Start aus dem Hintergrund blockieren.
            try {
                context.packageManager
                    .getLaunchIntentForPackage(context.packageName)
                    ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    ?.let { context.startActivity(it) }
            } catch (e: Exception) {
                SmsGatewayService.log("App-Start nach Boot/Update fehlgeschlagen (${e.javaClass.simpleName})")
            }
        }
    }
}
