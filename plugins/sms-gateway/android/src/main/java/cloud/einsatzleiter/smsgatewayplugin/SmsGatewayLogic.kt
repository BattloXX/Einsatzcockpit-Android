package cloud.einsatzleiter.smsgatewayplugin

import org.json.JSONObject
import java.util.UUID

/** Zustandsfreie Abbildungen; Reihenfolge und Zeichenketten bleiben unverändert. */
internal object SmsGatewayLogic {
    fun nextReconnectDelay(delay: Long): Long = minOf(delay * 2, 30_000L)

    fun toWsUrl(url: String): String = url
        .replace("https://", "wss://")
        .replace("http://", "ws://")
        .trimEnd('/')
        .plus("/ws/sms-gateway")

    fun buildSmsResult(jobId: String, ok: Boolean, error: String?): String =
        if (ok) """{"type":"sms.result","id":"$jobId","ok":true,"provider_response":"OK"}"""
        else    """{"type":"sms.result","id":"$jobId","ok":false,"error":"${error?.replace("\"","\\\"")}"}"""

    fun maskNumber(number: String): String {
        val digits = number.filter { it.isDigit() }
        return if (digits.length >= 6) "*".repeat(number.length - 4) + number.takeLast(4)
               else "****"
    }

    fun inboundEntry(from: String, text: String): JSONObject = JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("from", from)
            put("text", text)
            put("sent_at", System.currentTimeMillis())
        }

    fun inboundPayload(entry: JSONObject): JSONObject =
        JSONObject(entry.toString()).apply { put("type", "sms.received") }
}
