package com.bpi.cairn.gpx

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileInputStream

// ─── Data classes ─────────────────────────────────────────────────────────────

data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val ele: Double = 0.0,   // elevation in meters
    val time: String? = null
)

data class GpxTrack(
    val name: String,
    val points: List<TrackPoint>
) {
    /** Total distance in meters */
    val totalDistanceMeters: Double by lazy {
        if (points.size < 2) 0.0
        else points.zipWithNext { a, b -> haversineDistance(a, b) }.sum()
    }

    /** Positive elevation gain in meters */
    val elevationGain: Double by lazy {
        if (points.size < 2) 0.0
        else points.zipWithNext { a, b -> maxOf(0.0, b.ele - a.ele) }.sum()
    }

    /** Negative elevation loss in meters */
    val elevationLoss: Double by lazy {
        if (points.size < 2) 0.0
        else points.zipWithNext { a, b -> maxOf(0.0, a.ele - b.ele) }.sum()
    }

    /** Min elevation */
    val minElevation: Double by lazy { points.minOfOrNull { it.ele } ?: 0.0 }

    /** Max elevation */
    val maxElevation: Double by lazy { points.maxOfOrNull { it.ele } ?: 0.0 }
}

// ─── Parser ───────────────────────────────────────────────────────────────────

object GpxParser {

    /**
     * Parse a GPX file and return a [GpxTrack].
     * Supports both <trk>/<trkseg>/<trkpt> and <rte>/<rtept>.
     */
    fun parse(file: File): GpxTrack {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser  = factory.newPullParser()

        FileInputStream(file).use { stream ->
            parser.setInput(stream, null)

            var trackName = file.nameWithoutExtension
            val points    = mutableListOf<TrackPoint>()

            var currentLat = 0.0
            var currentLon = 0.0
            var currentEle = 0.0
            var currentTime: String? = null
            var insidePoint = false
            var insideEle   = false
            var insideTime  = false
            var insideName  = false

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "name" -> if (!insidePoint) insideName = true

                            "trkpt", "rtept" -> {
                                insidePoint = true
                                currentLat  = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                                currentLon  = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                                currentEle  = 0.0
                                currentTime = null
                            }

                            "ele"  -> if (insidePoint) insideEle  = true
                            "time" -> if (insidePoint) insideTime = true
                        }
                    }

                    XmlPullParser.TEXT -> {
                        when {
                            insideName -> trackName = parser.text.trim()
                            insideEle  -> currentEle  = parser.text.trim().toDoubleOrNull() ?: 0.0
                            insideTime -> currentTime = parser.text.trim()
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "name"                -> insideName = false
                            "ele"                 -> insideEle  = false
                            "time"                -> insideTime = false
                            "trkpt", "rtept" -> {
                                points.add(TrackPoint(currentLat, currentLon, currentEle, currentTime))
                                insidePoint = false
                            }
                        }
                    }
                }
                eventType = parser.next()
            }

            if (points.isEmpty()) throw IllegalArgumentException("Aucun point GPS dans ce fichier GPX")
            return GpxTrack(trackName, points)
        }
    }

    // Dans GpxParser.kt, à l'intérieur de l'object GpxParser
    fun parse(xmlContent: String): GpxTrack {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser  = factory.newPullParser()

        // On utilise un StringReader au lieu d'un FileInputStream
        parser.setInput(java.io.StringReader(xmlContent))

        var trackName = "Nouvelle trace"
        val points    = mutableListOf<TrackPoint>()

        var currentLat = 0.0
        var currentLon = 0.0
        var currentEle = 0.0
        var currentTime: String? = null
        var insidePoint = false
        var insideEle   = false
        var insideTime  = false
        var insideName  = false

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "name" -> if (!insidePoint) insideName = true

                        "trkpt", "rtept" -> {
                            insidePoint = true
                            currentLat  = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                            currentLon  = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                            currentEle  = 0.0
                            currentTime = null
                        }

                        "ele"  -> if (insidePoint) insideEle  = true
                        "time" -> if (insidePoint) insideTime = true
                    }
                }

                XmlPullParser.TEXT -> {
                    when {
                        insideName -> trackName = parser.text.trim()
                        insideEle  -> currentEle  = parser.text.trim().toDoubleOrNull() ?: 0.0
                        insideTime -> currentTime = parser.text.trim()
                    }
                }

                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "name"                -> insideName = false
                        "ele"                 -> insideEle  = false
                        "time"                -> insideTime = false
                        "trkpt", "rtept" -> {
                            points.add(TrackPoint(currentLat, currentLon, currentEle, currentTime))
                            insidePoint = false
                        }
                    }
                }
            }
            eventType = parser.next()
        }
        return GpxTrack(trackName, points)
    }
}

// ─── Haversine distance ───────────────────────────────────────────────────────

private const val EARTH_RADIUS = 6371000.0 // meters

fun haversineDistance(a: TrackPoint, b: TrackPoint): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val sinLat = kotlin.math.sin(dLat / 2)
    val sinLon = kotlin.math.sin(dLon / 2)
    val h = sinLat * sinLat +
            kotlin.math.cos(Math.toRadians(a.lat)) *
            kotlin.math.cos(Math.toRadians(b.lat)) *
            sinLon * sinLon
    return 2 * EARTH_RADIUS * kotlin.math.asin(kotlin.math.sqrt(h))
}

/**
 * Returns cumulative distances list (one entry per track point, starting at 0).
 */
fun GpxTrack.cumulativeDistances(): List<Double> {
    val result = mutableListOf(0.0)
    for (i in 1 until points.size) {
        result.add(result.last() + haversineDistance(points[i - 1], points[i]))
    }
    return result
}
