package ru.podmoskovye.transport.core

import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Одна станция в порядке следования линии. */
data class Station(
    val name: String,
    val lat: Double,
    val lon: Double,
    val index: Int,
)

enum class LineType { METRO, MCC, MCD, PROJECT }

/** Линия: номер, название, цвет, тип, станции по порядку, статус, кольцо. */
data class Line(
    val id: String,
    val type: LineType,
    val ref: String,
    val name: String,
    val color: String,
    val status: String,
    val ring: Boolean,
    val stations: List<Station>,
) {
    val shortName: String
        get() = when (type) {
            LineType.MCD -> "МЦД-" + ref.replace(Regex("^D", RegexOption.IGNORE_CASE), "")
            LineType.MCC -> "МЦК"
            else -> name
        }

    val fullName: String
        get() = if (type == LineType.MCD && name.isNotBlank()) name else shortName
}

/** Станция как транспортный узел: все линии, которые через неё проходят. */
data class Place(
    val name: String,
    val lat: Double,
    val lon: Double,
    val lines: List<String>,
    val city: String?,
    val cityApprox: Boolean,
    val isTransfer: Boolean,
    val hasProject: Boolean,
    val isTerminal: Boolean,
    val searchText: String,
)

class Network(
    val lines: List<Line>,
    val places: List<Place>,
    val byName: Map<String, Place>,
    val lineById: Map<String, Line>,
    val indexInLine: Map<String, Map<String, Int>>,
) {
    val metroLines get() = lines.filter { it.type == LineType.METRO }
    val mccLines get() = lines.filter { it.type == LineType.MCC }
    val mcdLines get() = lines.filter { it.type == LineType.MCD }
    val projectLines get() = lines.filter { it.type == LineType.PROJECT }
}

object Model {

    fun haversineKm(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val r = 6371.0
        val dLat = (bLat - aLat) * PI / 180
        val dLon = (bLon - aLon) * PI / 180
        val la1 = aLat * PI / 180
        val la2 = bLat * PI / 180
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(la1) * cos(la2) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * asin(min(1.0, sqrt(h)))
    }

    fun mercatorY(lat: Double): Double {
        val s = sin(lat * PI / 180)
        return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
    }

    private fun parseLines(json: JSONObject, status: String): List<Line> {
        val arr = json.optJSONArray("lines") ?: return emptyList()
        val out = mutableListOf<Line>()
        for (i in 0 until arr.length()) {
            val raw = arr.optJSONObject(i) ?: continue
            val stArr = raw.optJSONArray("stations") ?: continue
            val stations = mutableListOf<Station>()
            for (j in 0 until stArr.length()) {
                val st = stArr.optJSONObject(j) ?: continue
                val name = st.optString("name").trim()
                if (name.isEmpty()) continue
                val lat = st.optDouble("lat", Double.NaN)
                val lon = st.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) continue
                stations.add(Station(name, lat, lon, stations.size))
            }
            if (stations.isEmpty()) continue
            val type = when (raw.optString("type")) {
                "metro" -> LineType.METRO
                "mcc" -> LineType.MCC
                "mcd" -> LineType.MCD
                else -> LineType.PROJECT
            }
            out.add(
                Line(
                    id = raw.optString("id"),
                    type = type,
                    ref = raw.optString("ref"),
                    name = raw.optString("name"),
                    color = raw.optString("color", "#8B95A7"),
                    status = status,
                    ring = raw.optBoolean("ring", false),
                    stations = stations,
                )
            )
        }
        return out
    }

    private fun cityOf(
        name: String,
        lat: Double,
        lon: Double,
        types: Set<LineType>,
        cities: JSONObject?,
    ): Pair<String?, Boolean> {
        if (cities == null) return Pair(null, false)
        val arr = cities.optJSONArray("cities")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                if (c.optString("name") == name) return Pair(c.optString("name"), false)
            }
        }
        if (types.contains(LineType.METRO) || types.contains(LineType.MCC)) {
            return Pair(cities.optJSONObject("moscow")?.optString("name") ?: "Москва", false)
        }
        if (arr == null) return Pair(null, false)
        var best: String? = null
        var bestD = Double.MAX_VALUE
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val d = haversineKm(lat, lon, c.optDouble("lat"), c.optDouble("lon"))
            if (d < bestD) {
                bestD = d
                best = c.optString("name")
            }
        }
        val limit = cities.optDouble("maxDistanceKm", 18.0)
        return if (best != null && bestD <= limit) Pair(best, true) else Pair(null, false)
    }

    fun buildNetwork(base: JSONObject, project: JSONObject?, cities: JSONObject?): Network {
        val lines = parseLines(base, "active") + (project?.let { parseLines(it, "project") } ?: emptyList())
        require(lines.isNotEmpty()) { "В данных нет ни одной линии" }

        val lineById = lines.associateBy { it.id }

        data class Acc(
            var lat: Double = 0.0,
            var lon: Double = 0.0,
            var n: Int = 0,
            val lineIds: MutableSet<String> = LinkedHashSet(),
            val types: MutableSet<LineType> = LinkedHashSet(),
        )

        val acc = LinkedHashMap<String, Acc>()
        for (line in lines) {
            for (st in line.stations) {
                val a = acc.getOrPut(st.name) { Acc() }
                a.lat += st.lat
                a.lon += st.lon
                a.n += 1
                a.lineIds.add(line.id)
                a.types.add(line.type)
            }
        }

        val terminals = HashSet<String>()
        for (line in lines) {
            terminals.add(line.stations.first().name)
            terminals.add(line.stations.last().name)
        }

        val indexInLine = lines.associate { line ->
            val map = HashMap<String, Int>()
            line.stations.forEachIndexed { idx, st -> if (!map.containsKey(st.name)) map[st.name] = idx }
            line.id to map
        }

        val places = acc.map { (name, a) ->
            val lat = a.lat / a.n
            val lon = a.lon / a.n
            val town = cityOf(name, lat, lon, a.types, cities)
            val lineNames = a.lineIds.joinToString(" ") { id ->
                val l = lineById[id]
                if (l == null) "" else "${l.name} ${l.ref}"
            }
            Place(
                name = name,
                lat = lat,
                lon = lon,
                lines = a.lineIds.toList(),
                city = town.first,
                cityApprox = town.second,
                isTransfer = a.lineIds.size > 1,
                hasProject = a.types.contains(LineType.PROJECT),
                isTerminal = terminals.contains(name),
                searchText = "$name ${town.first ?: ""} $lineNames".lowercase(),
            )
        }

        return Network(
            lines = lines,
            places = places,
            byName = places.associateBy { it.name },
            lineById = lineById,
            indexInLine = indexInLine,
        )
    }

    /** Направление движения по участку: конечная станция или сторона кольца. */
    fun directionOf(line: Line, fromName: String, toName: String): Direction {
        val idx = line.stations.indexOfFirst { it.name == fromName }
        val idx2 = line.stations.indexOfFirst { it.name == toName }
        if (idx < 0 || idx2 < 0) {
            return Direction(true, line.stations.last().name, line.stations.last().name)
        }
        val n = line.stations.size
        val delta = idx2 - idx
        if (line.ring) {
            val forward = ((delta % n) + n) % n
            val backward = ((idx - idx2) % n + n) % n
            val clockwise = forward <= backward
            return Direction(
                ring = false,
                terminal = "",
                label = if (clockwise) "по часовой стрелке" else "против часовой стрелки",
                clockwise = clockwise,
        )
        }
        val terminal = if (delta > 0) line.stations.last().name else line.stations.first().name
        return Direction(true, terminal, terminal)
    }

    /** Станции участка от from до to включительно, в порядке движения. */
    fun sliceStations(line: Line, fromName: String, toName: String): List<Station> {
        val a = line.stations.indexOfFirst { it.name == fromName }
        val b = line.stations.indexOfFirst { it.name == toName }
        if (a < 0 || b < 0) return emptyList()
        val n = line.stations.size
        if (!line.ring) {
            val step = if (b >= a) 1 else -1
            val out = mutableListOf<Station>()
            var i = a
            while (if (step > 0) i <= b else i >= b) {
                out.add(line.stations[i])
                i += step
            }
            return out
        }
        val forward = ((b - a) % n + n) % n
        val backward = ((a - b) % n + n) % n
        val step = if (forward <= backward) 1 else -1
        val steps = if (step > 0) forward else backward
        val out = mutableListOf<Station>()
        for (k in 0..steps) out.add(line.stations[((a + step * k) % n + n) % n])
        return out
    }

    fun hypot2(a: Double, b: Double): Double = hypot(a, b)
}
