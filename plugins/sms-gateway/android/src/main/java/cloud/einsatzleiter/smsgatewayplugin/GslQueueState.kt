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
                bezeichnung = json.optStringOrNull("bezeichnung") ?: return null,
                meldung = json.optStringOrNull("meldung"),
                address = json.optStringOrNull("address") ?: "",
                lat = json.optDouble("lat", Double.NaN).takeIf { !it.isNaN() },
                lng = json.optDouble("lng", Double.NaN).takeIf { !it.isNaN() },
                gmapsUrl = json.optStringOrNull("gmaps_url"),
                priority = json.optStringOrNull("priority"),
                phase = json.optStringOrNull("phase") ?: "",
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
            val lageUrl = queue.optStringOrNull("lage_url")
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
                lageName = queue.optStringOrNull("lage_name") ?: "Grossschadenslage",
                lageUrl = lageUrl,
                isExercise = queue.optBoolean("is_exercise", false),
                current = current,
                upcoming = upcoming,
                remainingCount = queue.optInt("remaining_count", 0).coerceAtLeast(0),
            )
        }
    }
}
