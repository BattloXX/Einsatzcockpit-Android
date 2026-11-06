package cloud.einsatzleiter.smsgatewayplugin

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AsuewSchedulingTest {
    @Test fun nullDeadlineStaysNull() {
        assertNull(AsuewScheduling.triggerAtWallMs(null, 50_000L, 10_000L))
    }

    @Test fun serverTimeUsesPositiveNegativeAndZeroOffset() {
        assertEquals(18_000L, AsuewScheduling.triggerAtWallMs(20_000L, 2_000L, 10_000L)!!)
        assertEquals(22_000L, AsuewScheduling.triggerAtWallMs(20_000L, -2_000L, 10_000L)!!)
        assertEquals(20_000L, AsuewScheduling.triggerAtWallMs(20_000L, 0L, 10_000L)!!)
    }

    @Test fun pastPresentAndNearFutureAreRaisedToOneSecond() {
        for (deadline: Long in listOf(-1L, 0L, 9_000L, 10_000L, 10_001L, 10_999L)) {
            assertEquals(11_000L, AsuewScheduling.triggerAtWallMs(deadline, 0L, 10_000L)!!)
        }
        assertEquals(11_000L, AsuewScheduling.triggerAtWallMs(13_999L, 3_000L, 10_000L)!!)
    }

    @Test fun exactlyOneSecondAndLaterStayUnchanged() {
        assertEquals(11_000L, AsuewScheduling.triggerAtWallMs(14_000L, 3_000L, 10_000L)!!)
        assertEquals(11_001L, AsuewScheduling.triggerAtWallMs(14_001L, 3_000L, 10_000L)!!)
        val now: Long = 1_800_000_000_123L
        assertEquals(now + 60_000L, AsuewScheduling.triggerAtWallMs(now + 65_000L, 5_000L, now)!!)
    }

    @Test fun exactAlarmModeCoversPermissionBoundaryAndApiLevels() {
        for (sdk: Int in listOf(24, 28, 30)) {
            assertEquals("alarm_clock", AsuewScheduling.exactAlarmMode(sdk, false))
            assertEquals("alarm_clock", AsuewScheduling.exactAlarmMode(sdk, true))
        }
        for (sdk: Int in listOf(31, 32, 33, 34, 35, 36)) {
            assertEquals("inexakt", AsuewScheduling.exactAlarmMode(sdk, false))
            assertEquals("alarm_clock", AsuewScheduling.exactAlarmMode(sdk, true))
        }
    }

    @Test fun successfulSelftestContainsOnlyBackendKeysAndOptionalVolume() {
        val json: JSONObject = AsuewSelbsttest.build(true, true, true, true, true, true, 75)
        assertEquals(setOf("benachrichtigung", "online", "alarmton_getestet", "exakte_wecker",
            "vollbild", "akku_optimierung_ausgenommen", "alarm_lautstaerke_prozent"), json.keys().asSequence().toSet())
        assertEquals("granted", json.getString("benachrichtigung"))
        for (key: String in listOf("online", "alarmton_getestet", "exakte_wecker", "vollbild", "akku_optimierung_ausgenommen")) {
            assertEquals(true, json.get(key))
        }
        assertEquals(75, json.getInt("alarm_lautstaerke_prozent"))
    }

    @Test fun failedSelftestUsesDeniedAndBooleanFalseAndOmitsUnknownVolume() {
        val json: JSONObject = AsuewSelbsttest.build(false, false, false, false, false, false, null)
        assertEquals(6, json.length())
        assertEquals("denied", json.getString("benachrichtigung"))
        for (key: String in listOf("online", "alarmton_getestet", "exakte_wecker", "vollbild", "akku_optimierung_ausgenommen")) {
            assertEquals(false, json.get(key))
        }
        assertFalse(json.has("alarm_lautstaerke_prozent"))
    }

    @Test fun selftestMapsEachIndependentFlagAndVolumeBoundaries() {
        val json: JSONObject = AsuewSelbsttest.build(true, false, true, false, true, false, 0)
        assertFalse(json.getBoolean("online"))
        assertTrue(json.getBoolean("alarmton_getestet"))
        assertFalse(json.getBoolean("exakte_wecker"))
        assertTrue(json.getBoolean("vollbild"))
        assertFalse(json.getBoolean("akku_optimierung_ausgenommen"))
        assertEquals(0, json.getInt("alarm_lautstaerke_prozent"))
        assertEquals(100, AsuewSelbsttest.build(false, true, false, true, false, true, 100)
            .getInt("alarm_lautstaerke_prozent"))
    }

    @Test fun alarmToneConfirmationExpiresAtTwentyFourHours() {
        val tested: Long = 1_800_000_000_000L
        val day: Long = 24 * 60 * 60_000L
        assertTrue(AsuewScheduling.isAlarmToneTestValid(tested, tested))
        assertTrue(AsuewScheduling.isAlarmToneTestValid(tested, tested + day - 1L))
        assertFalse(AsuewScheduling.isAlarmToneTestValid(tested, tested + day))
        assertFalse(AsuewScheduling.isAlarmToneTestValid(tested, tested + day + 1L))
    }

    @Test fun missingInvalidAndFutureConfirmationsAreRejected() {
        val now: Long = 1_800_000_000_000L
        assertFalse(AsuewScheduling.isAlarmToneTestValid(null, now))
        assertFalse(AsuewScheduling.isAlarmToneTestValid(0L, now))
        assertFalse(AsuewScheduling.isAlarmToneTestValid(-1L, now))
        assertFalse(AsuewScheduling.isAlarmToneTestValid(now + 1L, now))
        assertFalse(AsuewScheduling.isAlarmToneTestValid(now, now - 60_000L))
    }
}
