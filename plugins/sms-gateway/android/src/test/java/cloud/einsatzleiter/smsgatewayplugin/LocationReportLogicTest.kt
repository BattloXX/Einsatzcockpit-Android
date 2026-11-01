package cloud.einsatzleiter.smsgatewayplugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationReportLogicTest {
    private val min = 60_000L
    private val h = 60 * min

    @Test fun `Messalter unterscheidet frisch alt veraltet und Mock`() {
        assertEquals("fresh", LocationReportLogic.classify(2 * min, false))
        assertEquals("old", LocationReportLogic.classify(3 * h, false))
        assertEquals("stale", LocationReportLogic.classify(25 * h, false))
        assertEquals("stale", LocationReportLogic.classify(-1, false))
        assertEquals("mock", LocationReportLogic.classify(1 * min, true))
        assertEquals("none", LocationReportLogic.classify(null, false))
    }

    @Test fun `neue Bestimmung erst nach Mindestabstand`() {
        assertTrue(LocationReportLogic.due(10 * h, 0))
        assertFalse(LocationReportLogic.due(10 * h, 8 * h))
        assertTrue(LocationReportLogic.due(14 * h, 8 * h))
        assertTrue("Uhr zurueckgestellt", LocationReportLogic.due(1 * h, 8 * h))
    }

    @Test fun `Status bei fehlender Berechtigung ausgeschaltetem Ortungsdienst und altem Fix`() {
        assertEquals("permission_denied", LocationReportLogic.statusFor(false, true, "none"))
        assertEquals("location_disabled", LocationReportLogic.statusFor(true, false, "none"))
        assertEquals("stale_fix", LocationReportLogic.statusFor(true, true, "stale"))
        assertEquals("mock_location", LocationReportLogic.statusFor(true, true, "mock"))
        assertEquals("no_fix", LocationReportLogic.statusFor(true, true, "none"))
    }

    @Test fun `Nutzlast enthaelt Messzeit und bei Fehlstatus keine Koordinaten`() {
        val ok = LocationReportLogic.payload(10 * h, "periodic", "ok", 47.5, 9.7, 12f, 9 * h, true, null)
        assertTrue(ok.contains("\"measured_at\":\"" + DiagnoseReportLogic.iso(9 * h) + "\""))
        assertTrue(ok.contains("\"lat\":47.5"))
        assertTrue(ok.contains("\"sent_at\":\"" + DiagnoseReportLogic.iso(10 * h) + "\""))
        val fehler = LocationReportLogic.payload(10 * h, "periodic", "permission_denied", 47.5, 9.7, 12f, null, false, "Bearer abc123 https://x.test")
        assertFalse(fehler.contains("\"lat\""))
        assertFalse(fehler.contains("abc123"))
        assertTrue(fehler.contains("\"background_permission\":false"))
    }
}
