package app.ghostroute.coredata.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-4c/d: real-data labels for search results. Context lines NEVER invent —
 * every fragment comes from the result object's own structured fields, and a
 * missing field renders as a dash (binding ruling), never a guess. Ambiguity
 * = the same (trimmed, case-insensitive) name appearing on >1 result.
 */
class PlaceLabelsTest {

    private fun place(
        name: String,
        city: String? = null,
        region: String? = null,
        country: String? = null,
    ) = PlaceResult(name, "", 0.0, 0.0, city = city, region = region, country = country)

    // --- geoContext: (d) geographic labels from the result's own fields ---

    @Test
    fun geoContextJoinsCityRegionCountryInOrder() {
        val line = PlaceLabels.geoContext(place("Tehran", city = "Tehran", region = "Tehran Province", country = "Iran"))
        assertEquals("Tehran · Tehran Province · Iran", line)
    }

    @Test
    fun geoContextMissingFieldsRenderDash_neverGuess() {
        val line = PlaceLabels.geoContext(place("Somewhere", region = "Kerman"))
        assertEquals("— · Kerman · —", line)
    }

    @Test
    fun geoContextAllFieldsMissingIsAllDashes() {
        assertEquals("— · — · —", PlaceLabels.geoContext(place("Bare")))
    }

    @Test
    fun geoContextBlankFieldCountsAsMissing() {
        val line = PlaceLabels.geoContext(place("X", city = "  ", region = "Y", country = "Z"))
        assertEquals("— · Y · Z", line)
    }

    // --- ambiguousNames: (c) same-named hits ---

    @Test
    fun duplicateNamesFlaggedCaseAndTrimInsensitive() {
        val places = listOf(
            place("Springfield", city = "Illinois", country = "USA"),
            place("  springfield  ", country = "UK"),
            place("Dublin"),
        )
        val flagged = PlaceLabels.ambiguousNames(places)
        assertEquals(1, flagged.size)
        assertTrue(flagged.contains("springfield"))
    }

    @Test
    fun uniqueNamesYieldEmptySet() {
        assertTrue(PlaceLabels.ambiguousNames(listOf(place("A"), place("B"))).isEmpty())
    }
}
