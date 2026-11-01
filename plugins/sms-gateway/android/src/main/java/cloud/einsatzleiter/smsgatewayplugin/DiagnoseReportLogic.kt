package cloud.einsatzleiter.smsgatewayplugin

import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Contract schema 1. Only explicitly admitted fields cross the diagnostic boundary. */
internal object DiagnoseReportLogic {
    val settings = setOf("el_alarm_tone", "el_alarm_volume_percent", "el_alarm_override_silent", "el_live_enabled", "el_gateway_receive_enabled", "auto_update", "base_url_host")
    private val fields = mapOf(
        "app" to "version_name version_code build_variant target_sdk min_sdk first_install_at last_update_at",
        "geraet" to "hersteller marke modell geraetename klasse foldable abi ram_mb_total ram_mb_free speicher_mb_free akku_prozent laedt akkusparer sim_anzahl nfc kamera telefonie",
        "android" to "version sdk_int sicherheitspatch build_id oberflaeche",
        "display" to "breite_px hoehe_px dpi dichte_bucket breite_dp hz ausrichtung schrift_skala anzeige_skala",
        "webview" to "paket version", "locale" to "sprache zeitzone uhr_abweichung_s",
        "berechtigungen" to "benachrichtigungen akku_optimierung_aus vollbild_intent dnd_zugriff standort sms netz",
        "dienste" to "poller_laeuft keepalive gateway_verbunden fcm_registriert fcm_faehigkeiten device_token_vorhanden gateway_token_vorhanden letzter_poll_ok_at letzte_push_bestaetigung_at letzter_kontaktsync_at",
    ).mapValues { it.value.split(" ").toSet() }
    data class Error(val ts: String, val component: String, val code: String, val text: String, val count: Int = 1, val severity: String = "error")

    fun mask(text: String): String = text
        .replace(Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE), "[URL]")
        .replace(Regex("(?i)Bearer\\s+[^\\s]+"), "Bearer ***")
        .replace(Regex("(?i)(?:[\\w-]*(?:token|secret|password|cookie|authorization)[\\w-]*)[\\s\"']*[:=][\\s\"']*[^\\s,;]+"), "[maskiert]")
        .replace(Regex("[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}"), "[E-Mail]")
        .replace(Regex("[+]?\\d[\\d ()/-]{5,}\\d"), "[Nummer]")
        .replace(Regex("[A-Za-z0-9_+/=-]{24,}"), "[ID]")
        .replace(Regex("[\\r\\n\\t]+"), " ").take(200)

    fun host(url: String): String? = runCatching { URI(url).host }.getOrNull()
    fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
    fun report(id: String, now: Long, sections: Map<String, Map<String, Any?>>, preferences: Map<String, Any?>, errors: List<Error>): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>("schema" to 1, "install_id" to id, "sent_at" to iso(now))
        fields.forEach { (section, allowed) ->
            sections[section]?.let { values -> result[section] = values.filterKeys { it in allowed }.mapValues { (_, v) -> safe(v) } }
        }
        result["einstellungen"] = preferences.filterKeys { it in settings }.mapNotNull { (key, value) ->
            val clean: Any? = when (key) {
                "base_url_host" -> value?.toString()?.let { host("https://$it") }?.take(200)
                "el_alarm_tone" -> value?.toString()?.takeIf { it in setOf("standard", "sirene", "martinshorn") }
                "el_alarm_volume_percent" -> value?.toString()?.toIntOrNull()?.coerceIn(0, 100)
                else -> when (value?.toString()?.lowercase()) { "true", "1" -> true; "false", "0" -> false; else -> null }
            }
            clean?.let { key to it }
        }.toMap()
        result["fehler"] = errors.takeLast(50).map { mapOf("ts" to it.ts, "komponente" to mask(it.component).take(40), "code" to mask(it.code).take(60), "text" to mask(it.text), "anzahl" to it.count.coerceAtLeast(1)) }
        return result
    }
    private fun safe(value: Any?): Any? = when (value) {
        is String -> value.take(200)
        is Boolean, is Number, null -> value
        is List<*> -> value.take(20).map { safe(it) }
        else -> null
    }
    /** Small JSON encoder keeps this logic usable in ordinary JVM tests. */
    fun json(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"" + value.map { c -> when (c) { '\\' -> "\\\\"; '\"' -> "\\\""; else -> if (c.code < 32) "\\u%04x".format(c.code) else c.toString() } }.joinToString("") + "\""
        is Boolean -> value.toString()
        is Number -> if (value.toDouble().isFinite()) value.toString() else "null"
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { json(it.key.toString()) + ":" + json(it.value) }
        is List<*> -> value.joinToString(",", "[", "]") { json(it) }
        else -> "null"
    }
}

internal object ReportThrottleLogic {
    fun due(reason: String, now: Long, last: Long): Boolean =
        last == 0L || now < last || now - last >= when (reason) { "startup" -> 6 * 60 * 60_000L; "error" -> 10 * 60_000L; else -> 0L }
}
