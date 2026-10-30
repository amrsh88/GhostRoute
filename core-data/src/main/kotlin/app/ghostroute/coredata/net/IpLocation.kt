package app.ghostroute.coredata.net

/**
 * P1-1a: the full usable shape of an ipwho.is answer (roadmap audit: the OLD
 * model discarded `type`/`region`, masking the IPv6 leak).
 *
 *  - [ip] is the observed public address (legacy Task 10.5 name, kept for
 *    baseline compat across SyncController/UI); [ipAddress] is the P1-1a
 *    explicit public-address field — always the same value as [ip].
 *  - [ipType] mirrors the provider `type` field: "IPv4" or "IPv6"; blank when
 *    the provider omitted it (NEVER fabricated).
 *  - [region] is blank when the provider omits it (NEVER fabricated); blank
 *    region is "missing" and degrades to [IpOutcome.Stale] per the existing
 *    unknown-city rule (the sync contract is city-level data).
 *  - [fetchedAtMs] is the wall-clock stamp of the parse (drives the "last
 *    update Xm ago" UI); 0 only for rows hydrated from a pre-P1.1 cache.
 */
data class IpLocation(
    val ip: String,
    val lat: Double,
    val lng: Double,
    val city: String,
    val country: String,
    val ipAddress: String = ip,
    val ipType: String = "",
    val region: String = "",
    val fetchedAtMs: Long = 0L,
)
