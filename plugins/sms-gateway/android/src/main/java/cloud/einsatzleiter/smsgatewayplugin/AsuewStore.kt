package cloud.einsatzleiter.smsgatewayplugin

import android.content.Context
import android.content.SharedPreferences

/** Nur persistenter Wächterzustand; keine zusätzliche Kopie im Arbeitsspeicher. */
object AsuewStore {
    private const val INCIDENT_ID = "ec_asuew_incident_id"
    private const val ACTIVE = "ec_asuew_active"
    private const val ACKED = "ec_asuew_acked"
    private const val LOCAL_EVENTS = "ec_asuew_local_events"
    private const val STATE_JSON = "ec_asuew_state_json"
    private const val STATE_WALL_MS = "ec_asuew_state_wall_ms"
    private const val OFFSET_MS = "ec_asuew_offset_ms"
    private const val LAST_OK_MS = "ec_asuew_last_ok_ms"

    private fun prefs(context: Context) =
        context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)

    fun isActive(context: Context): Boolean = prefs(context).getString(ACTIVE, null) == "1"

    fun incidentId(context: Context): Long? = prefs(context).getString(INCIDENT_ID, null)?.toLongOrNull()

    /** Eine neue Aktivierung beginnt ohne alte Quittierungen und Verbindungszeit. */
    fun activate(context: Context, incidentId: Long) {
        prefs(context).edit()
            .putString(INCIDENT_ID, incidentId.toString())
            .putString(ACTIVE, "1")
            .remove(ACKED)
            .remove(LOCAL_EVENTS)
            .remove(LAST_OK_MS)
            .remove(STATE_JSON)
            .remove(STATE_WALL_MS)
            .remove(OFFSET_MS)
            .apply()
    }

    fun deactivate(context: Context) {
        prefs(context).edit()
            .putString(ACTIVE, "0")
            .remove(INCIDENT_ID)
            .remove(ACKED)
            .remove(LOCAL_EVENTS)
            .remove(LAST_OK_MS)
            .remove(STATE_JSON)
            .remove(STATE_WALL_MS)
            .remove(OFFSET_MS)
            .apply()
    }

    fun saveState(context: Context, rawJson: String, wallMs: Long, offsetMs: Long) {
        prefs(context).edit().putString(STATE_JSON, rawJson)
            .putString(STATE_WALL_MS, wallMs.toString())
            .putString(OFFSET_MS, offsetMs.toString()).apply()
    }

    fun loadState(context: Context): Triple<String, Long, Long>? {
        val stored: SharedPreferences = prefs(context)
        val rawJson: String = stored.getString(STATE_JSON, null) ?: return null
        val wallMs: Long = stored.getString(STATE_WALL_MS, null)?.toLongOrNull() ?: return null
        val offsetMs: Long = stored.getString(OFFSET_MS, null)?.toLongOrNull() ?: return null
        return Triple(rawJson, wallMs, offsetMs)
    }

    fun acked(context: Context): String = prefs(context).getString(ACKED, "").orEmpty()

    fun localEvents(context: Context): String = prefs(context).getString(LOCAL_EVENTS, "[]") ?: "[]"

    fun saveLocalEvents(context: Context, json: String) {
        prefs(context).edit().putString(LOCAL_EVENTS, json).apply()
    }

    fun saveAcked(context: Context, csv: String) {
        prefs(context).edit().putString(ACKED, csv).apply()
    }

    fun lastOkMs(context: Context): Long = prefs(context).getString(LAST_OK_MS, null)?.toLongOrNull() ?: 0L

    fun saveLastOk(context: Context, lastOkMs: Long) {
        prefs(context).edit().putString(LAST_OK_MS, lastOkMs.toString()).apply()
    }
}
