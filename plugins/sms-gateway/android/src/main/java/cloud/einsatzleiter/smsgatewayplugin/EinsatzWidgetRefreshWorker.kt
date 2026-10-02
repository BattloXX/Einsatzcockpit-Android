package cloud.einsatzleiter.smsgatewayplugin

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** Keeps the incident widget current even while the foreground live service is stopped. */
class EinsatzWidgetRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {
    companion object {
        private const val PERIODIC_WORK_NAME = "einsatz-widget-refresh"
        private const val IMMEDIATE_WORK_NAME = "$PERIODIC_WORK_NAME-immediate"
        private const val INTERVAL_MINUTES = 15L

        fun schedule(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val periodic = PeriodicWorkRequestBuilder<EinsatzWidgetRefreshWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, periodic,
            )
            refreshNow(context)
        }

        fun refreshNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<EinsatzWidgetRefreshWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME, ExistingWorkPolicy.KEEP, request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).apply {
                cancelUniqueWork(PERIODIC_WORK_NAME)
                cancelUniqueWork(IMMEDIATE_WORK_NAME)
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .cookieJar(WebViewCookieJar())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
        val baseUrl = prefs.getString(EinsatzLivePoller.PREF_BASE_URL, null)?.trimEnd('/')
            ?.takeIf { it.isNotBlank() } ?: return Result.success()
        val deviceToken = prefs.getString("el_device_token", null)?.takeIf { it.isNotBlank() }
        val url = dutyStateUrl(baseUrl, reportedAppVersion(prefs))
        val request = try {
            Request.Builder().url(url).get().apply {
                deviceToken?.let { header("Authorization", "Bearer $it") }
            }.build()
        } catch (_: IllegalArgumentException) {
            return Result.success()
        }
        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 -> {
                        EcpWidgetSupport.clearIncident(applicationContext)
                        EcpWidgetSupport.clearGslQueue(applicationContext)
                        EcpWidgetSupport.clearGslLive(applicationContext)
                        Result.success()
                    }
                    response.isSuccessful -> {
                        val state = response.body?.string()?.let(DutyStateResponse::parse)
                        if (
                            state == null ||
                            (state.hasIncident && state.incident == null) ||
                            (state.hasGslQueue && state.gslQueue == null) ||
                            (state.hasGslLive && state.gslLive == null)
                        ) {
                            Result.retry()
                        } else {
                            state.applyToWidget(applicationContext)
                            Result.success()
                        }
                    }
                    else -> Result.retry()
                }
            }
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun dutyStateUrl(baseUrl: String, appVersion: String?): String = if (appVersion == null) {
        "$baseUrl/api/v1/device/duty-state"
    } else {
        val encoded = URLEncoder.encode(appVersion, StandardCharsets.UTF_8.toString())
        "$baseUrl/api/v1/device/duty-state?app_version=$encoded"
    }

    private fun reportedAppVersion(prefs: android.content.SharedPreferences): String? {
        val enabled = try {
            prefs.getBoolean(EinsatzLivePoller.PREF_DEVICE_STATUS_REPORTING_ENABLED, true)
        } catch (_: ClassCastException) {
            prefs.getString(EinsatzLivePoller.PREF_DEVICE_STATUS_REPORTING_ENABLED, null) != "false"
        }
        if (!enabled) return null
        return try {
            applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0).versionName
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }
}
