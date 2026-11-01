package cloud.einsatzleiter.smsgatewayplugin

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class SmsGatewayMappingTest {
    @Test
    fun receivedPayloadCopiesAllFieldsWithoutChangingQueuedEntry() {
        val entry = JSONObject("""{"id":"a","from":"+43123","text":"Zeile\n\"zwei\"","sent_at":123,"extra":true}""")
        val original = entry.toString()
        val payload = SmsGatewayLogic.inboundPayload(entry)
        assertEquals(original, entry.toString())
        assertEquals("sms.received", payload.remove("type"))
        assertEquals(original, payload.toString())
    }

    @Test
    fun inboundEntryRetainsSchemaAndGeneratesIdAndTimestamp() {
        val before = System.currentTimeMillis()
        val entry = SmsGatewayLogic.inboundEntry("+43123", "\"Hallo\"\nWelt")
        assertEquals(4, entry.length())
        assertEquals("+43123", entry.getString("from"))
        assertEquals("\"Hallo\"\nWelt", entry.getString("text"))
        UUID.fromString(entry.getString("id"))
        assertTrue(entry.getLong("sent_at") in before..System.currentTimeMillis())
    }
}
