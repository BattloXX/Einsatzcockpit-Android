package cloud.einsatzleiter.smsgatewayplugin

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Standortmeldung ohne laufenden Einsatz: periodisch (WorkManager, übersteht Neustart und
 * Prozessende) und zusätzlich einmalig bei Einsatzbeginn. Es gibt keinen Dauer-Dienst; der
 * Einsatz-Livestandort bleibt beim bestehenden Background-Geolocation-Watcher.
 */
internal object LocationReport {
    private const val PERIODIC = "location-report-periodic"
    private const val NOW = "location-report-now"
    fun prefs(context: Context) = context.getSharedPreferences("location-report", Context.MODE_PRIVATE)

    private val constraints get() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Idempotent (KEEP): bei jedem App-Start, Boot und Push gefahrlos aufrufbar. */
    fun ensureScheduled(context: Context) {
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<LocationReportWorker>(
                    LocationReportLogic.PERIOD_HOURS, TimeUnit.HOURS, LocationReportLogic.FLEX_HOURS, TimeUnit.HOURS,
                ).setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES).build(),
            )
        }
    }

    /** Einmalige Meldung (z. B. Einsatzbeginn); höchstens alle 5 Minuten. */
    fun runNow(context: Context, source: String): Boolean = runCatching {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        if (!LocationReportLogic.due(now, p.getLong("last_now", 0), LocationReportLogic.NOW_THROTTLE_MS)) return@runCatching false
        p.edit().putLong("last_now", now).apply()
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<LocationReportWorker>().setConstraints(constraints)
                .setInputData(workDataOf("source" to source, "force" to true))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build(),
        )
        true
    }.getOrDefault(false)

    fun backgroundGranted(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun permissionGranted(context: Context): Boolean =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /** Für Einstellungsseite und Diagnose: letzter Stand der Standortmeldung. */
    fun state(context: Context): Map<String, Any?> {
        val p = prefs(context)
        fun time(key: String) = p.getLong(key, 0).takeIf { it > 0 }?.let { DiagnoseReportLogic.iso(it) }
        return mapOf(
            "status" to p.getString("last_status", null), "fehler" to p.getString("last_error", null),
            "letzter_versuch_at" to time("last_attempt"), "letzte_messung_at" to time("last_fix"),
            "letzte_meldung_at" to time("last_success"), "berechtigung" to permissionGranted(context),
            "hintergrund" to backgroundGranted(context),
        )
    }
}

internal class LocationReportWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val context = applicationContext
        NativeLog.init(context)
        val prefs = LocationReport.prefs(context)
        val now = System.currentTimeMillis()
        val source = inputData.getString("source") ?: "periodic"
        try {
            if (DeviceAccessState.isInvalid(context)) return Result.failure()
            val base = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
                .getString(EinsatzLivePoller.PREF_BASE_URL, null)?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return Result.success()
            // Nur registrierte Einheitengeräte (Gerätetoken); sonst keine Ortung.
            val auth = FcmTokenRegistration.getAuthHeader(context, base) ?: return Result.success()
            if (!inputData.getBoolean("force", false) && !LocationReportLogic.due(now, prefs.getLong("last_success", 0))) {
                return Result.success()
            }
            val permission = LocationReport.permissionGranted(context)
            val manager = context.getSystemService(LocationManager::class.java)
            val providerOn = manager?.let { runCatching { it.isLocationEnabled }.getOrDefault(true) } ?: true
            var lat: Double? = null; var lng: Double? = null; var accuracy: Float? = null; var measured: Long? = null
            var quality = "none"
            if (permission && providerOn) {
                val client = LocationServices.getFusedLocationProviderClient(context)
                val location = runCatching {
                    Tasks.await(client.getCurrentLocation(
                        CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                            .setDurationMillis(20_000).setMaxUpdateAgeMillis(2 * 60_000).build(), null), 30, TimeUnit.SECONDS)
                }.getOrNull() ?: runCatching { Tasks.await(client.lastLocation, 10, TimeUnit.SECONDS) }.getOrNull()
                if (location != null) {
                    // Alter aus der Messzeit, nie aus dem Sendezeitpunkt.
                    val age = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
                    val mock = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && location.isMock
                    quality = LocationReportLogic.classify(age, mock)
                    measured = now - age
                    if (quality == "fresh" || quality == "old") {
                        lat = location.latitude; lng = location.longitude; accuracy = location.accuracy
                    }
                }
            }
            val status = if (lat != null) "ok" else LocationReportLogic.statusFor(permission, providerOn, quality)
            prefs.edit().putLong("last_attempt", now).putString("last_status", status).apply()
            val body = LocationReportLogic.payload(now, source, status, lat, lng, accuracy, measured,
                LocationReport.backgroundGranted(context), null)
            val request = DeviceAccessState.withDeviceHeaders(Request.Builder().url("$base/api/v1/device/location"))
                .header(auth.first, auth.second).post(body.toRequestBody("application/json".toMediaType())).build()
            FcmTokenRegistration.httpClient.newCall(request).execute().use { response ->
                DeviceAccessState.finalCode(response)?.let { DeviceAccessState.markInvalid(context, it); return Result.failure() }
                if (!response.isSuccessful) {
                    prefs.edit().putString("last_error", "HTTP ${response.code}").apply()
                    NativeLog.error(context, "standort", "http_${response.code}", "Standortmeldung: HTTP ${response.code}", schedule = false)
                    return if (response.code == 429 || response.code >= 500) Result.retry() else Result.failure()
                }
            }
            val edit = prefs.edit().remove("last_error")
            if (status == "ok") edit.putLong("last_success", now).putLong("last_fix", measured ?: now)
            edit.apply()
            // Ohne Fix später erneut versuchen (Backoff), ohne die Periodik zu verdoppeln.
            return if (status == "ok" || !permission || !providerOn || runAttemptCount >= 2) Result.success() else Result.retry()
        } catch (e: Exception) {
            prefs.edit().putLong("last_attempt", now).putString("last_error", e.javaClass.simpleName).apply()
            NativeLog.error(context, "standort", "network", "Standortmeldung fehlgeschlagen: ${e.javaClass.simpleName}", schedule = false)
            return Result.retry()
        }
    }
}
