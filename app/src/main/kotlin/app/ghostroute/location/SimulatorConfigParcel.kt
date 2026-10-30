package app.ghostroute.location

import android.os.Parcel
import android.os.Parcelable
import app.ghostroute.engine.MovementSpec
import app.ghostroute.engine.RealismSpec
import app.ghostroute.engine.SimulatorConfig
import app.ghostroute.engine.Waypoint

/**
 * Parcelable wrapper around the engine's [SimulatorConfig] for the service intent
 * API (DESIGN §6, Task 4 controller ruling (b)).
 *
 * Compat story — read before touching:
 * - The extra KEY `MockService.EXTRA_CONFIG` ("extra-simulator-config") is
 *   **baseline-compatible**: any automation client can stuff any Parcelable under
 *   that key, and older GhostRoute builds simply ignore what they can't read.
 * - The parcelable **CLASS** is NOT baseline-compatible: a Parcelable written by
 *   another app deserializes against that app's class loader only if the FQN
 *   matches, and cross-app package differences make that impossible in general.
 *   Clients that need typed configs today should ship the string extras
 *   (latitude/longitude and friends) — those are the automation-compat path.
 */
class SimulatorConfigParcel(val config: SimulatorConfig) : Parcelable {

    /**
     * Field order is write/read-symmetric and frozen: any change here must be
     * mirrored in both methods in the same commit (parcels have no versioning).
     */
    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeDouble(config.lat)
        dest.writeDouble(config.lng)
        dest.writeLong(config.intervalMs)
        dest.writeDouble(config.accuracyM)
        if (config.altitudeM == null) dest.writeInt(0) else {
            dest.writeInt(1)
            dest.writeDouble(config.altitudeM as Double)
        }
        dest.writeDouble(config.bearingDeg)
        dest.writeDouble(config.speedMps)
        writeMovement(dest, config.movement)
        dest.writeDouble(config.realism.baseAccuracyM)
        dest.writeDouble(config.realism.accuracyNoiseFraction)
        dest.writeDouble(config.realism.clampMinM)
        dest.writeDouble(config.realism.clampMaxM)
        dest.writeLong(config.seed)
    }

    override fun describeContents(): Int = 0

    private fun writeMovement(dest: Parcel, movement: MovementSpec) {
        when (movement) {
            is MovementSpec.Static -> {
                dest.writeInt(TAG_STATIC)
            }
            is MovementSpec.Jitter -> {
                dest.writeInt(TAG_JITTER)
                dest.writeDouble(movement.radiusM)
            }
            is MovementSpec.Route -> {
                dest.writeInt(TAG_ROUTE)
                dest.writeInt(movement.path.size)
                for (p in movement.path) {
                    dest.writeDouble(p.lat)
                    dest.writeDouble(p.lng)
                }
                dest.writeDouble(movement.speedMps)
                dest.writeInt(movement.mode.ordinal)
            }
        }
    }

    companion object {
        private const val TAG_STATIC = 0
        private const val TAG_JITTER = 1
        private const val TAG_ROUTE = 2

        @JvmField
        val CREATOR = object : Parcelable.Creator<SimulatorConfigParcel> {
            override fun createFromParcel(source: Parcel): SimulatorConfigParcel {
                val lat = source.readDouble()
                val lng = source.readDouble()
                val intervalMs = source.readLong()
                val accuracyM = source.readDouble()
                val altitudeM = if (source.readInt() == 0) null else source.readDouble()
                val bearingDeg = source.readDouble()
                val speedMps = source.readDouble()
                val movement = readMovement(source)
                val baseAccuracy = source.readDouble()
                val noiseFraction = source.readDouble()
                val clampStart = source.readDouble()
                val clampEnd = source.readDouble()
                val seed = source.readLong()
                return SimulatorConfigParcel(
                    SimulatorConfig(
                        lat = lat,
                        lng = lng,
                        intervalMs = intervalMs,
                        accuracyM = accuracyM,
                        altitudeM = altitudeM,
                        bearingDeg = bearingDeg,
                        speedMps = speedMps,
                        movement = movement,
                        realism = RealismSpec(
                            baseAccuracyM = baseAccuracy,
                            accuracyNoiseFraction = noiseFraction,
                            clampMinM = clampStart,
                            clampMaxM = clampEnd,
                        ),
                        seed = seed,
                    ),
                )
            }

            override fun newArray(size: Int): Array<SimulatorConfigParcel?> = arrayOfNulls(size)
        }

        private fun readMovement(source: Parcel): MovementSpec =
            when (source.readInt()) {
                TAG_JITTER -> MovementSpec.Jitter(source.readDouble())
                TAG_ROUTE -> {
                    val count = source.readInt()
                    require(count in 2..2000) { "route point limit" }
                    val path = ArrayList<Waypoint>(count)
                    repeat(count) {
                        path += Waypoint(source.readDouble(), source.readDouble())
                    }
                    val speed = source.readDouble()
                    val mode = MovementSpec.RouteMode.entries[source.readInt()]
                    MovementSpec.Route(path, speed, mode)
                }
                else -> MovementSpec.Static
            }
    }
}
