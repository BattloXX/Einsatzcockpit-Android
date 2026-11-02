package cloud.einsatzleiter.smsgatewayplugin

/** Reine Retry-Entscheidung; der sofortige Versuch zählt mit. */
internal object PushAckLogic {
    const val MAX_ATTEMPTS = 5
    enum class Outcome { SUCCESS, RETRY, FAILURE }

    fun outcome(statusCode: Int?, completedAttempts: Int): Outcome = when {
        statusCode != null && statusCode in 200..299 -> Outcome.SUCCESS
        completedAttempts < MAX_ATTEMPTS && (statusCode == null || statusCode == 429 || statusCode in 500..599) -> Outcome.RETRY
        else -> Outcome.FAILURE
    }
}
