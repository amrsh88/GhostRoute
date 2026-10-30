package app.ghostroute.coredata

import app.ghostroute.engine.Waypoint
import java.io.IOException
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory

/**
 * P2-2a: GPX 1.1 codec for Route Studio import/export. Engine-package style:
 * a pure object that NEVER throws on input — [parse] answers a [GpxResult];
 * every malformed form (bad XML, non-gpx root, missing/non-finite/out-of-range
 * trkpt coordinates, zero points, DOCTYPE) is an [GpxResult.Invalid] with a
 * reason. Strictness is deliberate: a rejected file must never partially
 * import (UI binding), so parse is all-or-nothing by construction (points
 * accumulate and only a fully successful pass returns Parsed).
 *
 * [serialize] emits minimal GPX 1.1 (trk/trkseg/trkpt, optional escaped name)
 * with Double.toString coordinates, so export → import roundtrips points
 * EXACTLY (pinned in GpxCodecTest).
 */
sealed interface GpxResult {
    /** Fully valid document: first track [name] (null when absent) + points in document order. */
    data class Parsed(val name: String?, val points: List<Waypoint>) : GpxResult

    /** Malformed anything — [reason] explains; no partial output ever escapes. */
    data class Invalid(val reason: String) : GpxResult
}

object GpxCodec {

    /** GPX 1.1 namespace — imports must declare it (strict-opening ruling). */
    private const val GPX_NS = "http://www.topografix.com/GPX/1/1"

    fun parse(xml: String): GpxResult {
        // Belt: DOCTYPE rejection up front — a GPX file never has one and
        // entity-expansion gymnastics have no place in a closed-format import.
        if (xml.contains("<!DOCTYPE", ignoreCase = true)) {
            return GpxResult.Invalid("DOCTYPE not allowed")
        }
        return try {
            val parser = XmlPullParserFactory.newInstance().apply {
                isNamespaceAware = true
            }.newPullParser().apply {
                setInput(xml.reader())
            }

            var name: String? = null
            // Parent-tag stack so <name> is only honored inside <trk>/<metadata>.
            val stack = ArrayDeque<String>()
            val points = ArrayList<Waypoint>()
            var sawGpxRoot = false

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name
                        if (stack.isEmpty()) {
                            if (tag != "gpx") return GpxResult.Invalid("not a GPX document ($tag)")
                            sawGpxRoot = true
                            val version = parser.getAttributeValue(null, "version")
                            if (version != "1.1") return GpxResult.Invalid("unsupported GPX version $version")
                        }
                        if (tag == "trkpt") {
                            val lat = parser.getAttributeValue(null, "lat")
                            val lon = parser.getAttributeValue(null, "lon")
                            val latV = lat?.trim()?.toDoubleOrNull()
                                ?: return GpxResult.Invalid("trkpt missing/invalid lat")
                            val lonV = lon?.trim()?.toDoubleOrNull()
                                ?: return GpxResult.Invalid("trkpt missing/invalid lon")
                            if (!latV.isFinite() || !lonV.isFinite()) {
                                return GpxResult.Invalid("trkpt non-finite lat/lon")
                            }
                            if (latV !in -90.0..90.0 || lonV !in -180.0..180.0) {
                                return GpxResult.Invalid("trkpt out of range ($lat, $lon)")
                            }
                            points.add(Waypoint(latV, lonV))
                        }
                        if ((tag == "name" && parentIs(stack, "trk", "metadata"))) {
                            val text = parser.nextText()?.trim()
                            if (!text.isNullOrEmpty() && name == null) name = text
                        }
                        stack.addLast(tag)
                    }
                    XmlPullParser.END_TAG -> stack.removeLastOrNull()
                }
                event = parser.next()
            }
            if (!sawGpxRoot) return GpxResult.Invalid("not a GPX document")
            if (points.isEmpty()) return GpxResult.Invalid("no track points found")
            GpxResult.Parsed(name = name, points = points)
        } catch (e: XmlPullParserException) {
            GpxResult.Invalid("malformed GPX XML: ${e.message ?: "parse error"}")
        } catch (e: IOException) {
            GpxResult.Invalid("unreadable GPX: ${e.message ?: "I/O error"}")
        } catch (e: Exception) {
            GpxResult.Invalid("malformed GPX: ${e.message ?: "unknown"}")
        }
    }

    fun serialize(name: String?, points: List<Waypoint>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"GhostRoute\" xmlns=\"$GPX_NS\">")
        append("<trk>")
        if (!name.isNullOrBlank()) append("<name>").append(escape(name)).append("</name>")
        append("<trkseg>")
        points.forEach { p -> append("<trkpt lat=\"${p.lat}\" lon=\"${p.lng}\"/>") }
        append("</trkseg></trk></gpx>")
    }

    private fun parentIs(stack: ArrayDeque<String>, vararg parents: String): Boolean =
        stack.lastOrNull() in parents

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
