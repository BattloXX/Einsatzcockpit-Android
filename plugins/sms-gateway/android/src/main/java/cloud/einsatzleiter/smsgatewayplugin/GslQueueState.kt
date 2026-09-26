package cloud.einsatzleiter.smsgatewayplugin

import org.json.JSONObject

data class GslSiteInfo(
    val id: Long,
    val bezeichnung: String,
    val meldung: String?,
    val address: String,
    val lat: Double?,
    val lng: Double?,
    val gmapsUrl: String?,
    val priority: String?,
    val phase: String,
) {
    companion object {
        fun fromJson(json: JSONObject): GslSiteInfo? {
            val id = json.optLong("id", -1L)
            if (id < 0L) return null
            return GslSiteInfo(
                id = id,
                bezeichnung = json.optString("bezeichnung").takeIf { it.isNotBlank() } ?: return null,
                meldung = json.optString("meldung").takeIf { it.isNotBlank() },
                address = json.optString("address"),
                lat = json.optDouble("lat", Double.NaN).takeIf { !it.isNaN() },
                lng = json.optDouble("lng", Double.NaN).takeIf { !it.isNaN() },
                gmapsUrl = json.optString("gmaps_url").takeIf { it.isNotBlank() },
                priority = json.optString("priority").takeIf { it.isNotBlank() },
                phase = json.optString("phase"),
            )
        }
    }
}

data class GslQueueState(
    val lageId: Long,
    val lageName: String,
    val lageUrl: String,
    val isExercise: Boolean,
    val current: GslSiteInfo,
    val upcoming: List<GslSiteInfo>,
    val remainingCount: Int,
) {
    companion object {
        fun fromJson(root: JSONObject): GslQueueState? {
            val queue = root.optJSONObject("my_lage_queue") ?: return null
            val lageId = queue.optLong("lage_id", -1L)
            val lageUrl = queue.optString("lage_url").takeIf { it.isNotBlank() }
            val current = queue.optJSONObject("current")?.let(GslSiteInfo::fromJson)
            if (lageId < 0L || lageUrl == null || current == null) return null
            val upcoming = buildList {
                val items = queue.optJSONArray("upcoming")
                for (index in 0 until minOf(items?.length() ?: 0, 2)) {
                    items?.optJSONObject(index)?.let(GslSiteInfo::fromJson)?.let(::add)
                }
            }
            return GslQueueState(
                lageId = lageId,
                lageName = queue.optString("lage_name").takeIf { it.isNotBlank() } ?: "Grossschadenslage",
                lageUrl = lageUrl,
                isExercise = queue.optBoolean("is_exercise", false),
                current = current,
                upcoming = upcoming,
                remainingCount = queue.optInt("remaining_count", 0).coerceAtLeast(0),
            )
        }
    }
}
