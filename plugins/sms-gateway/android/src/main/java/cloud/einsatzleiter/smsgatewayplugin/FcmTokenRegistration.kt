package cloud.einsatzleiter.smsgatewayplugin

import android.content.Context
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Registriert FCM-Tokens direkt mit dem langlebigen Device-Token beim Backend. */
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
            val deviceToken = prefs.getString("el_device_token", null)?.takeIf { it.isNotBlank() }
            if (baseUrl.isNullOrBlank() || deviceToken == null) {
                callback(Result.failure(IllegalStateException("Server-URL oder Device-Token fehlt")))
                return
            }
            val body = JSONObject()
                .put("token", token)
                .put("platform", "android")
                .toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/api/v1/device/fcm-token")
                .header("Authorization", "Bearer $deviceToken")
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
}
