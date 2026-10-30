package app.ghostroute.engine.movement

import app.ghostroute.engine.MovementSpec
import app.ghostroute.engine.Waypoint
import java.time.Instant

/** [MovementSpec.Static]: the device stays pinned at the anchor, forever. */
class StaticStrategy(
    override val spec: MovementSpec.Static,
    private val anchor: Waypoint,
) : MovementStrategy {
    override fun tick(t: Instant): Waypoint = anchor
}
