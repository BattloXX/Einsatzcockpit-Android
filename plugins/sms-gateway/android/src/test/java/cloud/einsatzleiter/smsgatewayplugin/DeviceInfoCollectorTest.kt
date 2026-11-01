package cloud.einsatzleiter.smsgatewayplugin

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceInfoCollectorTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Test fun collectsReadOnlyDeviceFactsAndUnknownClock() {
        ReportSender.prefs(context).edit().clear().commit()
        val data = DeviceInfoCollector(context).collect()
        assertEquals(35, data["android"]?.get("sdk_int"))
        assertEquals("standard", data["app"]?.get("build_variant"))
        assertNotNull(data["geraet"]?.get("modell"))
        assertNotNull(data["display"]?.get("dpi"))
        assertTrue(data["locale"]!!.containsKey("uhr_abweichung_s"))
        assertNull(data["locale"]?.get("uhr_abweichung_s"))
    }
    @Test fun privateInstallIdAndServerClock() {
        val first = ReportSender.installId(context)
        assertEquals(first, ReportSender.installId(context))
        assertEquals(4, java.util.UUID.fromString(first).version())
        ReportSender.serverTime(context, "{\"server_time\":\"2026-11-01T10:00:00Z\"}", null)
        assertTrue(ReportSender.prefs(context).contains("clock_offset_s"))
    }
    @Test fun dateHeaderFallbackAndUnknownTime() {
        ReportSender.prefs(context).edit().remove("clock_offset_s").commit()
        ReportSender.serverTime(context, "{}", "invalid")
        assertFalse(ReportSender.prefs(context).contains("clock_offset_s"))
        ReportSender.serverTime(context, "{}", "Sun, 01 Nov 2026 10:00:00 GMT")
        assertTrue(ReportSender.prefs(context).contains("clock_offset_s"))
    }
    @Test fun structuredErrorsAggregatePersistMaskAndClear() {
        NativeLog.init(context)
        NativeLog.clear()
        repeat(2) { NativeLog.error(context, "gateway", "network", "Bearer hidden user@example.org", schedule = false) }
        val error = NativeLog.errors(context).single()
        assertEquals(2, error.count)
        assertEquals("error", error.severity)
        assertFalse(error.text.contains("hidden"))
        repeat(60) { NativeLog.error(context, "poller", "error$it", "failure", schedule = false) }
        assertEquals(50, NativeLog.errors(context).size)
        assertFalse(ReportSender.report(context).contains("hidden"))
        NativeLog.clear()
        assertTrue(NativeLog.errors(context).isEmpty())
    }
}
