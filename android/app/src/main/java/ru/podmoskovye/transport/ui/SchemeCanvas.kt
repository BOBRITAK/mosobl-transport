package ru.podmoskovye.transport.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import ru.podmoskovye.transport.core.LineType
import ru.podmoskovye.transport.core.Network
import ru.podmoskovye.transport.core.Place
import ru.podmoskovye.transport.core.Route
import kotlin.math.hypot

/** Точка схемы. */
private data class SPoint(val name: String, var x: Float, var y: Float)

private data class SLine(
    val id: String,
    val type: LineType,
    val color: String,
    val names: List<String>,
    val ring: Boolean,
)

private class SchemeData(
    val width: Float,
    val height: Float,
    val lines: List<SLine>,
)

private fun parseScheme(json: JSONObject): SchemeData {
    val meta = json.optJSONObject("meta")
    val width = meta?.optDouble("width", 1000.0)?.toFloat() ?: 1000f
    val height = meta?.optDouble("height", 800.0)?.toFloat() ?: 800f
    val lines = mutableListOf<SLine>()
    val arr = json.optJSONArray("lines") ?: return SchemeData(width, height, emptyList())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val type = when (o.optString("type")) {
            "metro" -> LineType.METRO
            "mcc" -> LineType.MCC
            "mcd" -> LineType.MCD
            else -> LineType.PROJECT
        }
        val stArr = o.optJSONArray("stations") ?: continue
        val names = (0 until stArr.length()).map { stArr.getString(it) }
        lines.add(
            SLine(
                id = o.optString("id"),
                type = type,
                color = o.optString("color", "#8B95A7"),
                names = names,
                ring = o.optBoolean("ring", false),
            )
        )
    }
    return SchemeData(width, height, lines)
}

/** Позиции станций из файла. */
private fun parsePoints(json: JSONObject): Map<String, SPoint> {
    val out = HashMap<String, SPoint>()
    val arr = json.optJSONArray("stations") ?: return out
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val name = o.optString("name")
        if (name.isBlank()) continue
        val prev = out[name]
        val x = o.optDouble("x").toFloat()
        val y = o.optDouble("y").toFloat()
        // у станции несколько копий — усредняем, как на сайте
        if (prev == null) out[name] = SPoint(name, x, y)
        else { prev.x = (prev.x + x) / 2f; prev.y = (prev.y + y) / 2f }
    }
    return out
}

@Composable
fun SchemeCanvas(
    network: Network,
    schemeJson: JSONObject?,
    visibleTypes: Set<LineType>,
    route: Route?,
    focusPlace: String?,
    onStationClick: (Place) -> Unit,
    modifier: Modifier = Modifier,
) {
    val data = remember(schemeJson) { schemeJson?.let { parseScheme(it) } }
    val basePoints = remember(schemeJson) { schemeJson?.let { parsePoints(it) } ?: emptyMap() }

    // адаптивная геометрия: несколько уровней разведения
    val levels = remember(schemeJson) {
        if (basePoints.isEmpty()) emptyList()
        else buildSpreadLevels(basePoints)
    }

    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val textMeasurer = rememberTextMeasurer()

    if (data == null || basePoints.isEmpty()) {
        Box(modifier = modifier.fillMaxSize().background(Pal.Bg))
        return
    }

    val fitScale = if (size.width > 0) minOf(size.width / data.width, size.height / data.height) else 1f
    val t = ((zoom - 1.1f) / 2.1f).coerceIn(0f, 1f)
    val points = remember(levels, t) { interpolate(levels, t) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Pal.Bg)
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    panX += pan.x
                    panY += pan.y
                    zoom = (zoom * gestureZoom).coerceIn(1f, 6f)
                }
            }
            .pointerInput(points, fitScale, zoom, panX, panY, size) {
                detectTapGestures { tap ->
                    if (size.width == 0) return@detectTapGestures
                    val originX = (size.width - data.width * fitScale * zoom) / 2f + panX
                    val originY = (size.height - data.height * fitScale * zoom) / 2f + panY
                    var best: String? = null
                    var bestD = Float.MAX_VALUE
                    for (p in points.values) {
                        val sx = originX + p.x * fitScale * zoom
                        val sy = originY + p.y * fitScale * zoom
                        val d = hypot(sx - tap.x, sy - tap.y)
                        if (d < bestD) { bestD = d; best = p.name }
                    }
                    val found = best
                    if (found != null && bestD < 34f) {
                        network.byName[found]?.let(onStationClick)
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (size.width == 0) return@Canvas
            val s = fitScale * zoom
            val originX = (size.width - data.width * s) / 2f + panX
            val originY = (size.height - data.height * s) / 2f + panY

            translate(originX, originY) {
                scale(s, s, pivot = Offset.Zero) {
                    drawScheme(
                        data = data,
                        points = points,
                        visibleTypes = visibleTypes,
                        route = route,
                        network = network,
                        textMeasurer = textMeasurer,
                        unit = 1f / s,
                        viewport = Size(size.width.toFloat(), size.height.toFloat()),
                        origin = Offset(originX, originY),
                    )
                }
            }
        }

        // подписи рисуем отдельно в экранных координатах: так они не растягиваются
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (size.width == 0) return@Canvas
            val s = fitScale * zoom
            val originX = (size.width - data.width * s) / 2f + panX
            val originY = (size.height - data.height * s) / 2f + panY
            drawLabels(
                network = network,
                points = points,
                visibleTypes = visibleTypes,
                route = route,
                textMeasurer = textMeasurer,
                originX = originX,
                originY = originY,
                s = s,
                viewportW = size.width.toFloat(),
                viewportH = size.height.toFloat(),
                zoom = zoom,
            )
        }
    }
}

private fun buildSpreadLevels(base: Map<String, SPoint>): List<Map<String, SPoint>> {
    val minList = listOf(0f, 30f, 52f)
    val out = mutableListOf<Map<String, SPoint>>()
    var current = base.mapValues { SPoint(it.key, it.value.x, it.value.y) }
    out.add(current)
    for (i in 1 until minList.size) {
        val minD = minList[i]
        val pos = current.mapValues { SPoint(it.key, it.value.x, it.value.y) }.toMutableMap()
        val names = pos.keys.toList()
        repeat(50) {
            var moved = 0
            for (a in names.indices) {
                for (b in a + 1 until names.size) {
                    val pa = pos[names[a]]!!
                    val pb = pos[names[b]]!!
                    val dx = pb.x - pa.x
                    val dy = pb.y - pa.y
                    val d = hypot(dx, dy)
                    if (d > minD || d < 0.001f) continue
                    val push = (minD - d) / 2f * 0.5f
                    val ux = dx / d
                    val uy = dy / d
                    pa.x -= ux * push; pa.y -= uy * push
                    pb.x += ux * push; pb.y += uy * push
                    moved++
                }
            }
            if (moved == 0) return@repeat
        }
        out.add(pos)
        current = pos
    }
    return out
}

private fun interpolate(levels: List<Map<String, SPoint>>, t: Float): Map<String, SPoint> {
    if (levels.isEmpty()) return emptyMap()
    if (levels.size == 1) return levels[0]
    val scaled = t * (levels.size - 1)
    val i = scaled.toInt().coerceIn(0, levels.size - 2)
    val k = (scaled - i).coerceIn(0f, 1f)
    val a = levels[i]
    val b = levels[i + 1]
    val out = HashMap<String, SPoint>()
    for ((name, pa) in a) {
        val pb = b[name] ?: pa
        out[name] = SPoint(name, pa.x + (pb.x - pa.x) * k, pa.y + (pb.y - pa.y) * k)
    }
    return out
}

private fun DrawScope.drawScheme(
    data: SchemeData,
    points: Map<String, SPoint>,
    visibleTypes: Set<LineType>,
    route: Route?,
    network: Network,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    unit: Float,
    viewport: Size,
    origin: Offset,
) {
    val routeLineIds = route?.lineIds?.toSet() ?: emptySet()
    val lineWidth = 6f * unit
    val casingWidth = 10f * unit

    for (line in data.lines) {
        if (!visibleTypes.contains(line.type)) continue
        val pts = line.names.mapNotNull { points[it] }
        if (pts.size < 2) continue
        val dim = route != null && !routeLineIds.contains(line.id)
        val color = lineColor(line.color).copy(alpha = if (dim) 0.10f else 1f)
        val path = Path().apply {
            moveTo(pts.first().x, pts.first().y)
            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
            if (line.ring && pts.size > 2) close()
        }
        drawPath(path, Color(0xFF05080D).copy(alpha = color.alpha * 0.85f), style = Stroke(casingWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, color, style = Stroke(lineWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    if (route != null && route.path.size > 1) {
        // на схеме маршрут подсвечиваем линиями — здесь только точки пересадок
    }

    val stationR = 4.2f * unit
    val transferR = 5.6f * unit
    for ((name, p) in points) {
        val place = network.byName[name] ?: continue
        if (place.lines.none { id -> network.lineById[id]?.let { visibleTypes.contains(it.type) } == true }) continue
        val dim = route != null && place.lines.none { routeLineIds.contains(it) }
        val a = if (dim) 0.15f else 1f
        val color = place.lines.firstNotNullOfOrNull { id -> network.lineById[id]?.let { lineColor(it.color) } } ?: Pal.Muted
        val r = if (place.isTransfer) transferR else stationR
        drawCircle(Color(0xFF0B1119), radius = r + 1.4f * unit, center = Offset(p.x, p.y), alpha = a)
        drawCircle(color, radius = r, center = Offset(p.x, p.y), alpha = a)
        if (place.isTransfer) {
            drawCircle(Color.White, radius = r, center = Offset(p.x, p.y), style = Stroke(1.6f * unit), alpha = a)
        }
    }
    if (unit > 0) { /* параметр нужен для масштабирования обводок */ }
}

private fun DrawScope.drawLabels(
    network: Network,
    points: Map<String, SPoint>,
    visibleTypes: Set<LineType>,
    route: Route?,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    originX: Float,
    originY: Float,
    s: Float,
    viewportW: Float,
    viewportH: Float,
    zoom: Float,
) {
    val showAll = zoom >= 1.55f
    val routeLineIds = route?.lineIds?.toSet() ?: emptySet()
    val placed = mutableListOf<Rect>()

    val candidates = points.entries
        .mapNotNull { (name, p) -> network.byName[name]?.let { it to p } }
        .filter { (place, _) ->
            place.lines.any { id -> network.lineById[id]?.let { visibleTypes.contains(it.type) } == true } &&
                (route == null || place.lines.any { routeLineIds.contains(it) }) &&
                (showAll || place.isTransfer || place.isTerminal)
        }
        .sortedByDescending { (place, _) -> if (place.isTransfer) 1 else 0 }

    for ((place, p) in candidates) {
        val sx = originX + p.x * s
        val sy = originY + p.y * s
        if (sx < 0 || sy < 0 || sx > viewportW || sy > viewportH) continue
        val layout = textMeasurer.measure(
            text = place.name,
            style = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            maxLines = 1,
        )
        val rect = Rect(sx + 8f, sy - layout.size.height / 2f, sx + 10f + layout.size.width, sy + layout.size.height / 2f)
        if (placed.any { it.overlaps(rect) }) continue
        placed.add(rect)
        drawText(layout, topLeft = Offset(rect.left, rect.top))
    }
}
