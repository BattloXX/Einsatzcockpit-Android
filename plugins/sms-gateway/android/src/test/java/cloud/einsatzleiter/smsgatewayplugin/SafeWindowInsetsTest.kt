package cloud.einsatzleiter.smsgatewayplugin

import org.junit.Assert.assertEquals
import org.junit.Test

class SafeWindowInsetsTest {
    private val zero = SafePadding(0, 0, 0, 0)

    @Test fun keyboardAndSystemBarsOverlapRatherThanAdd() {
        assertEquals(SafePadding(40, 24, 0, 320), safeWindowPadding(
            zero, SafePadding(40, 24, 0, 24), SafePadding(0, 0, 0, 320),
        ))
    }

    @Test fun preservesInitialPaddingAndLandscapeNavigationBar() {
        assertEquals(SafePadding(8, 32, 56, 8), safeWindowPadding(
            SafePadding(8, 8, 8, 8), SafePadding(0, 24, 48, 0), zero,
        ))
    }

    @Test fun repeatedDispatchAndKeyboardDismissalDoNotAccumulatePadding() {
        val bars = SafePadding(0, 24, 0, 24)
        val keyboard = SafePadding(0, 0, 0, 320)
        repeat(3) {
            assertEquals(SafePadding(0, 24, 0, 320), safeWindowPadding(zero, bars, keyboard))
        }
        assertEquals(bars, safeWindowPadding(zero, bars, zero))
        assertEquals(zero, safeWindowPadding(zero, zero, zero))
    }
}
