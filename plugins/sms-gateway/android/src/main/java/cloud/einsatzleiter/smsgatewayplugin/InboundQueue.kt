package cloud.einsatzleiter.smsgatewayplugin

import org.json.JSONArray
import org.json.JSONObject

internal object InboundQueue {
    private const val INBOX_QUEUE_MAX = 200

    fun enqueue(arr: JSONArray, entry: JSONObject): JSONArray {
        arr.put(entry)
        // Aelteste Eintraege verwerfen wenn das Limit ueberschritten wird (Speicher begrenzen)
        val start = maxOf(0, arr.length() - INBOX_QUEUE_MAX)
        if (start == 0) return arr
        val trimmed = JSONArray()
        for (i in start until arr.length()) trimmed.put(arr.get(i))
        return trimmed
    }

    fun remove(arr: JSONArray, id: String): JSONArray {
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("id") != id) kept.put(obj)
        }
        return kept
    }
}
