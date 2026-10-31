package cloud.einsatzleiter.smsgatewayplugin

/** Pure transformations shared by the file log and JVM tests. */
internal object NativeLogLogic {
    private val bearer = Regex("(Bearer\\s+)[^\\s\"'<>]+", RegexOption.IGNORE_CASE)
    private val queryToken = Regex(
        "([?&](?:token|fcm_token|gateway_token|pin_device_token)=)[^&\\s#\"'<>]*",
        RegexOption.IGNORE_CASE,
    )
    private val lineBreaks = Regex("[\\r\\n\\u0085\\u2028\\u2029]+")

    fun line(timestamp: String, message: String): String {
        val redacted = queryToken.replace(bearer.replace(message) { "${it.groupValues[1]}***" }) {
            "${it.groupValues[1]}***"
        }
        return "$timestamp ${lineBreaks.replace(redacted, " ")}".take(500)
    }

    /** Keep only complete lines within the requested suffix, including exact boundaries. */
    fun tail(text: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        if (text.length <= maxChars) return text
        val start = text.length - maxChars
        if (text[start - 1] == '\n') return text.substring(start)
        val nextLine = text.indexOf('\n', start)
        return if (nextLine < 0) "" else text.substring(nextLine + 1)
    }

    fun trim(bytes: ByteArray, maxBytes: Int): ByteArray {
        if (maxBytes <= 0) return byteArrayOf()
        if (bytes.size <= maxBytes) return bytes
        var start = bytes.size - maxBytes
        if (bytes[start - 1] != '\n'.code.toByte()) {
            while (start < bytes.size && bytes[start] != '\n'.code.toByte()) start++
            if (start < bytes.size) start++
        }
        return bytes.copyOfRange(start, bytes.size)
    }
}
