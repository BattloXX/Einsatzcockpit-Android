package cloud.einsatzleiter.smsgatewayplugin

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.google.firebase.messaging.FirebaseMessaging
import cloud.einsatzleiter.smsgatewayplugin.kontakte.KontaktOfflineSyncWorker
import cloud.einsatzleiter.smsgatewayplugin.kontakte.KontaktSyncEngine
import cloud.einsatzleiter.smsgatewayplugin.kontakte.KontaktDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Capacitor-Plugin-Brücke zum DeviceKeepaliveService.
 *
 * JS-API (via window.Capacitor.Plugins.DeviceKeepalive):
 *   DeviceKeepalive.startKeepalive()  – startet den ForegroundService mit PARTIAL_WAKE_LOCK
 *   DeviceKeepalive.stopKeepalive()   – stoppt den Service (z.B. beim Abmelden)
 *   DeviceKeepalive.registerFcmToken() – registriert den aktuellen FCM-Token beim Server
 *   DeviceKeepalive.getPushToken()     – liest den aktuellen FCM-Token ohne Berechtigungsdialog
 *   DeviceKeepalive.getPushStatus()    – prüft die Registrierung des FCM-Tokens beim Server
 *   DeviceKeepalive.openOfflineKontakte() – öffnet die native Offline-Kontaktansicht
 *
 * Wird bei App-Start reaktiv aufgerufen; der Service beendet sich nach einer
 * Leerlauffrist selbst, sofern weder Einsatz noch Dienst aktiv sind.
 */
@CapacitorPlugin(name = "DeviceKeepalive")
class DeviceKeepalivePlugin : Plugin() {

    /**
     * Returns Android's actual, validated network state.
     *
     * WebView's navigator.onLine is not reliable after entering flight mode or
     * losing a mobile connection: it can still be true although no request can
     * leave the device. The local startup page uses this before it attempts a
     * device-login roundtrip, so it can select the cached PWA instead.
     */
    @PluginMethod
    fun getNetworkStatus(call: PluginCall) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager?.activeNetwork
        val capabilities = network?.let { manager.getNetworkCapabilities(it) }
        val connected = capabilities?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } == true
        call.resolve(JSObject().apply { put("connected", connected) })
    }

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
        ObjektOfflineSyncWorker.triggerImmediateSync(context)
        KontaktOfflineSyncWorker.schedule(context)
        KontaktOfflineSyncWorker.triggerImmediateSync(context)
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

    /** Schedules contact sync without starting the live-status foreground service. */
    @PluginMethod
    fun scheduleKontaktSync(call: PluginCall) {
        KontaktOfflineSyncWorker.schedule(context)
        KontaktOfflineSyncWorker.triggerImmediateSync(context)
        call.resolve()
    }

    /** Persists object-cache progress reported by the remote Android WebView. */
    @PluginMethod
    fun reportObjectCacheStatus(call: PluginCall) {
        val cached = call.getInt("cached", 0) ?: 0
        val total = call.getInt("total", 0) ?: 0
        val activity = call.getString("activity")?.takeIf { it.isNotBlank() }
            ?: "Objektcache aktualisiert"
        OfflineCacheStatusStore.updateObjects(context, cached, total, activity)
        call.resolve()
    }

    /** Returns one native summary for the local about screen, independent of WebView origins. */
    @PluginMethod
    fun getOfflineCacheStatus(call: PluginCall) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val database = KontaktDatabase.get(context.applicationContext)
                val contactCount = database.kontaktDao().count()
                val contactStatus = database.syncStatusDao().get()
                val objects = OfflineCacheStatusStore.objectSnapshot(context)
                call.resolve(JSObject().apply {
                    put("contactsCached", contactCount)
                    // The native feed is committed atomically, therefore cached equals total.
                    put("contactsTotal", contactCount)
                    contactStatus?.lastSuccessAtMs?.let { put("contactsUpdatedAtMs", it) }
                    contactStatus?.lastError?.let { put("contactsError", it) }
                    put("objectsCached", objects.cached)
                    put("objectsTotal", objects.total)
                    objects.updatedAtMs?.let { put("objectsUpdatedAtMs", it) }
                    objects.activity?.let { put("objectActivity", it) }
                    put("activities", org.json.JSONArray().apply {
                        objects.activities.forEach { entry ->
                            put(org.json.JSONObject().put("at", entry.atMs).put("text", entry.text))
                        }
                    })
                })
            } catch (error: Exception) {
                call.reject(error.message ?: "Offline-Cache-Status konnte nicht gelesen werden")
            }
        }
    }

    /** Lets the about screen start both offline synchronizers and record that request. */
    @PluginMethod
    fun refreshOfflineCache(call: PluginCall) {
        OfflineCacheStatusStore.logActivity(context, "Manueller Offline-Abgleich wurde gestartet")
        ObjektOfflineSyncWorker.schedule(context)
        ObjektOfflineSyncWorker.triggerImmediateSync(context)
        KontaktOfflineSyncWorker.schedule(context)
        KontaktOfflineSyncWorker.triggerImmediateSync(context)
        call.resolve()
    }

    /** Opens the native, locally stored contacts independently of the WebView. */
    @PluginMethod
    fun openOfflineKontakte(call: PluginCall) {
        context.startActivity(Intent(context, KontaktListActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        call.resolve()
    }

    @PluginMethod
    fun wipeKontakte(call: PluginCall) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                KontaktSyncEngine().wipeLocalData(context)
                KontaktOfflineSyncWorker.cancel(context)
                call.resolve()
            } catch (error: Exception) {
                call.reject(error.message ?: "Kontakte konnten nicht gelöscht werden")
            }
        }
    }
}
