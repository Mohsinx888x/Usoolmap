package com.usool.map.search

/** Makes text comparable across spelling variants: case, Arabic diacritics and letter forms, "ال". */
object TextNormalizer {
    private val DIACRITICS = Regex("[\\u064B-\\u065F\\u0670\\u0640]")

    fun normalize(text: String): String {
        val s = DIACRITICS.replace(text.trim().lowercase(), "")
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي').replace('ة', 'ه')
            .replace(Regex("[؟?!.,،]"), " ")
        return s.split(Regex("\\s+")).filter { it.isNotEmpty() }
            .joinToString(" ") { w -> if (w.startsWith("ال") && w.length > 4) w.substring(2) else w }
    }
}

/**
 * A kind of place people look for ("halal restaurant", "hotel"). It is recognised from words in
 * several languages and searched by its OpenStreetMap category, so the search works in any
 * country whatever language the places are named in.
 */
class PoiConcept(
    val id: String,
    private val labels: Map<String, String>,
    triggers: List<String>,
    /** Category keywords understood by the geocoder, e.g. "restaurant". */
    val keywords: List<String>,
    /** OSM `category=type` pairs a result must have. */
    val types: Set<String>,
    /** True when the place must also be marked or named halal. */
    val halal: Boolean = false,
) {
    val triggers: List<String> = triggers.map { TextNormalizer.normalize(it) }

    fun label(language: String): String = labels[language.substringBefore('-').lowercase()] ?: labels.getValue("en")
}

object PoiConcepts {
    private val FOOD = setOf("amenity=restaurant", "amenity=fast_food", "amenity=cafe", "amenity=food_court")

    /**
     * How "halal" is written on shop signs and in place names, by language. In Chinese the
     * established term is 清真 (qīngzhēn, "pure and true"); 穆斯林 (mùsīlín) means "Muslim".
     */
    private val HALAL_TERMS = mapOf(
        "zh" to listOf("清真", "穆斯林"), "ja" to listOf("ハラール", "ハラル"), "ko" to listOf("할랄"),
        "th" to listOf("ฮาลาล"), "ru" to listOf("халяль"), "tr" to listOf("helal"),
        "hi" to listOf("हलाल"), "ar" to listOf("حلال"), "fa" to listOf("حلال"), "ur" to listOf("حلال"),
    )

    /** Terms to look for in the language spoken where the map is, plus the international spelling. */
    fun halalTerms(areaLanguage: String): List<String> =
        (HALAL_TERMS[areaLanguage.substringBefore('-').lowercase()].orEmpty() + "halal").distinct()

    val all: List<PoiConcept> = listOf(
        PoiConcept("halal_restaurant", mapOf("en" to "Halal restaurant", "ar" to "مطعم حلال", "fr" to "Restaurant halal", "es" to "Restaurante halal", "hi" to "हलाल रेस्तरां", "zh" to "清真餐厅"),
            listOf("مطعم حلال", "مطاعم حلال", "مطعم مسلمين", "مطاعم مسلمين", "مطعم اسلامي", "اكل حلال", "طعام حلال", "حلال", "halal", "halal restaurant", "halal food", "muslim restaurant", "muslim food", "清真", "清真餐厅", "穆斯林餐厅", "穆斯林"),
            listOf("restaurant", "fast_food"), FOOD, halal = true),
        PoiConcept("restaurant", mapOf("en" to "Restaurant", "ar" to "مطعم", "fr" to "Restaurant", "es" to "Restaurante", "hi" to "रेस्तरां", "zh" to "餐厅"),
            listOf("مطعم", "مطاعم", "اكل", "restaurant", "restaurants", "food", "餐厅", "饭店", "餐馆"), listOf("restaurant", "fast_food"), setOf("amenity=restaurant", "amenity=fast_food", "amenity=food_court")),
        PoiConcept("hotel", mapOf("en" to "Hotel", "ar" to "فندق", "fr" to "Hôtel", "es" to "Hotel", "hi" to "होटल", "zh" to "酒店"),
            listOf("فندق", "فنادق", "نزل", "سكن", "hotel", "hotels", "hostel", "酒店", "宾馆", "旅馆"), listOf("hotel", "hostel"), setOf("tourism=hotel", "tourism=hostel", "tourism=guest_house", "tourism=motel")),
        PoiConcept("mosque", mapOf("en" to "Mosque", "ar" to "مسجد", "fr" to "Mosquée", "es" to "Mezquita", "hi" to "मस्जिद", "zh" to "清真寺"),
            listOf("مسجد", "مساجد", "جامع", "مصلى", "mosque", "mosques", "masjid", "清真寺"), listOf("mosque"), setOf("amenity=place_of_worship", "building=mosque", "amenity=mosque")),
        PoiConcept("cafe", mapOf("en" to "Café", "ar" to "مقهى", "fr" to "Café", "es" to "Cafetería", "hi" to "कैफ़े", "zh" to "咖啡馆"),
            listOf("مقهى", "مقاهي", "كافيه", "قهوه", "كوفي", "cafe", "coffee", "coffee shop", "咖啡", "咖啡馆"), listOf("cafe"), setOf("amenity=cafe")),
        PoiConcept("pharmacy", mapOf("en" to "Pharmacy", "ar" to "صيدلية", "fr" to "Pharmacie", "es" to "Farmacia", "hi" to "दवा की दुकान", "zh" to "药店"),
            listOf("صيدليه", "صيدليات", "pharmacy", "drugstore", "chemist", "药店", "药房"), listOf("pharmacy"), setOf("amenity=pharmacy")),
        PoiConcept("hospital", mapOf("en" to "Hospital", "ar" to "مستشفى", "fr" to "Hôpital", "es" to "Hospital", "hi" to "अस्पताल", "zh" to "医院"),
            listOf("مستشفى", "مستشفيات", "طوارئ", "عياده", "hospital", "clinic", "emergency", "医院"), listOf("hospital"), setOf("amenity=hospital", "amenity=clinic")),
        PoiConcept("atm", mapOf("en" to "ATM", "ar" to "صراف آلي", "fr" to "Distributeur", "es" to "Cajero", "hi" to "एटीएम", "zh" to "取款机"),
            listOf("صراف", "صراف الي", "صرافه", "atm", "cash machine", "取款机"), listOf("atm"), setOf("amenity=atm")),
        PoiConcept("bank", mapOf("en" to "Bank", "ar" to "بنك", "fr" to "Banque", "es" to "Banco", "hi" to "बैंक", "zh" to "银行"),
            listOf("بنك", "بنوك", "مصرف", "bank", "banks", "银行"), listOf("bank"), setOf("amenity=bank")),
        PoiConcept("fuel", mapOf("en" to "Fuel station", "ar" to "محطة وقود", "fr" to "Station-service", "es" to "Gasolinera", "hi" to "पेट्रोल पंप", "zh" to "加油站"),
            listOf("محطه وقود", "محطه بنزين", "بنزين", "وقود", "بترول", "petrol", "gas station", "fuel", "petrol station", "加油站"), listOf("fuel"), setOf("amenity=fuel")),
        PoiConcept("supermarket", mapOf("en" to "Supermarket", "ar" to "سوبرماركت", "fr" to "Supermarché", "es" to "Supermercado", "hi" to "सुपरमार्केट", "zh" to "超市"),
            listOf("سوبرماركت", "سوبر ماركت", "بقاله", "هايبر", "supermarket", "grocery", "超市"), listOf("supermarket"), setOf("shop=supermarket", "shop=convenience")),
        PoiConcept("mall", mapOf("en" to "Shopping mall", "ar" to "مركز تسوق", "fr" to "Centre commercial", "es" to "Centro comercial", "hi" to "मॉल", "zh" to "商场"),
            listOf("مول", "مركز تسوق", "مجمع تجاري", "سوق", "تسوق", "mall", "shopping", "shopping mall", "商场", "购物中心"), listOf("mall"), setOf("shop=mall")),
        PoiConcept("attraction", mapOf("en" to "Attraction", "ar" to "معلم سياحي", "fr" to "Attraction", "es" to "Atracción", "hi" to "पर्यटन स्थल", "zh" to "景点"),
            listOf("معالم", "معلم سياحي", "معالم سياحيه", "سياحه", "اماكن سياحيه", "attraction", "attractions", "sights", "sightseeing", "things to do", "景点"), listOf("attraction", "museum"), setOf("tourism=attraction", "tourism=museum", "tourism=viewpoint")),
        PoiConcept("museum", mapOf("en" to "Museum", "ar" to "متحف", "fr" to "Musée", "es" to "Museo", "hi" to "संग्रहालय", "zh" to "博物馆"),
            listOf("متحف", "متاحف", "museum", "museums", "博物馆"), listOf("museum"), setOf("tourism=museum")),
        PoiConcept("park", mapOf("en" to "Park", "ar" to "حديقة", "fr" to "Parc", "es" to "Parque", "hi" to "पार्क", "zh" to "公园"),
            listOf("حديقه", "حدائق", "منتزه", "park", "parks", "garden", "公园"), listOf("park"), setOf("leisure=park", "leisure=garden")),
        PoiConcept("parking", mapOf("en" to "Parking", "ar" to "موقف سيارات", "fr" to "Parking", "es" to "Aparcamiento", "hi" to "पार्किंग", "zh" to "停车场"),
            listOf("موقف", "مواقف", "موقف سيارات", "parking", "car park", "停车场"), listOf("parking"), setOf("amenity=parking")),
        PoiConcept("charging", mapOf("en" to "EV charging", "ar" to "شحن سيارات كهربائية", "fr" to "Borne de recharge", "es" to "Punto de carga", "hi" to "ईवी चार्जिंग", "zh" to "充电站"),
            listOf("شحن سيارات", "شاحن سيارات", "محطه شحن", "ev charging", "charging station", "charger", "充电站"), listOf("charging_station"), setOf("amenity=charging_station")),
        PoiConcept("airport", mapOf("en" to "Airport", "ar" to "مطار", "fr" to "Aéroport", "es" to "Aeropuerto", "hi" to "हवाई अड्डा", "zh" to "机场"),
            listOf("مطار", "مطارات", "airport", "机场"), listOf("aerodrome"), setOf("aeroway=aerodrome")),
        PoiConcept("station", mapOf("en" to "Station", "ar" to "محطة", "fr" to "Gare", "es" to "Estación", "hi" to "स्टेशन", "zh" to "车站"),
            listOf("مترو", "محطه مترو", "محطه قطار", "قطار", "محطه", "metro", "subway", "train station", "station", "地铁", "地铁站", "火车站"), listOf("station"), setOf("railway=station", "railway=halt", "railway=subway_entrance", "public_transport=station")),
        PoiConcept("police", mapOf("en" to "Police", "ar" to "شرطة", "fr" to "Police", "es" to "Policía", "hi" to "पुलिस", "zh" to "派出所"),
            listOf("شرطه", "مركز شرطه", "police", "police station", "派出所", "警察"), listOf("police"), setOf("amenity=police")),
        PoiConcept("toilets", mapOf("en" to "Toilets", "ar" to "دورة مياه", "fr" to "Toilettes", "es" to "Aseos", "hi" to "शौचालय", "zh" to "厕所"),
            listOf("حمام", "حمامات", "دوره مياه", "دورات مياه", "مرحاض", "toilet", "toilets", "wc", "restroom", "厕所", "洗手间"), listOf("toilets"), setOf("amenity=toilets")),
        PoiConcept("embassy", mapOf("en" to "Embassy", "ar" to "سفارة", "fr" to "Ambassade", "es" to "Embajada", "hi" to "दूतावास", "zh" to "大使馆"),
            listOf("سفاره", "سفارات", "قنصليه", "embassy", "consulate", "大使馆", "领事馆"), listOf("embassy"), setOf("amenity=embassy", "office=diplomatic")),
    )

    /**
     * The concept a search text names, or null for an ordinary name or address. The longest
     * matching phrase wins, so "مطعم حلال" is a halal restaurant rather than any restaurant.
     */
    fun match(query: String): PoiConcept? {
        val q = " " + TextNormalizer.normalize(query) + " "
        var best: PoiConcept? = null
        var bestLength = 0
        for (concept in all) for (trigger in concept.triggers) {
            val hit = if (trigger.any { it.code > 0x2E80 }) q.contains(trigger) else q.contains(" $trigger ")
            if (hit && trigger.length > bestLength) { best = concept; bestLength = trigger.length }
        }
        // Only treat it as a category when the category words make up most of what was typed.
        return best?.takeIf { bestLength * 2 >= q.trim().length }
    }
}

/** Main language of place names by country, used to translate a search into what is on the map. */
object AreaLanguage {
    private val BY_COUNTRY = mapOf(
        "CN" to "zh", "TW" to "zh", "HK" to "zh", "MO" to "zh", "JP" to "ja", "KR" to "ko", "TH" to "th", "VN" to "vi",
        "RU" to "ru", "BY" to "ru", "KZ" to "ru", "UA" to "uk", "TR" to "tr", "IR" to "fa", "PK" to "ur", "IN" to "hi", "BD" to "bn",
        "ID" to "id", "MY" to "ms", "GR" to "el", "IL" to "he", "FR" to "fr", "DE" to "de", "AT" to "de", "IT" to "it", "NL" to "nl",
        "ES" to "es", "MX" to "es", "AR" to "es", "CO" to "es", "CL" to "es", "PE" to "es", "BR" to "pt", "PT" to "pt", "PL" to "pl",
        "SA" to "ar", "QA" to "ar", "AE" to "ar", "KW" to "ar", "BH" to "ar", "OM" to "ar", "EG" to "ar", "JO" to "ar", "IQ" to "ar",
        "SY" to "ar", "LB" to "ar", "MA" to "ar", "DZ" to "ar", "TN" to "ar", "LY" to "ar", "SD" to "ar", "YE" to "ar", "PS" to "ar",
    )

    fun forCountry(code: String?): String = BY_COUNTRY[code?.uppercase()] ?: "en"
}
