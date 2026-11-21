package ru.podmoskovye.transport.core

/**
 * Направление движения.
 * Для радиальных линий — конечная станция в сторону движения.
 * Для кольцевых — сторона движения (по/против часовой).
 */
data class Direction(
    val ring: Boolean = false,
    val terminal: String = "",
    val label: String = "",
    val clockwise: Boolean = false,
)

/** Участок поездки по одной линии: именно тот отрезок, который едет пассажир. */
data class Leg(
    val lineId: String,
    val lineRef: String,
    val lineName: String,
    val lineShortName: String,
    val lineType: LineType,
    val color: String,
    val from: String,
    val to: String,
    val direction: Direction,
    val stations: List<String>,
    val points: List<Pair<Double, Double>>,
    val minutes: Int,
) {
    val stationCount: Int get() = stations.size
}

/** Пересадка: внутри станции или пеший переход между разными станциями. */
data class TransferStep(
    val at: String,
    val walk: Boolean,
    val km: Double,
    val minutes: Int,
    val fromLine: String,
    val toLine: String,
    val nextStation: String?,
    val direction: Direction?,
)

data class Route(
    val id: String,
    val label: String,
    val hint: String,
    val minutes: Int,
    val transfers: Int,
    val stationCount: Int,
    val legs: List<Leg>,
    val transfersList: List<TransferStep>,
    val path: List<Pair<Double, Double>>,
    val stations: List<String>,
) {
    val lineIds: List<String> get() = legs.map { it.lineId }.distinct()
}

data class RouteResult(
    val ok: Boolean,
    val reason: String = "",
    val variants: List<Route> = emptyList(),
)

/** Шаг текстовой инструкции — строится из того же объекта маршрута, что и карта. */
data class Instruction(val kind: String, val text: String)

private const val TRANSFER_MIN = 4.0
private const val WALK_SPEED_KMH = 4.5
private const val WALK_MAX_KM = 0.6

class Router(private val network: Network) {

    private data class Node(
        val key: String,
        val place: Place,
        val line: Line,
        val edges: MutableList<Edge> = mutableListOf(),
    )

    private data class Edge(
        val to: String,
        val w: Double,
        val kind: String,
        val via: String,
        val lineId: String? = null,
        val lineType: LineType? = null,
        val km: Double = 0.0,
    )

    private fun speed(type: LineType): Double = when (type) {
        LineType.METRO -> 39.0
        LineType.MCC -> 46.0
        LineType.MCD -> 55.0
        LineType.PROJECT -> 40.0
    }

    private fun buildGraph(types: Set<LineType>): Map<String, Node> {
        val nodes = LinkedHashMap<String, Node>()
        val usable = network.lines.filter { types.contains(it.type) }
        for (line in usable) {
            for (st in line.stations) {
                val place = network.byName[st.name] ?: continue
                val key = "${place.name}||${line.id}"
                nodes.getOrPut(key) { Node(key, place, line) }
            }
        }
        for (line in usable) {
            val places = line.stations.mapNotNull { network.byName[it.name] }
            for (i in 1 until places.size) {
                val a = places[i - 1]
                val b = places[i]
                if (a.name == b.name) continue
                val ka = "${a.name}||${line.id}"
                val kb = "${b.name}||${line.id}"
                val na = nodes[ka] ?: continue
                val nb = nodes[kb] ?: continue
                val km = Model.haversineKm(a.lat, a.lon, b.lat, b.lon)
                val minutes = maxOf(1.0, km / speed(line.type) * 60 + 0.7)
                na.edges.add(Edge(kb, minutes, "ride", b.name, line.id, line.type))
                nb.edges.add(Edge(ka, minutes, "ride", a.name, line.id, line.type))
            }
        }
        val byPlace = nodes.values.groupBy { it.place.name }
        for (group in byPlace.values) {
            for (a in group) {
                for (b in group) {
                    if (a === b) continue
                    a.edges.add(Edge(b.key, TRANSFER_MIN, "transfer", b.place.name, b.line.id, b.line.type))
                }
            }
        }
        val placeList = byPlace.keys.mapNotNull { network.byName[it] }
        for (i in placeList.indices) {
            for (j in i + 1 until placeList.size) {
                val p = placeList[i]
                val q = placeList[j]
                val km = Model.haversineKm(p.lat, p.lon, q.lat, q.lon)
                if (km > WALK_MAX_KM) continue
                val walkMin = maxOf(2.0, km / WALK_SPEED_KMH * 60 + 1.5)
                val from = byPlace[p.name] ?: continue
                val to = byPlace[q.name] ?: continue
                for (a in from) {
                    for (b in to) {
                        a.edges.add(Edge(b.key, walkMin, "walk", q.name, b.line.id, b.line.type, km))
                        b.edges.add(Edge(a.key, walkMin, "walk", p.name, a.line.id, a.line.type, km))
                    }
                }
            }
        }
        return nodes
    }

    private fun dijkstra(
        nodes: Map<String, Node>,
        fromName: String,
        toName: String,
        transferMul: Double,
        typeMul: Map<LineType, Double>,
        lineMul: Map<String, Double> = emptyMap(),
    ): List<Pair<Node, Edge?>>? {
        val starts = nodes.values.filter { it.place.name == fromName }
        val ends = nodes.values.filter { it.place.name == toName }.map { it.key }.toSet()
        if (starts.isEmpty() || ends.isEmpty()) return null

        val dist = HashMap<String, Double>()
        val prev = HashMap<String, Pair<String, Edge>>()
        val visited = HashSet<String>()
        for (s in starts) dist[s.key] = 0.0

        while (true) {
            var cur: String? = null
            var best = Double.MAX_VALUE
            for ((key, d) in dist) {
                if (visited.contains(key) || d >= best) continue
                best = d
                cur = key
            }
            val c = cur ?: break
            visited.add(c)
            val node = nodes[c] ?: continue
            for (e in node.edges) {
                val cost = if (e.kind == "ride") {
                    val tm = typeMul[e.lineType] ?: 1.0
                    val lm = e.lineId?.let { lineMul[it] } ?: 1.0
                    e.w * tm * lm
                } else if (e.kind == "walk") {
                    e.w * transferMul * 1.15
                } else {
                    e.w * transferMul
                }
                val nd = best + cost
                if (nd < (dist[e.to] ?: Double.MAX_VALUE)) {
                    dist[e.to] = nd
                    prev[e.to] = c to e
                }
            }
        }

        var end: String? = null
        var bestTime = Double.MAX_VALUE
        for (key in ends) {
            val d = dist[key] ?: continue
            if (d < bestTime) {
                bestTime = d
                end = key
            }
        }
        val last = end ?: return null

        val path = mutableListOf<Pair<Node, Edge?>>()
        var cursor: String? = last
        while (cursor != null) {
            val node = nodes[cursor] ?: break
            val p = prev[cursor]
            path.add(0, node to p?.second)
            cursor = p?.first
        }
        return path
    }

    private fun assemble(path: List<Pair<Node, Edge?>>, id: String, label: String, hint: String): Route {
        val legs = mutableListOf<Leg>()
        val transfers = mutableListOf<TransferStep>()
        var total = 0.0
        var walkKm = 0.0

        var curLine: Line? = null
        var from = ""
        var to = ""
        var rideMinutes = 0.0

        fun closeLeg() {
            val line = curLine ?: return
            val stations = Model.sliceStations(line, from, to)
            val dir = Model.directionOf(line, from, to)
            val leg = Leg(
                lineId = line.id,
                lineRef = line.ref,
                lineName = line.name,
                lineShortName = line.shortName,
                lineType = line.type,
                color = line.color,
                from = from,
                to = to,
                direction = dir,
                stations = stations.map { it.name },
                points = stations.map { it.lat to it.lon },
                minutes = maxOf(1, Math.round(rideMinutes).toInt()),
            )
            legs.add(leg)
            total += leg.minutes
            curLine = null
        }

        for ((i, entry) in path.withIndex()) {
            val (node, incoming) = entry
            val currentLine = curLine
            if (currentLine == null || currentLine.id != node.line.id) {
                closeLeg()
                curLine = node.line
                from = node.place.name
                to = node.place.name
                rideMinutes = 0.0
                if (legs.isNotEmpty()) {
                    val prevLeg = legs.last()
                    val te = incoming
                    val isTransferEdge = te != null && (te.kind == "transfer" || te.kind == "walk")
                    val walk = te != null && te.kind == "walk"
                    if (walk) walkKm += te?.km ?: 0.0
                    val minutes = if (isTransferEdge) maxOf(1, Math.round(te!!.w).toInt()) else TRANSFER_MIN.toInt()
                    total += minutes
                    transfers.add(
                        TransferStep(
                            at = node.place.name,
                            walk = walk,
                            km = if (walk) (te?.km ?: 0.0) else 0.0,
                            minutes = minutes,
                            fromLine = prevLeg.lineShortName,
                            toLine = node.line.shortName,
                            nextStation = null,
                            direction = null,
                        )
                    )
                }
                continue
            }
            if (incoming != null && incoming.kind == "ride") {
                to = node.place.name
                rideMinutes += incoming.w
            }
        }
        closeLeg()

        // убираем участки без движения
        legs.removeAll { it.stationCount < 2 }

        // пересадки: номер, следующая станция и направление из следующего участка
        val cleaned = mutableListOf<TransferStep>()
        var legIndex = 0
        for (step in transfers) {
            if (legIndex >= legs.size - 1) break
            val next = legs[legIndex + 1]
            cleaned.add(
                step.copy(
                    nextStation = next.stations.getOrNull(1) ?: next.to,
                    direction = next.direction,
                )
            )
            legIndex++
        }

        total = legs.sumOf { it.minutes.toDouble() } + cleaned.sumOf { it.minutes.toDouble() }

        val pathPoints = mutableListOf<Pair<Double, Double>>()
        for (leg in legs) {
            for (p in leg.points) {
                if (pathPoints.isEmpty() || pathPoints.last() != p) pathPoints.add(p)
            }
        }

        return Route(
            id = id,
            label = label,
            hint = hint,
            minutes = Math.round(total).toInt(),
            transfers = cleaned.size,
            stationCount = path.size,
            legs = legs,
            transfersList = cleaned,
            path = pathPoints,
            stations = path.map { it.first.place.name },
        )
    }

    fun findRoutes(fromName: String, toName: String, types: Set<LineType>): RouteResult {
        if (network.byName[fromName] == null) {
            return RouteResult(false, "Станция «$fromName» не найдена")
        }
        if (network.byName[toName] == null) {
            return RouteResult(false, "Станция «$toName» не найдена")
        }
        if (fromName == toName) return RouteResult(false, "Начало и конец маршрута совпадают")
        if (types.isEmpty()) return RouteResult(false, "Не выбрано ни одного вида транспорта")

        val nodes = buildGraph(types)

        val fastTypes = mapOf(
            LineType.METRO to 1.0,
            LineType.MCC to 1.0,
            LineType.MCD to 1.0,
            LineType.PROJECT to 1.05,
        )
        val fewTypes = mapOf(
            LineType.METRO to 1.0,
            LineType.MCC to 1.1,
            LineType.MCD to 1.1,
            LineType.PROJECT to 1.2,
        )

        val variants = mutableListOf<Route>()
        val seen = mutableSetOf<String>()
        fun signature(r: Route) = r.legs.joinToString(";") { "${it.lineId}|${it.from}|${it.to}" }

        fun tryAdd(r: Route?) {
            if (r == null) return
            if (r.legs.isEmpty()) return
            val s = signature(r)
            if (seen.add(s)) variants.add(r)
        }

        tryAdd(dijkstra(nodes, fromName, toName, 1.0, fastTypes)?.let {
            assemble(it, "fast", "Самый быстрый", "минимум времени")
        })
        tryAdd(dijkstra(nodes, fromName, toName, 3.4, fewTypes)?.let {
            assemble(it, "fewer", "Меньше пересадок", "без лишних переходов")
        })

        if (variants.isNotEmpty()) {
            val lineMul = variants.flatMap { it.lineIds }.associateWith { 1.8 }
            tryAdd(dijkstra(nodes, fromName, toName, 1.15, fastTypes, lineMul)?.let {
                assemble(it, "alt", "Альтернативный", "другие линии")
            })
        }

        if (variants.isEmpty()) {
            return RouteResult(false, "Маршрут не найден: станции не связаны выбранными линиями")
        }
        return RouteResult(true, "", variants.sortedBy { it.minutes })
    }

    /** Текст строится только из steps маршрута — источник один и тот же. */
    fun buildInstructions(route: Route): List<Instruction> {
        val out = mutableListOf<Instruction>()

        fun dirText(d: Direction): String =
            if (d.ring) d.label else "в направлении «${d.terminal}»"

        for ((index, leg) in route.legs.withIndex()) {
            out.add(
                Instruction(
                    "board",
                    "Сесть на ${leg.lineShortName} ${dirText(leg.direction)} и проехать до станции «${leg.to}»."
                )
            )
            val transfer = route.transfersList.getOrNull(index)
            if (transfer != null) {
                out.add(
                    Instruction(
                        "transfer",
                        if (transfer.walk) {
                            "Пешком перейти на «${transfer.at}» (${Math.round(transfer.km * 1000)} м, около ${transfer.minutes} мин)."
                        } else {
                            "Перейти на ${transfer.toLine} на станции «${transfer.at}»."
                        }
                    )
                )
            }
        }
        route.legs.lastOrNull()?.let {
            out.add(Instruction("finish", "Выйти на станции «${it.to}»."))
        }
        return out
    }
}
