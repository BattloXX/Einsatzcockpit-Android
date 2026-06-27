package cloud.einsatzleiter.smsgatewayplugin

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.getcapacitor.ActivityCallback
import com.getcapacitor.ActivityResult
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
 *   SmsGateway.getStatus() → { connected, lastError, sentCount, lastSentTo, lastSentAt }
 *   SmsGateway.sendTestSms({ to, text })
 *   SmsGateway.checkPermission() → { granted }
 *   SmsGateway.requestPermission() → { granted }
 *   SmsGateway.getBatteryOptimizationStatus() → { ignored }
 *   SmsGateway.requestBatteryOptimization() → { ignored }
 *
 * Events:
 *   statusChanged  → { connected, lastError, sentCount, lastSentTo, lastSentAt }
 *   smsSent        → { to (maskiert), parts, at }
 */
@CapacitorPlugin(
    name = "SmsGateway",
    permissions = [
        Permission(alias = "sendSms", strings = [Manifest.permission.SEND_SMS])
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
        }
        try {
            startActivityForResult(call, intent, "onBatteryOptResult")
        } catch (e: Exception) {
            call.reject("Konnte Einstellungen nicht öffnen: ${e.message}")
        }
    }

    @ActivityCallback
    private fun onBatteryOptResult(call: PluginCall?, result: ActivityResult) {
        val pm = context.getSystemService(PowerManager::class.java)
        call?.resolve(JSObject().apply {
            put("ignored", pm.isIgnoringBatteryOptimizations(context.packageName))
        })
    }
}
