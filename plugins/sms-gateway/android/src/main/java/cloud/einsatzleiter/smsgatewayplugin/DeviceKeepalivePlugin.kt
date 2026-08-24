package cloud.einsatzleiter.smsgatewayplugin

import android.content.Intent
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * Capacitor-Plugin-Brücke zum DeviceKeepaliveService.
 *
 * JS-API (via window.Capacitor.Plugins.DeviceKeepalive):
 *   DeviceKeepalive.startKeepalive()  – startet den ForegroundService mit PARTIAL_WAKE_LOCK
 *   DeviceKeepalive.stopKeepalive()   – stoppt den Service (z.B. beim Abmelden)
 *
 * Wird bei App-Start reaktiv aufgerufen; der Service beendet sich nach einer
 * Leerlauffrist selbst, sofern weder Einsatz noch Dienst aktiv sind.
 */
@CapacitorPlugin(name = "DeviceKeepalive")
class DeviceKeepalivePlugin : Plugin() {

    @PluginMethod
    fun registerFcmToken(call: PluginCall) {
        val token = call.getString("token")?.takeIf { it.isNotBlank() }
            ?: return call.reject("token erforderlich")
        FcmTokenRegistration.post(context, token) { result ->
            result.fold(
                onSuccess = { call.resolve() },
                onFailure = { call.reject(it.message ?: "FCM-Token konnte nicht registriert werden") },
            )
        }
    }

    @PluginMethod
    fun startKeepalive(call: PluginCall) {
        ObjektOfflineSyncWorker.schedule(context)
        val intent = Intent(context, DeviceKeepaliveService::class.java).apply {
            action = DeviceKeepaliveService.ACTION_START
        }
        context.startForegroundService(intent)
        call.resolve()
    }

    @PluginMethod
    fun stopKeepalive(call: PluginCall) {
        val intent = Intent(context, DeviceKeepaliveService::class.java).apply {
            action = DeviceKeepaliveService.ACTION_STOP
        }
        context.startService(intent)
        call.resolve()
    }
}
