package cloud.einsatzleiter.smsgatewayplugin

import org.junit.Assert.*
import org.junit.Test

class DiagnoseReportLogicTest {
    @Test fun schemaWhitelistAndNoSecrets() {
        val report = DiagnoseReportLogic.report("installation", 0, mapOf("app" to mapOf("version_name" to "1.2", "token" to "hidden"), "secret" to mapOf("token" to "hidden")),
            mapOf("el_alarm_tone" to "sirene", "el_device_token" to "hidden", "el_gateway_token" to "hidden", "base_url_host" to "example.org"), emptyList())
        val json = DiagnoseReportLogic.json(report)
        assertEquals(1, report["schema"])
        assertTrue(json.contains("1970-01-01T00:00:00.000Z"))
        assertFalse(json.contains("hidden"))
        assertFalse(json.contains("token"))
        assertTrue(json.contains("example.org"))
        assertTrue(json.contains("sirene"))
    }
    @Test fun masksErrorsAndLimitsToFifty() {
        val secret = "Bearer abc123 token=secret user@example.org +43 660 1234567 https://host/path?x=private"
        val errors = (1..80).map { DiagnoseReportLogic.Error("time", "poller", "auth", secret + " z".repeat(300), it) }
        val report = DiagnoseReportLogic.report("id", 0, emptyMap(), emptyMap(), errors)
        val list = report["fehler"] as List<*>
        assertEquals(50, list.size)
        assertEquals(31, (list.first() as Map<*, *>)["anzahl"])
        val json = DiagnoseReportLogic.json(report)
        listOf("abc123", "secret", "user@example", "1234567", "https://", "private").forEach { assertFalse(it, json.contains(it)) }
        assertTrue(((list.first() as Map<*, *>)["text"] as String).length <= 200)
    }
    @Test fun settingsAreTypedAndCannotCarryArbitraryStrings() {
        val report = DiagnoseReportLogic.report("id", 0, emptyMap(), mapOf(
            "el_alarm_tone" to "Bearer private", "el_live_enabled" to "1", "el_alarm_override_silent" to "false",
            "el_alarm_volume_percent" to "85", "auto_update" to true, "base_url_host" to "example.org/path?token=private",
            "el_gateway_receive_enabled" to "private", "sms_text" to "private"), emptyList())
        val settings = report["einstellungen"] as Map<*, *>
        assertEquals(true, settings["el_live_enabled"])
        assertEquals(false, settings["el_alarm_override_silent"])
        assertEquals(85, settings["el_alarm_volume_percent"])
        assertEquals("example.org", settings["base_url_host"])
        assertFalse(DiagnoseReportLogic.json(report).contains("private"))
        assertEquals(5, settings.size)
    }
    @Test fun contractErrorShapeAndUtf8Size() {
        val report = DiagnoseReportLogic.report("id", 0, emptyMap(), emptyMap(),
            (1..100).map { DiagnoseReportLogic.Error("2026-11-01T12:00:00Z", "poller", "network", "Ä".repeat(400), severity = "warning") })
        val error = (report["fehler"] as List<*>).first() as Map<*, *>
        assertEquals(setOf("ts", "komponente", "code", "text", "anzahl"), error.keys)
        assertTrue(DiagnoseReportLogic.json(report).toByteArray(Charsets.UTF_8).size < 64 * 1024)
    }
    @Test fun jsonEscapesAndRejectsNonFiniteNumbers() {
        assertEquals("{\"text\":\"a\\\"b\\n\",\"value\":null}", DiagnoseReportLogic.json(mapOf("text" to "a\"b\n", "value" to Double.NaN)).replace("\\u000a", "\\n"))
    }
    @Test fun throttleBoundariesAndManual() {
        assertTrue(ReportThrottleLogic.due("startup", 100, 0))
        assertFalse(ReportThrottleLogic.due("startup", 21_600_000, 1))
        assertTrue(ReportThrottleLogic.due("startup", 21_600_001, 1))
        assertFalse(ReportThrottleLogic.due("error", 600_000, 1))
        assertTrue(ReportThrottleLogic.due("error", 600_001, 1))
        assertTrue(ReportThrottleLogic.due("manual", 1, 1))
        assertTrue(ReportThrottleLogic.due("settings", 1, 1))
        assertTrue(ReportThrottleLogic.due("startup", 1, 2))
    }
}
