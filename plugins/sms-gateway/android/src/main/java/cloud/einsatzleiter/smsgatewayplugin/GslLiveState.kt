package cloud.einsatzleiter.smsgatewayplugin

import org.json.JSONObject

data class GslLiveCounts(
    val neu: Int,
    val inArbeit: Int,
    val erledigt: Int,
    val gesamt: Int,
)

data class GslLiveState(
    val id: Long,
    val url: String,
    val name: String,
    val isExercise: Boolean,
    val counts: GslLiveCounts,
) {
    companion object {
        fun fromJson(root: JSONObject): GslLiveState? {
            val lage = root.optJSONObject("lage") ?: return null
            val id = lage.optLong("id", -1L)
            val url = lage.optString("url").takeIf { it.isNotBlank() }
            if (id < 0L || url == null) return null
            val counts = lage.optJSONObject("counts")
            return GslLiveState(
                id = id,
                url = url,
                name = lage.optString("name").takeIf { it.isNotBlank() } ?: "Grossschadenslage",
                isExercise = lage.optBoolean("is_exercise", false),
                counts = GslLiveCounts(
                    neu = counts?.optInt("neu", 0)?.coerceAtLeast(0) ?: 0,
                    inArbeit = counts?.optInt("in_arbeit", 0)?.coerceAtLeast(0) ?: 0,
                    erledigt = counts?.optInt("erledigt", 0)?.coerceAtLeast(0) ?: 0,
                    gesamt = counts?.optInt("gesamt", 0)?.coerceAtLeast(0) ?: 0,
                ),
            )
        }
    }
}
