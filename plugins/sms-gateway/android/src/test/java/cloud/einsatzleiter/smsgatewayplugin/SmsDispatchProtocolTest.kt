package cloud.einsatzleiter.smsgatewayplugin

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SmsDispatchProtocolTest {
    private lateinit var context: Context
    private lateinit var journal: SmsDispatchJournal
    private lateinit var protocol: SmsDispatchProtocol
    private val reports = mutableListOf<Pair<String, JSONObject>>()
    private var now = 40 * 24 * 60 * 60_000L
    private var submissions = 0
    private var reject: String? = null
    private var callback: ((Int, Int, Boolean) -> Unit)? = null
    private val scope = SmsDispatchJournal.scope("https://cloud.test", "org-a-token")
    private fun message(type: String, generation: Int = 1) = JSONObject()
        .put("type", type).put("job_id", "job").put("generation", generation)
        .put("id", "job:$generation").put("to", "+430000").put("text", "Testalarm")
    private fun install() {
        protocol = SmsDispatchProtocol(journal, object : SmsDispatchProtocol.Modem {
            override fun preflight(recipient: String, body: String) = reject
            override fun submit(recipient: String, body: String, cb: (Int, Int, Boolean) -> Unit) {
                submissions++
                callback = cb
            }
        }) { namespace, body -> reports.add(namespace to body) }
    }
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("sms-dispatch-v2.db")
        journal = SmsDispatchJournal(context, { scope }, { now })
        install()
    }
    @After fun cleanup() { journal.close() }

    @Test fun receiptRequiresCommitAndDuplicateMessagesSubmitOnlyOnce() {
        protocol.handle(scope, message("sms.prepare"))
        protocol.handle(scope, message("sms.prepare"))
        assertEquals(0, submissions)
        assertEquals("sms.ack", reports.last().second.getString("type"))
        repeat(3) { protocol.handle(scope, message("sms.commit")) }
        assertEquals(1, submissions)
        callback!!(0, 1, true)
        val result = reports.last().second
        assertTrue(result.getBoolean("provider_accepted"))
        assertFalse(result.getBoolean("delivery_verified"))
        protocol.handle(scope, message("sms.commit"))
        assertEquals(1, submissions)
    }
    @Test fun restartAfterIntentIsUncertainAndCannotSendAgain() {
        protocol.handle(scope, message("sms.prepare"))
        protocol.handle(scope, message("sms.commit"))
        journal.close()
        journal = SmsDispatchJournal(context, { scope }, { now })
        journal.recover()
        install()
        protocol.handle(scope, message("sms.commit"))
        protocol.handle(scope, message("sms.prepare", 2))
        assertEquals(1, submissions)
        assertEquals("uncertain", reports.last().second.getString("outcome"))
    }
    @Test fun reconnectBeforeCommitResumesPersistedReceipt() {
        protocol.handle(scope, message("sms.prepare"))
        journal.close()
        journal = SmsDispatchJournal(context, { scope }, { now })
        journal.recover()
        install()
        assertEquals("received", protocol.hello(scope).getJSONArray("open_jobs").getJSONObject(0).getString("state"))
        protocol.handle(scope, message("sms.commit"))
        assertEquals(1, submissions)
    }
    @Test fun preflightFailureIsProvenNotSent() {
        reject = "permission_missing"
        protocol.handle(scope, message("sms.prepare"))
        protocol.handle(scope, message("sms.commit"))
        val result = reports.last().second
        assertEquals(0, submissions)
        assertTrue(result.getBoolean("not_sent"))
        assertFalse(result.getBoolean("attempted"))
    }
    @Test fun multipartDuplicatesCannotHideMissingOrFailedPart() {
        protocol.handle(scope, message("sms.prepare"))
        protocol.handle(scope, message("sms.commit"))
        callback!!(0, 2, true)
        callback!!(0, 2, true)
        assertEquals("attempting", journal.get(scope, "job")!!.getString("phase"))
        callback!!(1, 2, false)
        assertEquals("uncertain", reports.last().second.getString("outcome"))
        callback!!(1, 2, true)
        assertEquals("uncertain", journal.get(scope, "job")!!.getString("phase"))
    }
    @Test fun pairedTokenNamespacesAndAcknowledgedResultReplay() {
        protocol.handle(scope, message("sms.send"))
        callback!!(0, 1, true)
        reports.clear()
        protocol.pump(scope)
        assertEquals("sms.result", reports.last().second.getString("type"))
        protocol.handle(scope, message("sms.result.ack"))
        assertTrue(journal.pending(scope).isEmpty())
        assertTrue(protocol.hello(SmsDispatchJournal.scope("https://cloud.test", "org-b-token"))
            .getJSONArray("open_jobs").length() == 0)
        protocol.handle(scope, message("sms.send"))
        assertEquals(1, submissions)
    }
    @Test fun oldGenerationAndChangedPayloadCannotTriggerSecondSend() {
        protocol.handle(scope, message("sms.prepare", 2))
        protocol.handle(scope, message("sms.commit", 1))
        assertEquals(0, submissions)
        protocol.handle(scope, message("sms.prepare", 2).put("text", "different"))
        assertEquals("uncertain", reports.last().second.getString("outcome"))
        assertEquals(0, submissions)
    }
    @Test fun purgeKeepsUnacknowledgedCurrentJobsAndRecentOrNonterminalRows() {
        val day = 24 * 60 * 60_000L
        val purgeAt = now
        val foreign = SmsDispatchJournal.scope("https://cloud.test", "old-token")
        fun add(namespace: String, id: String, age: Long, phase: String, acked: Boolean) {
            now = purgeAt - age
            journal.prepare(namespace, message("sms.prepare").put("job_id", id).put("id", id))
            when (phase) {
                "failed" -> journal.reject(namespace, id, 1, "preflight_failed")
                "attempting", "sent", "uncertain" -> {
                    journal.beginAttempt(namespace, id, 1)
                    if (phase == "sent") journal.part(namespace, id, 1, 0, 1, true)
                    if (phase == "uncertain") journal.uncertain(namespace, id, 1, "unknown")
                }
            }
            if (acked) journal.acknowledge(namespace, id)
        }
        for (phase in listOf("sent", "failed", "uncertain")) {
            add(scope, "$phase-old-acked", 8 * day, phase, true)
            add(scope, "$phase-boundary", 7 * day, phase, true)
            add(scope, "$phase-recent", day, phase, true)
            add(scope, "$phase-unacked", 31 * day, phase, false)
        }
        for (phase in listOf("received", "attempting")) {
            add(scope, phase, 31 * day, phase, false)
        }
        add(foreign, "foreign-old", 31 * day, "received", false)
        add(foreign, "foreign-old-attempt", 31 * day, "attempting", false)
        add(foreign, "foreign-boundary", 30 * day, "received", false)
        add(foreign, "foreign-recent", 29 * day, "uncertain", false)
        now = purgeAt
        journal.purge(now)
        for (phase in listOf("sent", "failed", "uncertain")) {
            assertNull(journal.get(scope, "$phase-old-acked"))
            assertNotNull(journal.get(scope, "$phase-boundary"))
            assertNotNull(journal.get(scope, "$phase-recent"))
            assertNotNull(journal.get(scope, "$phase-unacked"))
        }
        assertNotNull(journal.get(scope, "received"))
        assertNotNull(journal.get(scope, "attempting"))
        assertNull(journal.get(foreign, "foreign-old"))
        assertNull(journal.get(foreign, "foreign-old-attempt"))
        assertNotNull(journal.get(foreign, "foreign-boundary"))
        assertNotNull(journal.get(foreign, "foreign-recent"))
    }
    @Test fun staleAttemptReportsUncertainAndLateCallbacksCannotChangeIt() {
        protocol.handle(scope, message("sms.send"))
        val startedAt = now
        assertEquals(startedAt, journal.get(scope, "job")!!.getLong("attempted_at"))
        now += 5 * 60_000L + 1
        reports.clear()
        journal.expireStaleAttempts(now)
        protocol.pump(scope)
        val row = journal.get(scope, "job")!!
        assertEquals("uncertain", row.getString("phase"))
        assertEquals("multipart_callback_timeout", row.getString("error"))
        assertTrue(row.isNull("recipient"))
        assertTrue(row.isNull("body"))
        val result = reports.last().second
        assertEquals("sms.result", result.getString("type"))
        assertEquals("uncertain", result.getString("outcome"))
        assertEquals("multipart_callback_timeout", result.getString("error"))
        assertTrue(result.getBoolean("attempted"))
        callback!!(0, 1, true)
        protocol.handle(scope, message("sms.commit"))
        assertEquals("uncertain", journal.get(scope, "job")!!.getString("phase"))
        assertEquals(1, submissions)
    }
    @Test fun freshAttemptAndExactTimeoutBoundaryStayAttempting() {
        protocol.handle(scope, message("sms.prepare"))
        now += 10 * 60_000L
        protocol.handle(scope, message("sms.commit"))
        journal.expireStaleAttempts(now)
        assertEquals("attempting", journal.get(scope, "job")!!.getString("phase"))
        journal.expireStaleAttempts(now + 5 * 60_000L)
        assertEquals("attempting", journal.get(scope, "job")!!.getString("phase"))
        journal.expireStaleAttempts(now + 1_001, 1_000)
        assertEquals("uncertain", journal.get(scope, "job")!!.getString("phase"))
    }
    @Test fun upgradeFromVersionOnePreservesRowsAndAddsAttemptTimestampAndIndex() {
        journal.close()
        context.deleteDatabase("sms-dispatch-v2.db")
        val legacy = object : SQLiteOpenHelper(context, "sms-dispatch-v2.db", null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("""CREATE TABLE sms_jobs (
                    scope TEXT NOT NULL, job_id TEXT NOT NULL, generation INTEGER NOT NULL,
                    wire_id TEXT NOT NULL, payload_hash TEXT NOT NULL, recipient TEXT, body TEXT,
                    phase TEXT NOT NULL, attempted INTEGER NOT NULL DEFAULT 0,
                    parts INTEGER NOT NULL DEFAULT 0, results TEXT NOT NULL DEFAULT '{}',
                    error TEXT, result_acked INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL, PRIMARY KEY(scope, job_id))""")
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        legacy.writableDatabase.execSQL("""INSERT INTO sms_jobs
            (scope, job_id, generation, wire_id, payload_hash, recipient, body, phase, created_at)
            VALUES (?, 'job', 1, 'job:1', ?, '+430000', 'Testalarm', 'received', ?)""",
            arrayOf(scope, SmsDispatchJournal.digest("+430000\u0000Testalarm"), now))
        legacy.close()
        journal = SmsDispatchJournal(context, { scope }, { now })
        val row = journal.get(scope, "job")!!
        assertEquals(2, journal.readableDatabase.version)
        assertEquals("received", row.getString("phase"))
        assertEquals("Testalarm", row.getString("body"))
        assertEquals(now, row.getLong("created_at"))
        assertTrue(row.has("attempted_at"))
        assertTrue(row.isNull("attempted_at"))
        journal.readableDatabase.rawQuery("PRAGMA index_info(sms_jobs_retention)", null).use { cursor ->
            val columns = buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            assertEquals(listOf("scope", "result_acked", "created_at"), columns)
        }
        install()
        protocol.handle(scope, message("sms.commit"))
        assertEquals(1, submissions)
        assertEquals(now, journal.get(scope, "job")!!.getLong("attempted_at"))
    }
}
