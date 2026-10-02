package cloud.einsatzleiter.smsgatewayplugin

import org.json.JSONObject

/** Unlike JSONObject.optString, keeps a JSON null from becoming the text "null". */
fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
