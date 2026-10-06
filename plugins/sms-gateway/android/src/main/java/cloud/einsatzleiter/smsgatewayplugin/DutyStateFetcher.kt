package cloud.einsatzleiter.smsgatewayplugin

import android.content.Context
import android.content.SharedPreferences
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** Synchronous duty-state refresh shared by the widget worker and FCM handling. */
object DutyStateFetcher {
    enum class Outcome { APPLIED, PARTIAL, NO_CONFIG, AUTH_FAILED, FAILED }

    private val client by lazy {
        OkHttpClient.Builder()
            .cookieJar(WebViewCookieJar())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun fetchAndApply(context: Context, reason: String): Outcome {
        val prefs = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
        val baseUrl = prefs.getString(EinsatzLivePoller.PREF_BASE_URL, null)?.trimEnd('/')
            ?.takeIf { it.isNotBlank() }
        if (baseUrl == null) return log(reason, Outcome.NO_CONFIG)
        val request = try {
            Request.Builder().url(dutyStateUrl(baseUrl, reportedAppVersion(context, prefs))).get().apply {
                prefs.getString("el_device_token", null)?.takeIf { it.isNotBlank() }
                    ?.let { header("Authorization", "Bearer $it") }
            }.build()
        } catch (_: IllegalArgumentException) {
            return log(reason, Outcome.FAILED, "(ungueltige URL)")
        }
        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 -> {
                        EcpWidgetSupport.clearIncident(context)
                        EcpWidgetSupport.clearGslQueue(context)
                        EcpWidgetSupport.clearGslLive(context)
                        log(reason, Outcome.AUTH_FAILED)
                    }
                    response.isSuccessful -> {
                        val state = response.body?.string()?.let(DutyStateResponse::parse)
                            ?: return@use log(reason, Outcome.FAILED, "(ungueltige Antwort)")
                        state.applyToWidget(context)
                        val partial = (state.hasIncident && state.incident == null) ||
                            (state.hasGslQueue && state.gslQueue == null) ||
                            (state.hasGslLive && state.gslLive == null)
                        log(reason, if (partial) Outcome.PARTIAL else Outcome.APPLIED, state = state)
                    }
                    else -> log(reason, Outcome.FAILED, "(HTTP ${response.code})")
                }
            }
        } catch (e: Exception) {
            log(reason, Outcome.FAILED, "(${e.javaClass.simpleName})")
        }
    }

    private fun log(reason: String, outcome: Outcome, detail: String? = null, state: DutyStateResponse? = null): Outcome {
        val result = when (outcome) {
            Outcome.APPLIED -> state?.incident?.let { "Einsatz ${it.id}" } ?: "kein Einsatz"
            Outcome.PARTIAL -> state?.incident?.let { "Einsatz ${it.id} (teilweise)" } ?: "teilweise aktualisiert"
            Outcome.NO_CONFIG -> "keine Konfiguration"
            Outcome.AUTH_FAILED -> "Anmeldung fehlgeschlagen"
            Outcome.FAILED -> "fehlgeschlagen${detail ?: ""}"
        }
        SmsGatewayService.log("Widget-Aktualisierung ($reason): $result")
        return outcome
    }

    private fun dutyStateUrl(baseUrl: String, appVersion: String?): String {
        if (appVersion == null) return "$baseUrl/api/v1/device/duty-state"
        val encoded = URLEncoder.encode(appVersion, StandardCharsets.UTF_8.toString())
        return "$baseUrl/api/v1/device/duty-state?app_version=$encoded"
    }

    private fun reportedAppVersion(context: Context, prefs: SharedPreferences): String? {
        val enabled = try {
            prefs.getBoolean(EinsatzLivePoller.PREF_DEVICE_STATUS_REPORTING_ENABLED, true)
        } catch (_: ClassCastException) {
            prefs.getString(EinsatzLivePoller.PREF_DEVICE_STATUS_REPORTING_ENABLED, null) != "false"
        }
        if (!enabled) return null
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}
