package cloud.einsatzleiter.smsgatewayplugin

/** null features bedeutet unbekannt/Legacy: bestehende Endpunkte weiter versuchen. */
internal object ServerCapabilitiesLogic {
    const val CACHE_MS = 6 * 60 * 60_000L
    const val UNSUPPORTED_MS = 24 * 60 * 60_000L

    fun fresh(now: Long, fetchedAt: Long): Boolean = fetchedAt > 0 && now >= fetchedAt && now - fetchedAt < CACHE_MS

    fun shouldSkip(feature: String, features: Set<String>?, now: Long, unsupportedUntil: Long): Boolean =
        now < unsupportedUntil || (features != null && feature !in features)

    fun unsupportedResponse(statusCode: Int): Boolean = statusCode == 404
}
