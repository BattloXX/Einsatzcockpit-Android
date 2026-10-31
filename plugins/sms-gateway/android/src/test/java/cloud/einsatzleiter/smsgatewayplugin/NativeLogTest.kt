package cloud.einsatzleiter.smsgatewayplugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeLogTest {
    private val timestamp = "2026-10-31 12:34:56.789"

    @Test
    fun redactsBearerCaseInsensitively() {
        assertEquals("$timestamp Authorization: Bearer *** bearer ***",
            NativeLogLogic.line(timestamp, "Authorization: Bearer secret bearer OTHER"))
    }

    @Test
    fun redactsAllQueryTokensAndPreservesOtherParameters() {
        val message = "https://example.org/?token=one&FCM_TOKEN=two&gateway_token=three&Pin_Device_Token=four&ok=5#end"
        assertEquals("$timestamp https://example.org/?token=***&FCM_TOKEN=***&gateway_token=***&Pin_Device_Token=***&ok=5#end",
            NativeLogLogic.line(timestamp, message))
    }

    @Test
    fun doesNotRedactUnrelatedParameterNames() {
        assertEquals("$timestamp ?other_token=keep&token=***",
            NativeLogLogic.line(timestamp, "?other_token=keep&token=hide"))
    }

    @Test
    fun collapsesLineBreaksBeforeWriting() {
        assertEquals("$timestamp first second third fourth fifth sixth",
            NativeLogLogic.line(timestamp, "first\r\nsecond\nthird\rfourth\u2028fifth\u2029sixth"))
    }

    @Test
    fun limitsWholeLineTo500CharactersAfterRedaction() {
        val line = NativeLogLogic.line(timestamp, "Bearer secret " + "x".repeat(600))
        assertEquals(500, line.length)
        assertTrue(line.startsWith("$timestamp Bearer *** "))
    }

    @Test
    fun tailStartsAtNextCompleteLine() {
        val text = "first\nsecond\nthird\n"
        assertEquals("third\n", NativeLogLogic.tail(text, 10))
        assertEquals("second\nthird\n", NativeLogLogic.tail(text, 13))
        assertEquals(text, NativeLogLogic.tail(text, 100))
        assertEquals("", NativeLogLogic.tail(text, 0))
        assertEquals("", NativeLogLogic.tail(text, -1))
        assertEquals("", NativeLogLogic.tail("oversized", 4))
        assertEquals("", NativeLogLogic.tail(text, 1))
    }

    @Test
    fun rotationUsesByteLimitAndCompleteUtf8Lines() {
        val bytes = "old\näää\nnew\n".toByteArray(Charsets.UTF_8)
        assertEquals("new\n", NativeLogLogic.trim(bytes, 9).toString(Charsets.UTF_8))
        assertEquals("äää\nnew\n", NativeLogLogic.trim(bytes, 11).toString(Charsets.UTF_8))
        assertEquals(bytes.toList(), NativeLogLogic.trim(bytes, 100).toList())
        assertEquals(0, NativeLogLogic.trim(bytes, 0).size)
        assertEquals(0, NativeLogLogic.trim(bytes, 1).size)
    }
}
