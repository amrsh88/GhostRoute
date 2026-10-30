package app.ghostroute.coredata

/** One imported/exported saved Spot row; folders are intentionally not part of CSV. */
data class SpotCsvRow(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val note: String? = null,
)

sealed interface SpotCsvResult {
    data class Parsed(val rows: List<SpotCsvRow>) : SpotCsvResult
    data class Invalid(val reason: Reason) : SpotCsvResult

    enum class Reason { TOO_LARGE, MALFORMED, HEADER, EMPTY, TOO_MANY_ROWS, BAD_ROW }
}

/** Strict, all-or-nothing CSV interchange for saved spots. No external parser or network required. */
object SpotCsvCodec {
    const val MAX_BYTES = 5_000_000
    const val MAX_ROWS = 5_000
    private const val MAX_NAME_CHARS = 256
    private const val MAX_NOTE_CHARS = 2_048
    private val header = listOf("name", "latitude", "longitude", "note")
    private val formulaPrefixes = setOf('=', '+', '-', '@')

    fun encode(rows: List<SpotCsvRow>): String = buildString {
        append("name,latitude,longitude,note\r\n")
        rows.forEach { row ->
            append(field(spreadsheetSafe(row.name)))
            append(',')
            append(row.latitude.toString())
            append(',')
            append(row.longitude.toString())
            append(',')
            append(field(spreadsheetSafe(row.note.orEmpty())))
            append("\r\n")
        }
    }

    fun parse(csv: String): SpotCsvResult {
        if (csv.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            return SpotCsvResult.Invalid(SpotCsvResult.Reason.TOO_LARGE)
        }
        val records = parseRecords(csv.removePrefix("\uFEFF"))
            ?: return SpotCsvResult.Invalid(SpotCsvResult.Reason.MALFORMED)
        if (records.isEmpty()) return SpotCsvResult.Invalid(SpotCsvResult.Reason.EMPTY)
        if (records.first().map { it.trim().lowercase() } != header) {
            return SpotCsvResult.Invalid(SpotCsvResult.Reason.HEADER)
        }
        val dataRows = records.drop(1)
        if (dataRows.isEmpty()) return SpotCsvResult.Invalid(SpotCsvResult.Reason.EMPTY)
        if (dataRows.size > MAX_ROWS) return SpotCsvResult.Invalid(SpotCsvResult.Reason.TOO_MANY_ROWS)

        val spots = ArrayList<SpotCsvRow>(dataRows.size)
        for (record in dataRows) {
            if (record.size != header.size) return SpotCsvResult.Invalid(SpotCsvResult.Reason.BAD_ROW)
            val name = restoreSpreadsheetSafe(record[0].trim())
            val lat = record[1].trim().toDoubleOrNull()
            val lng = record[2].trim().toDoubleOrNull()
            val note = restoreSpreadsheetSafe(record[3]).ifBlank { null }
            if (name.isBlank() || name.length > MAX_NAME_CHARS || (note?.length ?: 0) > MAX_NOTE_CHARS ||
                lat == null || lng == null || !lat.isFinite() || !lng.isFinite() ||
                lat !in -SpotRepository.MAX_LAT..SpotRepository.MAX_LAT ||
                lng !in -SpotRepository.MAX_LNG..SpotRepository.MAX_LNG
            ) {
                return SpotCsvResult.Invalid(SpotCsvResult.Reason.BAD_ROW)
            }
            spots += SpotCsvRow(name.trim(), lat, lng, note)
        }
        return SpotCsvResult.Parsed(spots)
    }

    /** RFC-4180-style quoting; formula-like text gets a reversible tab prefix for spreadsheet safety. */
    private fun field(value: String): String {
        val quote = value.any { it == ',' || it == '"' || it == '\r' || it == '\n' } ||
            value.firstOrNull()?.isWhitespace() == true || value.lastOrNull()?.isWhitespace() == true
        val escaped = value.replace("\"", "\"\"")
        return if (quote) "\"$escaped\"" else escaped
    }

    private fun spreadsheetSafe(value: String): String =
        if (value.trimStart().firstOrNull()?.let { it in formulaPrefixes } == true) "\t$value" else value

    private fun restoreSpreadsheetSafe(value: String): String =
        if (value.startsWith('\t') && value.drop(1).trimStart().firstOrNull()?.let { it in formulaPrefixes } == true) {
            value.drop(1)
        } else {
            value
        }

    /** Minimal strict CSV state machine: quoted commas/newlines and doubled quotes are supported. */
    private fun parseRecords(input: String): List<List<String>>? {
        val records = ArrayList<List<String>>()
        var record = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var quoteClosed = false
        var i = 0

        fun finishField() {
            record += field.toString()
            field.setLength(0)
            quoteClosed = false
        }

        fun finishRecord() {
            finishField()
            if (record.any { it.isNotEmpty() }) records += record
            record = ArrayList()
        }

        while (i < input.length) {
            val ch = input[i]
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < input.length && input[i + 1] == '"') {
                        field.append('"')
                        i++
                    } else {
                        quoted = false
                        quoteClosed = true
                    }
                } else {
                    field.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> {
                        if (field.isNotEmpty() || quoteClosed) return null
                        quoted = true
                    }
                    ',' -> finishField()
                    '\n' -> finishRecord()
                    '\r' -> {
                        finishRecord()
                        if (i + 1 < input.length && input[i + 1] == '\n') i++
                    }
                    else -> {
                        if (quoteClosed && !ch.isWhitespace()) return null
                        if (!quoteClosed) field.append(ch)
                    }
                }
            }
            if (records.size > MAX_ROWS + 1) return null // header plus at most MAX_ROWS data rows
            i++
        }
        if (quoted) return null
        if (field.isNotEmpty() || record.isNotEmpty() || quoteClosed) finishRecord()
        if (records.size > MAX_ROWS + 1) return null
        return records
    }
}
