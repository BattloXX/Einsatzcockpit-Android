package cloud.einsatzleiter.smsgatewayplugin

import android.content.Context
import okhttp3.Request
import org.json.JSONObject

/** Nur von Hintergrund-Workern aufrufen; Alarm- und Push-Pfade warten nie auf diesen GET. */
internal object ServerCapabilities {
    private fun prefs(context: Context) = context.getSharedPreferences("server-capabilities", Context.MODE_PRIVATE)

    @Synchronized private fun features(context: Context, base: String, auth: Pair<String, String>): Set<String>? {
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        if (ServerCapabilitiesLogic.fresh(now, prefs.getLong("$base:fetched_at", 0))) {
            return prefs.getStringSet("$base:features", null)
        }
        // Fehler oder unverständliche Antworten lassen die Fähigkeiten unbekannt.
        return runCatching {
            val request = DeviceAccessState.withDeviceHeaders(Request.Builder().url("$base/api/v1/device/capabilities"))
                .header(auth.first, auth.second).get().build()
            FcmTokenRegistration.httpClient.newCall(request).execute().use { response ->
                DeviceAccessState.finalCode(response)?.let { DeviceAccessState.markInvalid(context, it) }
                var result: String? = null
                val features = when {
                    response.code == 404 -> null
                    response.isSuccessful -> {
                        result = response.body?.string().orEmpty()
                        val json = JSONObject(result)
                        json.getInt("api_version")
                        val array = json.getJSONArray("features")
                        (0 until array.length()).map { array.getString(it) }.toSet()
                    }
                    else -> return@runCatching null
                }
                val edit = prefs.edit().putLong("$base:fetched_at", now)
                    .putString("$base:result", result)
                if (features == null) edit.remove("$base:features") else edit.putStringSet("$base:features", features)
                edit.apply()
                features
            }
        }.getOrNull()
    }

    fun shouldSkip(context: Context, base: String, auth: Pair<String, String>, feature: String): Boolean {
        val prefs = prefs(context)
        val until = prefs.getLong("$base:unsupported:$feature", 0)
        val now = System.currentTimeMillis()
        // Ein gemerkter POST-404 benötigt auch keinen Capability-GET.
        val features = if (now < until) null else features(context, base, auth)
        val skip = ServerCapabilitiesLogic.shouldSkip(feature, features, now, until)
        if (skip) logSkip(context, base, feature)
        else prefs.edit().remove("$base:logged:$feature").apply()
        return skip
    }

    fun rememberUnsupported(context: Context, base: String, feature: String, statusCode: Int): Boolean {
        if (!ServerCapabilitiesLogic.unsupportedResponse(statusCode)) return false
        prefs(context).edit().putLong("$base:unsupported:$feature", System.currentTimeMillis() + ServerCapabilitiesLogic.UNSUPPORTED_MS).apply()
        logSkip(context, base, feature)
        return true
    }

    @Synchronized private fun logSkip(context: Context, base: String, feature: String) {
        val prefs = prefs(context)
        val key = "$base:logged:$feature"
        if (prefs.getBoolean(key, false)) return
        prefs.edit().putBoolean(key, true).apply()
        NativeLog.init(context)
        NativeLog.append("[info][capabilities] $feature wird von diesem Server nicht unterstützt; Meldung übersprungen")
    }
}
