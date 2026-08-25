package cloud.einsatzleiter.smsgatewayplugin

import android.content.Intent
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Capacitor-Plugin-Brücke zum DeviceKeepaliveService.
 *
 * JS-API (via window.Capacitor.Plugins.DeviceKeepalive):
 *   DeviceKeepalive.startKeepalive()  – startet den ForegroundService mit PARTIAL_WAKE_LOCK
 *   DeviceKeepalive.stopKeepalive()   – stoppt den Service (z.B. beim Abmelden)
 *   DeviceKeepalive.registerFcmToken() – registriert den aktuellen FCM-Token beim Server
 *   DeviceKeepalive.getPushToken()     – liest den aktuellen FCM-Token ohne Berechtigungsdialog
 *   DeviceKeepalive.getPushStatus()    – prüft die Registrierung des FCM-Tokens beim Server
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
    fun getPushToken(call: PluginCall) {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                if (token.isBlank()) call.reject("FCM-Token ist nicht verfügbar")
                else call.resolve(JSObject().apply { put("token", token) })
            }
            .addOnFailureListener {
                call.reject(it.message ?: "FCM-Token konnte nicht abgerufen werden")
            }
    }

    @PluginMethod
    fun getPushStatus(call: PluginCall) {
        val token = call.getString("token")?.takeIf { it.isNotBlank() }
            ?: return call.reject("token erforderlich")
        FcmTokenRegistration.getStatus(context, token) { result ->
            result.fold(
                onSuccess = { json ->
                    call.resolve(JSObject().apply {
                        put("registered", json.getBoolean("registered"))
                        if (json.has("registered_at")) put("registered_at", json.opt("registered_at"))
                        if (json.has("last_delivery_success")) {
                            put("last_delivery_success", json.opt("last_delivery_success"))
                        }
                        if (json.has("last_delivery_at")) put("last_delivery_at", json.opt("last_delivery_at"))
                    })
                },
                onFailure = { call.reject(it.message ?: "Status konnte nicht abgerufen werden") },
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
