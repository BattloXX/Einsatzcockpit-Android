package cloud.einsatzleiter.smsgatewayplugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmDedupeTest {
    private val now = 4_000_000L
    private val window = 30 * 60 * 1000L

    @Test
    fun firstAlarmIsRecordedAndDuplicateDoesNotExtendWindow() {
        val first = AlarmDedupe.check("", 42, now)
        assertTrue(first.shouldAlarm)
        assertEquals("42:$now", first.seen)
        val duplicate = AlarmDedupe.check(first.seen, 42, now + window - 1)
        assertFalse(duplicate.shouldAlarm)
        assertEquals(first.seen, duplicate.seen)
        assertTrue(AlarmDedupe.check(duplicate.seen, 42, now + window).shouldAlarm)
    }

    @Test
    fun otherIncidentsAreIndependentAndExpiredEntriesArePruned() {
        val decision = AlarmDedupe.check("41:$now,40:${now - window}", 42, now)
        assertTrue(decision.shouldAlarm)
        assertEquals("41:$now,42:$now", decision.seen)
    }

    @Test
    fun testsAlwaysAlarmAndDoNotChangeHistory() {
        val seen = "42:$now"
        repeat(2) {
            val decision = AlarmDedupe.check(seen, 42, now, isTest = true)
            assertTrue(decision.shouldAlarm)
            assertEquals(seen, decision.seen)
        }
    }

    @Test
    fun malformedAndFutureEntriesDoNotSuppressAlarm() {
        val decision = AlarmDedupe.check("ungueltig,42:?,42:${now + 1},:12,0:$now,42:$now:extra", 42, now)
        assertTrue(decision.shouldAlarm)
        assertEquals("42:$now", decision.seen)
    }

    @Test
    fun missingIncidentIdDoesNotDeduplicateUnrelatedAlarms() {
        val first = AlarmDedupe.check("", 0, now)
        assertTrue(first.shouldAlarm)
        assertEquals("", first.seen)
        assertTrue(AlarmDedupe.check(first.seen, 0, now).shouldAlarm)
    }
}
