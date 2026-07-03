package cloud.einsatzleiter.smsgatewayplugin

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback

/**
 * Capacitor-Plugin-Brücke zum SmsGatewayService.
 *
 * JS-API:
 *   SmsGateway.startGateway({ url, token })
 *   SmsGateway.stopGateway()
 *   SmsGateway.getStatus() → { connected, lastError, sentCount, lastSentTo, lastSentAt,
 *                               receiveEnabled, receivePermissionGranted }
 *   SmsGateway.sendTestSms({ to, text })
 *   SmsGateway.checkPermission() → { granted }
 *   SmsGateway.requestPermission() → { granted }
 *   SmsGateway.checkReceivePermission() → { granted }
 *   SmsGateway.requestReceivePermission() → { granted }  (registriert bei Erfolg sofort den Empfangs-Receiver)
 *   SmsGateway.getBatteryOptimizationStatus() → { ignored }
 *   SmsGateway.requestBatteryOptimization() → { ignored } | { pending: true } (öffnet Systemdialog, JS prüft Status beim resume)
 *   SmsGateway.getAppVersion() → { versionName }
 *
 * Events:
 *   statusChanged  → { connected, lastError, sentCount, lastSentTo, lastSentAt }
 *   smsSent        → { to (maskiert), parts, at }
 *   smsReceived    → { from (maskiert), preview, at }
 *   configChanged  → { receiveEnabled, receivePermissionGranted }  (Server-Config empfangen)
 */
@CapacitorPlugin(
    name = "SmsGateway",
    permissions = [
        Permission(alias = "sendSms", strings = [Manifest.permission.SEND_SMS]),
        Permission(alias = "receiveSms", strings = [Manifest.permission.RECEIVE_SMS]),
    ]
)
class SmsGatewayPlugin : Plugin() {

    private val serviceListener: (String, JSObject) -> Unit = { event, data ->
        notifyListeners(event, data)
    }

    override fun load() {
        SmsGatewayService.addPluginListener(serviceListener)
    }

    override fun handleOnDestroy() {
        SmsGatewayService.removePluginListener(serviceListener)
        super.handleOnDestroy()
    }

    // ── Gateway starten ───────────────────────────────────────────────────────

    @PluginMethod
    fun startGateway(call: PluginCall) {
        val url   = call.getString("url")   ?: return call.reject("url erforderlich")
        val token = call.getString("token") ?: return call.reject("token erforderlich")

        val intent = Intent(context, SmsGatewayService::class.java).apply {
            action = SmsGatewayService.ACTION_START
            putExtra(SmsGatewayService.EXTRA_URL, url.trimEnd('/'))
            putExtra(SmsGatewayService.EXTRA_TOKEN, token)
        }
        context.startForegroundService(intent)
        call.resolve()
    }

    // ── Gateway stoppen ───────────────────────────────────────────────────────

    @PluginMethod
    fun stopGateway(call: PluginCall) {
        val intent = Intent(context, SmsGatewayService::class.java).apply {
            action = SmsGatewayService.ACTION_STOP
        }
        context.startService(intent)
        call.resolve()
    }

    // ── Status abfragen ───────────────────────────────────────────────────────

    @PluginMethod
    fun getStatus(call: PluginCall) {
        call.resolve(JSObject().apply {
            put("connected",  SmsGatewayService.isConnected)
            put("lastError",  SmsGatewayService.lastError ?: "")
            put("sentCount",  SmsGatewayService.sentCount.get())
            put("lastSentTo", SmsGatewayService.lastSentTo ?: "")
            put("lastSentAt", SmsGatewayService.lastSentAt)
            put("receiveEnabled", context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
                .getBoolean("el_gateway_receive_enabled", false))
            put("receivePermissionGranted", getPermissionState("receiveSms") == PermissionState.GRANTED)
        })
    }

    // ── Test-SMS senden (kein WebSocket-Job, direkt per SmsManager) ───────────

    @PluginMethod
    fun sendTestSms(call: PluginCall) {
        val to   = call.getString("to")   ?: return call.reject("to erforderlich")
        val text = call.getString("text") ?: "Test-SMS von Einsatzcockpit"

        SmsGatewayService.sendDirectSms(context, to, text) { ok, msg ->
            if (ok) call.resolve() else call.reject(msg)
        }
    }

    // ── Berechtigung prüfen / anfragen ────────────────────────────────────────

    @PluginMethod
    fun checkPermission(call: PluginCall) {
        call.resolve(JSObject().apply {
            put("granted", getPermissionState("sendSms") == PermissionState.GRANTED)
        })
    }

    @PluginMethod
    fun requestPermission(call: PluginCall) {
        if (getPermissionState("sendSms") == PermissionState.GRANTED) {
            call.resolve(JSObject().apply { put("granted", true) })
            return
        }
        requestPermissionForAlias("sendSms", call, "onPermissionResult")
    }

    @PermissionCallback
    private fun onPermissionResult(call: PluginCall) {
        val granted = getPermissionState("sendSms") == PermissionState.GRANTED
        call.resolve(JSObject().apply { put("granted", granted) })
    }

    // ── SMS-Empfangsberechtigung ──────────────────────────────────────────────
    // Optional, nur angefordert wenn der Org-Schalter (Server-Config) aktiv ist.

    @PluginMethod
    fun checkReceivePermission(call: PluginCall) {
        call.resolve(JSObject().apply {
            put("granted", getPermissionState("receiveSms") == PermissionState.GRANTED)
        })
    }

    @PluginMethod
    fun requestReceivePermission(call: PluginCall) {
        if (getPermissionState("receiveSms") == PermissionState.GRANTED) {
            refreshReceiver()
            call.resolve(JSObject().apply { put("granted", true) })
            return
        }
        requestPermissionForAlias("receiveSms", call, "onReceivePermissionResult")
    }

    @PermissionCallback
    private fun onReceivePermissionResult(call: PluginCall) {
        val granted = getPermissionState("receiveSms") == PermissionState.GRANTED
        if (granted) refreshReceiver()
        call.resolve(JSObject().apply { put("granted", granted) })
    }

    /** Stoesst den Service an, den Empfangs-Receiver neu zu bewerten (nach Berechtigungsänderung). */
    private fun refreshReceiver() {
        val intent = Intent(context, SmsGatewayService::class.java).apply {
            action = SmsGatewayService.ACTION_REFRESH_RECEIVER
        }
        context.startService(intent)
    }

    // ── App-Version ───────────────────────────────────────────────────────────

    @PluginMethod
    fun getAppVersion(call: PluginCall) {
        try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            call.resolve(JSObject().apply {
                put("versionName", pi.versionName ?: "unbekannt")
            })
        } catch (e: Exception) {
            call.reject("Version nicht abrufbar: ${e.message}")
        }
    }

    // ── Akku-Optimierung ──────────────────────────────────────────────────────

    @PluginMethod
    fun getBatteryOptimizationStatus(call: PluginCall) {
        val pm = context.getSystemService(PowerManager::class.java)
        call.resolve(JSObject().apply {
            put("ignored", pm.isIgnoringBatteryOptimizations(context.packageName))
        })
    }

    @PluginMethod
    fun requestBatteryOptimization(call: PluginCall) {
        val pm = context.getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(context.packageName)) {
            call.resolve(JSObject().apply { put("ignored", true) })
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
            // Resolve immediately; JS prüft den Status beim resume-Event
            call.resolve(JSObject().apply { put("pending", true) })
        } catch (e: Exception) {
            call.reject("Konnte Einstellungen nicht öffnen: ${e.message}")
        }
    }
}
