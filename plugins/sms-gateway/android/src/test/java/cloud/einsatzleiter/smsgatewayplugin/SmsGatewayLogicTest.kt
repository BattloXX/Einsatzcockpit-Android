package cloud.einsatzleiter.smsgatewayplugin

import org.junit.Assert.assertEquals
import org.junit.Test

class SmsGatewayLogicTest {
    @Test
    fun reconnectSequenceKeepsThirtySecondCap() {
        var delay = 1000L
        val sequence = List(7) { delay.also { delay = SmsGatewayLogic.nextReconnectDelay(delay) } }
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L), sequence)
    }

    @Test
    fun urlConversionPreservesLiteralReplacementAndTrailingSlashRules() {
        assertEquals("wss://example.org/ws/sms-gateway", SmsGatewayLogic.toWsUrl("https://example.org///"))
        assertEquals("ws://example.org/path/ws/sms-gateway", SmsGatewayLogic.toWsUrl("http://example.org/path/"))
        assertEquals("wss://host/ws://path/ws/sms-gateway", SmsGatewayLogic.toWsUrl("https://host/http://path"))
    }

    @Test
    fun maskingCountsDigitsButKeepsOriginalLastFourCharacters() {
        assertEquals("****", SmsGatewayLogic.maskNumber("12345"))
        assertEquals("**3456", SmsGatewayLogic.maskNumber("123456"))
        assertEquals("******6 78", SmsGatewayLogic.maskNumber("+43 126 78"))
        assertEquals("****", SmsGatewayLogic.maskNumber(""))
    }

    @Test
    fun resultRetainsExactWireTextIncludingLegacyNullAndQuoteEscaping() {
        assertEquals("""{"type":"sms.result","id":"a","ok":true,"provider_response":"OK"}""",
            SmsGatewayLogic.buildSmsResult("a", true, "ignored"))
        assertEquals("""{"type":"sms.result","id":"a","ok":false,"error":"null"}""",
            SmsGatewayLogic.buildSmsResult("a", false, null))
        assertEquals("""{"type":"sms.result","id":"a","ok":false,"error":"say \"no\""}""",
            SmsGatewayLogic.buildSmsResult("a", false, "say \"no\""))
    }
}
