package app.ghostroute.engine

import kotlinx.serialization.Serializable

/**
 * Full description of a simulation run (DESIGN §6).
 *
 * `seed` is REQUIRED (approved amendment, F-45 dependency): every stochastic
 * artifact derived from a run — realism noise — must be reproducible from it.
 */
@Serializable
data class SimulatorConfig(
    val lat: Double,
    val lng: Double,
    val intervalMs: Long,
    val accuracyM: Double,
    val altitudeM: Double?,
    val bearingDeg: Double,
    val speedMps: Double,
    val movement: MovementSpec,
    val realism: RealismSpec,
    val seed: Long,
)
