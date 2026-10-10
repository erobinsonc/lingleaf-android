package app.lingleaf.core

/**
 * Which language to translate into (SPEC S14). Codes are the app's own
 * language codes ("es", "pt", "zh"…); [preferred] holds BCP 47 tags in the
 * order of the device's preferred-language list ("pt-BR", "zh-Hant-TW").
 */
object TranslationLanguages {
    /** Languages offered first when the learner has to choose, after the region's language. */
    val suggested = listOf("es", "pt", "fr", "zh", "ar")

    /** At most this many languages are suggested; a "More languages" choice follows them. */
    const val MAX_SUGGESTIONS = 5

    /**
     * The first language in [preferred] that is supported and not English, or
     * null when there is none (then the learner is asked). Any Chinese variant
     * maps to Simplified Chinese ("zh-Hans" when supported, otherwise "zh").
     */
    fun pick(
        preferred: List<String>,
        supported: List<String>,
    ): String? =
        preferred
            .asSequence()
            .map(::languageOf)
            .filter { it != ENGLISH }
            .mapNotNull { resolve(it, supported) }
            .firstOrNull()

    /**
     * Languages to offer when [pick] found none: the language spoken in
     * [region] (an ISO 3166 code such as "BR") when supported, then
     * [suggested], without repeats, at most [MAX_SUGGESTIONS].
     */
    fun suggestions(
        region: String?,
        supported: List<String>,
    ): List<String> {
        val regional = region?.uppercase()?.let(REGION_LANGUAGES::get)
        return (listOfNotNull(regional) + suggested)
            .mapNotNull { resolve(it, supported) }
            .distinct()
            .take(MAX_SUGGESTIONS)
    }

    /** "pt-BR" → "pt", "zh_Hant_TW" → "zh", "ES" → "es". */
    private fun languageOf(tag: String) = tag.substringBefore('-').substringBefore('_').lowercase()

    private fun resolve(
        language: String,
        supported: List<String>,
    ): String? =
        if (language == CHINESE) {
            listOf(SIMPLIFIED_CHINESE, CHINESE).firstOrNull { it in supported }
        } else {
            language.takeIf { it in supported }
        }

    private const val ENGLISH = "en"
    private const val CHINESE = "zh"
    private const val SIMPLIFIED_CHINESE = "zh-Hans"

    /** Main language of countries whose language Lingleaf can translate into. */
    private val REGION_LANGUAGES: Map<String, String> =
        buildMap {
            fun add(
                language: String,
                vararg regions: String,
            ) = regions.forEach { put(it, language) }
            add(
                "es", "ES", "MX", "AR", "CO", "CL", "PE", "VE", "EC", "GT", "CU", "BO", "DO", "HN", "PY", "SV", "NI", "CR", "PA",
                "UY", "PR", "GQ",
            )
            add("pt", "BR", "PT", "AO", "MZ", "CV", "GW", "ST", "TL")
            add("fr", "FR", "BE", "LU", "MC", "SN", "CI", "CM", "ML", "BF", "NE", "TG", "BJ", "GN", "CD", "CG", "GA", "MG", "HT")
            add(
                "ar", "EG", "SA", "AE", "MA", "DZ", "TN", "LY", "SD", "IQ", "JO", "LB", "SY", "KW", "QA", "BH", "OM", "YE", "PS",
                "MR",
            )
            add("it", "IT", "SM", "VA")
            add("de", "DE", "AT", "CH", "LI")
            add("nl", "NL", "SR")
            add("pl", "PL")
            add("uk", "UA")
            add("ru", "RU", "BY", "KZ", "KG")
            add("tr", "TR")
            add("hi", "IN")
            add("id", "ID")
            add("vi", "VN")
            add("zh", "CN", "TW", "HK", "MO")
            add("ja", "JP")
            add("ko", "KR")
            add("th", "TH")
        }
}
