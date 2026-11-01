package cloud.einsatzleiter.smsgatewayplugin

internal object EcpWidgetLogic {
    enum class Display { EMPTY, GSL_QUEUE, INCIDENT, GSL_LIVE }

    fun display(hasIncident: Boolean, hasQueue: Boolean, hasLive: Boolean): Display = when {
        !hasIncident && !hasQueue && !hasLive -> Display.EMPTY
        hasQueue -> Display.GSL_QUEUE
        hasIncident -> Display.INCIDENT
        else -> Display.GSL_LIVE
    }

    fun isExpanded(minHeight: Int): Boolean = minHeight >= 150

    fun incidentLabel(isExercise: Boolean, phase: String): String {
        val kind = if (isExercise) "LAUFENDE ÜBUNG" else "LAUFENDER EINSATZ"
        return "$kind · ${phase.uppercase()}"
    }

    fun gslLabel(isExercise: Boolean, name: String): String {
        val kind = if (isExercise) "LAUFENDE ÜBUNG" else "GROSSSCHADENSLAGE"
        return "$kind · $name"
    }

    fun countsLabel(counts: GslLiveCounts): String =
        "${counts.neu} neu · ${counts.inArbeit} in Arbeit · ${counts.erledigt} erledigt"
}
