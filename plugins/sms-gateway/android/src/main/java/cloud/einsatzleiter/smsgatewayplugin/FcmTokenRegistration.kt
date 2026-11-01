package cloud.einsatzleiter.smsgatewayplugin

import android.content.Context
import android.webkit.CookieManager
import com.google.firebase.messaging.FirebaseMessaging
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** Registriert FCM-Tokens mit der verfuegbaren nativen Anmeldung beim Backend. */
object FcmTokenRegistration {
    private const val PREF_DEVICE_STATUS_REPORTING_ENABLED = "ec_device_status_reporting_enabled"

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    fun ensureCapabilitiesRegistered(context: Context, reason: String) {
        fun logFailure(error: Throwable) {
            NativeLog.error(context, "fcm", "registration", "FCM-Faehigkeiten nicht registriert: ${error.javaClass.simpleName}")
        }
        runCatching {
            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
            val capabilitiesVersion = capabilitiesRegistrationValue(appContext)
            if (prefs.getString("ec_fcm_caps_registered", null) == capabilitiesVersion) return
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                runCatching {
                    post(appContext, token) { result ->
                        result.fold(onSuccess = { nativeAlarm ->
                            if (!nativeAlarm) {
                                runCatching { SmsGatewayService.log("FCM-Faehigkeiten vom Server nicht bestaetigt ($reason)") }
                                return@fold
                            }
                            runCatching {
                                prefs.edit().putString("ec_fcm_caps_registered", capabilitiesVersion).apply()
                                SmsGatewayService.log("FCM-Faehigkeiten registriert ($reason)")
                            }.onFailure { logFailure(it) }
                        }, onFailure = { logFailure(it) })
                    }
                }.onFailure { logFailure(it) }
            }.addOnFailureListener { logFailure(it) }
        }.onFailure { logFailure(it) }
    }

    fun capabilitiesRegistrationValue(context: Context): String {
        val version = installedAppVersion(context) ?: error("App-Version nicht verfuegbar")
        return "$version:native_alarm:v2"
    }

    fun confirmsNativeAlarm(body: String): Boolean {
        return runCatching {
            val capabilities = JSONObject(body).optJSONArray("capabilities") ?: return false
            (0 until capabilities.length()).any { capabilities.optString(it) == "native_alarm" }
        }.getOrDefault(false)
    }

    fun post(context: Context, token: String, callback: (Result<Boolean>) -> Unit) {
        try {
            val prefs = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
            val baseUrl = prefs.getString(EinsatzLivePoller.PREF_BASE_URL, null)?.trimEnd('/')
            if (baseUrl.isNullOrBlank()) {
                callback(Result.failure(IllegalStateException("Server-URL fehlt")))
                return
            }
            if (DeviceAccessState.isInvalid(context)) {
                callback(Result.failure(IllegalStateException(DeviceAccessState.MESSAGE)))
                return
            }
            val authHeader = getAuthHeader(context, baseUrl)
            if (authHeader == null) {
                callback(Result.failure(IllegalStateException(
                    "Keine Anmeldung gefunden (weder Geräte-Token noch Sitzungs-Cookie)",
                )))
                return
            }
            val json = JSONObject()
                .put("token", token)
                .put("platform", "android")
                .put("capabilities", JSONArray().put("native_alarm"))
            if (isDeviceStatusReportingEnabled(prefs)) {
                installedAppVersion(context)?.let { json.put("app_version", it) }
            }
            val body = json
                .toString()
                .toRequestBody("application/json".toMediaType())
            val request = DeviceAccessState.withDeviceHeaders(
                Request.Builder().url("$baseUrl/api/v1/device/fcm-token"),
            ).header(authHeader.first, authHeader.second).post(body).build()
            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    NativeLog.error(context, "fcm", "network", "FCM-Anfrage fehlgeschlagen: ${e.javaClass.simpleName}")
                    callback(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            DeviceAccessState.finalCode(it)?.let { code -> DeviceAccessState.markInvalid(context, code) }
                            if (!it.isSuccessful) {
                                NativeLog.error(context, "fcm", "http_${it.code}", "FCM-Anfrage HTTP ${it.code}")
                                throw IOException("Server antwortet mit HTTP ${it.code}")
                            }
                            confirmsNativeAlarm(it.body?.string().orEmpty())
                        }
                    }
                    callback(result)
                }
            })
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }

    fun sendTestAlarm(context: Context, token: String, callback: (Result<JSONObject>) -> Unit) =
        sendTest(context, token, "/api/v1/device/fcm-test-alarm", callback)

    fun sendTestPush(context: Context, token: String, callback: (Result<JSONObject>) -> Unit) =
        sendTest(context, token, "/api/v1/device/fcm-test-push", callback)

    private fun sendTest(context: Context, token: String, path: String, callback: (Result<JSONObject>) -> Unit) {
        try {
            val prefs = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
            val baseUrl = prefs.getString(EinsatzLivePoller.PREF_BASE_URL, null)?.trimEnd('/')
            if (baseUrl.isNullOrBlank()) {
                callback(Result.failure(IllegalStateException("Server-URL fehlt")))
                return
            }
            if (DeviceAccessState.isInvalid(context)) {
                callback(Result.failure(IllegalStateException(DeviceAccessState.MESSAGE)))
                return
            }
            val authHeader = getAuthHeader(context, baseUrl)
            if (authHeader == null) {
                callback(Result.failure(IllegalStateException(
                    "Keine Anmeldung gefunden (weder Geräte-Token noch Sitzungs-Cookie)",
                )))
                return
            }
            val body = JSONObject().put("token", token).toString()
                .toRequestBody("application/json".toMediaType())
            val request = DeviceAccessState.withDeviceHeaders(
                Request.Builder().url("$baseUrl$path"),
            ).header(authHeader.first, authHeader.second).post(body).build()
            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    NativeLog.error(context, "fcm", "network", "FCM-Anfrage fehlgeschlagen: ${e.javaClass.simpleName}")
                    callback(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            DeviceAccessState.finalCode(it)?.let { code -> DeviceAccessState.markInvalid(context, code) }
                            if (it.code == 429) throw IOException("Bitte 20 Sekunden warten")
                            val responseBody = it.body?.string().orEmpty()
                            if (it.code == 404 && path == "/api/v1/device/fcm-test-push") {
                                val detail = runCatching { JSONObject(responseBody).optString("detail") }.getOrDefault("")
                                throw IOException(if (detail == "Token nicht gefunden") {
                                    "Gerät nicht registriert – bitte Registrieren tippen"
                                } else {
                                    "Server kennt den Test-Push noch nicht – bitte Server aktualisieren"
                                })
                            }
                            if (!it.isSuccessful) {
                                NativeLog.error(context, "fcm", "http_${it.code}", "FCM-Anfrage HTTP ${it.code}")
                                throw IOException("Server antwortet mit HTTP ${it.code}")
                            }
                            JSONObject(responseBody)
                        }
                    }
                    callback(result)
                }
            })
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }

    fun getStatus(context: Context, token: String, callback: (Result<JSONObject>) -> Unit) {
        try {
            val prefs = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
            val baseUrl = prefs.getString(EinsatzLivePoller.PREF_BASE_URL, null)?.trimEnd('/')
            if (baseUrl.isNullOrBlank()) {
                callback(Result.failure(IllegalStateException("Server-URL fehlt")))
                return
            }
            val authHeader = getAuthHeader(context, baseUrl)
            if (authHeader == null) {
                callback(Result.failure(IllegalStateException(
                    "Keine Anmeldung gefunden (weder Geräte-Token noch Sitzungs-Cookie)",
                )))
                return
            }
            val encodedToken = URLEncoder.encode(token, StandardCharsets.UTF_8.toString())
            val request = Request.Builder()
                .url("$baseUrl/api/v1/device/fcm-token/status?token=$encodedToken")
                .header(authHeader.first, authHeader.second)
                .get()
                .build()
            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    NativeLog.error(context, "fcm", "network", "FCM-Anfrage fehlgeschlagen: ${e.javaClass.simpleName}")
                    callback(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val responseBody = it.body?.string().orEmpty()
                        if (it.isSuccessful) callback(Result.success(JSONObject(responseBody)))
                        else callback(Result.failure(IOException(
                            "Server antwortet mit HTTP ${it.code}: $responseBody",
                        )))
                    }
                }
            })
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }

    fun getAuthHeader(context: Context, baseUrl: String): Pair<String, String>? {
        val prefs = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
        val deviceToken = prefs.getString("el_device_token", null)?.takeIf { it.isNotBlank() }
        if (deviceToken != null) return "Authorization" to "Bearer $deviceToken"

        val cookie = CookieManager.getInstance().getCookie(baseUrl)?.takeIf { it.isNotBlank() }
        return cookie?.let { "Cookie" to it }
    }

    private fun installedAppVersion(context: Context): String? {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun isDeviceStatusReportingEnabled(prefs: android.content.SharedPreferences): Boolean {
        return try {
            prefs.getBoolean(PREF_DEVICE_STATUS_REPORTING_ENABLED, true)
        } catch (_: ClassCastException) {
            // Capacitor Preferences persists values as strings; native callers may use booleans.
            prefs.getString(PREF_DEVICE_STATUS_REPORTING_ENABLED, null) != "false"
        }
    }
}
