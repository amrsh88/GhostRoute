package app.ghostroute.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RunningPauseTest {

    private val t0 = Instant.ofEpochMilli(1_700_000_000_000L)
    private val moving = EngineState.Running(Waypoint(52.0, 21.0), t0, 1L, null)

    @Test
    fun `a moving run maps the simulation clock to wall time`() {
        val wall = t0.plusSeconds(30)
        assertFalse(moving.paused)
        assertEquals(wall, moving.simulationInstant(wall))
    }

    @Test
    fun `a paused run freezes at the pause instant minus the completed pauses`() {
        val run = moving.copy(pausedAt = t0.plusSeconds(20), pausedTotalMs = 5_000L)
        assertTrue(run.paused)
        assertEquals(t0.plusSeconds(15), run.simulationInstant(t0.plusSeconds(90)))
        assertEquals(t0.plusSeconds(15), run.simulationInstant(t0.plusSeconds(200)))
    }
}
