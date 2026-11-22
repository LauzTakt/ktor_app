package ch.lauzhack.twin.query

/**
 * Incident severity, most severe first. The one mapping to rank, map colour and wording, shared by the REST API, the
 * WebSocket, push notifications, the passenger app and the assistant.
 */
enum class Severity(val colour: String, val word: String) {
    CRITICAL("red", "Severe disruption"),
    MAJOR("red", "Disruption"),
    MINOR("orange", "Incident"),
    INFO("blue", "Information");

    /** Wire value: `critical`, `major`, `minor`, `info`. */
    val id: String = name.lowercase()

    /** 0 = most severe. */
    val rank: Int get() = ordinal

    companion object {
        /** Wire values, most severe first. */
        val IDS: List<String> = entries.map { it.id }

        /** The severity named [s] (any case), or null when it is not one. */
        fun of(s: String?): Severity? = s?.trim()?.lowercase()?.let { v -> entries.find { it.id == v } }

        /** The severity named [s]; unknown or missing reads as [INFO]. */
        fun parse(s: String?): Severity = of(s) ?: INFO

        /** sbb-twin disruption `effect` -> severity: interrupted = critical, limited = major, otherwise [unknown]. */
        fun fromEffect(effect: String?, unknown: Severity = INFO): Severity = when (effect) {
            "interrupted" -> CRITICAL
            "limited" -> MAJOR
            else -> unknown
        }
    }
}
