package cloud.einsatzleiter.smsgatewayplugin

import android.content.Context
import android.content.SharedPreferences
import androidx.work.*
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Persistent unique work: one pending report, regenerated from current state on each attempt. */
internal object ReportSender {
    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("diagnose-report", Context.MODE_PRIVATE)
    @Synchronized fun installId(context: Context): String {
        val prefs = prefs(context)
        return prefs.getString("install_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("install_id", it).commit() }
    }
    @Synchronized fun schedule(context: Context, reason: String): Boolean =
        runCatching {
            val prefs = prefs(context)
            val key = "scheduled_$reason"
            val now = System.currentTimeMillis()
            if (!ReportThrottleLogic.due(reason, now, prefs.getLong(key, 0))) return@runCatching true
            WorkManager.getInstance(context).enqueueUniqueWork("diagnose-report", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<DiagnoseReportWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
            prefs.edit().putLong(key, now).apply()
            true
        }.getOrDefault(false)
    fun mark(context: Context, key: String) { prefs(context).edit().putLong(key, System.currentTimeMillis()).apply() }
    fun serverTime(context: Context, body: String?, date: String?) {
        fun parse(value: String?, pattern: String): Long? = runCatching {
            SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(value.orEmpty())?.time
        }.getOrNull()
        val time = runCatching { JSONObject(body.orEmpty()).optString("server_time") }.getOrNull()
        val server = parse(time?.replace(Regex("(\\.\\d{3})\\d+(?=Z|[+-])"), "$1"), "yyyy-MM-dd'T'HH:mm:ss.SSSX") ?: parse(time, "yyyy-MM-dd'T'HH:mm:ssX")
            ?: parse(date, "EEE, dd MMM yyyy HH:mm:ss zzz")
        server?.let { prefs(context).edit().putLong("clock_offset_s", (System.currentTimeMillis() - it) / 1000).apply() }
    }
    fun report(context: Context): String {
        val settings = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE).all
            .filterKeys { it in DiagnoseReportLogic.settings }.toMutableMap()
        val base = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE).getString(EinsatzLivePoller.PREF_BASE_URL, "")
        settings["base_url_host"] = DiagnoseReportLogic.host(base.orEmpty())
        settings["auto_update"] = context.resources.getBoolean(R.bool.auto_update_enabled)
        return DiagnoseReportLogic.json(DiagnoseReportLogic.report(installId(context), System.currentTimeMillis(), DeviceInfoCollector(context).collect(), settings, NativeLog.errors(context)))
    }
}

internal class DiagnoseReportWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val context = applicationContext
        NativeLog.init(context)
        try {
            if (DeviceAccessState.isInvalid(context)) return Result.failure()
            val base = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE).getString(EinsatzLivePoller.PREF_BASE_URL, null)?.trimEnd('/')
                ?.takeIf { it.isNotBlank() } ?: return Result.failure()
            val auth = FcmTokenRegistration.getAuthHeader(context, base) ?: return Result.failure()
            val json = ReportSender.report(context)
            if (json.toByteArray(Charsets.UTF_8).size > 64 * 1024) {
                NativeLog.error(context, "diagnose", "size", "Bericht überschreitet 64 KB", schedule = false)
                return Result.failure()
            }
            val request = DeviceAccessState.withDeviceHeaders(Request.Builder().url("$base/api/v1/device/diagnose-report"))
                .header(auth.first, auth.second).post(json.toRequestBody("application/json".toMediaType())).build()
            FcmTokenRegistration.httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    NativeLog.error(context, "diagnose", "http_${response.code}", "Bericht: HTTP ${response.code}", schedule = false)
                    return if (response.code == 429 || response.code >= 500) Result.retry() else Result.failure()
                }
                val body = response.body?.string()
                if (JSONObject(body.orEmpty()).optBoolean("ok") != true) return Result.retry()
                ReportSender.serverTime(context, body, response.header("Date"))
                ReportSender.mark(context, "last_sent")
                return Result.success()
            }
        } catch (e: Exception) {
            NativeLog.error(context, "diagnose", "network", "Bericht fehlgeschlagen: ${e.javaClass.simpleName}", schedule = false)
            return Result.retry()
        }
    }
}
