package cloud.einsatzleiter.smsgatewayplugin

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AsuewStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)

    @Before fun setup() { prefs.edit().clear().commit() }

    @Test fun activateAndDeactivatePersistOnlyWatcherKeys() {
        assertFalse(AsuewStore.isActive(context))
        assertNull(AsuewStore.incidentId(context))
        assertEquals("", AsuewStore.acked(context))
        assertEquals(0L, AsuewStore.lastOkMs(context))
        prefs.edit().putString("el_base_url", "https://einsatzcockpit.com").commit()
        AsuewStore.activate(context, 5_000_000_000L)
        assertTrue(AsuewStore.isActive(context))
        assertEquals(5_000_000_000L, AsuewStore.incidentId(context)!!)
        assertEquals("5000000000", prefs.getString("ec_asuew_incident_id", null))
        assertEquals("1", prefs.getString("ec_asuew_active", null))
        AsuewStore.saveAcked(context, "1:notfall:100")
        AsuewStore.saveLastOk(context, 1234L)
        AsuewStore.saveState(context, "{}", 1234L, -5000L)
        AsuewStore.deactivate(context)
        assertFalse(AsuewStore.isActive(context))
        assertNull(AsuewStore.incidentId(context))
        assertEquals("0", prefs.getString("ec_asuew_active", null))
        assertEquals("", AsuewStore.acked(context))
        assertEquals(0L, AsuewStore.lastOkMs(context))
        assertNull(AsuewStore.loadState(context))
        assertEquals(setOf("el_base_url", "ec_asuew_active"), prefs.all.keys)
        assertEquals("https://einsatzcockpit.com", prefs.getString("el_base_url", null))
    }

    @Test fun acknowledgementsRoundTripThroughTrackerAndPreferences() {
        val tracker = AsuewAlarmTracker()
        tracker.ack("2:notfall:100")
        tracker.ack("1:withdraw:100")
        AsuewStore.saveAcked(context, tracker.serializeAcked())
        val restored = AsuewAlarmTracker()
        restored.restoreAcked(AsuewStore.acked(context))
        assertEquals(tracker.acked, restored.acked)
        assertEquals("1:withdraw:100,2:notfall:100", prefs.getString("ec_asuew_acked", null))
        AsuewStore.saveAcked(context, "")
        assertEquals("", AsuewStore.acked(context))
    }

    @Test fun lastOkRoundTripsAsStringAndNewActivationClearsOldSessionData() {
        AsuewStore.activate(context, 42L)
        AsuewStore.saveLastOk(context, 1_800_000_000_123L)
        assertEquals(1_800_000_000_123L, AsuewStore.lastOkMs(context))
        assertEquals("1800000000123", prefs.getString("ec_asuew_last_ok_ms", null))
        AsuewStore.saveAcked(context, "1:notfall:100")
        AsuewStore.saveState(context, "{}", 1000L, 200L)
        AsuewStore.activate(context, 43L)
        assertEquals(43L, AsuewStore.incidentId(context)!!)
        assertNull(AsuewStore.loadState(context))
        assertEquals(0L, AsuewStore.lastOkMs(context))
        assertEquals("", AsuewStore.acked(context))
    }

    @Test fun malformedStoredNumbersUseEmptyDefaults() {
        prefs.edit().putString("ec_asuew_incident_id", "kaputt")
            .putString("ec_asuew_last_ok_ms", "kaputt").commit()
        assertNull(AsuewStore.incidentId(context))
        assertEquals(0L, AsuewStore.lastOkMs(context))
    }

    @Test fun stateRoundTripsWithLongTimesAndSignedOffset() {
        assertNull(AsuewStore.loadState(context))
        val raw: String = """{"incident_id":42,"troops":[]}"""
        val wall: Long = 1_800_000_000_123L
        AsuewStore.saveState(context, raw, wall, -12_345L)
        assertEquals(Triple(raw, wall, -12_345L), AsuewStore.loadState(context))
        assertEquals(raw, prefs.getString("ec_asuew_state_json", null))
        assertEquals(wall.toString(), prefs.getString("ec_asuew_state_wall_ms", null))
        assertEquals("-12345", prefs.getString("ec_asuew_offset_ms", null))
        AsuewStore.saveState(context, "{}", wall + 1L, 0L)
        assertEquals(Triple("{}", wall + 1L, 0L), AsuewStore.loadState(context))
    }

    @Test fun incompleteOrMalformedStateMetadataIsRejected() {
        prefs.edit().putString("ec_asuew_state_json", "{}").commit()
        assertNull(AsuewStore.loadState(context))
        prefs.edit().putString("ec_asuew_state_wall_ms", "1000").commit()
        assertNull(AsuewStore.loadState(context))
        prefs.edit().putString("ec_asuew_offset_ms", "kaputt").commit()
        assertNull(AsuewStore.loadState(context))
        prefs.edit().putString("ec_asuew_offset_ms", "0")
            .putString("ec_asuew_state_wall_ms", "kaputt").commit()
        assertNull(AsuewStore.loadState(context))
        prefs.edit().putString("ec_asuew_state_wall_ms", "1000")
            .remove("ec_asuew_state_json").commit()
        assertNull(AsuewStore.loadState(context))
    }

    @Test fun deactivationPreservesConfirmedAlarmTest() {
        prefs.edit().putString("ec_asuew_alarm_tested_at_ms", "1800000000000").commit()
        AsuewStore.activate(context, 42L)
        AsuewStore.saveState(context, "{}", 1000L, 0L)
        AsuewStore.deactivate(context)
        assertNull(AsuewStore.loadState(context))
        assertEquals("1800000000000", prefs.getString("ec_asuew_alarm_tested_at_ms", null))
    }
}
