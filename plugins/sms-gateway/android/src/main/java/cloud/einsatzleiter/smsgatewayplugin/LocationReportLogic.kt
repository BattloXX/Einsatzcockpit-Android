package cloud.einsatzleiter.smsgatewayplugin

/** Reine Entscheidungslogik der Standortmeldung (ohne Android-Abhängigkeiten, JVM-testbar). */
internal object LocationReportLogic {
    const val PERIOD_HOURS = 6L
    const val FLEX_HOURS = 2L
    const val MIN_GAP_MS = 5 * 60 * 60_000L
    const val FRESH_MAX_AGE_MS = 10 * 60_000L
    const val MAX_AGE_MS = 24 * 60 * 60_000L
    const val NOW_THROTTLE_MS = 5 * 60_000L

    /** Messalter: frisch (<= 10 min), alt (<= 24 h) oder zu alt für eine Übermittlung. */
    fun classify(ageMs: Long?, mock: Boolean): String = when {
        ageMs == null -> "none"
        mock -> "mock"
        ageMs < 0 || ageMs > MAX_AGE_MS -> "stale"
        ageMs <= FRESH_MAX_AGE_MS -> "fresh"
        else -> "old"
    }

    /** Neue Bestimmung nur, wenn die letzte erfolgreiche Meldung lange genug zurückliegt. */
    fun due(now: Long, lastSuccess: Long, minGap: Long = MIN_GAP_MS): Boolean =
        lastSuccess == 0L || now < lastSuccess || now - lastSuccess >= minGap

    /** Server-Status, wenn keine nutzbare Position vorliegt. */
    fun statusFor(permission: Boolean, providerOn: Boolean, quality: String): String = when {
        !permission -> "permission_denied"
        !providerOn -> "location_disabled"
        quality == "mock" -> "mock_location"
        quality == "stale" -> "stale_fix"
        else -> "no_fix"
    }

    fun payload(
        now: Long, source: String, status: String, lat: Double?, lng: Double?, accuracy: Float?,
        measuredAt: Long?, backgroundGranted: Boolean, error: String?,
    ): String {
        val map = linkedMapOf<String, Any?>(
            "status" to status, "source" to source, "sent_at" to DiagnoseReportLogic.iso(now),
            "background_permission" to backgroundGranted,
        )
        if (status == "ok" && lat != null && lng != null && measuredAt != null) {
            map["lat"] = lat; map["lng"] = lng
            accuracy?.takeIf { it > 0 }?.let { map["accuracy"] = it.toDouble() }
            map["measured_at"] = DiagnoseReportLogic.iso(measuredAt)
        } else if (measuredAt != null) {
            map["measured_at"] = DiagnoseReportLogic.iso(measuredAt)
        }
        error?.let { map["error"] = DiagnoseReportLogic.mask(it) }
        return DiagnoseReportLogic.json(map)
    }
}
