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

    fun error(context: Context, component: String, code: String, text: String, severity: String = "error", schedule: Boolean = true) {
        init(context)
        synchronized(lock) {
            runCatching {
                val previous = errors(context).toMutableList()
                val masked = DiagnoseReportLogic.mask(text)
                val index = previous.indexOfFirst { it.component == component && it.code == code && it.text == masked && it.severity == severity }
                val count = if (index >= 0) previous.removeAt(index).count.toLong().plus(1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 1
                previous.add(DiagnoseReportLogic.Error(DiagnoseReportLogic.iso(System.currentTimeMillis()), component.take(40), code.take(60), masked, count, severity.take(20)))
                val json = org.json.JSONArray()
                previous.takeLast(50).forEach { e -> json.put(org.json.JSONObject().put("ts", e.ts).put("component", e.component).put("code", e.code).put("text", e.text).put("count", e.count).put("severity", e.severity)) }
                ReportSender.prefs(context).edit().putString("errors", json.toString()).apply()
                append("[$severity][$component/$code] $masked")
            }
        }
        if (schedule) ReportSender.schedule(context, "error")
    }

    fun errors(context: Context): List<DiagnoseReportLogic.Error> = synchronized(lock) {
        runCatching {
            val array = org.json.JSONArray(ReportSender.prefs(context).getString("errors", "[]"))
            (0 until array.length()).map { i -> val e = array.getJSONObject(i)
                DiagnoseReportLogic.Error(e.getString("ts"), e.getString("component"), e.getString("code"), e.getString("text"), e.getInt("count"), e.optString("severity", "error")) }
        }.getOrDefault(emptyList())
    }

    fun clear() {
        synchronized(lock) {
            try {
                logFile()?.writeText("")
                appContext?.let { ReportSender.prefs(it).edit().remove("errors").apply() }
            } catch (_: Exception) {
                // Best effort, just like append.
            }
        }
    }

    private fun logFile(): File? = appContext?.let { File(it.filesDir, "native-log.txt") }
}
