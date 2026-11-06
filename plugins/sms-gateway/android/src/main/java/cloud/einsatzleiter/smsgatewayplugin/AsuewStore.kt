package cloud.einsatzleiter.smsgatewayplugin

import android.content.Context

/** Nur persistenter Wächterzustand; keine zusätzliche Kopie im Arbeitsspeicher. */
object AsuewStore {
    private const val INCIDENT_ID = "ec_asuew_incident_id"
    private const val ACTIVE = "ec_asuew_active"
    private const val ACKED = "ec_asuew_acked"
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
            .remove(LAST_OK_MS)
            .apply()
    }

    fun deactivate(context: Context) {
        prefs(context).edit()
            .putString(ACTIVE, "0")
            .remove(INCIDENT_ID)
            .remove(ACKED)
            .remove(LAST_OK_MS)
            .apply()
    }

    fun acked(context: Context): String = prefs(context).getString(ACKED, "").orEmpty()

    fun saveAcked(context: Context, csv: String) {
        prefs(context).edit().putString(ACKED, csv).apply()
    }

    fun lastOkMs(context: Context): Long = prefs(context).getString(LAST_OK_MS, null)?.toLongOrNull() ?: 0L

    fun saveLastOk(context: Context, lastOkMs: Long) {
        prefs(context).edit().putString(LAST_OK_MS, lastOkMs.toString()).apply()
    }
}
