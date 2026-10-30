package app.ghostroute.engine

import kotlinx.serialization.Serializable

import java.time.Instant

/** A target point the simulator wants to occupy. */
@Serializable
data class Waypoint(val lat: Double, val lng: Double)

/**
 * One realized location fix, Android-independent. The injector (Task 4, `:app`)
 * maps this onto `LocationManager`/FLP test-provider locations.
 */
data class LocationSample(
    val lat: Double,
    val lng: Double,
    val altM: Double?,
    val bearingDeg: Double,
    val speedMps: Double,
    val accuracyM: Double,
    val t: Instant,
)
