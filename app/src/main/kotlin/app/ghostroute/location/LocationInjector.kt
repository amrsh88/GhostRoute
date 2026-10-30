package app.ghostroute.location

import android.location.provider.ProviderProperties
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import app.ghostroute.engine.LocationSample

/**
 * Bridges engine [LocationSample]s onto Android's test-provider API so any app
 * listening on the mocked providers receives our simulated fixes (DESIGN §6).
 *
 * Provider lifecycle: [bind] registers the given providers as test providers and
 * enables them; [inject] pushes a sample to every bound provider; [unbind] disables
 * and removes them. Injection only reaches providers bound by this instance —
 * injecting before [bind] is a deliberate no-op.
 *
 * FLP: `flp` is deliberately typed `Any?` — fused-location injection needs the
 * FusedLocationProviderClient, which requires a GMS compile dependency that a
 * no-GMS build must not carry (plan Review Focus 5). v1 keeps the parameter as
 * the hook point and no-ops it (see [injectFused]); the null path must stay
 * crash-free. Reflection wiring is Tier 1.5.
 */
class LocationInjector(private val lm: LocationManager, private val flp: Any?) {

    /** Providers registered by [bind]; insertion-ordered to mirror the bind call order. */
    private val bound = LinkedHashSet<String>()

    /**
     * Registers each provider as a test provider (low power draw, altitude+speed
     * supported) and enables it. Constants are [Criteria]'s — the framework's
     * canonical power/accuracy vocabulary for test providers.
     *
     * A [SecurityException] (device's mock-location appop points elsewhere) may
     * surface here or in [inject] and is allowed to propagate: MockService
     * handles it service-wide.
     */
    fun bind(providers: Set<String> = DEFAULT_PROVIDERS) {
        for (name in providers) {
            val accuracy = if (name == GPS_PROVIDER) { ProviderProperties.ACCURACY_FINE } else { ProviderProperties.ACCURACY_COARSE }
            lm.addTestProvider(
                name,
                false, // requiresNetwork
                false, // requiresSatellite
                false, // requiresCell
                false, // hasMonetaryCost
                true,  // supportsAltitude
                true,  // supportsSpeed
                false, // supportsBearing (we still set bearing on each fix; harmless)
                ProviderProperties.POWER_USAGE_LOW,
                accuracy,
            )
            bound.add(name)
            lm.setTestProviderEnabled(name, true)
        }
    }

    /**
     * Pushes [sample] to every bound provider. Each fix carries `time` = the sample
     * instant and a FRESH `elapsedRealtimeNanos` (recomputed per injection) — the
     * baseline-correct pairing consumers expect; reusing a stale elapsed value would
     * look like clock skew to listeners.
     */
    fun inject(sample: LocationSample) {
        for (name in bound) {
            lm.setTestProviderLocation(name, buildLocation(name, sample))
        }
        injectFused(sample)
    }

    /**
     * Disables then removes each bound provider; removing an unregistered provider
     * throws [IllegalArgumentException], so every call is individually guarded and
     * the whole operation is idempotent.
     */
    fun unbind() {
        for (name in bound.toList()) {
            // SecurityException guard: teardown runs on the permission-loss path, so
            // a revoked slot must not abort removal of the remaining providers.
            try {
                lm.setTestProviderEnabled(name, false)
            } catch (_: IllegalArgumentException) {
            } catch (_: SecurityException) {
            }
            try {
                lm.removeTestProvider(name)
            } catch (_: IllegalArgumentException) {
            } catch (_: SecurityException) {
            }
        }
        bound.clear()
    }

    private fun buildLocation(provider: String, sample: LocationSample): Location =
        Location(provider).apply {
            latitude = sample.lat
            longitude = sample.lng
            sample.altM?.let { altitude = it }
            bearing = sample.bearingDeg.toFloat()
            speed = sample.speedMps.toFloat()
            accuracy = sample.accuracyM.toFloat()
            time = sample.t.toEpochMilli()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }

    /**
     * Fused-location hook point (Tier 1.5). `flp != null` would inject via
     * FusedLocationProviderClient reflection here; v1 no-ops so the no-GMS
     * build path stays crash-free.
     */
    private fun injectFused(@Suppress("UNUSED_PARAMETER") sample: LocationSample) {
        // flp is unused in v1; wiring is Tier 1.5 (see class KDoc).
    }

    companion object {
        private const val GPS_PROVIDER = "gps"
        val DEFAULT_PROVIDERS: Set<String> = setOf("gps", "network")
    }
}
