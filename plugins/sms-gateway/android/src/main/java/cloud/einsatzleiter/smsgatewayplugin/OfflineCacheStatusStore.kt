package cloud.einsatzleiter.smsgatewayplugin

import android.content.Context
import org.json.JSONArray

/** Small, cross-WebView status store for the offline data shown in "Über die App". */
object OfflineCacheStatusStore {
    private const val PREFS = "ec_offline_cache_status"
    private const val KEY_OBJECT_CACHED = "object_cached"
    private const val KEY_OBJECT_TOTAL = "object_total"
    private const val KEY_OBJECT_UPDATED = "object_updated"
    private const val KEY_OBJECT_ACTIVITY = "object_activity"
    private const val KEY_CURRENT_ACTIVITY = "current_activity"
    private const val KEY_ACTIVITIES = "activities"
    private const val MAX_ACTIVITIES = 8

    fun updateObjects(context: Context, cached: Int, total: Int, activity: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_OBJECT_CACHED, cached.coerceAtLeast(0))
            .putInt(KEY_OBJECT_TOTAL, total.coerceAtLeast(0))
            .putLong(KEY_OBJECT_UPDATED, System.currentTimeMillis())
            .putString(KEY_OBJECT_ACTIVITY, activity)
            .putString(KEY_CURRENT_ACTIVITY, activity)
            .putString(KEY_ACTIVITIES, appendActivity(prefs.getString(KEY_ACTIVITIES, null), activity))
            .apply()
    }

    fun logActivity(context: Context, activity: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_CURRENT_ACTIVITY, activity)
            .putString(KEY_ACTIVITIES, appendActivity(prefs.getString(KEY_ACTIVITIES, null), activity))
            .apply()
    }

    fun clearObjects(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val activity = "Objektcache wurde gelöscht"
        prefs.edit()
            .remove(KEY_OBJECT_CACHED)
            .remove(KEY_OBJECT_TOTAL)
            .remove(KEY_OBJECT_UPDATED)
            .remove(KEY_OBJECT_ACTIVITY)
            .putString(KEY_CURRENT_ACTIVITY, activity)
            .putString(KEY_ACTIVITIES, appendActivity(prefs.getString(KEY_ACTIVITIES, null), activity))
            .apply()
    }

    fun objectSnapshot(context: Context): ObjectSnapshot {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ObjectSnapshot(
            cached = prefs.getInt(KEY_OBJECT_CACHED, 0),
            total = prefs.getInt(KEY_OBJECT_TOTAL, 0),
            updatedAtMs = prefs.getLong(KEY_OBJECT_UPDATED, 0).takeIf { it > 0 },
            activity = prefs.getString(KEY_CURRENT_ACTIVITY, null)
                ?: prefs.getString(KEY_OBJECT_ACTIVITY, null),
            activities = readActivities(prefs.getString(KEY_ACTIVITIES, null)),
        )
    }

    private fun appendActivity(existing: String?, activity: String): String {
        val entries = readActivities(existing).toMutableList()
        if (entries.firstOrNull()?.text == activity) return existing ?: "[]"
        entries.add(0, ActivityEntry(System.currentTimeMillis(), activity))
        return JSONArray().apply {
            entries.take(MAX_ACTIVITIES).forEach { entry ->
                put(org.json.JSONObject().put("at", entry.atMs).put("text", entry.text))
            }
        }.toString()
    }

    private fun readActivities(raw: String?): List<ActivityEntry> = try {
        val array = JSONArray(raw ?: "[]")
        buildList {
            for (index in 0 until array.length()) {
                val value = array.optJSONObject(index) ?: continue
                add(ActivityEntry(value.optLong("at"), value.optString("text")))
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    data class ObjectSnapshot(
        val cached: Int,
        val total: Int,
        val updatedAtMs: Long?,
        val activity: String?,
        val activities: List<ActivityEntry>,
    )

    data class ActivityEntry(val atMs: Long, val text: String)
}
