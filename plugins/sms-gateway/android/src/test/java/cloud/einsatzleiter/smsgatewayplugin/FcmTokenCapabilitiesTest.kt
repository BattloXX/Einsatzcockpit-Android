package cloud.einsatzleiter.smsgatewayplugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class FcmTokenCapabilitiesTest {
    @Test
    fun appMeldetAlarmUndAtemschutzSitzung() {
        assertEquals(listOf("native_alarm", "asuew"), FcmTokenRegistration.CAPABILITIES)
    }

    @Test
    fun nativerAlarmWirdWeiterAusDerServerAntwortBestaetigt() {
        assertTrue(FcmTokenRegistration.confirmsNativeAlarm("""{"ok":true,"capabilities":["asuew","native_alarm"]}"""))
        assertFalse(FcmTokenRegistration.confirmsNativeAlarm("""{"ok":true,"capabilities":["asuew"]}"""))
    }
}
