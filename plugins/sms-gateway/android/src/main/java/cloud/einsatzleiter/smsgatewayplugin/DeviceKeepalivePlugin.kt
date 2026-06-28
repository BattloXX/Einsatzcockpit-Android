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
 * Wird in index.html aufgerufen bevor die App zur Remote-PWA weiterleitet,
 * damit der Prozess auch bei ausgeschaltetem Bildschirm nicht beendet wird.
 */
@CapacitorPlugin(name = "DeviceKeepalive")
class DeviceKeepalivePlugin : Plugin() {

    @PluginMethod
    fun startKeepalive(call: PluginCall) {
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
