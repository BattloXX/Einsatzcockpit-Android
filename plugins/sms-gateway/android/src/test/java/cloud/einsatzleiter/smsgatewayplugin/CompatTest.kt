package cloud.einsatzleiter.smsgatewayplugin

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class CompatTest {
    @Test
    @Config(sdk = [24, 33, 35])
    fun parcelableIntentSupportsMissingAndPresentExtra() {
        val outer = Intent()
        assertNull(Compat.getParcelableExtraCompat(outer, Intent.EXTRA_INTENT, Intent::class.java))
        outer.putExtra(Intent.EXTRA_INTENT, Intent("confirm.update").putExtra("session", 42))
        val value = Compat.getParcelableExtraCompat(outer, Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals("confirm.update", value.action)
        assertEquals(42, value.getIntExtra("session", -1))
    }
}
