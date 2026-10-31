package cloud.einsatzleiter.smsgatewayplugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EinsatzLivePollerTest {
    @Test fun thirdConsecutiveAuthFailureReachesStopThreshold() {
        var failures = 0
        repeat(2) {
            failures = nextAuthFailureCount(failures, true)
            assertFalse(failures >= 3)
        }
        failures = nextAuthFailureCount(failures, true)
        assertTrue(failures >= 3)
    }

    @Test fun successBreaksAuthFailureSequence() {
        var failures = nextAuthFailureCount(nextAuthFailureCount(0, true), true)
        failures = nextAuthFailureCount(failures, false)
        assertEquals(0, failures)
        failures = nextAuthFailureCount(failures, true)
        assertFalse(failures >= 3)
        assertEquals(1, failures)
    }
}
