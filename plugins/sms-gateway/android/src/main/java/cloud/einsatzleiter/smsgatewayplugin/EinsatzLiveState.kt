package cloud.einsatzleiter.smsgatewayplugin

import android.os.SystemClock
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class EinsatzLiveState(
    val id: Long,
    val url: String,
    val alarmTypeCode: String,
    val address: String,
    val startedAt: String,
    val phaseIndex: Int,
    val phaseCount: Int,
    val phaseLabel: String,
    val incidentCount: Int,
    val chronometerBase: Long
) {
    companion object {
        fun fromJson(root: JSONObject): EinsatzLiveState? {
            val incident = root.optJSONObject("incident") ?: return null
            val serverMs = parseUtc(root.optString("server_time")) ?: return null
            val startedAt = incident.optString("started_at")
            val startedMs = parseUtc(startedAt) ?: return null
            val elapsed = (serverMs - startedMs).coerceAtLeast(0L)
            return EinsatzLiveState(
                id = incident.getLong("id"),
                url = incident.getString("url"),
                alarmTypeCode = incident.optString("alarm_type_code", "Einsatz"),
                address = incident.optString("address"),
                startedAt = startedAt,
                phaseIndex = incident.optInt("phase_index", 0),
                phaseCount = incident.optInt("phase_count", 4).coerceAtLeast(1),
                phaseLabel = incident.optString("phase_label", "Einsatz läuft"),
                incidentCount = root.optInt("incident_count", 1).coerceAtLeast(1),
                chronometerBase = SystemClock.elapsedRealtime() - elapsed
            )
        }

        private fun parseUtc(value: String): Long? = try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }.parse(value)?.time
        } catch (_: Exception) { null }
    }
}
