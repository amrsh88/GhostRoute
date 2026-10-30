package app.ghostroute.coredata.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * P6a (IP history, Part 4 + PDF P1): ONE observed public-IP exit record.
 * The table is a RECORD of lookups — background polls included — and carries
 * no map concept at all (the P1 rework binding: a poll can never mutate the
 * map; history recording never touches pin/camera/lastPos either).
 *
 *  - [ip] the observed public address (as reported; never normalized).
 *  - [city]/[region]/[country] exactly what the provider answered; empty
 *    string means "not supplied" (never fabricated).
 *  - [lat]/[lng] also provider-supplied: NULL when absent (never 0).
 *  - [tunnelStatus] the OBSERVED exit status at lookup time — the raw
 *    lowercase [TunnelStatus]-family token (through_vpn / bypasses_tunnel /
 *    no_vpn) or "" when unknowable (UNKNOWN state stays a record with an
 *    empty status; it is never guessed).
 *  - [firstSeenAt] first observation of this (ip, tunnelStatus) run;
 *    [lastSeenAt] refreshed by every consecutive identical observation
 *    (DEDUPE rule — see [IpHistoryRepository.record]).
 *
 * Storage-only (no Room defaultValue tricks needed): the table is brand new
 * in the additive v5->v6 migration (CREATE TABLE — see [GhostRouteDb]).
 */
@Entity(tableName = "ip_history")
data class IpHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ip: String,
    val city: String,
    val region: String,
    val country: String,
    val lat: Double?,
    val lng: Double?,
    val provider: String,
    val tunnelStatus: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
)
