package cloud.einsatzleiter.smsgatewayplugin

import android.Manifest
import android.content.Intent
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
        val text = call.getString("text") ?: "Test-SMS von einsatzleiter.cloud"

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
}
