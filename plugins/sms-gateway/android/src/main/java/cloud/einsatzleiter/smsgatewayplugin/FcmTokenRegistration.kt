package cloud.einsatzleiter.smsgatewayplugin

import android.content.Context
import android.webkit.CookieManager
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** Registriert FCM-Tokens mit der verfuegbaren nativen Anmeldung beim Backend. */
object FcmTokenRegistration {
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    fun post(context: Context, token: String, callback: (Result<Unit>) -> Unit) {
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
            val body = JSONObject()
                .put("token", token)
                .put("platform", "android")
                .toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/api/v1/device/fcm-token")
                .header(authHeader.first, authHeader.second)
                .post(body)
                .build()
            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = callback(Result.failure(e))

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (it.isSuccessful) callback(Result.success(Unit))
                        else callback(Result.failure(IOException("Server antwortet mit HTTP ${it.code}")))
                    }
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
                override fun onFailure(call: Call, e: IOException) = callback(Result.failure(e))

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
}
