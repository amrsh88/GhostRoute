package app.ghostroute.coredata

import app.ghostroute.coredata.db.IpHistoryEntity

/**
 * P6a: the IP-history CSV export formatter — pure, dependency-free. Consumed
 * by the history sheet's ACTION_SEND share (the DiagnosticsSheet share
 * shape); RFC 4180 quoting because place strings are free-form geocoder data
 * and may contain commas, quotes or newlines. Column order is stable and
 * documented in the header row.
 */
object IpHistoryCsv {

    const val HEADER = "ip,city,region,country,lat,lng,provider,tunnel_status,first_seen_at,last_seen_at"

    /** [rows] are rendered in the given order (newest-first from the repo). */
    fun format(rows: List<IpHistoryEntity>): String = buildString {
        append(HEADER)
        rows.forEach { row ->
            append('\n')
            append(field(row.ip)).append(',')
            append(field(row.city)).append(',')
            append(field(row.region)).append(',')
            append(field(row.country)).append(',')
            append(row.lat?.toString().orEmpty()).append(',')
            append(row.lng?.toString().orEmpty()).append(',')
            append(field(row.provider)).append(',')
            append(field(row.tunnelStatus)).append(',')
            append(row.firstSeenAt).append(',')
            append(row.lastSeenAt)
        }
    }

    /** RFC 4180: quote fields holding comma/quote/CR/LF; double embedded quotes. */
    private fun field(raw: String): String =
        if (raw.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) {
            "\"" + raw.replace("\"", "\"\"") + "\""
        } else {
            raw
        }
}
