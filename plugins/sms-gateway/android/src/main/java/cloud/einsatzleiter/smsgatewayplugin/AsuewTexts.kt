package cloud.einsatzleiter.smsgatewayplugin

/** Texte und Entscheidungen des Wächters ohne Android-Abhängigkeiten. */
object AsuewTexts {
    fun alarmTitle(kind: String): String = when (kind) {
        "notfall" -> "ATEMSCHUTZNOTFALL"
        "withdraw" -> "Rückzugsdruck erreicht"
        "funk_verloren" -> "Kein Funkkontakt"
        "max_exceeded" -> "Maximale Einsatzzeit überschritten"
        "two_third_due" -> "Kontrolle fällig (2/3)"
        "one_third_due" -> "Kontrolle fällig (1/3)"
        "session_ended" -> "Überwachungssitzung beendet - App öffnen"
        "connection_lost" -> "Verbindung zum Server verloren"
        "battery_critical" -> "Akku kritisch"
        else -> "Atemschutzüberwachung"
    }

    fun alarmBody(troopName: String, kind: String): String {
        when (kind) {
            "connection_lost" -> return "Verbindung zum Server verloren - Trupps per Funk kontrollieren"
            "battery_critical" -> return "Akku kritisch - Ladegerät anschließen"
            "session_ended" -> return alarmTitle(kind)
        }
        val statement: String = when (kind) {
            "notfall" -> "Atemschutznotfall gemeldet. Rettungsmaßnahmen einleiten."
            "withdraw" -> "Rückzugsdruck erreicht. Rückzug veranlassen."
            "funk_verloren" -> "Kein Funkkontakt. Gruppenkommandant verständigen."
            "max_exceeded" -> "Maximale Einsatzzeit überschritten. Trupp kontrollieren."
            "two_third_due" -> "Zweite Kontrolle: Lage und niedrigsten Druck abfragen."
            "one_third_due" -> "Erste Kontrolle: Lage und niedrigsten Druck abfragen."
            else -> alarmTitle(kind)
        }
        return "$troopName: $statement"
    }

    fun statusLine(connection: String, battery: Int?): String {
        val label: String = when (connection) {
            "ok" -> "ok"
            "verloren" -> "verloren"
            else -> "gestört"
        }
        return "Verbindung: $label, Akku: ${battery?.let { "$it %" } ?: "unbekannt"}"
    }

    fun hasActiveTroops(state: AsuewState?): Boolean = state?.troops?.any {
        it.entryAtMs != null && it.status in listOf("im_einsatz", "rueckzug", "notfall", "funk_verloren")
    } == true

    fun shouldStop(nowElapsedMs: Long, inactiveSinceMs: Long?, noTroopsSinceMs: Long?): Boolean =
        (inactiveSinceMs != null && nowElapsedMs - inactiveSinceMs > 10 * 60_000L) ||
            (noTroopsSinceMs != null && nowElapsedMs - noTroopsSinceMs > 15 * 60_000L)

    fun shouldHoldWakeLock(hasActiveTroops: Boolean, lastOkElapsedMs: Long?, nowElapsedMs: Long): Boolean =
        hasActiveTroops || (lastOkElapsedMs != null && nowElapsedMs - lastOkElapsedMs < 10 * 60_000L)

    fun newConnectionEvent(previous: String, current: String): String? =
        current.takeIf { it != previous && it in listOf("gestoert", "verloren") }

    /** Ohne ersten Serverzustand sind gespeicherte Trupp-Quittierungen noch nicht widerlegt. */
    fun retainedAlarmKeys(alarms: List<AsuewAlarm>, acked: Set<String>, hasState: Boolean): Set<String> {
        val keys: Set<String> = alarms.map { it.key }.toSet()
        if (hasState) return keys
        return keys + acked.filter { it.substringBefore(':').toLongOrNull()?.let { id -> id > 0L } == true }
    }

    fun isInactiveResponse(result: AsuewFetchResult): Boolean = when (result) {
        is AsuewFetchResult.NoSession -> result.code == 403 || result.code == 404
        is AsuewFetchResult.Failure -> result.reason == "http_403" || result.reason == "http_404"
        is AsuewFetchResult.Success -> false
    }
}
