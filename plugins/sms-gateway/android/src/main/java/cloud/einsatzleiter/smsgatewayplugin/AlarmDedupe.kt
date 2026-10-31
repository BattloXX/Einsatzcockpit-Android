package cloud.einsatzleiter.smsgatewayplugin

/** Reine Entscheidung ohne Android-Abhaengigkeiten; alte Eintraege werden verworfen. */
object AlarmDedupe {
    private const val WINDOW_MS = 30 * 60 * 1000L
    const val PREF_KEY = "ec_native_alarm_seen"

    data class Decision(val shouldAlarm: Boolean, val seen: String)

    fun check(seen: String, incidentId: Long, now: Long, isTest: Boolean = false): Decision {
        if (isTest) return Decision(true, seen)
        val entries = linkedMapOf<Long, Long>()
        seen.split(',').forEach { entry ->
            val parts = entry.split(':')
            val id = parts.getOrNull(0)?.toLongOrNull()
            val timestamp = parts.getOrNull(1)?.toLongOrNull()
            if (parts.size == 2 && id != null && id > 0 && timestamp != null &&
                timestamp <= now && now - timestamp < WINDOW_MS) {
                entries[id] = maxOf(entries[id] ?: timestamp, timestamp)
            }
        }
        val shouldAlarm = incidentId <= 0 || incidentId !in entries
        if (shouldAlarm && incidentId > 0) entries[incidentId] = now
        return Decision(shouldAlarm, entries.entries.joinToString(",") { "${it.key}:${it.value}" })
    }
}
