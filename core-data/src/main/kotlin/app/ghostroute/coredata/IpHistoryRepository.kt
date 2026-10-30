package app.ghostroute.coredata

import android.content.Context
import app.ghostroute.coredata.db.DbProvider
import app.ghostroute.coredata.db.IpHistoryDao
import app.ghostroute.coredata.db.IpHistoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * P6a (IP history, Part 4 + PDF P1): the persisted RECORD of observed
 * public-IP exits. This repository is deliberately map-blind — recording an
 * observation can never move the pin, camera or last position (the P1 rework
 * binding), and a lookup failure records nothing (a failed lookup is not an
 * observation).
 *
 * DEDUPE (docs rule): consecutive identical IP + tunnel status = the SAME
 * run — [record] touches lastSeenAt (and refreshes the observed place data)
 * instead of minting a row; a different IP or a different observed tunnel
 * status starts a new run.
 *
 * RETENTION: [record] prunes to [cap] rows after every write; [cap] == 0
 * means OFF — recording is disabled entirely (privacy), nothing is written
 * (rows are also never deleted silently by turning the dial: off stops
 * recording, "clear" is the explicit wipe).
 */
class IpHistoryRepository(
    private val dao: IpHistoryDao,
) {

    suspend fun record(
        ip: String,
        city: String,
        region: String,
        country: String,
        lat: Double?,
        lng: Double?,
        provider: String,
        tunnelStatus: String,
        cap: Int,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (cap <= 0) return // OFF: recording disabled entirely — no write, ever
        val candidate = ip.trim()
        if (candidate.isEmpty()) return // never a blank-IP row (no data != an observation)
        val latest = dao.latestOnce()
        if (latest != null && latest.ip == candidate && latest.tunnelStatus == tunnelStatus) {
            // Same run: touch in place — firstSeenAt and id immutable.
            dao.touch(
                id = latest.id,
                city = city.trim(),
                region = region.trim(),
                country = country.trim(),
                lat = lat,
                lng = lng,
                provider = provider,
                tunnelStatus = tunnelStatus,
                lastSeenAt = nowMs,
            )
        } else {
            dao.insert(
                IpHistoryEntity(
                    ip = candidate,
                    city = city.trim(),
                    region = region.trim(),
                    country = country.trim(),
                    lat = lat,
                    lng = lng,
                    provider = provider,
                    tunnelStatus = tunnelStatus,
                    firstSeenAt = nowMs,
                    lastSeenAt = nowMs,
                ),
            )
        }
        dao.prune(keep = cap)
    }

    /** Newest-first timeline feed (the sheet caps the rows it renders). */
    fun history(limit: Int = HISTORY_UI_LIMIT): Flow<List<IpHistoryEntity>> = dao.recent(limit)

    /** One-shot newest-first snapshot (CSV export). */
    suspend fun snapshot(): List<IpHistoryEntity> = dao.allOnce()

    /** Explicit wipe (confirm-gated in the UI layer). */
    suspend fun clear() = dao.clearAll()

    companion object {
        /** The sheet's initial timeline render cap (the table holds up to 500). */
        const val HISTORY_UI_LIMIT = 50

        /** Production db: the process-wide singleton (DbProvider since P1-2b). */
        fun from(context: Context): IpHistoryRepository =
            IpHistoryRepository(DbProvider.get(context).ipHistoryDao())
    }
}
