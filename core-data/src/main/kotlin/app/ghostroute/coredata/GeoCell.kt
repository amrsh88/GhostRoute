package app.ghostroute.coredata

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * P1-2 review: THE canonical 3-decimal coordinate cell (~100 m), shared by the
 * recents dedup (SpotRepository) and the IP-sync material-change rule
 * (SyncController) so the two "same place" rules can never diverge.
 *
 * Half-up on the DECIMAL value ([BigDecimal.valueOf] — the double's shortest
 * decimal string) canonicalises ties away from zero, matching SQLite ROUND's
 * tie semantics; the dedup DAO query now fetches a raw neighborhood and cells
 * are compared HERE in Kotlin, so SQLite rounding no longer takes part in the
 * rule at all.
 */
object GeoCell {

    fun of(v: Double): Double = BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP).toDouble()

    fun same(latA: Double, lngA: Double, latB: Double, lngB: Double): Boolean =
        of(latA) == of(latB) && of(lngA) == of(lngB)
}
