package app.ghostroute.coredata.net

/**
 * P2-4c/d: real-data labels for search results, shared by the sheets.
 * ANDROID-FREE per the engine/core-data rule: pure functions over the result
 * objects. NOTHING is invented — [geoContext] renders only what the upstream
 * result actually carried, with an explicit dash for a missing field (binding
 * ruling: "if a result lacks a field, show dash — never guess"). The joining
 * punctuation (" · ") is locale-neutral, like [GeocodeClient]'s ", " detail
 * join; the field text itself is whatever language the geocoder answered in
 * (it is asked with the UI locale, so context grows localized for free).
 */
object PlaceLabels {

    /** The explicit missing-field marker (both locales; not localized text). */
    const val DASH = "—"

    /** Parsed geographic trio from one upstream result object. */
    data class GeoFields(val city: String?, val region: String?, val country: String?)

    /**
     * (d) The geographic context line: `city · region · country` in that
     * order, each fragment present-or-dash. Always three cells — the row keeps
     * a stable shape so a dash is visibly "upstream had nothing".
     */
    fun geoContext(place: PlaceResult): String =
        listOf(place.city, place.region, place.country)
            .joinToString(" · ") { it?.trim()?.takeIf(String::isNotEmpty) ?: DASH }

    /**
     * (c) Names (trimmed, case-insensitive) occurring on MORE than one result
     * — the rows the sheet must disambiguate. Real input only: the results the
     * geocoder actually returned.
     */
    fun ambiguousNames(places: List<PlaceResult>): Set<String> =
        places.map { it.name.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
}
