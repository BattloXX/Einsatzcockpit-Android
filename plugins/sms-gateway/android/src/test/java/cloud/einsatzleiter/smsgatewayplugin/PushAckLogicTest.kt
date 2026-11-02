package cloud.einsatzleiter.smsgatewayplugin

import org.junit.Assert.assertEquals
import org.junit.Test

class PushAckLogicTest {
    @Test fun `2xx succeeds including the last attempt`() {
        for (code in 200..299) {
            assertEquals(PushAckLogic.Outcome.SUCCESS, PushAckLogic.outcome(code, 1))
            assertEquals(PushAckLogic.Outcome.SUCCESS, PushAckLogic.outcome(code, 5))
        }
    }

    @Test fun `network errors 429 and 5xx retry within the budget`() {
        for (code in listOf(null, 429, 500, 502, 503, 599)) {
            for (attempt in 1..4) assertEquals(PushAckLogic.Outcome.RETRY, PushAckLogic.outcome(code, attempt))
            assertEquals(PushAckLogic.Outcome.FAILURE, PushAckLogic.outcome(code, 5))
            assertEquals(PushAckLogic.Outcome.FAILURE, PushAckLogic.outcome(code, 6))
        }
    }

    @Test fun `other 4xx and redirects do not retry`() {
        for (code in (400..499).filter { it != 429 } + listOf(301, 302, 307)) {
            assertEquals(PushAckLogic.Outcome.FAILURE, PushAckLogic.outcome(code, 1))
        }
    }
}
