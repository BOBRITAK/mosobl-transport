package ru.podmoskovye.transport.ui

import androidx.compose.ui.graphics.Color
import ru.podmoskovye.transport.core.LineType
import ru.podmoskovye.transport.core.Network
import ru.podmoskovye.transport.core.Place
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.sin

/**
 * Предвычисленные данные для отрисовки карты.
 *
 * Раньше на каждом кадре жеста пересчитывались координаты всех станций и
 * создавались новые объекты. Здесь всё считается один раз при загрузке сети:
 * координаты хранятся в нормализованном виде (0..1) и на кадре остаётся
 * только умножение и смещение — это несколько сотен операций вместо тысяч
 * аллокаций.
 */
class MapRenderData(network: Network) {

    class LineShape(
        val id: String,
        val type: LineType,
        val color: Color,
        val xs: FloatArray,
        val ys: FloatArray,
        val ring: Boolean,
    )

    class StationPoint(
        val place: Place,
        val x: Float,
        val y: Float,
        val color: Color,
        val transfer: Boolean,
        val terminal: Boolean,
        val project: Boolean,
        val types: Set<LineType>,
    )

    val lines: List<LineShape>
    val stations: List<StationPoint>

    /** Станции, чьи подписи показываем в первую очередь. */
    val labelled: List<StationPoint>

    init {
        val t0 = System.nanoTime()
        val shapes = ArrayList<LineShape>(network.lines.size)
        for (line in network.lines) {
            val n = line.stations.size
            val xs = FloatArray(n)
            val ys = FloatArray(n)
            line.stations.forEachIndexed { i, st ->
                xs[i] = normX(st.lon)
                ys[i] = normY(st.lat)
            }
            shapes.add(LineShape(line.id, line.type, parseColor(line.color), xs, ys, line.ring))
        }
        lines = shapes

        val points = ArrayList<StationPoint>(network.places.size)
        for (place in network.places) {
            val placeLines = place.lines.mapNotNull { network.lineById[it] }
            val dominant = placeLines.firstOrNull()
            val types = placeLines.map { it.type }.toSet()
            points.add(
                StationPoint(
                    place = place,
                    x = normX(place.lon),
                    y = normY(place.lat),
                    color = parseColor(dominant?.color ?: "#8B95A7"),
                    transfer = place.isTransfer,
                    terminal = place.isTerminal,
                    project = place.hasProject && types.none { it != LineType.PROJECT },
                    types = types,
                )
            )
        }
        stations = points
        labelled = points.filter { it.transfer || it.terminal }
        ru.podmoskovye.transport.data.PerfLog.record("map_data_ms", (System.nanoTime() - t0) / 1_000_000)
    }

    companion object {
        fun normX(lon: Double): Float = ((lon + 180.0) / 360.0).toFloat()

        fun normY(lat: Double): Float {
            val s = sin(lat * PI / 180.0)
            return (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)).toFloat()
        }
    }
}

internal fun parseColor(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (e: Exception) {
    Pal.Muted
}
