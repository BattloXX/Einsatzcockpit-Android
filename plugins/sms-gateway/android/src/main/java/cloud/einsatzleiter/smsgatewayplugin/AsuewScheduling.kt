package cloud.einsatzleiter.smsgatewayplugin

import org.json.JSONObject

/** Zeitumrechnung und Selbsttest bleiben unabhängig von Android-Diensten. */
object AsuewScheduling {
    fun triggerAtWallMs(nextWakeupServerMs: Long?, offsetMs: Long, nowWallMs: Long): Long? {
        if (nextWakeupServerMs == null) return null
        return maxOf(AsuewLogic.serverToLocal(nextWakeupServerMs, offsetMs), nowWallMs + 1000L)
    }

    fun exactAlarmMode(sdkInt: Int, canScheduleExact: Boolean): String =
        if (sdkInt < 31 || canScheduleExact) "alarm_clock" else "inexakt"

    fun isAlarmToneTestValid(testedAtMs: Long?, nowMs: Long): Boolean =
        testedAtMs != null && testedAtMs > 0L && testedAtMs <= nowMs &&
            nowMs - testedAtMs < 24 * 60 * 60_000L
}

object AsuewSelbsttest {
    fun build(
        notificationsEnabled: Boolean,
        online: Boolean,
        alarmToneTested: Boolean,
        exactAlarms: Boolean,
        fullScreen: Boolean,
        batteryOptimizationIgnored: Boolean,
        alarmVolumePercent: Int?,
    ): JSONObject = JSONObject().apply {
        put("benachrichtigung", if (notificationsEnabled) "granted" else "denied")
        put("online", online)
        put("alarmton_getestet", alarmToneTested)
        put("exakte_wecker", exactAlarms)
        put("vollbild", fullScreen)
        put("akku_optimierung_ausgenommen", batteryOptimizationIgnored)
        alarmVolumePercent?.let { put("alarm_lautstaerke_prozent", it) }
    }
}
