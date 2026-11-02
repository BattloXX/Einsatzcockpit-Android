package cloud.einsatzleiter.smsgatewayplugin

import android.content.Context
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class ServerCapabilitiesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var server: MockWebServer
    private lateinit var base: String
    private val body = """{"api_version":1,"server_version":"test","features":["location"]}"""
    private val auth = "Authorization" to "Bearer test-device"
    private val prefs get() = context.getSharedPreferences("server-capabilities", Context.MODE_PRIVATE)

    @Before fun setup() {
        prefs.edit().clear().commit()
        context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE).edit().clear().commit()
        server = MockWebServer()
        server.start()
        base = server.url("/").toString().trimEnd('/')
    }

    @After fun teardown() { server.shutdown() }

    @Test fun `fetch uses device headers and caches known features per server`() {
        server.enqueue(MockResponse().setBody(body))
        assertFalse(ServerCapabilities.shouldSkip(context, base, auth, "location"))
        assertTrue(ServerCapabilities.shouldSkip(context, base, auth, "diagnose_report"))
        assertEquals(1, server.requestCount)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("/api/v1/device/capabilities", request.path)
        assertEquals("android", request.getHeader("X-ECP-Platform"))
        assertEquals(auth.second, request.getHeader("Authorization"))
        assertEquals(body, prefs.getString("$base:result", null))
        // Ein anderer Server (hier anderer Basispfad) erbt keine fehlenden Features.
        server.enqueue(MockResponse().setResponseCode(404))
        assertFalse(ServerCapabilities.shouldSkip(context, "$base/other", auth, "diagnose_report"))
    }

    @Test fun `capabilities 404 is cached as legacy and permits both report types`() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertFalse(ServerCapabilities.shouldSkip(context, base, auth, "location"))
        assertFalse(ServerCapabilities.shouldSkip(context, base, auth, "diagnose_report"))
        assertEquals(1, server.requestCount)
        assertTrue(prefs.contains("$base:fetched_at"))
        assertFalse(prefs.contains("$base:features"))
    }

    @Test fun `expired capabilities are refreshed and failed fetch stays unknown`() {
        server.enqueue(MockResponse().setBody(body))
        assertTrue(ServerCapabilities.shouldSkip(context, base, auth, "diagnose_report"))
        prefs.edit().putLong("$base:fetched_at", System.currentTimeMillis() - ServerCapabilitiesLogic.CACHE_MS).commit()
        server.enqueue(MockResponse().setResponseCode(503))
        assertFalse(ServerCapabilities.shouldSkip(context, base, auth, "diagnose_report"))
        assertEquals(2, server.requestCount)
    }

    @Test fun `malformed capabilities remain unknown`() {
        server.enqueue(MockResponse().setBody("""{"api_version":1}"""))
        assertFalse(ServerCapabilities.shouldSkip(context, base, auth, "location"))
        assertFalse(prefs.contains("$base:fetched_at"))
    }

    @Test fun `POST 404 suppresses only that feature without another fetch until expiry`() {
        assertTrue(ServerCapabilities.rememberUnsupported(context, base, "location", 404))
        assertTrue(ServerCapabilities.shouldSkip(context, base, auth, "location"))
        assertEquals(0, server.requestCount)
        server.enqueue(MockResponse().setBody(body))
        assertTrue(ServerCapabilities.shouldSkip(context, base, auth, "diagnose_report"))
        assertEquals(1, server.requestCount)
        prefs.edit().putLong("$base:unsupported:location", System.currentTimeMillis() - 1).commit()
        assertFalse(ServerCapabilities.shouldSkip(context, base, auth, "location"))
        assertFalse(ServerCapabilities.rememberUnsupported(context, base, "location", 500))
    }

    @Test fun `unsupported reports log info once without recording errors`() {
        NativeLog.init(context)
        NativeLog.clear()
        assertTrue(ServerCapabilities.rememberUnsupported(context, base, "location", 404))
        repeat(3) { assertTrue(ServerCapabilities.shouldSkip(context, base, auth, "location")) }
        assertEquals(1, NativeLog.read().lineSequence().count { it.contains("[info][capabilities]") })
        assertTrue(NativeLog.errors(context).isEmpty())
        assertEquals(0, server.requestCount)
    }
}
