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
class AsuewLogicTest {
    private val entry: Long = 1_000_000L
    private val minute: Long = 60_000L

    private fun troop(id: Long = 1L): AsuewTroop = AsuewTroop(
        id = id, name = "Trupp Alpha", status = "im_einsatz", isSicherheitstrupp = false,
        entryAtMs = entry, plannedDurationMin = 30, oneThirdSeconds = 600L,
        twoThirdSeconds = 1200L, maxSeconds = 1800L, withdrawPressCalc = 60.0,
        notfallAtMs = null, funkVerlorenAtMs = null, lastMeldungAtMs = null,
        lastPressureAtMs = null, maxTimeAcked = false, serverWarnings = emptyList(),
        kontrollen = emptyList(), members = emptyList(),
    )

    private fun state(vararg troops: AsuewTroop): AsuewState =
        AsuewState(entry, 42L, true, troops.toList())

    private fun kontrolle(stufe: String, art: String = "funk"): AsuewKontrolle =
        AsuewKontrolle(stufe, art, entry + 10 * minute, 180.0)

    private fun member(id: Long, current: Double?, withdraw: Double?): AsuewMember =
        AsuewMember(id, "Mitglied $id", "mitglied", 300.0, current, withdraw)

    private fun minimalJson(): JSONObject = JSONObject("""{
        "server_time": "1970-01-01T00:00:00Z", "incident_id": 42,
        "troops": [{"id": 1, "name": "Trupp Alpha", "status": "bereit"}]
    }""")

    @Test fun parserReadsCompleteBackendPayload() {
        val parsed = AsuewLogic.parseState("""{
            "server_time": "1970-01-01T01:00:00.123456Z", "incident_id": 42,
            "asuew_app_pflicht": true, "settings": {"unknown": 1},
            "troops": [{
                "id": 7, "name": "Trupp Alpha", "status": "rueckzug",
                "is_sicherheitstrupp": true, "entry_at": "1970-01-01T00:00:01Z",
                "planned_duration_min": 30, "one_third_seconds": 600,
                "two_third_seconds": 1200, "max_seconds": 1800, "withdraw_press_calc": 60.5,
                "notfall_at": "1970-01-01T00:02:00Z",
                "funk_verloren_at": "1970-01-01T00:01:00Z",
                "last_meldung_at": "1970-01-01T00:03:00Z",
                "last_pressure_at": "1970-01-01T00:04:00.001234Z",
                "max_time_acked": true, "warnings": ["notfall", "withdraw"],
                "kontrollen": [{"stufe": "one_third", "art": "funk",
                    "erledigt_at": "1970-01-01T00:10:00Z", "niedrigster_druck": 180.5}],
                "members": [{"id": 99, "name": "Anna", "role": "fuehrer",
                    "start_press": 300, "current_press": 180.5, "withdraw_press": 60}]
            }]
        }""")!!
        assertEquals(3_600_123L, parsed.serverTimeMs)
        assertEquals(42L, parsed.incidentId)
        assertTrue(parsed.appPflicht)
        assertEquals(1, parsed.troops.size)
        val t = parsed.troops.single()
        assertEquals(7L, t.id)
        assertEquals("Trupp Alpha", t.name)
        assertEquals("rueckzug", t.status)
        assertTrue(t.isSicherheitstrupp)
        assertEquals(1_000L, t.entryAtMs!!)
        assertEquals(30, t.plannedDurationMin!!)
        assertEquals(600L, t.oneThirdSeconds!!)
        assertEquals(1200L, t.twoThirdSeconds!!)
        assertEquals(1800L, t.maxSeconds!!)
        assertEquals(60.5, t.withdrawPressCalc!!, 0.0)
        assertEquals(120_000L, t.notfallAtMs!!)
        assertEquals(60_000L, t.funkVerlorenAtMs!!)
        assertEquals(180_000L, t.lastMeldungAtMs!!)
        assertEquals(240_001L, t.lastPressureAtMs!!)
        assertTrue(t.maxTimeAcked)
        assertEquals(listOf("notfall", "withdraw"), t.serverWarnings)
        assertEquals(listOf(AsuewKontrolle("one_third", "funk", 600_000L, 180.5)), t.kontrollen)
        assertEquals(listOf(AsuewMember(99L, "Anna", "fuehrer", 300.0, 180.5, 60.0)), t.members)
    }

    @Test fun parserAllowsMissingOptionalFieldsAndEmptyTroops() {
        val parsed = AsuewLogic.parseState(minimalJson().toString())!!
        assertEquals(0L, parsed.serverTimeMs)
        assertEquals(42L, parsed.incidentId)
        assertFalse(parsed.appPflicht)
        assertEquals(troop().copy(
            status = "bereit", entryAtMs = null, plannedDurationMin = null,
            oneThirdSeconds = null, twoThirdSeconds = null, maxSeconds = null,
            withdrawPressCalc = null,
        ), parsed.troops.single())
        assertTrue(AsuewLogic.parseState(minimalJson().put("troops", JSONArray()).toString())!!.troops.isEmpty())
    }

    @Test fun parserAllowsExplicitNullOptionals() {
        val root = minimalJson()
        val t = root.getJSONArray("troops").getJSONObject(0)
        for (key in listOf(
            "entry_at", "planned_duration_min", "one_third_seconds", "two_third_seconds",
            "max_seconds", "withdraw_press_calc", "notfall_at", "funk_verloren_at",
            "last_meldung_at", "last_pressure_at", "kontrollen", "members", "warnings",
        )) t.put(key, JSONObject.NULL)
        t.put("members", JSONArray("""[{"id": 2, "name": "Anna", "role": "mitglied",
            "start_press": null, "current_press": null, "withdraw_press": null}]"""))
        t.put("kontrollen", JSONArray("""[{"stufe": "frei", "art": "snooze",
            "erledigt_at": "1970-01-01T00:00:00Z", "niedrigster_druck": null}]"""))
        val parsed = AsuewLogic.parseState(root.toString())!!.troops.single()
        assertNull(parsed.entryAtMs)
        assertNull(parsed.oneThirdSeconds)
        assertNull(parsed.lastPressureAtMs)
        assertTrue(parsed.serverWarnings.isEmpty())
        assertEquals(AsuewMember(2L, "Anna", "mitglied", null, null, null), parsed.members.single())
        assertNull(parsed.kontrollen.single().niedrigsterDruck)
    }

    @Test fun parserRejectsGarbageAndMissingOrInvalidRequiredFields() {
        for (json in listOf("", "Müll", "null", "[]", "{}", "{broken", minimalJson().toString() + "Müll")) {
            assertNull(json, AsuewLogic.parseState(json))
        }
        for (key in listOf("server_time", "incident_id", "troops")) {
            val root = minimalJson()
            root.remove(key)
            assertNull(key, AsuewLogic.parseState(root.toString()))
            root.put(key, JSONObject.NULL)
            assertNull(key, AsuewLogic.parseState(root.toString()))
        }
        for (key in listOf("id", "name", "status")) {
            val root = minimalJson()
            root.getJSONArray("troops").getJSONObject(0).remove(key)
            assertNull(key, AsuewLogic.parseState(root.toString()))
        }
        assertNull(AsuewLogic.parseState(minimalJson().put("incident_id", "abc").toString()))
        assertNull(AsuewLogic.parseState(minimalJson().put("incident_id", 42.5).toString()))
        assertNull(AsuewLogic.parseState(minimalJson().put("troops", "abc").toString()))
    }

    @Test fun parserRejectsMalformedOptionalValuesInsteadOfDisablingDeadlinesSilently() {
        for ((key, value) in listOf(
            "entry_at" to "abc", "last_pressure_at" to "abc", "one_third_seconds" to "abc",
            "two_third_seconds" to 1.5, "max_seconds" to "abc", "current_press" to "abc",
            "members" to "abc", "kontrollen" to "abc", "warnings" to "abc",
        )) {
            val root = minimalJson()
            val t = root.getJSONArray("troops").getJSONObject(0)
            if (key == "current_press") {
                t.put("members", JSONArray().put(JSONObject()
                    .put("id", 2).put("name", "Anna").put("role", "mitglied").put(key, value)))
            } else t.put(key, value)
            assertNull(key, AsuewLogic.parseState(root.toString()))
        }
    }

    @Test fun parserRejectsMalformedNestedRequiredRecords() {
        for ((key, content) in listOf(
            "members" to """[{"name":"Anna", "role":"mitglied"}]""",
            "members" to """[{"id":2, "role":"mitglied"}]""",
            "members" to """[{"id":2, "name":"Anna"}]""",
            "kontrollen" to """[{"stufe":"one_third", "art":"funk"}]""",
            "kontrollen" to """[{"stufe":"one_third", "art":"funk", "erledigt_at":"abc"}]""",
            "troops" to """[null]""",
        )) {
            val root = minimalJson()
            if (key == "troops") root.put(key, JSONArray(content)) else
                root.getJSONArray("troops").getJSONObject(0).put(key, JSONArray(content))
            assertNull(content, AsuewLogic.parseState(root.toString()))
        }
    }

    @Test fun parserTruncatesMicrosecondsAndPadsShortFractionsInUtc() {
        val root = minimalJson().put("server_time", "2026-11-05T22:00:00Z")
        val base = AsuewLogic.parseState(root.toString())!!.serverTimeMs
        for ((fraction, millis) in listOf("123456" to 123L, "999999" to 999L, "1" to 100L, "01" to 10L)) {
            root.put("server_time", "2026-11-05T22:00:00.${fraction}Z")
            assertEquals(base + millis, AsuewLogic.parseState(root.toString())!!.serverTimeMs)
        }
        // Epoch-Wert prüft zusätzlich die UTC-Auswertung unabhängig von der Gerätezeitzone.
        root.put("server_time", "1970-01-01T00:00:00.000001Z")
        assertEquals(0L, AsuewLogic.parseState(root.toString())!!.serverTimeMs)
        for (invalid in listOf(
            "2026-02-30T22:00:00Z", "2026-11-05T25:00:00Z", "2026-11-05T22:00:00",
            "2026-11-05T22:00:00Zextra", "2026-11-05T22:00:00.Z", "abc",
        )) {
            root.put("server_time", invalid)
            assertNull(invalid, AsuewLogic.parseState(root.toString()))
        }
    }

    @Test fun offsetsConvertInBothDirections() {
        for (offset in listOf(-90_000L, 0L, 120_000L)) {
            assertEquals(offset, AsuewLogic.clockOffsetMs(entry + offset, entry))
            assertEquals(entry + offset, AsuewLogic.serverNow(entry, offset))
            assertEquals(entry, AsuewLogic.serverToLocal(entry + offset, offset))
        }
    }

    @Test fun timeWarningsRespectEveryExactBoundaryAndBackendOrder() {
        val t = troop()
        assertTrue(AsuewLogic.timeWarnings(t, entry - 1L).isEmpty())
        assertTrue(AsuewLogic.timeWarnings(t, entry + 10 * minute - 1L).isEmpty())
        assertEquals(listOf("one_third_due"), AsuewLogic.timeWarnings(t, entry + 10 * minute))
        assertEquals(listOf("one_third_due"), AsuewLogic.timeWarnings(t, entry + 20 * minute - 1L))
        assertEquals(listOf("two_third_due", "one_third_due"), AsuewLogic.timeWarnings(t, entry + 20 * minute))
        assertEquals(listOf("two_third_due", "one_third_due"), AsuewLogic.timeWarnings(t, entry + 30 * minute - 1L))
        assertEquals(listOf("max_exceeded", "two_third_due", "one_third_due"),
            AsuewLogic.timeWarnings(t, entry + 30 * minute))
    }

    @Test fun allThreeBackendExamples() {
        val now = entry + 60 * minute
        val examples = listOf(
            12L to listOf("one_third_due"),
            21L to listOf("two_third_due", "one_third_due"),
            31L to listOf("max_exceeded", "two_third_due", "one_third_due"),
        )
        for ((minutes, expected) in examples) {
            assertEquals(expected, AsuewLogic.timeWarnings(troop().copy(entryAtMs = now - minutes * minute), now))
        }
    }

    @Test fun onlyMatchingFunkControlsCompleteStages() {
        val now = entry + 21 * minute
        val t = troop().copy(kontrollen = listOf(kontrolle("one_third")))
        assertEquals(listOf("two_third_due"), AsuewLogic.timeWarnings(t, now))
        assertEquals(listOf("one_third_due"), AsuewLogic.timeWarnings(
            troop().copy(kontrollen = listOf(kontrolle("two_third"))), now))
        assertTrue(AsuewLogic.timeWarnings(t.copy(kontrollen = listOf(
            kontrolle("one_third"), kontrolle("two_third"))), now).isEmpty())
        for (control in listOf(kontrolle("frei"), kontrolle("one_third", "snooze"),
            kontrolle("two_third", "kein_kontakt"))) {
            assertEquals(listOf("two_third_due", "one_third_due"),
                AsuewLogic.timeWarnings(troop().copy(kontrollen = listOf(control)), now))
        }
    }

    @Test fun pressureBeforeDueAndFreeTextDoNotCompleteControls() {
        val now = entry + 31 * minute
        val t = troop().copy(lastMeldungAtMs = now, lastPressureAtMs = entry + 10 * minute - 1L)
        assertEquals(listOf("max_exceeded", "two_third_due", "one_third_due"), AsuewLogic.timeWarnings(t, now))
        for (pressureAt in listOf(entry + 10 * minute, entry + 10 * minute + 1L, entry + 20 * minute - 1L)) {
            assertEquals(listOf("max_exceeded", "two_third_due"),
                AsuewLogic.timeWarnings(t.copy(lastPressureAtMs = pressureAt), now))
        }
        for (pressureAt in listOf(entry + 20 * minute, now)) {
            assertEquals(listOf("max_exceeded"), AsuewLogic.timeWarnings(t.copy(lastPressureAtMs = pressureAt), now))
        }
    }

    @Test fun maxAckOnlyCompletesMaxAndInactiveTroopsHaveNoTimeWarnings() {
        val now = entry + 31 * minute
        assertEquals(listOf("two_third_due", "one_third_due"),
            AsuewLogic.timeWarnings(troop().copy(maxTimeAcked = true), now))
        assertEquals(3, AsuewLogic.timeWarnings(troop().copy(status = "rueckzug"), now).size)
        for (status in listOf("bereit", "zurueck", "erholt", "notfall", "unbekannt")) {
            val t = troop().copy(status = status)
            assertTrue(AsuewLogic.timeWarnings(t, now).isEmpty())
            assertTrue(AsuewLogic.nextDeadlines(state(t), entry).isEmpty())
        }
        assertTrue(AsuewLogic.timeWarnings(troop().copy(entryAtMs = null), now).isEmpty())
        assertTrue(AsuewLogic.timeWarnings(troop().copy(
            oneThirdSeconds = null, twoThirdSeconds = null, maxSeconds = null), now).isEmpty())
    }

    @Test fun configuredZeroThresholdIsReachedAtEntry() {
        val t = troop().copy(oneThirdSeconds = 0L, twoThirdSeconds = null, maxSeconds = 0L)
        assertEquals(listOf("max_exceeded", "one_third_due"), AsuewLogic.timeWarnings(t, entry))
        assertEquals(2, AsuewLogic.nextDeadlines(state(t), entry - 1L).size)
    }

    @Test fun alarmsUnionDeduplicateSortBySeverityThenTroopIdAndUseCycleKeys() {
        val kinds = listOf("one_third_due", "withdraw", "notfall", "funk_verloren",
            "max_exceeded", "two_third_due", "withdraw", "unknown")
        val t1 = troop(1L).copy(serverWarnings = kinds)
        val t2 = troop(2L).copy(name = "Trupp Beta", serverWarnings = kinds.reversed())
        val alarms = AsuewLogic.activeAlarms(state(t2, t1), entry + 31 * minute)
        val expected = listOf("notfall", "withdraw", "funk_verloren", "max_exceeded", "two_third_due", "one_third_due")
            .flatMap { kind -> listOf(1L to kind, 2L to kind) }
        assertEquals(expected, alarms.map { it.troopId to it.kind })
        assertEquals(12, alarms.map { it.key }.toSet().size)
        assertEquals("1:notfall:$entry", alarms.first().key)
        assertEquals("Trupp Alpha", alarms.first().troopName)
        assertEquals("Trupp Beta", alarms[1].troopName)
        val newCycle = AsuewLogic.activeAlarms(state(t1.copy(entryAtMs = entry + 1L)), entry).first()
        assertEquals("1:notfall:${entry + 1L}", newCycle.key)
    }

    @Test fun inactiveServerAlarmsNeedMatchingEmergencyFlagAndAreNeverInvented() {
        val warnings = listOf("notfall", "funk_verloren", "withdraw", "max_exceeded", "one_third_due", "two_third_due", "unknown")
        val inactive = troop().copy(status = "bereit", entryAtMs = null, serverWarnings = warnings)
        assertTrue(AsuewLogic.activeAlarms(state(inactive), entry).isEmpty())
        val emergency = inactive.copy(notfallAtMs = entry)
        assertEquals(listOf(AsuewAlarm(1L, "Trupp Alpha", "notfall", "1:notfall:0")),
            AsuewLogic.activeAlarms(state(emergency), entry))
        assertEquals(listOf("funk_verloren"), AsuewLogic.activeAlarms(
            state(inactive.copy(funkVerlorenAtMs = entry)), entry).map { it.kind })
        assertEquals(listOf("notfall", "funk_verloren"), AsuewLogic.activeAlarms(
            state(emergency.copy(funkVerlorenAtMs = entry)), entry).map { it.kind })
        assertTrue(AsuewLogic.activeAlarms(state(emergency.copy(serverWarnings = emptyList())), entry).isEmpty())
    }

    @Test fun alarmsAddLocalDeadlinesToServerPressureWarningsWithoutCalculatingPressureAlarms() {
        val t = troop().copy(serverWarnings = listOf("withdraw", "unknown"))
        assertEquals(listOf("withdraw", "one_third_due"),
            AsuewLogic.activeAlarms(state(t), entry + 12 * minute).map { it.kind })
        assertEquals(listOf("one_third_due"), AsuewLogic.activeAlarms(state(t.copy(
            serverWarnings = emptyList(), members = listOf(member(1L, 10.0, 60.0)),
        )), entry + 12 * minute).map { it.kind })
        assertTrue(AsuewLogic.activeAlarms(state(), entry).isEmpty())
    }

    @Test fun serverWarningsRemainAuthoritativeEvenWhenLocalControlIsComplete() {
        val t = troop().copy(lastPressureAtMs = entry + 31 * minute,
            maxTimeAcked = true, serverWarnings = listOf("one_third_due", "withdraw", "ignored"))
        assertEquals(listOf("withdraw", "one_third_due"),
            AsuewLogic.activeAlarms(state(t), entry + 31 * minute).map { it.kind })
    }

    @Test fun deadlinesAndWakeupOnlyIncludeUnreachedUncompletedStages() {
        val t = troop()
        val expected = listOf(
            AsuewDeadline(1L, "one_third_due", entry + 10 * minute),
            AsuewDeadline(1L, "two_third_due", entry + 20 * minute),
            AsuewDeadline(1L, "max_exceeded", entry + 30 * minute),
        )
        assertEquals(expected, AsuewLogic.nextDeadlines(state(t), entry))
        assertEquals(entry + 10 * minute, AsuewLogic.nextWakeupServerMs(state(t), entry)!!)
        assertEquals(expected.drop(1), AsuewLogic.nextDeadlines(state(t), entry + 10 * minute))
        val controlled = t.copy(kontrollen = listOf(kontrolle("one_third")))
        assertEquals(expected.drop(1), AsuewLogic.nextDeadlines(state(controlled), entry))
        assertEquals(entry + 20 * minute, AsuewLogic.nextWakeupServerMs(state(controlled), entry)!!)
        assertEquals(expected.drop(1), AsuewLogic.nextDeadlines(
            state(t.copy(lastPressureAtMs = entry + 10 * minute)), entry))
        assertEquals(expected, AsuewLogic.nextDeadlines(
            state(t.copy(lastPressureAtMs = entry + 10 * minute - 1L, lastMeldungAtMs = entry + 20 * minute)), entry))
        assertEquals(expected.take(2), AsuewLogic.nextDeadlines(state(t.copy(maxTimeAcked = true)), entry))
        assertTrue(AsuewLogic.nextDeadlines(state(t), entry + 30 * minute).isEmpty())
        assertNull(AsuewLogic.nextWakeupServerMs(state(t), entry + 30 * minute))
        assertNull(AsuewLogic.nextWakeupServerMs(state(), entry))
        assertNull(AsuewLogic.nextWakeupServerMs(state(t.copy(entryAtMs = null)), entry))
    }

    @Test fun deadlinesSortAcrossTroopsAndSkipMissingThresholds() {
        val early = troop(2L).copy(entryAtMs = entry - minute)
        val deadlines = AsuewLogic.nextDeadlines(state(troop(), early), entry)
        assertEquals(listOf(2L, 1L, 2L, 1L, 2L, 1L), deadlines.map { it.troopId })
        assertEquals(entry + 9 * minute, AsuewLogic.nextWakeupServerMs(state(troop(), early), entry)!!)
        assertEquals(listOf("max_exceeded"), AsuewLogic.nextDeadlines(state(troop().copy(
            oneThirdSeconds = null, twoThirdSeconds = null)), entry).map { it.kind })
        assertTrue(AsuewLogic.nextDeadlines(state(troop().copy(
            lastPressureAtMs = entry + 20 * minute, maxTimeAcked = true)), entry).isEmpty())
        assertEquals(listOf("one_third_due", "max_exceeded"), AsuewLogic.nextDeadlines(state(troop().copy(
            kontrollen = listOf(kontrolle("two_third")))), entry).map { it.kind })
    }

    @Test fun trackerRepeatsAtSixtySecondsAndNotfallAtThirtySeconds() {
        val tracker = AsuewAlarmTracker()
        val alarms = listOf(
            AsuewAlarm(1L, "Alpha", "withdraw", "1:withdraw:$entry"),
            AsuewAlarm(2L, "Beta", "notfall", "2:notfall:$entry"),
        )
        assertEquals(alarms, tracker.due(alarms, 0L))
        // due verändert den Zustand nicht; erst die tatsächliche Ausgabe zählt.
        assertEquals(alarms, tracker.due(alarms, 0L))
        alarms.forEach { tracker.markFired(it.key, 0L) }
        assertEquals(mapOf(alarms[0].key to 0L, alarms[1].key to 0L), tracker.lastAlarmAtElapsed)
        assertTrue(tracker.due(alarms, 29_999L).isEmpty())
        assertEquals(listOf(alarms[1]), tracker.due(alarms, 30_000L))
        tracker.markFired(alarms[1].key, 30_000L)
        assertTrue(tracker.due(alarms, 59_999L).isEmpty())
        assertEquals(alarms, tracker.due(alarms, 60_000L))
        assertEquals(listOf(alarms[0]), tracker.due(alarms, 5_000L, repeatMs = 5_000L))
    }

    @Test fun trackerAckPruneAndReactivation() {
        val tracker = AsuewAlarmTracker()
        val first = AsuewAlarm(1L, "Alpha", "one_third_due", "1:one_third_due:$entry")
        val other = AsuewAlarm(2L, "Beta", "notfall", "2:notfall:$entry")
        tracker.ack(first.key)
        tracker.markFired(first.key, 0L)
        tracker.markFired(other.key, 0L)
        assertEquals(listOf(other), tracker.due(listOf(first, other), 120_000L))
        tracker.prune(setOf(first.key))
        assertEquals(setOf(first.key), tracker.acked)
        assertEquals(mapOf(first.key to 0L), tracker.lastAlarmAtElapsed)
        tracker.prune(emptySet())
        assertTrue(tracker.acked.isEmpty())
        assertTrue(tracker.lastAlarmAtElapsed.isEmpty())
        assertEquals(listOf(first, other), tracker.due(listOf(first, other), 1L))
        tracker.ack(first.key)
        val nextCycle = first.copy(key = "1:one_third_due:${entry + 1L}")
        assertEquals(listOf(nextCycle), tracker.due(listOf(nextCycle), 1L))
    }

    @Test fun trackerSerializesAcknowledgementsDeterministicallyAndRestoresByReplacement() {
        val tracker = AsuewAlarmTracker()
        assertEquals("", tracker.serializeAcked())
        tracker.ack("2:notfall:100")
        tracker.ack("1:withdraw:100")
        tracker.ack("1:withdraw:100")
        assertEquals("1:withdraw:100,2:notfall:100", tracker.serializeAcked())
        val restored = AsuewAlarmTracker()
        restored.restoreAcked(tracker.serializeAcked())
        assertEquals(tracker.acked, restored.acked)
        val alarm = AsuewAlarm(2L, "Beta", "notfall", "2:notfall:100")
        assertTrue(restored.due(listOf(alarm), 999_999L).isEmpty())
        assertTrue(restored.lastAlarmAtElapsed.isEmpty())
        restored.restoreAcked(" 1:withdraw:100, ,1:withdraw:100, ")
        assertEquals(setOf("1:withdraw:100"), restored.acked)
        assertEquals(listOf(alarm), restored.due(listOf(alarm, alarm), 0L))
        restored.restoreAcked("")
        assertTrue(restored.acked.isEmpty())
    }

    @Test fun connectionStatusAtAllBoundaries() {
        assertEquals("verbindet", AsuewLogic.connectionStatus(null, 0L))
        for ((age, expected) in listOf(
            0L to "ok", 19_999L to "ok", 20_000L to "ok", 20_001L to "gestoert",
            59_999L to "gestoert", 60_000L to "gestoert", 60_001L to "verloren",
        )) assertEquals(expected, AsuewLogic.connectionStatus(123L, 123L + age))
    }

    @Test fun batteryStatusAtAllBoundariesAndWhileCharging() {
        assertEquals("unbekannt", AsuewLogic.batteryStatus(null, false))
        assertEquals("unbekannt", AsuewLogic.batteryStatus(null, true))
        for ((percent, expected) in listOf(
            0 to "kritisch", 9 to "kritisch", 10 to "warnung", 19 to "warnung", 20 to "ok", 100 to "ok",
        )) {
            assertEquals(expected, AsuewLogic.batteryStatus(percent, false))
            assertEquals("ok", AsuewLogic.batteryStatus(percent, true))
        }
    }

    @Test fun notificationLinesUseElapsedTimeAndIndependentMemberMinimums() {
        val t = troop().copy(members = listOf(
            member(1L, 200.0, 60.0), member(2L, 180.0, 90.0), member(3L, null, null),
        ))
        assertEquals(listOf("Trupp Alpha: 12:30 min, niedrigster Druck 180 bar, Rückzug bei 60 bar"),
            AsuewLogic.notificationLines(state(t), entry + 12 * minute + 30_999L))
        val beta = troop(2L).copy(name = "Trupp Beta", status = "rueckzug", entryAtMs = entry - minute)
        assertEquals(listOf("Trupp Alpha: 00:00 min", "Trupp Beta: 01:00 min"),
            AsuewLogic.notificationLines(state(troop(), beta, troop(3L).copy(status = "bereit")), entry))
        assertTrue(AsuewLogic.notificationLines(state(t.copy(entryAtMs = null)), entry).isEmpty())
    }

    @Test fun notificationLinesOmitMissingValuesAndKeepZeroAndFractionalPressures() {
        assertEquals(listOf("Trupp Alpha: 00:00 min"), AsuewLogic.notificationLines(state(troop()), entry - 1L))
        assertEquals(listOf("Trupp Alpha: 100:01 min"),
            AsuewLogic.notificationLines(state(troop()), entry + 100 * minute + 1000L))
        val t = troop().copy(members = listOf(member(1L, null, null)))
        assertEquals(listOf("Trupp Alpha: 00:00 min"), AsuewLogic.notificationLines(state(t), entry))
        assertEquals(listOf("Trupp Alpha: 00:00 min, niedrigster Druck 0 bar"),
            AsuewLogic.notificationLines(state(t.copy(members = listOf(member(1L, 0.0, null)))), entry))
        assertEquals(listOf("Trupp Alpha: 00:00 min, Rückzug bei 60,5 bar"),
            AsuewLogic.notificationLines(state(t.copy(members = listOf(member(1L, null, 60.5)))), entry))
    }
}
