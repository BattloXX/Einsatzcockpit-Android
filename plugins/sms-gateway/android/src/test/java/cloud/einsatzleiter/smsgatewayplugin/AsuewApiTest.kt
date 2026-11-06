package cloud.einsatzleiter.smsgatewayplugin

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AsuewApiTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
    private lateinit var server: MockWebServer
    private lateinit var base: String
    private lateinit var api: AsuewApi
    private var closed = false
    private val client = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(1, TimeUnit.SECONDS)
        .writeTimeout(1, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
    private val stateJson = """{"server_time":"1970-01-01T00:00:01.123456Z","incident_id":42,"asuew_app_pflicht":true,"troops":[{"id":7,"name":"Alpha","status":"bereit"}]}"""
    private val heartbeatJson = """{"ok":true,"server_time":"1970-01-01T00:00:01.123456Z","ampel":"gruen","probleme":[]}"""
    private data class HeartbeatFields(
        val battery: Int?,
        val charging: Boolean?,
        val selftest: JSONObject?,
        val expected: Set<String>,
    )

    @Before fun setup() {
        prefs.edit().clear().commit()
        server = MockWebServer()
        server.start()
        base = server.url("/").toString().trimEnd('/')
        // Ausschließlich diese Testinstanz akzeptiert den konkreten Mockserver-Ursprung.
        // Der öffentliche Konstruktor und UrlPolicy erlauben weiterhin kein localhost.
        api = AsuewApi(context, client, { base }, { UrlPolicy.isLocalPage(it, base) })
    }

    @After fun teardown() {
        if (!closed) server.shutdown()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    @Test fun fetchParsesStatePreservesRawJsonAndSendsDeviceHeaders() {
        prefs.edit().putString("el_device_token", "test-device").commit()
        server.enqueue(MockResponse().setBody(stateJson))
        val result = api.fetchState(42L) as AsuewFetchResult.Success
        assertEquals(stateJson, result.rawJson)
        assertEquals(42L, result.state.incidentId)
        assertEquals(1123L, result.state.serverTimeMs)
        assertTrue(result.state.appPflicht)
        assertEquals("Alpha", result.state.troops.single().name)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("/api/asuew/42/zustand", request.path)
        assertEquals("application/json", request.getHeader("Accept"))
        assertEquals("android", request.getHeader("X-ECP-Platform"))
        assertNotNull(request.getHeader("X-ECP-OS-Version"))
        assertNotNull(request.getHeader("X-ECP-Device-Model"))
        assertEquals("Bearer test-device", request.getHeader("Authorization"))
        assertEquals(0L, request.bodySize)
    }

    @Test fun fetchDistinguishesMissingSessionFromHttpAndParseErrors() {
        for (code in listOf(401, 403)) {
            server.enqueue(MockResponse().setResponseCode(code).setBody("""{"detail":"Keine Sitzung"}"""))
            assertEquals(AsuewFetchResult.NoSession(code), api.fetchState(42L))
        }
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(AsuewFetchResult.Failure("http_500"), api.fetchState(42L))
        for (body in listOf("kaputtes JSON", "{}", "$stateJson extra")) {
            server.enqueue(MockResponse().setBody(body))
            assertEquals(AsuewFetchResult.Failure("parse"), api.fetchState(42L))
        }
    }

    @Test fun heartbeatParsesAmpelAndTimeAndSendsAllFields() {
        prefs.edit().putString("el_device_token", "test-device").commit()
        server.enqueue(MockResponse().setBody(heartbeatJson))
        assertEquals(AsuewHeartbeatResult.Success("gruen", 1123L),
            api.postHeartbeat(0, false, JSONObject().put("alarm", true)))
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/api/asuew/heartbeat", request.path)
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
        assertEquals("android", request.getHeader("X-ECP-Platform"))
        assertEquals("Bearer test-device", request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals(3, body.length())
        assertEquals(0, body.getInt("akku_prozent"))
        assertFalse(body.getBoolean("laedt"))
        assertTrue(body.getJSONObject("selbsttest").getBoolean("alarm"))
    }

    @Test fun heartbeatOmitsUnsetFieldsAndAcceptsMissingServerTime() {
        for ((battery, charging, selftest, expected) in listOf(
            HeartbeatFields(null, null, null, emptySet()),
            HeartbeatFields(70, null, null, setOf("akku_prozent")),
            HeartbeatFields(null, true, null, setOf("laedt")),
            HeartbeatFields(null, null, JSONObject(), setOf("selbsttest")),
        )) {
            server.enqueue(MockResponse().setBody("""{"ok":true,"ampel":"gelb"}"""))
            assertEquals(AsuewHeartbeatResult.Success("gelb", null),
                api.postHeartbeat(battery, charging, selftest))
            val body = JSONObject(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8())
            assertEquals(expected, body.keys().asSequence().toSet())
        }
    }

    @Test fun heartbeatRecognizesEndedSessionsAndRejectsOtherErrors() {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"sitzung_beendet","message":"Beendet"}"""))
        assertEquals(AsuewHeartbeatResult.SessionEnded, api.postHeartbeat(null, null, null))
        for (code in listOf(401, 403)) {
            server.enqueue(MockResponse().setResponseCode(code))
            assertEquals(AsuewHeartbeatResult.SessionEnded, api.postHeartbeat(null, null, null))
        }
        for (code in listOf(409, 500)) {
            server.enqueue(MockResponse().setResponseCode(code).setBody("""{"error":"anderer_fehler"}"""))
            assertEquals(AsuewHeartbeatResult.Failure("http_$code"), api.postHeartbeat(null, null, null))
        }
        for (body in listOf("kaputt", "{}", """{"ok":false,"ampel":"gruen"}""",
            """{"ok":true,"ampel":"gruen","server_time":"kaputt"}""")) {
            server.enqueue(MockResponse().setBody(body))
            assertEquals(AsuewHeartbeatResult.Failure("parse"), api.postHeartbeat(null, null, null))
        }
    }

    @Test fun closedServerReturnsNetworkFailuresForBothMethods() {
        server.shutdown()
        closed = true
        assertEquals(AsuewFetchResult.Failure("network"), api.fetchState(42L))
        assertEquals(AsuewHeartbeatResult.Failure("network"), api.postHeartbeat(null, null, null))
    }

    @Test fun publicConstructorRejectsDisallowedUrlsIncludingMockserver() {
        for (url in listOf(null, "", base, "https://localhost/", "https://example.org/",
            "http://einsatzcockpit.com/", "https://einsatzcockpit.com:8443/")) {
            val strictApi = AsuewApi(context, client, { url })
            assertEquals(AsuewFetchResult.Failure("url"), strictApi.fetchState(42L))
            assertEquals(AsuewHeartbeatResult.Failure("url"), strictApi.postHeartbeat(null, null, null))
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun defaultBaseUrlProviderReadsCapacitorStorage() {
        prefs.edit().putString("el_base_url", base).commit()
        // Die gespeicherte lokale URL muss an der öffentlichen Policy scheitern.
        assertEquals(AsuewFetchResult.Failure("url"), AsuewApi(context, client).fetchState(42L))
        assertEquals(0, server.requestCount)
    }

    @Test fun redirectsAreReturnedAsFailuresWithoutFollowingThem() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "$base/other"))
        assertEquals(AsuewFetchResult.Failure("http_302"), api.fetchState(42L))
        assertEquals(1, server.requestCount)
    }

    @Test fun finalDeviceErrorMarksAccessInvalidAndPreventsFurtherRequests() {
        prefs.edit().putString("el_device_token", "revoked").commit()
        server.enqueue(MockResponse().setResponseCode(401).setHeader(DeviceAccessState.HEADER, "device_revoked"))
        assertEquals(AsuewFetchResult.NoSession(401), api.fetchState(42L))
        assertEquals("device_revoked", prefs.getString(DeviceAccessState.PREF_INVALID, null))
        assertNull(prefs.getString("el_device_token", null))
        assertEquals(AsuewHeartbeatResult.Failure("device_access_invalid"), api.postHeartbeat(null, null, null))
        assertEquals(AsuewFetchResult.Failure("device_access_invalid"), api.fetchState(42L))
        assertEquals(1, server.requestCount)
    }

    @Test fun heartbeatAlsoMarksFinalDeviceErrors() {
        prefs.edit().putString("el_device_token", "unknown").commit()
        server.enqueue(MockResponse().setResponseCode(401).setHeader(DeviceAccessState.HEADER, "device_unknown"))
        assertEquals(AsuewHeartbeatResult.SessionEnded, api.postHeartbeat(null, null, null))
        assertEquals("device_unknown", prefs.getString(DeviceAccessState.PREF_INVALID, null))
        assertNull(prefs.getString("el_device_token", null))
    }
}
