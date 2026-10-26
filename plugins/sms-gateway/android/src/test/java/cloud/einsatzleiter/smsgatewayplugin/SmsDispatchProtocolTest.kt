package cloud.einsatzleiter.smsgatewayplugin

import android.content.Context
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
        journal = SmsDispatchJournal(context)
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
        journal = SmsDispatchJournal(context)
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
        journal = SmsDispatchJournal(context)
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
}
