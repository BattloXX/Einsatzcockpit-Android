package cloud.einsatzleiter.smsgatewayplugin

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.view.WindowManager
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

@CapacitorPlugin(name = "Atemschutz")
class AtemschutzPlugin : Plugin() {
    @PluginMethod
    fun start(call: PluginCall) {
        val raw: Any? = call.data.opt("incidentId")
        val id: Long? = when (raw) {
            is Number -> raw.toString().toLongOrNull()
                ?: raw.toDouble().takeIf { it.isFinite() && it > 0 && it < Long.MAX_VALUE.toDouble() && it % 1.0 == 0.0 }?.toLong()
            is String -> raw.toLongOrNull()
            else -> null
        }
        if (id == null || id <= 0L) {
            call.reject("Gültige Einsatz-ID erforderlich")
            return
        }
        send(call, AtemschutzWatchService.ACTION_START, id)
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        // Auch nach einem Prozessende muss die persistente Markierung verschwinden.
        AsuewStore.deactivate(context)
        send(call, AtemschutzWatchService.ACTION_STOP)
    }

    @PluginMethod
    fun refresh(call: PluginCall) {
        send(call, AtemschutzWatchService.ACTION_REFRESH)
    }

    @PluginMethod
    fun getState(call: PluginCall) {
        val state: AsuewWatchSnapshot = AtemschutzWatchService.snapshot
        call.resolve(JSObject().apply {
            put("active", AsuewStore.isActive(context))
            put("incidentId", AsuewStore.incidentId(context)?.toString() ?: "")
            put("lastOkMs", AsuewStore.lastOkMs(context))
            put("connection", state.connection)
            put("battery", state.battery)
            put("alarmCount", state.alarmCount)
        })
    }

    @PluginMethod
    fun keepScreenOn(call: PluginCall) {
        val currentActivity: Activity? = activity
        if (currentActivity == null) {
            call.reject("Keine aktive App-Ansicht")
            return
        }
        val on: Boolean = call.getBoolean("on") ?: false
        currentActivity.runOnUiThread {
            try {
                if (on) currentActivity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else currentActivity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                call.resolve()
            } catch (error: Exception) {
                NativeLog.append("ASÜW Bildschirm: ${error.javaClass.simpleName}")
                call.reject("Bildschirmstatus konnte nicht gesetzt werden")
            }
        }
    }

    private fun send(call: PluginCall, action: String, incidentId: Long? = null) {
        try {
            val intent: Intent = Intent(context, AtemschutzWatchService::class.java).setAction(action)
            incidentId?.let { intent.putExtra(AtemschutzWatchService.EXTRA_INCIDENT_ID, it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
            call.resolve()
        } catch (error: Exception) {
            NativeLog.init(context)
            NativeLog.append("ASÜW Plugin: ${error.javaClass.simpleName}")
            call.reject("Atemschutzüberwachung konnte nicht angesprochen werden")
        }
    }
}
