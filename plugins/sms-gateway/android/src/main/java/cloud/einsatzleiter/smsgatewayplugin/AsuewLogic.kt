package cloud.einsatzleiter.smsgatewayplugin

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class AsuewKontrolle(
    val stufe: String,
    val art: String,
    val erledigtAtMs: Long,
    val niedrigsterDruck: Double?,
)

data class AsuewMember(
    val id: Long,
    val name: String,
    val role: String,
    val startPress: Double?,
    val currentPress: Double?,
    val withdrawPress: Double?,
)

data class AsuewTroop(
    val id: Long,
    val name: String,
    val status: String,
    val isSicherheitstrupp: Boolean,
    val entryAtMs: Long?,
    val plannedDurationMin: Int?,
    val oneThirdSeconds: Long?,
    val twoThirdSeconds: Long?,
    val maxSeconds: Long?,
    val withdrawPressCalc: Double?,
    val notfallAtMs: Long?,
    val funkVerlorenAtMs: Long?,
    val lastMeldungAtMs: Long?,
    val lastPressureAtMs: Long?,
    val maxTimeAcked: Boolean,
    val serverWarnings: List<String>,
    val kontrollen: List<AsuewKontrolle>,
    val members: List<AsuewMember>,
)

data class AsuewState(
    val serverTimeMs: Long,
    val incidentId: Long,
    val appPflicht: Boolean,
    val troops: List<AsuewTroop>,
)

data class AsuewAlarm(val troopId: Long, val troopName: String, val kind: String, val key: String)

data class AsuewDeadline(val troopId: Long, val kind: String, val atServerMs: Long)

/** Fristen und Darstellung ohne Geräteuhr oder Android-Zustand. */
object AsuewLogic {
    private val severity: List<String> = listOf(
        "notfall", "withdraw", "funk_verloren", "max_exceeded", "two_third_due", "one_third_due",
    )
    private val utcPattern: Regex = Regex("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d+))?Z$")

    fun parseState(json: String): AsuewState? = try {
        val input = JSONTokener(json)
        val root = input.nextValue()
        require(root is JSONObject)
        require(input.nextClean() == '\u0000')
        val serverTime = parseUtc(requiredString(root, "server_time"))
        require(serverTime != null)
        val troops = root.getJSONArray("troops")
        AsuewState(
            serverTimeMs = serverTime,
            incidentId = requiredLong(root, "incident_id"),
            appPflicht = root.optBoolean("asuew_app_pflicht", false),
            troops = (0 until troops.length()).map { parseTroop(troops.getJSONObject(it)) },
        )
    } catch (_: Exception) { null }

    private fun parseTroop(item: JSONObject): AsuewTroop {
        val kontrollen = optionalArray(item, "kontrollen")
        val members = optionalArray(item, "members")
        val warnings = optionalArray(item, "warnings")
        return AsuewTroop(
            id = requiredLong(item, "id"),
            name = requiredString(item, "name"),
            status = requiredString(item, "status"),
            isSicherheitstrupp = item.optBoolean("is_sicherheitstrupp", false),
            entryAtMs = optionalTime(item, "entry_at"),
            plannedDurationMin = optionalLong(item, "planned_duration_min")
                ?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt(),
            oneThirdSeconds = optionalLong(item, "one_third_seconds"),
            twoThirdSeconds = optionalLong(item, "two_third_seconds"),
            maxSeconds = optionalLong(item, "max_seconds"),
            withdrawPressCalc = optionalDouble(item, "withdraw_press_calc"),
            notfallAtMs = optionalTime(item, "notfall_at"),
            funkVerlorenAtMs = optionalTime(item, "funk_verloren_at"),
            lastMeldungAtMs = optionalTime(item, "last_meldung_at"),
            lastPressureAtMs = optionalTime(item, "last_pressure_at"),
            maxTimeAcked = item.optBoolean("max_time_acked", false),
            serverWarnings = if (warnings == null) emptyList() else
                (0 until warnings.length()).mapNotNull { warnings.opt(it) as? String },
            kontrollen = if (kontrollen == null) emptyList() else
                (0 until kontrollen.length()).map {
                    val kontrolle = kontrollen.getJSONObject(it)
                    val erledigt = parseUtc(requiredString(kontrolle, "erledigt_at"))
                    require(erledigt != null)
                    AsuewKontrolle(
                        requiredString(kontrolle, "stufe"), requiredString(kontrolle, "art"),
                        erledigt, optionalDouble(kontrolle, "niedrigster_druck"),
                    )
                },
            members = if (members == null) emptyList() else
                (0 until members.length()).map {
                    val member = members.getJSONObject(it)
                    AsuewMember(
                        requiredLong(member, "id"), requiredString(member, "name"),
                        requiredString(member, "role"), optionalDouble(member, "start_press"),
                        optionalDouble(member, "current_press"), optionalDouble(member, "withdraw_press"),
                    )
                },
        )
    }

    private fun requiredString(item: JSONObject, name: String): String {
        val value = item.get(name)
        require(value is String)
        return value
    }

    private fun requiredLong(item: JSONObject, name: String): Long {
        val value = optionalLong(item, name)
        require(value != null)
        return value
    }

    private fun optionalLong(item: JSONObject, name: String): Long? {
        if (item.isNull(name)) return null
        val value = item.opt(name)
        return when (value) {
            is Long -> value
            is Int -> value.toLong()
            else -> throw IllegalArgumentException("Ungültige Ganzzahl: $name")
        }
    }

    private fun optionalDouble(item: JSONObject, name: String): Double? {
        if (item.isNull(name)) return null
        val value = item.get(name)
        require(value is Number)
        val number = value.toDouble()
        require(!number.isNaN() && !number.isInfinite())
        return number
    }

    private fun optionalArray(item: JSONObject, name: String): JSONArray? =
        if (item.isNull(name)) null else item.getJSONArray(name)

    private fun optionalTime(item: JSONObject, name: String): Long? {
        if (item.isNull(name)) return null
        val time = parseUtc(requiredString(item, name))
        require(time != null)
        return time
    }

    private fun parseUtc(value: String): Long? = try {
        val match = utcPattern.matchEntire(value)
        if (match == null) null else {
            // Mikrosekunden auf Millisekunden abschneiden, kurze Bruchteile rechts auffüllen.
            val fraction = match.groupValues[2].take(3).padEnd(3, '0')
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }.parse("${match.groupValues[1]}.${fraction}Z")?.time
        }
    } catch (_: Exception) { null }

    fun clockOffsetMs(serverTimeMs: Long, localNowMs: Long): Long = serverTimeMs - localNowMs

    fun serverNow(localNowMs: Long, offsetMs: Long): Long = localNowMs + offsetMs

    fun serverToLocal(atServerMs: Long, offsetMs: Long): Long = atServerMs - offsetMs

    private fun isActive(troop: AsuewTroop): Boolean =
        troop.entryAtMs != null && (troop.status == "im_einsatz" || troop.status == "rueckzug")

    /** Gemeinsame Erledigungsprüfung für fällige Warnungen und kommende Wecker. */
    private fun pendingDeadlines(troop: AsuewTroop): List<AsuewDeadline> {
        if (!isActive(troop)) return emptyList()
        val entry = troop.entryAtMs ?: return emptyList()
        val result = mutableListOf<AsuewDeadline>()
        val max = troop.maxSeconds
        if (max != null && !troop.maxTimeAcked) {
            result.add(AsuewDeadline(troop.id, "max_exceeded", entry + max * 1000L))
        }
        val stages = listOf(
            Triple(troop.twoThirdSeconds, "two_third_due", "two_third"),
            Triple(troop.oneThirdSeconds, "one_third_due", "one_third"),
        )
        for ((seconds, kind, stufe) in stages) {
            if (seconds == null) continue
            val at = entry + seconds * 1000L
            val controlled = troop.kontrollen.any { it.art == "funk" && it.stufe == stufe }
            val pressureAt = troop.lastPressureAtMs
            if (!controlled && (pressureAt == null || pressureAt < at)) {
                result.add(AsuewDeadline(troop.id, kind, at))
            }
        }
        return result
    }

    fun timeWarnings(troop: AsuewTroop, nowServerMs: Long): List<String> =
        pendingDeadlines(troop).filter { nowServerMs >= it.atServerMs }.map { it.kind }

    fun activeAlarms(state: AsuewState, nowServerMs: Long): List<AsuewAlarm> {
        val alarms = mutableListOf<AsuewAlarm>()
        for (troop in state.troops) {
            val serverKinds = troop.serverWarnings.filter {
                it in severity && (isActive(troop) ||
                    (it == "notfall" && troop.notfallAtMs != null) ||
                    (it == "funk_verloren" && troop.funkVerlorenAtMs != null))
            }
            for (kind in (serverKinds + timeWarnings(troop, nowServerMs)).distinct()) {
                alarms.add(AsuewAlarm(troop.id, troop.name, kind, "${troop.id}:$kind:${troop.entryAtMs ?: 0L}"))
            }
        }
        return alarms.distinctBy { it.key }.sortedWith(
            compareBy<AsuewAlarm> { severity.indexOf(it.kind) }.thenBy { it.troopId },
        )
    }

    fun nextDeadlines(state: AsuewState, nowServerMs: Long): List<AsuewDeadline> =
        state.troops.flatMap { pendingDeadlines(it) }.filter { it.atServerMs > nowServerMs }
            .sortedWith(compareBy<AsuewDeadline> { it.atServerMs }.thenBy { it.troopId }.thenBy { it.kind })

    fun nextWakeupServerMs(state: AsuewState, nowServerMs: Long): Long? =
        nextDeadlines(state, nowServerMs).firstOrNull()?.atServerMs

    fun connectionStatus(lastOkElapsedMs: Long?, nowElapsedMs: Long): String = when {
        lastOkElapsedMs == null -> "verbindet"
        nowElapsedMs - lastOkElapsedMs <= 20_000L -> "ok"
        nowElapsedMs - lastOkElapsedMs <= 60_000L -> "gestoert"
        else -> "verloren"
    }

    fun batteryStatus(percent: Int?, charging: Boolean): String = when {
        percent == null -> "unbekannt"
        charging -> "ok"
        percent < 10 -> "kritisch"
        percent < 20 -> "warnung"
        else -> "ok"
    }

    fun notificationLines(state: AsuewState, nowServerMs: Long): List<String> =
        state.troops.filter { isActive(it) }.map { troop ->
            val elapsedSeconds = ((nowServerMs - (troop.entryAtMs ?: nowServerMs)) / 1000L).coerceAtLeast(0L)
            val duration = String.format(Locale.US, "%02d:%02d", elapsedSeconds / 60L, elapsedSeconds % 60L)
            val parts = mutableListOf("${troop.name}: $duration min")
            troop.members.mapNotNull { it.currentPress }.minOrNull()?.let {
                parts.add("niedrigster Druck ${formatPressure(it)} bar")
            }
            troop.members.mapNotNull { it.withdrawPress }.minOrNull()?.let {
                parts.add("Rückzug bei ${formatPressure(it)} bar")
            }
            parts.joinToString(", ")
        }

    private fun formatPressure(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString().replace('.', ',')
}

/** Quittierungen sind persistent; Wiederholungszeiten verwenden ausschließlich die monotone Uhr. */
class AsuewAlarmTracker {
    private val ackedKeys: MutableSet<String> = mutableSetOf()
    private val firedAt: MutableMap<String, Long> = mutableMapOf()

    val acked: Set<String> get() = ackedKeys.toSet()
    val lastAlarmAtElapsed: Map<String, Long> get() = firedAt.toMap()

    fun due(alarms: List<AsuewAlarm>, nowElapsedMs: Long, repeatMs: Long = 60_000L): List<AsuewAlarm> =
        alarms.distinctBy { it.key }.filter {
            val last = firedAt[it.key]
            val interval = if (it.kind == "notfall") 30_000L else repeatMs
            it.key !in ackedKeys && (last == null || nowElapsedMs - last >= interval)
        }

    fun markFired(key: String, nowElapsedMs: Long): Unit {
        firedAt[key] = nowElapsedMs
    }

    fun ack(key: String): Unit {
        ackedKeys.add(key)
    }

    fun prune(activeKeys: Set<String>): Unit {
        ackedKeys.retainAll(activeKeys)
        firedAt.keys.retainAll(activeKeys)
    }

    fun serializeAcked(): String = ackedKeys.sorted().joinToString(",")

    fun restoreAcked(csv: String): Unit {
        ackedKeys.clear()
        ackedKeys.addAll(csv.split(',').map { it.trim() }.filter { it.isNotEmpty() })
    }
}
