package ru.podmoskovye.transport.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.podmoskovye.transport.core.LineType
import ru.podmoskovye.transport.core.Network
import ru.podmoskovye.transport.core.Place
import ru.podmoskovye.transport.core.Route
import kotlin.math.hypot

internal class SchemePoint(val name: String, var x: Float, var y: Float)

internal class SchemeLine(
    val id: String,
    val type: LineType,
    val color: Color,
    val names: List<String>,
    val ring: Boolean,
)

internal class SchemeStation(
    val name: String,
    val lineIds: List<String>,
    val types: Set<LineType>,
    val color: Color,
    val transfer: Boolean,
    val terminal: Boolean,
    val project: Boolean,
)

internal class SchemeData(
    val width: Float,
    val height: Float,
    val lines: List<SchemeLine>,
    val base: Map<String, SchemePoint>,
    val stations: Map<String, SchemeStation>,
    val levels: List<Map<String, SchemePoint>>,
)

/** Разбор геометрии схемы — чистые данные, считается в фоне. */
internal fun parseScheme(json: JSONObject, network: Network): SchemeData {
    val t0 = System.nanoTime()
    val meta = json.optJSONObject("meta")
    val width = meta?.optDouble("width", 1000.0)?.toFloat() ?: 1000f
    val height = meta?.optDouble("height", 800.0)?.toFloat() ?: 800f

    val lines = mutableListOf<SchemeLine>()
    val arr = json.optJSONArray("lines")
    if (arr != null) {
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
                SchemeLine(
                    id = o.optString("id"),
                    type = type,
                    color = parseColor(o.optString("color", "#8B95A7")),
                    names = names,
                    ring = o.optBoolean("ring", false),
                )
            )
        }
    }

    val base = HashMap<String, SchemePoint>()
    val stArr = json.optJSONArray("stations")
    if (stArr != null) {
        for (i in 0 until stArr.length()) {
            val o = stArr.optJSONObject(i) ?: continue
            val name = o.optString("name")
            if (name.isBlank()) continue
            val x = o.optDouble("x").toFloat()
            val y = o.optDouble("y").toFloat()
            val prev = base[name]
            if (prev == null) base[name] = SchemePoint(name, x, y)
            else { prev.x = (prev.x + x) / 2f; prev.y = (prev.y + y) / 2f }
        }
    }

    val stations = HashMap<String, SchemeStation>()
    for (place in network.places) {
        if (!base.containsKey(place.name)) continue
        val placeLines = place.lines.mapNotNull { network.lineById[it] }
        val types = placeLines.map { it.type }.toSet()
        stations[place.name] = SchemeStation(
            name = place.name,
            lineIds = place.lines,
            types = types,
            color = parseColor(placeLines.firstOrNull()?.color ?: "#8B95A7"),
            transfer = place.isTransfer,
            terminal = place.isTerminal,
            project = place.hasProject && types.none { it != LineType.PROJECT },
        )
    }

    val levels = buildSpreadLevels(base)
    ru.podmoskovye.transport.data.PerfLog.record("scheme_layout_ms", (System.nanoTime() - t0) / 1_000_000)
    return SchemeData(width, height, lines, base, stations, levels)
}

/**
 * Уровни разведения станций.
 * Раньше считалось в composition при открытии схемы (~30 млн операций в
 * главном потоке) — схема «замирала». Теперь это фоновая задача.
 */
internal fun buildSpreadLevels(base: Map<String, SchemePoint>): List<Map<String, SchemePoint>> {
    val minDistances = floatArrayOf(0f, 30f, 52f)
    val levels = ArrayList<Map<String, SchemePoint>>(minDistances.size)
    var current: MutableMap<String, SchemePoint> =
        base.mapValuesTo(HashMap()) { SchemePoint(it.key, it.value.x, it.value.y) }
    levels.add(current)

    for (levelIndex in 1 until minDistances.size) {
        val minD = minDistances[levelIndex]
        val pos: MutableMap<String, SchemePoint> = HashMap()
        for ((k, v) in current) pos[k] = SchemePoint(k, v.x, v.y)
        val names = pos.keys.toList()
        val n = names.size
        repeat(45) {
            var moved = 0
            for (a in 0 until n) {
                val pa = pos[names[a]] ?: continue
                for (b in a + 1 until n) {
                    val pb = pos[names[b]] ?: continue
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
        levels.add(pos)
        current = pos
    }
    return levels
}

private fun interpolate(levels: List<Map<String, SchemePoint>>, t: Float): Map<String, SchemePoint> {
    if (levels.isEmpty()) return emptyMap()
    if (levels.size == 1) return levels[0]
    val scaled = t * (levels.size - 1)
    val i = scaled.toInt().coerceIn(0, levels.size - 2)
    val k = (scaled - i).coerceIn(0f, 1f)
    val a = levels[i]
    val b = levels[i + 1]
    val out = HashMap<String, SchemePoint>(a.size)
    for ((name, pa) in a) {
        val pb = b[name] ?: pa
        out[name] = SchemePoint(name, pa.x + (pb.x - pa.x) * k, pa.y + (pb.y - pa.y) * k)
    }
    return out
}

private class SchemeState {
    var zoom: Float = 1f
    var panX: Float = 0f
    var panY: Float = 0f
    var width: Int = 0
    var height: Int = 0
    var tick: Int = 0
    var spreadBucket: Int = -1
    var points: Map<String, SchemePoint> = emptyMap()
    var placements: Map<String, Int> = emptyMap()
    var placementBucket: Int = -1
    fun invalidate() { tick++ }
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
    val data by produceState<SchemeData?>(initialValue = null, schemeJson, network) {
        value = if (schemeJson == null) null else withContext(Dispatchers.Default) {
            parseScheme(schemeJson, network)
        }
    }

    val state = remember { SchemeState() }
    val textMeasurer = rememberTextMeasurer()
    val labelCache = remember { HashMap<String, TextLayoutResult>() }
    val currentOnClick by rememberUpdatedState(onStationClick)

    LaunchedEffect(focusPlace, data, state.width) {
        val d = data ?: return@LaunchedEffect
        val name = focusPlace ?: return@LaunchedEffect
        val p = d.base[name] ?: return@LaunchedEffect
        if (state.width == 0) return@LaunchedEffect
        val s = fitScale(d, state) * state.zoom
        val originX = (state.width - d.width * s) / 2f
        val originY = (state.height - d.height * s) / 2f
        state.panX = state.width / 2f - (originX + p.x * s)
        state.panY = state.height / 2f - (originY + p.y * s)
        state.invalidate()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Pal.Bg)
            .onSizeChanged {
                state.width = it.width
                state.height = it.height
                state.placementBucket = -1
                state.invalidate()
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoomChange, _ ->
                    if (state.width == 0) return@detectTransformGestures
                    state.panX += pan.x
                    state.panY += pan.y
                    state.zoom = (state.zoom * zoomChange).coerceIn(0.85f, 6f)
                    state.invalidate()
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { tap ->
                    val d = data ?: return@detectTapGestures
                    if (state.width == 0) return@detectTapGestures
                    val s = fitScale(d, state) * state.zoom
                    val originX = (state.width - d.width * s) / 2f + state.panX
                    val originY = (state.height - d.height * s) / 2f + state.panY
                    var best: String? = null
                    var bestD = 34f
                    for (p in state.points.values) {
                        val sx = originX + p.x * s
                        val sy = originY + p.y * s
                        val dist = hypot(sx - tap.x, sy - tap.y)
                        if (dist < bestD) { bestD = dist; best = p.name }
                    }
                    best?.let { network.byName[it] }?.let(currentOnClick)
                }
            },
    ) {
        val d = data
        Canvas(modifier = Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION")
            state.tick
            if (d == null || state.width == 0) return@Canvas

            // разведение пересчитываем только при смене «шага» масштаба
            val t = ((state.zoom - 1.1f) / 2.1f).coerceIn(0f, 1f)
            val bucket = (t * 12).toInt()
            if (state.spreadBucket != bucket) {
                state.spreadBucket = bucket
                state.points = interpolate(d.levels, bucket / 12f)
                state.placementBucket = -1
            }
            if (state.points.isEmpty()) state.points = d.base

            val s = fitScale(d, state) * state.zoom
            val originX = (state.width - d.width * s) / 2f + state.panX
            val originY = (state.height - d.height * s) / 2f + state.panY

            translate(originX, originY) {
                scale(s, s, pivot = Offset.Zero) {
                    drawSchemeLines(d, state, visibleTypes, route, 1f / s)
                    drawSchemeStations(d, state, visibleTypes, route, 1f / s)
                }
            }
            drawSchemeLabels(d, state, textMeasurer, labelCache, visibleTypes, route, originX, originY, s)
        }
    }
}

private fun fitScale(d: SchemeData, state: SchemeState): Float =
    if (state.width == 0) 1f else minOf(state.width / d.width, state.height / d.height)

private fun DrawScope.drawSchemeLines(
    d: SchemeData,
    state: SchemeState,
    visibleTypes: Set<LineType>,
    route: Route?,
    unit: Float,
) {
    val routeLines = route?.lineIds?.toSet() ?: emptySet()
    val width = 6f * unit
    val casing = 10f * unit
    for (line in d.lines) {
        if (!visibleTypes.contains(line.type)) continue
        val pts = line.names.mapNotNull { state.points[it] }
        if (pts.size < 2) continue
        val dim = route != null && !routeLines.contains(line.id)
        val color = line.color.copy(alpha = if (dim) 0.10f else 1f)
        val path = Path()
        pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
        if (line.ring && pts.size > 2) path.close()
        drawPath(path, Color(0xFF05080D).copy(alpha = color.alpha * 0.85f), style = Stroke(casing, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private fun DrawScope.drawSchemeStations(
    d: SchemeData,
    state: SchemeState,
    visibleTypes: Set<LineType>,
    route: Route?,
    unit: Float,
) {
    val routeLines = route?.lineIds?.toSet() ?: emptySet()
    val stationR = 4.4f * unit
    val transferR = 6.0f * unit
    for ((name, p) in state.points) {
        val st = d.stations[name] ?: continue
        if (st.types.none { visibleTypes.contains(it) }) continue
        val dim = route != null && st.lineIds.none { routeLines.contains(it) }
        val a = if (dim) 0.15f else 1f
        val r = if (st.transfer) transferR else stationR
        val center = Offset(p.x, p.y)
        drawCircle(Color(0xFF0B1119), radius = r + 1.4f * unit, center = center, alpha = a)
        drawCircle(st.color, radius = r, center = center, alpha = a)
        if (st.transfer) {
            drawCircle(Color.White, radius = r, center = center, style = Stroke(1.6f * unit), alpha = a)
        }
    }
}

private fun DrawScope.drawSchemeLabels(
    d: SchemeData,
    state: SchemeState,
    textMeasurer: TextMeasurer,
    labelCache: HashMap<String, TextLayoutResult>,
    visibleTypes: Set<LineType>,
    route: Route?,
    originX: Float,
    originY: Float,
    s: Float,
) {
    val zoom = state.zoom
    val showAll = zoom >= 1.55f
    val limit = when {
        zoom < 1.15f -> 40
        zoom < 1.55f -> 90
        zoom < 2.4f -> 200
        else -> 400
    }
    val fontSize = if (zoom >= 1.8f) 11.5f else 10.5f
    val bucket = (zoom * 4).toInt()

    if (state.placementBucket != bucket) {
        state.placementBucket = bucket
        val placed = ArrayList<Rect>(limit)
        val result = HashMap<String, Int>()
        val routeLines = route?.lineIds?.toSet() ?: emptySet()
        var count = 0
        // приоритет: пересадочные, конечные, затем остальные
        val ordered = d.stations.values.sortedByDescending {
            when {
                it.transfer -> 3
                it.terminal -> 2
                else -> 1
            }
        }
        for (st in ordered) {
            if (count >= limit) break
            if (!showAll && !st.transfer && !st.terminal) continue
            if (st.types.none { visibleTypes.contains(it) }) continue
            if (route != null && st.lineIds.none { routeLines.contains(it) }) continue
            val p = state.points[st.name] ?: continue
            val x = originX + p.x * s
            val y = originY + p.y * s
            if (x < -40 || y < -40 || x > state.width + 40 || y > state.height + 40) continue
            val layout = labelFor(st.name, textMeasurer, labelCache, fontSize)
            val w = layout.size.width.toFloat()
            val h = layout.size.height.toFloat()
            var chosen = -1
            for (variant in 0..7) {
                val (dx, dy) = labelOffset(variant, w, h)
                val rect = Rect(x + dx, y + dy, x + dx + w, y + dy + h)
                if (placed.any { it.overlaps(rect) }) continue
                chosen = variant
                placed.add(rect)
                break
            }
            if (chosen >= 0) { result[st.name] = chosen; count++ }
        }
        state.placements = result
    }

    for ((name, variant) in state.placements) {
        val p = state.points[name] ?: continue
        val x = originX + p.x * s
        val y = originY + p.y * s
        if (x < -80 || y < -40 || x > state.width + 80 || y > state.height + 40) continue
        val layout = labelFor(name, textMeasurer, labelCache, fontSize)
        val (dx, dy) = labelOffset(variant, layout.size.width.toFloat(), layout.size.height.toFloat())
        drawText(layout, topLeft = Offset(x + dx, y + dy))
    }
}

private fun labelFor(
    name: String,
    textMeasurer: TextMeasurer,
    cache: HashMap<String, TextLayoutResult>,
    fontSize: Float,
): TextLayoutResult {
    val key = "$name|$fontSize"
    cache[key]?.let { return it }
    val layout = textMeasurer.measure(
        text = name,
        style = TextStyle(color = Color.White, fontSize = fontSize.sp, fontWeight = FontWeight.SemiBold),
        maxLines = 1,
    )
    cache[key] = layout
    return layout
}

private fun labelOffset(variant: Int, w: Float, h: Float): Pair<Float, Float> = when (variant) {
    0 -> Pair(8f, -h / 2f)
    1 -> Pair(-w - 8f, -h / 2f)
    2 -> Pair(-w / 2f, -h - 7f)
    3 -> Pair(-w / 2f, 7f)
    4 -> Pair(8f, -h - 4f)
    5 -> Pair(-w - 8f, -h - 4f)
    6 -> Pair(8f, 2f)
    else -> Pair(-w - 8f, 2f)
}
