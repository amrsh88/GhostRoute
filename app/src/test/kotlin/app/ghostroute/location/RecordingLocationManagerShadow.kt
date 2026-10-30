package app.ghostroute.location

import android.location.Location
import android.location.LocationManager
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter
import org.robolectric.shadows.ShadowLocationManager

/**
 * Test double for the LocationManager test-provider API surface.
 *
 * Why a custom shadow: Robolectric 4.17 ships NO @Implementation for
 * addTestProvider/setTestProviderEnabled/setTestProviderLocation/removeTestProvider,
 * and its fake ILocationManager binder is a NULL_PROXY — the real framework calls
 * would be silent no-ops with nothing to assert on. This shadow records the four
 * calls so the injector's API contract (which provider, which flags, which field
 * mapping, disable-before-remove order) is verifiable; a one-shot
 * [failNextSetTestProviderLocation] flag lets the service test exercise the real
 * SecurityException path through the real injector code.
 *
 * Recording-only by design: it does not feed the parent shadow's simulator state
 * (`isProviderEnabled`/`getLastKnownLocation` remain the parent shadow's business).
 */
@Implements(LocationManager::class)
class RecordingLocationManagerShadow : ShadowLocationManager() {

    /** Immutable record of one addTestProvider call. */
    data class AddedProvider(
        val name: String,
        val supportsAltitude: Boolean,
        val supportsSpeed: Boolean,
        val supportsBearing: Boolean,
        val powerUsage: Int,
        val accuracy: Int,
    )

    companion object {
        /** provider -> addTestProvider args (last call wins). */
        val added = LinkedHashMap<String, AddedProvider>()

        /** provider -> last setTestProviderEnabled value. */
        val enabled = LinkedHashMap<String, Boolean>()

        /** provider -> locations passed to setTestProviderLocation, in order. */
        val injected = LinkedHashMap<String, MutableList<Location>>()

        /** Ordered event log: "enabled:<provider>:<bool>" and "removed:<provider>". */
        val events = mutableListOf<String>()

        /** One-shot: next setTestProviderLocation throws this, then the flag clears. */
        var failNextSetTestProviderLocation: SecurityException? = null

        /** One-shot: next setTestProviderEnabled throws this, then the flag clears. */
        var failNextSetTestProviderEnabled: SecurityException? = null

        @JvmStatic
        @Resetter
        fun resetRecording() {
            added.clear()
            enabled.clear()
            injected.clear()
            events.clear()
            failNextSetTestProviderLocation = null
            failNextSetTestProviderEnabled = null
        }
    }

    @Implementation
    fun addTestProvider(
        name: String,
        requiresNetwork: Boolean,
        requiresSatellite: Boolean,
        requiresCell: Boolean,
        hasMonetaryCost: Boolean,
        supportsAltitude: Boolean,
        supportsSpeed: Boolean,
        supportsBearing: Boolean,
        powerUsage: Int,
        accuracy: Int,
    ) {
        added[name] = AddedProvider(name, supportsAltitude, supportsSpeed, supportsBearing, powerUsage, accuracy)
    }

    @Implementation
    fun setTestProviderEnabled(name: String, enabled: Boolean) {
        val toThrow = failNextSetTestProviderEnabled
        failNextSetTestProviderEnabled = null
        if (toThrow != null) throw toThrow
        RecordingLocationManagerShadow.enabled[name] = enabled
        events += "enabled:$name:$enabled"
    }

    @Implementation
    fun setTestProviderLocation(name: String, location: Location) {
        val toThrow = failNextSetTestProviderLocation
        failNextSetTestProviderLocation = null
        if (toThrow != null) throw toThrow
        injected.getOrPut(name) { mutableListOf() }.add(location)
    }

    @Implementation
    fun removeTestProvider(name: String) {
        events += "removed:$name"
        injected.remove(name)
        added.remove(name)
        enabled.remove(name)
    }
}
