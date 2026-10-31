package cloud.einsatzleiter.smsgatewayplugin

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Best-effort, process-local locking for a bounded persistent diagnostic log. */
internal object NativeLog {
    private val lock = Any()
    private var appContext: Context? = null
    private const val MAX_BYTES = 256 * 1024
    private const val RETAIN_BYTES = 128 * 1024

    fun init(context: Context) {
        synchronized(lock) {
            if (appContext == null) appContext = context.applicationContext
        }
    }

    fun append(message: String) {
        synchronized(lock) {
            try {
                val file = logFile() ?: return
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date())
                file.appendText(NativeLogLogic.line(timestamp, message) + "\n", Charsets.UTF_8)
                if (file.length() > MAX_BYTES) {
                    file.writeBytes(NativeLogLogic.trim(file.readBytes(), RETAIN_BYTES))
                }
            } catch (_: Exception) {
                // Diagnostics must never prevent the caller from continuing.
            }
        }
    }

    fun read(maxChars: Int = 60_000): String = synchronized(lock) {
        try {
            val file = logFile()
            if (file == null || !file.exists()) ""
            else NativeLogLogic.tail(file.readText(Charsets.UTF_8), maxChars)
        } catch (_: Exception) {
            ""
        }
    }

    fun clear() {
        synchronized(lock) {
            try {
                logFile()?.writeText("")
            } catch (_: Exception) {
                // Best effort, just like append.
            }
        }
    }

    private fun logFile(): File? = appContext?.let { File(it.filesDir, "native-log.txt") }
}
