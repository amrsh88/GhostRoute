package app.ghostroute.location

/**
 * Mock-slot failure modes (Task 4 amendment, feeds the F-09 self-heal):
 * "app deselected mid-run" and "dev-options toggle cleared the slot" need
 * different user guidance, so the ACTION_MOCK_PERMISSION_LOST broadcast
 * carries a machine-readable category alongside the raw message.
 */
enum class MockSlotFault { DESELECTED, CLEARED, UNKNOWN }

/**
 * Best-effort classification of the framework's SecurityException message.
 * OEM/AOSP wording varies across versions; UNKNOWN is always a safe answer,
 * so classify() must never throw.
 */
object MockSlotDiagnostics {
    /**
     * Classifies the mock-slot failure from the framework's exception message.
     *
     * Two distinct user-fixable failures exist (Task 4 amendment, F-09 self-heal):
     *  - DESELECTED: another app (or none) is selected in Developer options —
     *    AOSP wording is "…is not the currently selected mock location app".
     *  - CLEARED: the slot itself was revoked (dev-options toggle clears the
     *    `secure mock_location` setting and the appop while Settings may still
     *    list this app) — wording references the `mock_location` appop.
     *
     * Matching is best-effort: OEM wording varies, so anything unrecognized is
     * UNKNOWN and this function never throws.
     */
    fun classify(e: SecurityException): MockSlotFault {
        val msg = e.message?.lowercase() ?: return MockSlotFault.UNKNOWN
        return when {
            "not" in msg && "selected" in msg -> MockSlotFault.DESELECTED
            "mock_location" in msg || "appop" in msg -> MockSlotFault.CLEARED
            else -> MockSlotFault.UNKNOWN
        }
    }
}
