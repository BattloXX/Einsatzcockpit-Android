package cloud.einsatzleiter.smsgatewayplugin

import android.content.Context
import android.content.Intent
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

@CapacitorPlugin(name = "EinsatzLive")
class EinsatzLivePlugin : Plugin() {
    override fun load() {
        super.load()
        navigateIfPresent(activity?.intent)
    }

    override fun handleOnNewIntent(intent: Intent) {
        super.handleOnNewIntent(intent)
        navigateIfPresent(intent)
    }

    override fun handleOnResume() {
        super.handleOnResume()
        sendServiceAction(DeviceKeepaliveService.ACTION_LIVE_REFRESH)
    }

    @PluginMethod
    fun setEnabled(call: PluginCall) {
        val enabled = call.getBoolean("enabled") ?: false
        context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
            .edit().putString(EinsatzLivePoller.PREF_ENABLED, if (enabled) "1" else "0").apply()
        sendServiceAction(
            if (enabled) DeviceKeepaliveService.ACTION_START
            else DeviceKeepaliveService.ACTION_LIVE_DISABLE
        )
        call.resolve()
    }

    @PluginMethod
    fun refreshNow(call: PluginCall) {
        sendServiceAction(DeviceKeepaliveService.ACTION_LIVE_REFRESH)
        call.resolve()
    }

    @PluginMethod
    fun getState(call: PluginCall) {
        val prefs = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
        call.resolve(JSObject().apply {
            put("enabled", prefs.getString(EinsatzLivePoller.PREF_ENABLED, null) == "1")
            put("incidentId", prefs.getString(EinsatzLivePoller.PREF_INCIDENT_ID, null) ?: "")
            put("lastOkMs", prefs.getString(EinsatzLivePoller.PREF_LAST_OK_MS, null)?.toLongOrNull() ?: 0L)
        })
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(context, DeviceKeepaliveService::class.java).apply { this.action = action }
        if (action == DeviceKeepaliveService.ACTION_START) context.startForegroundService(intent)
        else context.startService(intent)
    }

    private fun navigateIfPresent(intent: Intent?) {
        val url = intent?.getStringExtra(EinsatzLiveNotifier.EXTRA_EC_URL) ?: return
        intent.removeExtra(EinsatzLiveNotifier.EXTRA_EC_URL)
        bridge.webView.post { bridge.webView.loadUrl(url) }
    }
}
