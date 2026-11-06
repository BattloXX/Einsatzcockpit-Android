package cloud.einsatzleiter.smsgatewayplugin

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

sealed class AsuewFetchResult {
    data class Success(val state: AsuewState, val rawJson: String) : AsuewFetchResult()
    data class NoSession(val code: Int) : AsuewFetchResult()
    data class Failure(val reason: String) : AsuewFetchResult()
}

sealed class AsuewHeartbeatResult {
    data class Success(val ampel: String, val serverTimeMs: Long?) : AsuewHeartbeatResult()
    object SessionEnded : AsuewHeartbeatResult()
    data class Failure(val reason: String) : AsuewHeartbeatResult()
}

/** Synchroner HTTP-Zugriff; der Aufrufer muss einen Hintergrund-Thread verwenden. */
class AsuewApi internal constructor(
    private val context: Context,
    client: OkHttpClient,
    private val baseUrlProvider: () -> String?,
    private val urlAllowed: (String) -> Boolean,
) {
    constructor(
        context: Context,
        client: OkHttpClient = OkHttpClient.Builder()
            .cookieJar(WebViewCookieJar())
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build(),
        baseUrlProvider: () -> String? = {
            context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
                .getString(EinsatzLivePoller.PREF_BASE_URL, null)
        },
    ) : this(context, client, baseUrlProvider, { UrlPolicy.isAllowedRemoteUrl(it) })

    // Keine Weiterleitung auf ungeprüfte Ziele, auch nicht mit Sitzungs-Cookies.
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    fun fetchState(incidentId: Long): AsuewFetchResult = try {
        val base = allowedBaseUrl()
        if (base == null) {
            AsuewFetchResult.Failure("url")
        } else if (DeviceAccessState.isInvalid(context)) {
            AsuewFetchResult.Failure("device_access_invalid")
        } else {
            client.newCall(request(base, "/api/asuew/$incidentId/zustand").get().build()).execute().use {
                markInvalid(it)
                when {
                    it.code == 401 || it.code == 403 -> AsuewFetchResult.NoSession(it.code)
                    !it.isSuccessful -> AsuewFetchResult.Failure("http_${it.code}")
                    else -> {
                        val raw = it.body?.string().orEmpty()
                        val state = AsuewLogic.parseState(raw)
                        if (state == null) AsuewFetchResult.Failure("parse")
                        else AsuewFetchResult.Success(state, raw)
                    }
                }
            }
        }
    } catch (_: IOException) {
        AsuewFetchResult.Failure("network")
    } catch (_: Exception) {
        AsuewFetchResult.Failure("request")
    }

    fun postHeartbeat(
        akkuProzent: Int?,
        laedt: Boolean?,
        selbsttest: JSONObject?,
    ): AsuewHeartbeatResult = try {
        val base = allowedBaseUrl()
        if (base == null) {
            AsuewHeartbeatResult.Failure("url")
        } else if (DeviceAccessState.isInvalid(context)) {
            AsuewHeartbeatResult.Failure("device_access_invalid")
        } else {
            val json = JSONObject()
            akkuProzent?.let { json.put("akku_prozent", it) }
            laedt?.let { json.put("laedt", it) }
            selbsttest?.let { json.put("selbsttest", it) }
            val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            client.newCall(request(base, "/api/asuew/heartbeat").post(body).build()).execute().use {
                markInvalid(it)
                when {
                    it.code == 401 || it.code == 403 -> AsuewHeartbeatResult.SessionEnded
                    it.code == 409 -> {
                        val error = parseObject(it.body?.string().orEmpty())?.optString("error")
                        if (error == "sitzung_beendet") AsuewHeartbeatResult.SessionEnded
                        else AsuewHeartbeatResult.Failure("http_409")
                    }
                    !it.isSuccessful -> AsuewHeartbeatResult.Failure("http_${it.code}")
                    else -> parseHeartbeat(it.body?.string().orEmpty())
                }
            }
        }
    } catch (_: IOException) {
        AsuewHeartbeatResult.Failure("network")
    } catch (_: Exception) {
        AsuewHeartbeatResult.Failure("request")
    }

    private fun allowedBaseUrl(): String? {
        val base = baseUrlProvider()?.trimEnd('/') ?: return null
        return base.takeIf { urlAllowed(it) }
    }

    private fun request(base: String, path: String): Request.Builder =
        DeviceAccessState.withDeviceHeaders(Request.Builder().url("$base$path"))
            .header("Accept", "application/json")
            .apply {
                // Die Sitzung ec_asuew kommt zusätzlich zum Bearer über die Cookie-Jar.
                val token = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
                    .getString(DeviceAccessState.PREF_DEVICE_TOKEN, null)?.takeIf { it.isNotBlank() }
                token?.let { header("Authorization", "Bearer $it") }
            }

    private fun markInvalid(response: Response) {
        DeviceAccessState.finalCode(response)?.let { DeviceAccessState.markInvalid(context, it) }
    }

    private fun parseObject(raw: String): JSONObject? = try {
        val input = JSONTokener(raw)
        val root = input.nextValue()
        if (root is JSONObject && input.nextClean() == '\u0000') root else null
    } catch (_: Exception) { null }

    private fun parseHeartbeat(raw: String): AsuewHeartbeatResult {
        val json = parseObject(raw) ?: return AsuewHeartbeatResult.Failure("parse")
        val ampel = json.opt("ampel") as? String
        if (json.opt("ok") != true || ampel.isNullOrBlank()) return AsuewHeartbeatResult.Failure("parse")
        val time = if (json.isNull("server_time")) null else {
            val value = json.opt("server_time") as? String
                ?: return AsuewHeartbeatResult.Failure("parse")
            parseServerTime(value) ?: return AsuewHeartbeatResult.Failure("parse")
        }
        return AsuewHeartbeatResult.Success(ampel, time)
    }

    private fun parseServerTime(value: String): Long? = try {
        val match = Regex("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d+))?Z$")
            .matchEntire(value)
        if (match == null) null else {
            // Das Backend liefert UTC mit optionalen Mikrosekunden.
            val fraction = match.groupValues[2].take(3).padEnd(3, '0')
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }.parse("${match.groupValues[1]}.$fraction" + "Z")?.time
        }
    } catch (_: Exception) { null }
}
