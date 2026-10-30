package app.ghostroute.location

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mock-slot failure-mode classification (Task 4 amendment, feeds F-09 self-heal):
 * "app deselected mid-run" and "dev-options toggle cleared the slot" are distinct
 * failures a user can fix differently, so the broadcast must discriminate.
 */
class MockSlotFaultTest {

    @Test
    fun `message naming a non-selected app classifies as DESELECTED`() {
        val e = SecurityException("app.ghostroute is not the currently selected mock location app")
        assertEquals(MockSlotFault.DESELECTED, MockSlotDiagnostics.classify(e))
    }

    @Test
    fun `message referencing the mock_location appop classifies as CLEARED`() {
        val e = SecurityException("gps provider: appop android:mock_location not granted")
        assertEquals(MockSlotFault.CLEARED, MockSlotDiagnostics.classify(e))
    }

    @Test
    fun `unrecognized message classifies as UNKNOWN`() {
        val e = SecurityException("something else entirely")
        assertEquals(MockSlotFault.UNKNOWN, MockSlotDiagnostics.classify(e))
    }

    @Test
    fun `null message classifies as UNKNOWN`() {
        val e = SecurityException((null as String?))
        assertEquals(MockSlotFault.UNKNOWN, MockSlotDiagnostics.classify(e))
    }
}
