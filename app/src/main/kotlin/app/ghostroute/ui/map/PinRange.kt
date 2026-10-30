package app.ghostroute.ui.map

import app.ghostroute.engine.Geo
import app.ghostroute.engine.Waypoint

/** Straight-line range from the live simulated fix to the staged destination. */
data class PinRange(
    val distanceMeters: Double,
    val bearingDegrees: Double,
) {
    /** Eight compass sectors, clockwise from north. */
    val compassSector: Int
        get() = Geo.compassSector(bearingDegrees)

    companion object {
        fun between(origin: Waypoint, destination: Waypoint) = PinRange(
            distanceMeters = Geo.haversineM(origin.lat, origin.lng, destination.lat, destination.lng),
            bearingDegrees = Geo.bearingDeg(origin.lat, origin.lng, destination.lat, destination.lng),
        )
    }
}
