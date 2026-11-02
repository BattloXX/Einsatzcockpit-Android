package cloud.einsatzleiter.smsgatewayplugin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerCapabilitiesLogicTest {
    private val now = 100 * 60 * 60_000L

    @Test fun `capabilities expire at six hours and a clock rollback refreshes`() {
        assertTrue(ServerCapabilitiesLogic.fresh(now, now))
        assertTrue(ServerCapabilitiesLogic.fresh(now, now - ServerCapabilitiesLogic.CACHE_MS + 1))
        assertFalse(ServerCapabilitiesLogic.fresh(now, now - ServerCapabilitiesLogic.CACHE_MS))
        assertFalse(ServerCapabilitiesLogic.fresh(now, 0))
        assertFalse(ServerCapabilitiesLogic.fresh(now, now + 1))
    }

    @Test fun `unknown and legacy servers still get reports but known missing features skip`() {
        for (feature in listOf("diagnose_report", "location")) {
            assertFalse(ServerCapabilitiesLogic.shouldSkip(feature, null, now, 0))
            assertFalse(ServerCapabilitiesLogic.shouldSkip(feature, setOf(feature), now, 0))
            assertTrue(ServerCapabilitiesLogic.shouldSkip(feature, emptySet(), now, 0))
            assertTrue(ServerCapabilitiesLogic.shouldSkip(feature, setOf("push_ack", "duty_state"), now, 0))
        }
    }

    @Test fun `POST 404 overrides advertised features for 24 hours only`() {
        val until = now + ServerCapabilitiesLogic.UNSUPPORTED_MS
        for (features in listOf(null, setOf("location"))) {
            assertTrue(ServerCapabilitiesLogic.shouldSkip("location", features, now, until))
            assertTrue(ServerCapabilitiesLogic.shouldSkip("location", features, until - 1, until))
            assertFalse(ServerCapabilitiesLogic.shouldSkip("location", features, until, until))
        }
        assertTrue(ServerCapabilitiesLogic.unsupportedResponse(404))
        for (code in listOf(200, 401, 403, 429, 500, 503)) assertFalse(ServerCapabilitiesLogic.unsupportedResponse(code))
    }
}
