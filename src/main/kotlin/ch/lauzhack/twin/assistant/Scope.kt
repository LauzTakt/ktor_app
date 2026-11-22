package ch.lauzhack.twin.assistant

/**
 * What the assistant cannot answer (no data): a cheap keyword check before any model call. A question that is only
 * about such topics gets a fixed answer at once; otherwise the detected topics are added to `unavailable`.
 */
object Scope {
    /** The values of [AskResponse.unavailable], in a stable order. */
    val TOPICS = listOf(
        "platforms", "accessibility", "fares", "tickets", "refunds", "occupancy", "luggage", "bikes",
        "bus_stop_location", "local_transport",
    )

    private fun rx(vararg words: String) = Regex(words.joinToString("|", "(?iu)(?<![\\p{L}])(?:", ")(?![\\p{L}])"))

    private val KEYWORDS: Map<String, Regex> = mapOf(
        "platforms" to rx("platforms?", "which track", "track number", "gleis", "gleise", "perron", "voie", "quai", "binario", "binari"),
        "accessibility" to rx("wheelchairs?", "step-free", "step free", "accessible", "accessibility", "lifts?", "elevators?",
            "rollstuhl", "barrierefrei", "fauteuil roulant", "ascenseurs?", "sedia a rotelle", "ascensori?", "accessibilit\\p{L}*"),
        "fares" to rx("fares?", "price", "prices", "how much", "cost", "costs", "preis", "kostet", "kosten", "prix", "combien",
            "prezzo", "quanto costa"),
        "tickets" to rx("tickets?", "billets?", "billett", "fahrkarte", "fahrschein", "biglietto", "biglietti", "halbtax",
            "half-fare", "travelcard", "season pass", "abo", "abonnement"),
        "refunds" to rx("refunds?", "refunded", "compensation", "money back", "rückerstattung", "entschädigung",
            "rembours\\p{L}*", "rimbors\\p{L}*"),
        "occupancy" to rx("crowded", "occupancy", "seats?", "free seats?", "besetzt", "auslastung", "sitzpl\\p{L}*", "bondé",
            "places assises", "affollato", "posti a sedere"),
        "luggage" to rx("luggage", "suitcases?", "baggage", "gepäck", "koffer", "bagages?", "valises?", "bagagli"),
        "bikes" to rx("bikes?", "bicycles?", "velo", "velos", "fahrrad", "fahrräder", "vélo", "vélos", "bici", "bicicletta"),
        "bus_stop_location" to rx("bus stop", "where (?:is|does) the (?:replacement )?bus", "haltestelle", "bushaltestelle",
            "arrêt de bus", "arrêt du bus", "fermata"),
        "local_transport" to rx("trams?", "tramway", "city bus", "local bus", "metro", "métro", "strassenbahn", "straßenbahn"),
    )

    /** Words that make a question about something the assistant can check (then the model answers it). */
    private val IN_SCOPE = rx(
        "delay\\p{L}*", "late", "disrupt\\p{L}*", "interrupt\\p{L}*", "cancel\\p{L}*", "next", "connection\\p{L}*", "when",
        "what time", "how long", "fastest", "route", "get to", "go to", "travel\\p{L}*", "journey", "stopped", "running",
        "verspät\\p{L}*", "ausfäll\\p{L}*", "ausfall", "unterbr\\p{L}*", "wann", "nächste\\p{L}*", "verbindung\\p{L}*",
        "retard\\p{L}*", "annul\\p{L}*", "interromp\\p{L}*", "prochain\\p{L}*", "quand", "ritard\\p{L}*", "cancellat\\p{L}*",
        "soppress\\p{L}*", "prossim\\p{L}*", "quando",
    )

    fun topicsOf(question: String): List<String> = TOPICS.filter { KEYWORDS.getValue(it).containsMatchIn(question) }

    /** Topics when the question is only about them (no cue of a timetable or disruption question), else null. */
    fun onlyOutOfScope(question: String): List<String>? {
        val q = question.trim()
        if (q.isEmpty() || q.length > 160) return null
        val topics = topicsOf(q)
        return topics.takeIf { it.isNotEmpty() && !IN_SCOPE.containsMatchIn(q) }
    }

    /** The fixed answer, in the question's language (a guess from its words). */
    fun answer(question: String, topics: List<String>): String {
        val lang = language(question)
        val names = topics.map { NAMES.getValue(lang)[TOPICS.indexOf(it)] }.distinct()
        val list = when (names.size) {
            1 -> names[0]
            else -> names.dropLast(1).joinToString(", ") + AND.getValue(lang) + names.last()
        }
        return TEMPLATE.getValue(lang).replace("{x}", list)
    }

    /** "where is my train?", "is my train late?", "track my train", "my connection" (EN/DE/FR/IT). */
    private val MY_TRAIN = rx(
        "my train", "my connection", "track my", "my ride", "mein zug", "meinen zug", "meinem zug", "meine verbindung",
        "mon train", "ma correspondance", "il mio treno", "mio treno", "la mia coincidenza",
    )
    private val TIME = Regex("(?<!\\d)([01]?\\d|2[0-3])[:.h]([0-5]\\d)(?!\\d)|(?i)\\b(?:at|um|à|alle)\\s+\\d{1,2}\\b")

    fun isMyTrain(question: String): Boolean = MY_TRAIN.containsMatchIn(question)
    fun namesTime(question: String): Boolean = TIME.containsMatchIn(question)

    fun whichTrain(question: String): String = WHICH_TRAIN.getValue(language(question))
    private val WHICH_TRAIN = mapOf(
        "en" to "Which train are you on? Pick one on the screen, or tell me the line and time.",
        "de" to "In welchem Zug sind Sie? Wählen Sie ihn auf dem Bildschirm, oder nennen Sie mir Linie und Zeit.",
        "fr" to "Dans quel train êtes-vous ? Choisissez-le à l'écran, ou dites-moi la ligne et l'heure.",
        "it" to "Su quale treno si trova? Lo scelga sullo schermo, oppure mi dica la linea e l'orario.",
    )

    private fun language(q: String): String {
        val s = " " + q.lowercase() + " "
        fun score(vararg w: String) = w.count { s.contains(it) }
        val scores = mapOf(
            "de" to score(" ich ", " der ", " die ", " das ", " ist ", " wo ", " gibt ", " mein", " kann ", "gleis", " zug"),
            "fr" to score(" je ", " le ", " la ", " est ", " où ", " mon ", " puis", " quel", " des ", "train ", " voie"),
            "it" to score(" il ", " io ", " dove ", " è ", " mio ", " posso ", " quale", " treno", " binario", " della"),
            "en" to score(" i ", " the ", " is ", " where ", " my ", " can ", " which ", " train", " what "),
        )
        return scores.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key ?: "en"
    }

    private val TEMPLATE = mapOf(
        "en" to "I'm sorry, I don't have information on {x}. The station staff or the SBB app can tell you.",
        "de" to "Entschuldigung, zu {x} habe ich keine Informationen. Das Bahnhofspersonal oder die SBB-App helfen Ihnen weiter.",
        "fr" to "Désolé, je n'ai pas d'informations sur {x}. Le personnel de la gare ou l'app CFF peuvent vous renseigner.",
        "it" to "Mi dispiace, non ho informazioni su {x}. Il personale della stazione o l'app FFS possono aiutarla.",
    )
    private val AND = mapOf("en" to " or ", "de" to " oder ", "fr" to " ou ", "it" to " o ")

    // Same order as TOPICS.
    private val NAMES = mapOf(
        "en" to listOf("platforms", "accessibility", "fares", "tickets", "refunds", "how full trains are", "luggage",
            "bikes", "the exact bus stop location", "local transport such as trams and city buses"),
        "de" to listOf("Gleisen", "Barrierefreiheit", "Preisen", "Billetten", "Rückerstattungen", "der Auslastung", "Gepäck",
            "Velos", "dem genauen Standort der Bushaltestelle", "dem Ortsverkehr wie Trams und Stadtbussen"),
        "fr" to listOf("les voies", "l'accessibilité", "les tarifs", "les billets", "les remboursements", "l'occupation des trains",
            "les bagages", "les vélos", "l'emplacement exact de l'arrêt de bus", "les transports locaux comme les trams et bus urbains"),
        "it" to listOf("i binari", "l'accessibilità", "le tariffe", "i biglietti", "i rimborsi", "l'occupazione dei treni",
            "i bagagli", "le bici", "la posizione esatta della fermata del bus", "i trasporti locali come tram e bus urbani"),
    )
}
