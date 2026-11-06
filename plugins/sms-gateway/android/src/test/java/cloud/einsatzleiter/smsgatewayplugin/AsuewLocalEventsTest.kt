package cloud.einsatzleiter.smsgatewayplugin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AsuewLocalEventsTest {
    private val entry: Long = 1_800_000_000_000L
    private val minute: Long = 60_000L

    private fun troop(): AsuewTroop = AsuewTroop(
        id = 1L, name = "Trupp Alpha", status = "im_einsatz", isSicherheitstrupp = false,
        entryAtMs = entry, plannedDurationMin = 30, oneThirdSeconds = 600L,
        twoThirdSeconds = 1200L, maxSeconds = 1800L, withdrawPressCalc = 60.0,
        notfallAtMs = null, funkVerlorenAtMs = null, lastMeldungAtMs = null,
        lastPressureAtMs = null, maxTimeAcked = false, serverWarnings = emptyList(),
        kontrollen = emptyList(), members = emptyList(),
    )

    private fun state(troop: AsuewTroop = troop()): AsuewState =
        AsuewState(entry, 42L, true, listOf(troop))

    private fun event(kind: String = "kontrolle", stufe: String? = "one_third",
        ackKind: String? = null, at: Long = entry + 11 * minute): AsuewLocalEvent =
        AsuewLocalEvent(1L, kind, stufe, ackKind, at)

    @Test fun controlCompletesWarningsAlarmsAndFutureDeadlineWithoutChangingServerState() {
        val original: AsuewState = state(troop().copy(serverWarnings = listOf("one_third_due", "withdraw")))
        val overlaid: AsuewState = AsuewLogic.applyLocalEvents(original, listOf(event()))
        val updated: AsuewTroop = overlaid.troops.single()
        assertEquals(listOf(AsuewKontrolle("one_third", "funk", entry + 11 * minute, null)), updated.kontrollen)
        assertFalse(AsuewLogic.timeWarnings(updated, entry + 11 * minute).contains("one_third_due"))
        assertEquals(listOf("withdraw"), AsuewLogic.activeAlarms(overlaid, entry + 11 * minute).map { it.kind })
        assertEquals(listOf("two_third_due", "max_exceeded"),
            AsuewLogic.nextDeadlines(overlaid, entry).map { it.kind })
        assertTrue(original.troops.single().kontrollen.isEmpty())
        assertEquals(listOf("one_third_due", "withdraw"), original.troops.single().serverWarnings)
        assertSame(original, AsuewLogic.applyLocalEvents(original, emptyList()))
    }

    @Test fun pressureCompletesOnlyDeadlinesAtOrBeforeItsTimestampAndNeverMovesBackwards() {
        val original: AsuewState = state(troop().copy(serverWarnings = listOf("one_third_due", "two_third_due")))
        for (at: Long in listOf(entry + 10 * minute, entry + 11 * minute)) {
            val overlaid: AsuewState = AsuewLogic.applyLocalEvents(original, listOf(event("druck", null, at = at)))
            assertEquals(listOf("two_third_due"), AsuewLogic.activeAlarms(overlaid, entry + 21 * minute).map { it.kind })
            assertFalse(AsuewLogic.nextDeadlines(overlaid, entry).any { it.kind == "one_third_due" })
        }
        val before: AsuewState = AsuewLogic.applyLocalEvents(original,
            listOf(event("druck", null, at = entry + 10 * minute - 1L)))
        assertTrue(AsuewLogic.timeWarnings(before.troops.single(), entry + 11 * minute).contains("one_third_due"))
        assertTrue(AsuewLogic.activeAlarms(before, entry + 11 * minute).any { it.kind == "one_third_due" })
        assertTrue(AsuewLogic.nextDeadlines(before, entry).any { it.kind == "one_third_due" })
        val newer: AsuewState = state(troop().copy(lastPressureAtMs = entry + 21 * minute))
        assertEquals(entry + 21 * minute, AsuewLogic.applyLocalEvents(newer,
            listOf(event("druck", null))).troops.single().lastPressureAtMs!!)
    }

    @Test fun acknowledgementsCompleteMaxTimeAndRemoveOnlyWithdrawWarning() {
        val original: AsuewState = state(troop().copy(serverWarnings = listOf("max_exceeded", "withdraw", "notfall")))
        val overlaid: AsuewState = AsuewLogic.applyLocalEvents(original, listOf(
            event("ack", null, "max_time"), event("ack", null, "withdraw"),
        ))
        assertTrue(overlaid.troops.single().maxTimeAcked)
        assertEquals(listOf("notfall"), overlaid.troops.single().serverWarnings)
        assertFalse(AsuewLogic.timeWarnings(overlaid.troops.single(), entry + 31 * minute).contains("max_exceeded"))
        assertFalse(AsuewLogic.activeAlarms(overlaid, entry + 31 * minute).any { it.kind in listOf("max_exceeded", "withdraw") })
        assertFalse(AsuewLogic.nextDeadlines(overlaid, entry).any { it.kind == "max_exceeded" })
    }

    @Test fun oldCycleUnknownTroopAndUnknownKindsAreIgnored() {
        val original: AsuewState = state(troop().copy(serverWarnings = listOf("withdraw")))
        val events: List<AsuewLocalEvent> = listOf(
            event(at = entry - 1L), event("druck", null, at = entry - 1L),
            event("ack", null, "max_time", entry - 1L), event("ack", null, "withdraw", entry - 1L),
            event().copy(troopId = 2L), event("unbekannt"), event("ack", null, "unbekannt"),
        )
        assertEquals(original, AsuewLogic.applyLocalEvents(original, events))
        assertEquals(1, AsuewLogic.applyLocalEvents(original, listOf(event(at = entry))).troops.single().kontrollen.size)
    }

    @Test fun applyingTwiceIsIdempotentAndFunkControlsAreNotDuplicated() {
        val events: List<AsuewLocalEvent> = listOf(event(), event(), event("druck", null),
            event("ack", null, "max_time"), event("ack", null, "withdraw"))
        val once: AsuewState = AsuewLogic.applyLocalEvents(state(), events)
        assertEquals(once, AsuewLogic.applyLocalEvents(once, events))
        assertEquals(1, once.troops.single().kontrollen.size)
        val snoozed: AsuewState = state(troop().copy(kontrollen = listOf(
            AsuewKontrolle("one_third", "snooze", entry, null))))
        assertEquals(2, AsuewLogic.applyLocalEvents(snoozed, listOf(event())).troops.single().kontrollen.size)
        assertEquals(once.troops.single().kontrollen,
            AsuewLogic.applyLocalEvents(once, listOf(event(at = entry + 12 * minute))).troops.single().kontrollen)
    }

    @Test fun secondStageAndFreeControlOnlyCompleteTheirOwnStage() {
        val second: AsuewState = AsuewLogic.applyLocalEvents(state(), listOf(event(stufe = "two_third")))
        assertEquals(listOf("one_third_due"), AsuewLogic.timeWarnings(second.troops.single(), entry + 21 * minute))
        val free: AsuewState = AsuewLogic.applyLocalEvents(state(), listOf(event(stufe = "frei")))
        assertEquals(listOf("two_third_due", "one_third_due"),
            AsuewLogic.timeWarnings(free.troops.single(), entry + 21 * minute))
        assertEquals(3, AsuewLogic.nextDeadlines(free, entry).size)
    }

    @Test fun pruningUsesServerConfirmationForEveryEventKind() {
        val events: List<AsuewLocalEvent> = listOf(event(), event("druck", null),
            event("ack", null, "max_time"), event("ack", null, "withdraw"))
        val pending: AsuewState = state(troop().copy(serverWarnings = listOf("withdraw")))
        val now: Long = entry + 12 * minute
        assertEquals(events, AsuewLogic.pruneLocalEvents(pending, events, now))
        val confirmed: AsuewState = state(troop().copy(
            kontrollen = listOf(AsuewKontrolle("one_third", "funk", entry + 11 * minute, 180.0)),
            lastPressureAtMs = entry + 11 * minute, maxTimeAcked = true,
        ))
        assertTrue(AsuewLogic.pruneLocalEvents(confirmed, events, now).isEmpty())
        val beforePressure: AsuewState = pending.copy(troops = listOf(pending.troops.single().copy(
            lastPressureAtMs = entry + 11 * minute - 1L)))
        assertEquals(events, AsuewLogic.pruneLocalEvents(beforePressure, events, now))
    }

    @Test fun pruningDropsExpiredMissingTroopsAndOldCycles() {
        val local: AsuewLocalEvent = event()
        val expiresAt: Long = local.atServerMs + 12 * 60 * minute
        assertEquals(listOf(local), AsuewLogic.pruneLocalEvents(state(), listOf(local), expiresAt - 1L))
        assertTrue(AsuewLogic.pruneLocalEvents(state(), listOf(local), expiresAt).isEmpty())
        assertTrue(AsuewLogic.pruneLocalEvents(state(), listOf(local), expiresAt + 1L).isEmpty())
        assertTrue(AsuewLogic.pruneLocalEvents(state().copy(troops = emptyList()), listOf(local), entry).isEmpty())
        assertTrue(AsuewLogic.pruneLocalEvents(state(), listOf(event(at = entry - 1L)), entry).isEmpty())
    }

    @Test fun serializationRoundTripsAllKindsAndNullFields() {
        val events: List<AsuewLocalEvent> = listOf(event(), event("druck", null),
            event("ack", null, "max_time"), event("ack", null, "withdraw"), event(stufe = "frei"))
        assertEquals(events, AsuewLogic.parseLocalEvents(AsuewLogic.serializeLocalEvents(events)))
        assertEquals("[]", AsuewLogic.serializeLocalEvents(emptyList()))
    }

    @Test fun parserRejectsGarbageAndSkipsInvalidElements() {
        for (json: String in listOf("", "Müll", "{}", "null", "[", "[] Müll")) {
            assertTrue(json, AsuewLogic.parseLocalEvents(json).isEmpty())
        }
        val valid: JSONObject = JSONArray(AsuewLogic.serializeLocalEvents(listOf(event()))).getJSONObject(0)
        val mixed: JSONArray = JSONArray().put(7).put(JSONObject.NULL).put(JSONObject())
            .put(JSONObject(valid.toString()).put("kind", "unbekannt"))
            .put(JSONObject(valid.toString()).put("stufe", "falsch"))
            .put(JSONObject(valid.toString()).put("troopId", "1"))
            .put(JSONObject(valid.toString()).put("atServerMs", 1.5))
            .put(JSONObject(valid.toString()).put("ackKind", "falsch"))
            .put(valid)
        assertEquals(listOf(event()), AsuewLogic.parseLocalEvents(mixed.toString()))
    }

    @Test fun parserAndSerializerKeepNewestTwoHundredRegardlessOfInputOrder() {
        val events: List<AsuewLocalEvent> = (0 until 205).map { event(at = entry + it.toLong()) }
        val raw: JSONArray = JSONArray()
        for (local: AsuewLocalEvent in events.reversed()) {
            raw.put(JSONObject().put("troopId", local.troopId).put("kind", local.kind)
                .put("stufe", local.stufe).put("atServerMs", local.atServerMs))
        }
        assertEquals(events.drop(5), AsuewLogic.parseLocalEvents(raw.toString()))
        assertEquals(events.drop(5), AsuewLogic.parseLocalEvents(AsuewLogic.serializeLocalEvents(events.reversed())))
    }
}
