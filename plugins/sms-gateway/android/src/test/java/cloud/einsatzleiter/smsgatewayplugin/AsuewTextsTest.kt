package cloud.einsatzleiter.smsgatewayplugin

import org.junit.Assert.*
import org.junit.Test

class AsuewTextsTest {
    @Test fun alarmTitlesCoverAllTroopWarnings() {
        val titles: Map<String, String> = mapOf(
            "notfall" to "ATEMSCHUTZNOTFALL",
            "withdraw" to "Rückzugsdruck erreicht",
            "funk_verloren" to "Kein Funkkontakt",
            "max_exceeded" to "Maximale Einsatzzeit überschritten",
            "two_third_due" to "Kontrolle fällig (2/3)",
            "one_third_due" to "Kontrolle fällig (1/3)",
        )
        for ((kind: String, title: String) in titles) {
            assertEquals(title, AsuewTexts.alarmTitle(kind))
            assertTrue(AsuewTexts.alarmBody("Trupp Alpha", kind).startsWith("Trupp Alpha: "))
        }
        assertTrue(AsuewTexts.alarmBody("Alpha", "one_third_due").contains("niedrigsten Druck"))
        assertTrue(AsuewTexts.alarmBody("Alpha", "two_third_due").contains("Zweite Kontrolle"))
        assertEquals("Atemschutzüberwachung", AsuewTexts.alarmTitle("unbekannt"))
    }

    @Test fun deviceAlarmsExplainTheRequiredAction() {
        assertEquals("Verbindung zum Server verloren - Trupps per Funk kontrollieren", AsuewTexts.alarmBody("", "connection_lost"))
        assertEquals("Akku kritisch - Ladegerät anschließen", AsuewTexts.alarmBody("", "battery_critical"))
        assertEquals("Überwachungssitzung beendet - App öffnen", AsuewTexts.alarmBody("", "session_ended"))
    }

    @Test fun startupPreservesTroopAcknowledgementsUntilTheFirstState() {
        val acked: Set<String> = setOf("7:withdraw:100", "sitzung:beendet", "verbindung:verloren")
        assertEquals(setOf("7:withdraw:100"), AsuewTexts.retainedAlarmKeys(emptyList(), acked, false))
        assertTrue(AsuewTexts.retainedAlarmKeys(emptyList(), acked, true).isEmpty())
        val alarm: AsuewAlarm = AsuewAlarm(7L, "Alpha", "withdraw", "7:withdraw:100")
        assertEquals(setOf(alarm.key), AsuewTexts.retainedAlarmKeys(listOf(alarm), acked, true))
    }

    @Test fun statusLineUsesGermanLabelsAndHandlesMissingBattery() {
        assertEquals("Verbindung: ok, Akku: 75 %", AsuewTexts.statusLine("ok", 75))
        assertEquals("Verbindung: gestört, Akku: 19 %", AsuewTexts.statusLine("gestoert", 19))
        assertEquals("Verbindung: verloren, Akku: unbekannt", AsuewTexts.statusLine("verloren", null))
        assertEquals("Verbindung: gestört, Akku: unbekannt", AsuewTexts.statusLine("verbindet", null))
    }

    @Test fun stopRequiresContinuousInactiveResponseOrMoreThanFifteenMinutesWithoutTroops() {
        assertFalse(AsuewTexts.shouldStop(2_000_000L, null, null))
        assertFalse(AsuewTexts.shouldStop(600_099L, 100L, null))
        assertFalse(AsuewTexts.shouldStop(600_100L, 100L, null))
        assertTrue(AsuewTexts.shouldStop(600_101L, 100L, null))
        assertFalse(AsuewTexts.shouldStop(900_100L, null, 100L))
        assertTrue(AsuewTexts.shouldStop(900_101L, null, 100L))
        // Eine erfolgreiche Antwort setzt die 403/404-Folge zurück.
        assertFalse(AsuewTexts.shouldStop(600_100L, null, 100L))
    }

    @Test fun inactiveResponsesExcludeAuthenticationAndNetworkFailures() {
        assertTrue(AsuewTexts.isInactiveResponse(AsuewFetchResult.NoSession(403)))
        assertTrue(AsuewTexts.isInactiveResponse(AsuewFetchResult.Failure("http_404")))
        assertFalse(AsuewTexts.isInactiveResponse(AsuewFetchResult.NoSession(401)))
        assertFalse(AsuewTexts.isInactiveResponse(AsuewFetchResult.Failure("network")))
        assertFalse(AsuewTexts.isInactiveResponse(AsuewFetchResult.Failure("http_500")))
        assertFalse(AsuewTexts.isInactiveResponse(AsuewFetchResult.Success(AsuewState(0L, 42L, true, emptyList()), "{}")))
    }

    @Test fun connectionEventsOnlyOccurOnEnteringDisturbedOrLostState() {
        assertEquals("gestoert", AsuewTexts.newConnectionEvent("ok", "gestoert"))
        assertEquals("verloren", AsuewTexts.newConnectionEvent("gestoert", "verloren"))
        assertEquals("verloren", AsuewTexts.newConnectionEvent("ok", "verloren"))
        assertNull(AsuewTexts.newConnectionEvent("verloren", "verloren"))
        assertNull(AsuewTexts.newConnectionEvent("gestoert", "gestoert"))
        assertNull(AsuewTexts.newConnectionEvent("verloren", "ok"))
    }

    @Test fun wakeLockRequiresTroopsOrRecentSuccess() {
        assertTrue(AsuewTexts.shouldHoldWakeLock(true, null, 1_000_000L))
        assertFalse(AsuewTexts.shouldHoldWakeLock(false, null, 1_000L))
        assertTrue(AsuewTexts.shouldHoldWakeLock(false, 100L, 600_099L))
        assertFalse(AsuewTexts.shouldHoldWakeLock(false, 100L, 600_100L))
    }

    private fun troop(status: String, entry: Long? = 100L): AsuewTroop = AsuewTroop(
        id = 1L, name = "Alpha", status = status, isSicherheitstrupp = false,
        entryAtMs = entry, plannedDurationMin = null, oneThirdSeconds = null,
        twoThirdSeconds = null, maxSeconds = null, withdrawPressCalc = null,
        notfallAtMs = null, funkVerlorenAtMs = null, lastMeldungAtMs = null,
        lastPressureAtMs = null, maxTimeAcked = false, serverWarnings = emptyList(),
        kontrollen = emptyList(), members = emptyList(),
    )

    @Test fun emergencyAndRadioLossStillRequireTheWatcher() {
        for (status: String in listOf("im_einsatz", "rueckzug", "notfall", "funk_verloren")) {
            assertTrue(AsuewTexts.hasActiveTroops(AsuewState(0L, 42L, true, listOf(troop(status)))))
        }
        for (status: String in listOf("bereit", "zurueck", "erholt")) {
            assertFalse(AsuewTexts.hasActiveTroops(AsuewState(0L, 42L, true, listOf(troop(status)))))
        }
        assertFalse(AsuewTexts.hasActiveTroops(AsuewState(0L, 42L, true, listOf(troop("im_einsatz", null)))))
        assertFalse(AsuewTexts.hasActiveTroops(null))
    }

    @Test fun oneShotEventStaysQuietUntilRecoveryAndThenCanFireAgain() {
        val tracker: AsuewAlarmTracker = AsuewAlarmTracker()
        val alarm: AsuewAlarm = AsuewAlarm(0L, "", "connection_lost", "verbindung:verloren")
        assertEquals(listOf(alarm), tracker.due(listOf(alarm), 0L))
        tracker.markFired(alarm.key, 0L)
        tracker.ack(alarm.key)
        val restored: AsuewAlarmTracker = AsuewAlarmTracker().apply { restoreAcked(tracker.serializeAcked()) }
        restored.prune(setOf(alarm.key))
        assertTrue(restored.due(listOf(alarm), 120_000L).isEmpty())
        restored.prune(emptySet())
        assertEquals(listOf(alarm), restored.due(listOf(alarm), 125_000L))
    }
}
