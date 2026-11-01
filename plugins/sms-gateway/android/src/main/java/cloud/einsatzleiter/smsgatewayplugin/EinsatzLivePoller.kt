package cloud.einsatzleiter.smsgatewayplugin

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

internal fun nextAuthFailureCount(previous: Int, authFailure: Boolean): Int =
    if (authFailure) (previous + 1).coerceAtMost(3) else 0

class EinsatzLivePoller(
    private val context: Context,
    private val onNeeded: () -> Unit = {},
    private val onIdleTimeout: () -> Unit = {},
) {
    companion object {
        @Volatile internal var isRunning = false
        const val PREF_BASE_URL = "el_base_url"
        const val PREF_INCIDENT_ID = "el_live_incident_id"
        const val PREF_LAST_OK_MS = "el_live_last_ok_ms"
        const val PREF_DISMISSED_INCIDENT_ID = "el_live_dismissed_incident_id"
        const val PREF_ENABLED = "el_live_enabled"
        const val PREF_DEVICE_STATUS_REPORTING_ENABLED = "ec_device_status_reporting_enabled"

        private const val IDLE_INTERVAL_MS = 300_000L
        private const val ACTIVE_INTERVAL_MS = 30_000L
        private const val AUTH_INTERVAL_MS = 900_000L
        // Drei regulaere Idle-Polls: kurze Ruhephasen erzeugen kein Flattern,
        // nach 15 Minuten ohne Einsatz und ohne Dienst endet der Foreground-Service.
        private const val IDLE_STOP_MS = 900_000L
        private const val INITIAL_DELAY_MS = 10_000L
        private const val STALE_WARNING_MS = 900_000L
        private const val HARD_CUTOFF_MS = 3_600_000L
        private val ERROR_BACKOFF_MS = longArrayOf(30_000L, 60_000L, 120_000L, 300_000L)
    }

    private val prefs = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val notifier = EinsatzLiveNotifier(context)
    private val client = OkHttpClient.Builder()
        .cookieJar(WebViewCookieJar())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private var running = false
    private var inFlight: Call? = null
    private var lastResult: String? = null
    private var lastResultLogMs = 0L
    private var failures = 0
    private var authFailures = 0
    private var currentState: EinsatzLiveState? = null
    private var idleSinceMs: Long? = null
    private val pollRunnable = Runnable { poll() }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshNow()
    }

    fun restorePersistedState() {
        val incidentId = prefs.getString(PREF_INCIDENT_ID, null)
        val lastOk = prefs.getString(PREF_LAST_OK_MS, null)?.toLongOrNull() ?: 0L
        if (incidentId != null && (lastOk == 0L || System.currentTimeMillis() - lastOk >= HARD_CUTOFF_MS)) {
            clearIncident()
        }
    }

    fun start() {
        if (running) return
        if (!isEnabled()) {
            log("Live-Poller nicht gestartet: Device-Token/Live-Opt-in fehlt")
            onIdleTimeout()
            return
        }
        running = true
        isRunning = true
        try { connectivityManager.registerDefaultNetworkCallback(networkCallback) } catch (_: Exception) {}
        schedule(INITIAL_DELAY_MS)
    }

    fun stop() {
        running = false
        isRunning = false
        handler.removeCallbacks(pollRunnable)
        inFlight?.cancel()
        inFlight = null
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
    }

    fun disable() {
        stop()
        clearIncident()
    }

    fun refreshNow() {
        if (!running) return
        if (!isEnabled()) {
            log("Live-Poll abgebrochen: Device-Token/Live-Opt-in fehlt")
            clearIncident()
            stop()
            onIdleTimeout()
            return
        }
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
    }

    private fun isEnabled(): Boolean =
        !prefs.getString("el_device_token", null).isNullOrBlank() ||
            prefs.getString(PREF_ENABLED, null) == "1"

    private fun poll() {
        if (!running || inFlight != null) return
        if (!isEnabled()) {
            log("Live-Poll beendet: Device-Token/Live-Opt-in fehlt")
            clearIncident()
            stop()
            onIdleTimeout()
            return
        }
        val baseUrl = prefs.getString(PREF_BASE_URL, null)?.trimEnd('/')
        if (baseUrl.isNullOrBlank()) {
            log("Live-Poll ausgesetzt: Server-URL fehlt")
            schedule(IDLE_INTERVAL_MS)
            return
        }
        if (DeviceAccessState.isInvalid(context)) {
            log("Live-Poll gestoppt: Gerätezugang ungültig")
            stop()
            onIdleTimeout()
            return
        }
        val deviceToken = prefs.getString("el_device_token", null)?.takeIf { it.isNotBlank() }
        val appVersion = reportedAppVersion()
        val dutyStateUrl = if (appVersion == null) {
            "$baseUrl/api/v1/device/duty-state"
        } else {
            val encodedVersion = URLEncoder.encode(appVersion, StandardCharsets.UTF_8.toString())
            "$baseUrl/api/v1/device/duty-state?app_version=$encodedVersion"
        }
        val request = try {
            DeviceAccessState.withDeviceHeaders(Request.Builder().url(dutyStateUrl).get())
                .apply { deviceToken?.let { header("Authorization", "Bearer $it") } }
                .build()
        } catch (_: IllegalArgumentException) {
            log("Live-Poll ausgesetzt: Server-URL ist ungültig")
            schedule(IDLE_INTERVAL_MS)
            return
        }
        inFlight = client.newCall(request).also { call ->
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    handler.post {
                        inFlight = null
                        if (running && !call.isCanceled()) handleNetworkFailure(baseUrl)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val code = it.code
                        val invalidCode = DeviceAccessState.finalCode(it)
                        val body = it.body?.string()
                        if (it.isSuccessful) ReportSender.serverTime(context, body, it.header("Date"))
                        handler.post {
                            inFlight = null
                            if (!running) return@post
                            when {
                                invalidCode != null -> handleAccessInvalid(invalidCode)
                                code == 401 || code == 403 -> handleAuthFailure(code)
                                code in 200..299 && body != null -> handleSuccess(baseUrl, body)
                                else -> handleNetworkFailure(baseUrl)
                            }
                        }
                    }
                }
            })
        }
    }

    private fun handleSuccess(baseUrl: String, body: String) {
        val response = DutyStateResponse.parse(body) ?: run {
            handleNetworkFailure(baseUrl)
            return
        }
        failures = 0
        authFailures = nextAuthFailureCount(authFailures, false)
        val now = System.currentTimeMillis()
        val state = response.incident
        if (response.hasIncident && state == null) {
            handleNetworkFailure(baseUrl)
            return
        }
        ReportSender.mark(context, "letzter_poll_ok_at")
        logResult(if (state == null) "kein Einsatz" else "Einsatz aktiv")
        val gslQueue = response.gslQueue
        val gslLive = response.gslLive
        if (state == null) clearIncident(clearGslState = false)
        if (gslQueue != null) {
            EcpWidgetSupport.saveGslQueue(context, gslQueue)
        } else {
            EcpWidgetSupport.clearGslQueue(context)
        }
        if (gslLive != null) {
            EcpWidgetSupport.saveGslLive(context, gslLive)
        } else {
            EcpWidgetSupport.clearGslLive(context)
        }
        if (state == null && gslQueue == null && gslLive == null) {
            clearIncident()
            if (response.dutyActive) {
                idleSinceMs = null
                onNeeded()
            } else {
                val idleSince = idleSinceMs ?: now.also { idleSinceMs = it }
                if (now - idleSince >= IDLE_STOP_MS) {
                    onIdleTimeout()
                    return
                }
            }
            schedule(IDLE_INTERVAL_MS)
            return
        }
        state?.let { incident ->
            val oldId = prefs.getString(PREF_INCIDENT_ID, null)
            if (oldId != incident.id.toString()) {
                prefs.edit().remove(PREF_DISMISSED_INCIDENT_ID).apply()
            }
            currentState = incident
            prefs.edit()
                .putString(PREF_INCIDENT_ID, incident.id.toString())
                .putString(PREF_LAST_OK_MS, now.toString())
                .apply()
            EcpWidgetSupport.saveIncident(context, incident)
            notifier.post(incident, baseUrl)
        }
        idleSinceMs = null
        onNeeded()
        schedule(ACTIVE_INTERVAL_MS)
    }

    /** Endgültig ungültiger Gerätezugang: keine weiteren Versuche, Hinweis für den Nutzer. */
    private fun handleAccessInvalid(code: String) {
        NativeLog.error(context, "poller", "access_invalid", "Gerätezugang ungültig")
        lastResult = "Fehler"
        log("Live-Poll beendet: Gerätezugang ungültig ($code)")
        failures = 0
        clearIncident()
        DeviceAccessState.markInvalid(context, code)
        stop()
        onIdleTimeout()
    }

    private fun handleAuthFailure(statusCode: Int) {
        NativeLog.error(context, "poller", "auth", "Authentifizierung fehlgeschlagen (HTTP $statusCode)")
        lastResult = "Fehler"
        failures = 0
        clearIncident()
        authFailures = nextAuthFailureCount(authFailures, true)
        if (authFailures >= 3) {
            stop()
            onIdleTimeout()
            return
        }
        schedule(AUTH_INTERVAL_MS)
    }

    private fun handleNetworkFailure(baseUrl: String) {
        failures++
        lastResult = "Fehler"
        NativeLog.error(context, "poller", "network", "Live-Poll fehlgeschlagen")
        val lastOk = prefs.getString(PREF_LAST_OK_MS, null)?.toLongOrNull() ?: 0L
        val staleFor = if (lastOk > 0L) System.currentTimeMillis() - lastOk else Long.MAX_VALUE
        when {
            staleFor >= HARD_CUTOFF_MS -> clearIncident()
            failures >= 3 || staleFor >= STALE_WARNING_MS -> currentState?.let {
                notifier.post(it, baseUrl, "Stand ${formatTime(lastOk)} · keine Verbindung")
            }
        }
        schedule(ERROR_BACKOFF_MS[(failures - 1).coerceIn(ERROR_BACKOFF_MS.indices)])
    }

    private fun clearIncident(clearGslState: Boolean = true) {
        currentState = null
        notifier.cancel()
        prefs.edit()
            .remove(PREF_INCIDENT_ID)
            .remove(PREF_LAST_OK_MS)
            .remove(PREF_DISMISSED_INCIDENT_ID)
            .apply()
        EcpWidgetSupport.clearIncident(context)
        if (clearGslState) {
            EcpWidgetSupport.clearGslQueue(context)
            EcpWidgetSupport.clearGslLive(context)
        }
    }

    private fun schedule(delayMs: Long) {
        if (!running) return
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, delayMs)
    }

    private fun logResult(result: String) {
        val now = System.currentTimeMillis()
        if (lastResult != result || now - lastResultLogMs >= 3_600_000L) {
            log("Live-Poll: $result")
            lastResultLogMs = now
        }
        lastResult = result
    }

    private fun log(message: String) = SmsGatewayService.log(message)

    private fun reportedAppVersion(): String? {
        if (!isDeviceStatusReportingEnabled()) return null
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun isDeviceStatusReportingEnabled(): Boolean {
        return try {
            prefs.getBoolean(PREF_DEVICE_STATUS_REPORTING_ENABLED, true)
        } catch (_: ClassCastException) {
            // Capacitor Preferences persists values as strings; native callers may use booleans.
            prefs.getString(PREF_DEVICE_STATUS_REPORTING_ENABLED, null) != "false"
        }
    }

    private fun formatTime(timestamp: Long): String =
        SimpleDateFormat("HH:mm", Locale.GERMANY).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date(timestamp))
}
