package cloud.einsatzleiter.smsgatewayplugin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class InboundQueueTest {
    private fun entry(id: String) = JSONObject().put("id", id).put("text", "SMS $id")

    @Test
    fun enqueuePreservesOrderAndPayloadAcrossSerialization() {
        val first = InboundQueue.enqueue(JSONArray(), entry("a"))
        val queue = InboundQueue.enqueue(JSONArray(first.toString()), entry("b"))
        assertEquals(2, queue.length())
        assertEquals("a", queue.getJSONObject(0).getString("id"))
        assertEquals("SMS a", queue.getJSONObject(0).getString("text"))
        assertEquals("b", queue.getJSONObject(1).getString("id"))
    }

    @Test
    fun limitKeepsNewest200InOrder() {
        var queue = JSONArray()
        repeat(200) { queue = InboundQueue.enqueue(queue, entry(it.toString())) }
        assertEquals(200, queue.length())
        assertEquals("0", queue.getJSONObject(0).getString("id"))
        repeat(5) { queue = InboundQueue.enqueue(queue, entry((200 + it).toString())) }
        assertEquals(200, queue.length())
        repeat(200) { assertEquals((it + 5).toString(), queue.getJSONObject(it).getString("id")) }
    }

    @Test
    fun removeMatchesIdAndPreservesRemainingOrder() {
        val queue = JSONArray().put(entry("a")).put(entry("b")).put(entry("a")).put(entry("c"))
        val kept = InboundQueue.remove(queue, "a")
        assertEquals(2, kept.length())
        assertEquals("b", kept.getJSONObject(0).getString("id"))
        assertEquals("c", kept.getJSONObject(1).getString("id"))
        assertEquals(kept.toString(), InboundQueue.remove(kept, "missing").toString())
    }

    @Test
    fun removeHandlesEmptyQueueAndLastEntry() {
        assertEquals(0, InboundQueue.remove(JSONArray(), "a").length())
        assertEquals(0, InboundQueue.remove(JSONArray().put(entry("a")), "a").length())
    }
}
