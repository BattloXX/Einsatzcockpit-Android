package cloud.einsatzleiter.smsgatewayplugin

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Durable receipt and send-intent boundary, scoped to the paired cloud/token. */
class SmsDispatchJournal(
    context: Context, private val currentScope: () -> String,
    private val clock: () -> Long = System::currentTimeMillis
) : SQLiteOpenHelper(context, "sms-dispatch-v2.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE sms_jobs (
            scope TEXT NOT NULL, job_id TEXT NOT NULL, generation INTEGER NOT NULL,
            wire_id TEXT NOT NULL, payload_hash TEXT NOT NULL, recipient TEXT, body TEXT,
            phase TEXT NOT NULL, attempted INTEGER NOT NULL DEFAULT 0, attempted_at INTEGER,
            parts INTEGER NOT NULL DEFAULT 0, results TEXT NOT NULL DEFAULT '{}',
            error TEXT, result_acked INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL, PRIMARY KEY(scope, job_id))""")
        db.execSQL("CREATE INDEX sms_jobs_retention ON sms_jobs(scope, result_acked, created_at)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE sms_jobs ADD COLUMN attempted_at INTEGER")
            db.execSQL("CREATE INDEX sms_jobs_retention ON sms_jobs(scope, result_acked, created_at)")
        }
    }
    companion object {
        fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        fun scope(url: String, token: String) = digest(url.trimEnd('/') + "\u0000" + token)
    }
    private fun record(cursor: Cursor): JSONObject = JSONObject().apply {
        for (i in 0 until cursor.columnCount) when (cursor.getType(i)) {
            Cursor.FIELD_TYPE_NULL -> put(cursor.getColumnName(i), JSONObject.NULL)
            Cursor.FIELD_TYPE_INTEGER -> put(cursor.getColumnName(i), cursor.getLong(i))
            else -> put(cursor.getColumnName(i), cursor.getString(i))
        }
    }
    @Synchronized fun get(scope: String, jobId: String): JSONObject? = readableDatabase.rawQuery(
        "SELECT * FROM sms_jobs WHERE scope=? AND job_id=?", arrayOf(scope, jobId)
    ).use { if (it.moveToFirst()) record(it) else null }

    @Synchronized fun prepare(scope: String, message: JSONObject): JSONObject? {
        val wire = message.optString("id")
        val jobId = message.optString("job_id", wire)
        val generation = message.optInt("generation", 1)
        val recipient = message.optString("to")
        val body = message.optString("text")
        if (wire.isBlank() || jobId.isBlank() || generation <= 0 || recipient.isBlank()) return null
        val hash = digest(recipient + "\u0000" + body)
        val previous = get(scope, jobId)
        if (previous != null) {
            if (previous.getInt("generation") > generation) return null
            val unsafe = previous.getInt("attempted") != 0 || previous.getString("payload_hash") != hash
            if ((previous.getInt("generation") < generation && unsafe) || previous.getString("payload_hash") != hash) {
                return JSONObject().put("job_id", jobId).put("generation", generation).put("wire_id", wire)
                    .put("phase", "uncertain").put("attempted", 1).put("error", "prior_attempt_or_payload_conflict")
            }
            if (previous.getInt("generation") == generation) return previous
        }
        writableDatabase.insertWithOnConflict("sms_jobs", null, ContentValues().apply {
            put("scope", scope); put("job_id", jobId); put("generation", generation); put("wire_id", wire)
            put("payload_hash", hash); put("recipient", recipient); put("body", body); put("phase", "received")
            put("attempted", 0); put("results", "{}"); put("result_acked", 0); put("parts", 0)
            put("created_at", clock())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        return get(scope, jobId)
    }
    @Synchronized fun beginAttempt(scope: String, jobId: String, generation: Int): JSONObject? {
        val changed = writableDatabase.update("sms_jobs", ContentValues().apply {
            put("phase", "attempting"); put("attempted", 1); put("attempted_at", clock())
        }, "scope=? AND job_id=? AND generation=? AND phase='received'", arrayOf(scope, jobId, generation.toString()))
        return if (changed == 1) get(scope, jobId) else null
    }
    @Synchronized fun reject(scope: String, jobId: String, generation: Int, reason: String) {
        writableDatabase.update("sms_jobs", ContentValues().apply {
            put("phase", "failed"); put("error", reason); putNull("recipient"); putNull("body")
        }, "scope=? AND job_id=? AND generation=? AND phase='received'", arrayOf(scope, jobId, generation.toString()))
    }
    @Synchronized fun uncertain(scope: String, jobId: String, generation: Int, reason: String) {
        writableDatabase.update("sms_jobs", ContentValues().apply {
            put("phase", "uncertain"); put("error", reason); putNull("recipient"); putNull("body")
        }, "scope=? AND job_id=? AND generation=? AND phase='attempting'", arrayOf(scope, jobId, generation.toString()))
    }
    @Synchronized fun part(scope: String, jobId: String, generation: Int, index: Int, total: Int, ok: Boolean) {
        val row = get(scope, jobId) ?: return
        if (row.getInt("generation") != generation || row.getString("phase") != "attempting" ||
            index < 0 || index >= total || total < 1) return
        val results = JSONObject(row.getString("results"))
        if (results.has(index.toString())) return
        if (row.getInt("parts") != 0 && row.getInt("parts") != total) return
        results.put(index.toString(), ok)
        val values = ContentValues().apply { put("results", results.toString()); put("parts", total) }
        if (results.length() == total) {
            val accepted = (0 until total).all { results.optBoolean(it.toString(), false) }
            values.put("phase", if (accepted) "sent" else "uncertain")
            if (!accepted) values.put("error", "multipart_result_unknown")
            values.putNull("recipient"); values.putNull("body")
        }
        writableDatabase.update("sms_jobs", values, "scope=? AND job_id=? AND generation=? AND phase='attempting'",
            arrayOf(scope, jobId, generation.toString()))
    }
    @Synchronized fun pending(scope: String): List<JSONObject> = readableDatabase.rawQuery(
        "SELECT * FROM sms_jobs WHERE scope=? AND result_acked=0 ORDER BY created_at LIMIT 256", arrayOf(scope)
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(record(cursor)) } }
    @Synchronized fun acknowledge(scope: String, wire: String) {
        writableDatabase.update("sms_jobs", ContentValues().apply { put("result_acked", 1) },
            "scope=? AND wire_id=? AND phase IN ('sent','failed','uncertain')", arrayOf(scope, wire))
    }
    @Synchronized fun purge(now: Long) {
        writableDatabase.delete("sms_jobs",
            "(result_acked=1 AND phase IN ('sent','failed','uncertain') AND created_at<?) OR (scope<>? AND created_at<?)",
            arrayOf((now - 7 * 24 * 60 * 60_000L).toString(), currentScope(), (now - 30 * 24 * 60 * 60_000L).toString()))
    }
    @Synchronized fun expireStaleAttempts(now: Long, maxAgeMs: Long = 5 * 60_000L) {
        writableDatabase.update("sms_jobs", ContentValues().apply {
            put("phase", "uncertain"); put("error", "multipart_callback_timeout")
            putNull("recipient"); putNull("body")
        }, "phase='attempting' AND attempted_at<?", arrayOf((now - maxAgeMs).toString()))
    }
    @Synchronized fun recover() {
        writableDatabase.update("sms_jobs", ContentValues().apply {
            put("phase", "uncertain"); put("error", "restart_after_send_intent")
            putNull("recipient"); putNull("body")
        }, "phase='attempting'", emptyArray())
    }
}

class SmsDispatchProtocol(
    private val journal: SmsDispatchJournal, private val modem: Modem,
    private val emit: (String, JSONObject) -> Unit
) {
    interface Modem {
        fun preflight(recipient: String, body: String): String?
        fun submit(recipient: String, body: String, callback: (Int, Int, Boolean) -> Unit)
    }
    private fun envelope(row: JSONObject, type: String) = JSONObject().put("type", type)
        .put("id", row.getString("wire_id")).put("job_id", row.getString("job_id"))
        .put("generation", row.getInt("generation"))
    fun report(scope: String, row: JSONObject) {
        val phase = row.getString("phase")
        if (phase == "received" || phase == "attempting") emit(scope, envelope(row, "sms.ack"))
        if (row.optInt("attempted") != 0) emit(scope, envelope(row, "sms.attempt"))
        if (phase in listOf("sent", "failed", "uncertain")) emit(scope, envelope(row, "sms.result")
            .put("ok", phase == "sent").put("outcome", phase).put("provider_accepted", phase == "sent")
            .put("delivery_verified", false).put("attempted", row.optInt("attempted") != 0)
            .put("not_sent", phase == "failed" && row.optInt("attempted") == 0)
            .put("error", row.optString("error", "")))
        // Legacy-Cloud (sms.send ohne job_id) sendet nie sms.result.ack: einmal melden genügt.
        if (phase in listOf("sent", "failed", "uncertain") && row.getString("job_id") == row.getString("wire_id"))
            journal.acknowledge(scope, row.getString("wire_id"))
    }
    fun pump(scope: String) = journal.pending(scope).forEach { report(scope, it) }
    fun hello(scope: String) = JSONObject().put("type", "hello").put("role", "sms-gateway")
        .put("version", "2.0").put("capabilities", JSONArray().put("sms-transaction-v2"))
        .put("open_jobs", JSONArray().apply {
            journal.pending(scope).forEach { put(envelope(it, "resume").put("state", it.getString("phase"))) }
        })
    fun handle(scope: String, message: JSONObject) {
        when (message.optString("type")) {
            "sms.prepare", "sms.send" -> {
                val row = journal.prepare(scope, message) ?: return
                report(scope, row)
                if (message.getString("type") == "sms.send" && row.getString("phase") == "received")
                    commit(scope, row.getString("job_id"), row.getInt("generation"))
            }
            "sms.commit" -> {
                val job = message.optString("job_id")
                val generation = message.optInt("generation")
                val row = journal.get(scope, job) ?: return
                if (row.getInt("generation") == generation && row.getString("wire_id") == message.optString("id"))
                    commit(scope, job, generation)
            }
            "sms.result.ack" -> journal.acknowledge(scope, message.optString("id"))
            "sms.abandon" -> journal.pending(scope).firstOrNull { it.getString("wire_id") == message.optString("id") }?.let {
                journal.reject(scope, it.getString("job_id"), it.getInt("generation"), "cloud_abandoned")
            }
        }
    }
    private fun commit(scope: String, job: String, generation: Int) {
        val current = journal.get(scope, job) ?: return
        if (current.getString("phase") != "received") { report(scope, current); return }
        val error = modem.preflight(current.getString("recipient"), current.getString("body"))
        if (error != null) {
            journal.reject(scope, job, generation, error)
            journal.get(scope, job)?.let { report(scope, it) }
            return
        }
        val row = journal.beginAttempt(scope, job, generation) ?: return
        report(scope, row)
        try {
            modem.submit(row.getString("recipient"), row.getString("body")) { part, total, accepted ->
                journal.part(scope, job, generation, part, total, accepted)
                journal.get(scope, job)?.let { report(scope, it) }
            }
        } catch (_: Exception) {
            journal.uncertain(scope, job, generation, "exception_after_send_intent")
            journal.get(scope, job)?.let { report(scope, it) }
        }
    }
}
