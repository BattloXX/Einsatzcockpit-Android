package cloud.einsatzleiter.smsgatewayplugin

/** Tonauswahl und Normalisierung ohne Android-Abhaengigkeiten. */
object AlarmTone {
    const val PREF_KEY = "el_alarm_tone"
    const val DEFAULT = "standard"

    val displayNames = mapOf(
        DEFAULT to "Standard-Alarm",
        "sirene" to "Sirene",
        "martinshorn" to "Martinshorn",
    )
    val ids = displayNames.keys.toList()

    fun normalize(id: String?): String = id?.takeIf { it in displayNames } ?: DEFAULT
}

fun rawRes(id: String): Int = when (AlarmTone.normalize(id)) {
    "sirene" -> R.raw.alarm_sirene
    "martinshorn" -> R.raw.alarm_martinshorn
    else -> R.raw.einsatz_alarm
}
