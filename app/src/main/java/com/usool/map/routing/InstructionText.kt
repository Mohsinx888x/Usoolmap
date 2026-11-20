package com.usool.map.routing

/**
 * Built-in wording for the professional voice and the offline router. It needs no network and no
 * AI service. Languages without a table here fall back to English.
 */
class InstructionText private constructor(private val t: Map<String, String>) {
    fun maneuver(type: ManeuverType, street: String?, exit: Int?): String {
        val base = when (type) {
            ManeuverType.DEPART -> t.getValue("depart")
            ManeuverType.ARRIVE -> return t.getValue("arrive")
            ManeuverType.ROUNDABOUT -> t.getValue("roundabout").replace("{n}", (exit ?: 1).toString())
            ManeuverType.CONTINUE, ManeuverType.OTHER -> t.getValue("continue")
            ManeuverType.TRANSIT_BOARD, ManeuverType.TRANSIT_ALIGHT -> t.getValue("continue")
            else -> t.getValue(type.name.lowercase())
        }
        return if (street.isNullOrBlank()) "$base." else t.getValue("onto").replace("{a}", base).replace("{s}", street) + "."
    }

    /** "In 300 meters, turn right." [distance] is already formatted in the user's units. */
    fun inDistance(distance: String, instruction: String): String =
        t.getValue("in").replace("{d}", spokenUnits(distance)).replace("{i}", instruction)

    fun now(instruction: String): String = instruction

    val offRoute: String get() = t.getValue("reroute")
    val signalLost: String get() = t.getValue("signal_lost")
    val signalBack: String get() = t.getValue("signal_back")
    fun speedCamera(distance: String): String = t.getValue("camera").replace("{d}", spokenUnits(distance))

    private fun spokenUnits(d: String): String = d
        .replace(Regex("""(\d) m$"""), "$1 ${t.getValue("u_m")}")
        .replace(Regex("""(\d) km$"""), "$1 ${t.getValue("u_km")}")
        .replace(Regex("""(\d) ft$"""), "$1 ${t.getValue("u_ft")}")
        .replace(Regex("""(\d) mi$"""), "$1 ${t.getValue("u_mi")}")

    companion object {
        private val EN = mapOf(
            "depart" to "Head out", "arrive" to "You have arrived at your destination.", "continue" to "Continue",
            "slight_left" to "Keep left", "left" to "Turn left", "sharp_left" to "Turn sharp left",
            "slight_right" to "Keep right", "right" to "Turn right", "sharp_right" to "Turn sharp right",
            "uturn" to "Make a U-turn", "roundabout" to "At the roundabout, take exit {n}", "merge" to "Merge",
            "exit_left" to "Take the exit on the left", "exit_right" to "Take the exit on the right", "ferry" to "Take the ferry",
            "onto" to "{a} onto {s}", "in" to "In {d}, {i}", "reroute" to "Recalculating the route.",
            "signal_lost" to "Satellite signal lost.", "signal_back" to "Satellite signal restored.",
            "camera" to "Speed camera reported in {d}.",
            "u_m" to "meters", "u_km" to "kilometers", "u_ft" to "feet", "u_mi" to "miles",
        )
        private val AR = EN + mapOf(
            "depart" to "انطلق", "arrive" to "لقد وصلت إلى وجهتك.", "continue" to "تابع السير",
            "slight_left" to "الزم اليسار", "left" to "انعطف يسارًا", "sharp_left" to "انعطف يسارًا بحدة",
            "slight_right" to "الزم اليمين", "right" to "انعطف يمينًا", "sharp_right" to "انعطف يمينًا بحدة",
            "uturn" to "قم بالدوران للخلف", "roundabout" to "عند الدوار، اسلك المخرج رقم {n}", "merge" to "اندمج مع الطريق",
            "exit_left" to "اسلك المخرج على اليسار", "exit_right" to "اسلك المخرج على اليمين", "ferry" to "استقل العبّارة",
            "onto" to "{a} إلى {s}", "in" to "بعد {d}، {i}", "reroute" to "جارٍ إعادة حساب المسار.",
            "signal_lost" to "انقطعت إشارة الأقمار الصناعية.", "signal_back" to "عادت إشارة الأقمار الصناعية.",
            "camera" to "كاميرا سرعة مُبلّغ عنها بعد {d}.",
            "u_m" to "متر", "u_km" to "كيلومتر", "u_ft" to "قدم", "u_mi" to "ميل",
        )
        private val FR = EN + mapOf(
            "depart" to "Partez", "arrive" to "Vous êtes arrivé à destination.", "continue" to "Continuez",
            "slight_left" to "Serrez à gauche", "left" to "Tournez à gauche", "sharp_left" to "Tournez franchement à gauche",
            "slight_right" to "Serrez à droite", "right" to "Tournez à droite", "sharp_right" to "Tournez franchement à droite",
            "uturn" to "Faites demi-tour", "roundabout" to "Au rond-point, prenez la sortie {n}", "merge" to "Insérez-vous",
            "exit_left" to "Prenez la sortie à gauche", "exit_right" to "Prenez la sortie à droite", "ferry" to "Prenez le ferry",
            "onto" to "{a} sur {s}", "in" to "Dans {d}, {i}", "reroute" to "Recalcul de l'itinéraire.",
            "signal_lost" to "Signal satellite perdu.", "signal_back" to "Signal satellite rétabli.",
            "camera" to "Radar signalé dans {d}.",
            "u_m" to "mètres", "u_km" to "kilomètres", "u_ft" to "pieds", "u_mi" to "miles",
        )
        private val ES = EN + mapOf(
            "depart" to "Inicie la marcha", "arrive" to "Ha llegado a su destino.", "continue" to "Continúe",
            "slight_left" to "Manténgase a la izquierda", "left" to "Gire a la izquierda", "sharp_left" to "Gire bruscamente a la izquierda",
            "slight_right" to "Manténgase a la derecha", "right" to "Gire a la derecha", "sharp_right" to "Gire bruscamente a la derecha",
            "uturn" to "Haga un cambio de sentido", "roundabout" to "En la rotonda, tome la salida {n}", "merge" to "Incorpórese",
            "exit_left" to "Tome la salida a la izquierda", "exit_right" to "Tome la salida a la derecha", "ferry" to "Tome el ferry",
            "onto" to "{a} hacia {s}", "in" to "En {d}, {i}", "reroute" to "Recalculando la ruta.",
            "signal_lost" to "Señal de satélite perdida.", "signal_back" to "Señal de satélite recuperada.",
            "camera" to "Radar de velocidad notificado en {d}.",
            "u_m" to "metros", "u_km" to "kilómetros", "u_ft" to "pies", "u_mi" to "millas",
        )
        private val HI = EN + mapOf(
            "depart" to "चलना शुरू करें", "arrive" to "आप अपनी मंज़िल पर पहुँच गए हैं।", "continue" to "सीधे चलते रहें",
            "slight_left" to "बाएँ रहें", "left" to "बाएँ मुड़ें", "sharp_left" to "तेज़ी से बाएँ मुड़ें",
            "slight_right" to "दाएँ रहें", "right" to "दाएँ मुड़ें", "sharp_right" to "तेज़ी से दाएँ मुड़ें",
            "uturn" to "यू-टर्न लें", "roundabout" to "गोल चक्कर पर निकास {n} लें", "merge" to "सड़क में शामिल हों",
            "exit_left" to "बाईं ओर का निकास लें", "exit_right" to "दाईं ओर का निकास लें", "ferry" to "फ़ेरी लें",
            "onto" to "{s} पर {a}", "in" to "{d} के बाद, {i}", "reroute" to "रास्ता दोबारा निकाला जा रहा है।",
            "signal_lost" to "सैटेलाइट सिग्नल चला गया।", "signal_back" to "सैटेलाइट सिग्नल वापस आ गया।",
            "camera" to "{d} में स्पीड कैमरा बताया गया है।",
            "u_m" to "मीटर", "u_km" to "किलोमीटर", "u_ft" to "फ़ुट", "u_mi" to "मील",
        )
        private val TABLES = mapOf("en" to EN, "ar" to AR, "fr" to FR, "es" to ES, "hi" to HI)

        val builtInLanguages: Set<String> get() = TABLES.keys

        fun forLanguage(tag: String): InstructionText =
            InstructionText(TABLES[tag.substringBefore('-').lowercase()] ?: EN)
    }
}
