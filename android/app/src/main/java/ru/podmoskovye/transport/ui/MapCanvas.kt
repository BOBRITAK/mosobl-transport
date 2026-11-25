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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.podmoskovye.transport.core.LineType
import ru.podmoskovye.transport.core.Network
import ru.podmoskovye.transport.core.Place
import ru.podmoskovye.transport.core.Route
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

private const val MIN_ZOOM = 7.5f
private const val MAX_ZOOM = 17f
private const val TILE_PX = 256.0

/**
 * Состояние карты без Compose-состояний.
 *
 * Почему так: раньше zoom/center были mutableStateOf и читались в теле
 * композиции — каждый кадр жеста пересобирал весь экран вместе с тайлами.
 * Теперь состояние меняется в pointer-обработчике, а кадр перерисовывается
 * через счётчик, который читается только в draw-фазе.
 */
private class MapViewState {
    var zoom: Float = 10.2f
    var centerLat: Double = 55.7512
    var centerLon: Double = 37.6400
    var width: Int = 0
    var height: Int = 0
    var tick: Int = 0

    // кэш размещения подписей: имя -> индекс варианта смещения
    var placements: MutableMap<String, Int> = HashMap()
    var placementBucket: Int = -1
    var placementVersion: Int = 0

    fun invalidate() { tick++ }

    fun pan(pan: Offset, zoomChange: Float) {
        val worldSize = TILE_PX * 2.0.pow(zoom.toDouble())
        val newZoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
        // Пересчитываем центр в долях мира, чтобы пан не зависел от зума
        val normX = MapRenderData.normX(centerLon) - pan.x / worldSize.toFloat()
        val normY = MapRenderData.normY(centerLat) - pan.y / worldSize.toFloat()
        centerLon = normToLon(normX)
        centerLat = normToLat(normY)
        zoom = newZoom
    }

    fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        val worldSize = TILE_PX * 2.0.pow(zoom.toDouble())
        val cx = width / 2f
        val cy = height / 2f
        val normX = MapRenderData.normX(centerLon)
        val normY = MapRenderData.normY(centerLat)
        // точка под пальцем остаётся на месте
        val dx = (focusX - cx) / worldSize.toFloat()
        val dy = (focusY - cy) / worldSize.toFloat()
        val newZoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val factorWorld = (2.0.pow(zoom.toDouble()) / 2.0.pow(newZoom.toDouble())).toFloat()
        centerLon = normToLon(normX + dx - dx * factorWorld)
        centerLat = normToLat(normY + dy - dy * factorWorld)
        zoom = newZoom
    }
}

private fun normToLon(x: Float): Double = x * 360.0 - 180.0

private fun normToLat(y: Float): Double {
    val n = PI - 2.0 * PI * y
    return 180.0 / PI * kotlin.math.atan(0.5 * (kotlin.math.exp(n) - kotlin.math.exp(-n)))
}

@Composable
fun MapCanvas(
    network: Network,
    visibleTypes: Set<LineType>,
    route: Route?,
    focusPlace: String?,
    onStationClick: (Place) -> Unit,
    modifier: Modifier = Modifier,
) {
    val data = remember(network) { MapRenderData(network) }
    val scope = rememberCoroutineScope()
    val tiles = remember { TileCache(scope) }
    val state = remember { MapViewState() }
    val textMeasurer = rememberTextMeasurer()
    val labelCache = remember { HashMap<String, TextLayoutResult>() }
    val currentOnClick by rememberUpdatedState(onStationClick)

    LaunchedEffect(focusPlace) {
        val place = focusPlace?.let { network.byName[it] } ?: return@LaunchedEffect
        state.centerLat = place.lat
        state.centerLon = place.lon
        if (state.zoom < 13.5f) state.zoom = 13.5f
        state.placementBucket = -1
        state.invalidate()
    }

    Box(modifier = modifier.fillMaxSize().background(Pal.Bg)) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged {
                    state.width = it.width
                    state.height = it.height
                    state.placementBucket = -1
                    state.invalidate()
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoomChange, _ ->
                        if (state.width == 0) return@detectTransformGestures
                        if (pan != Offset.Zero) state.pan(pan, 1f)
                        if (zoomChange != 1f) state.zoomBy(zoomChange, state.width / 2f, state.height / 2f)
                        state.invalidate()
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { tap ->
                        if (state.width == 0) return@detectTapGestures
                        val hit = hitTest(data, state, tap, 30f)
                        if (hit != null) currentOnClick(hit)
                    }
                },
        ) {
            // чтение счётчиков в draw-фазе: меняется только перерисовка
            @Suppress("UNUSED_EXPRESSION")
            state.tick
            @Suppress("UNUSED_EXPRESSION")
            tiles.version.intValue

            if (state.width == 0) return@Canvas
            drawMap(data, state, tiles, visibleTypes, route, textMeasurer, labelCache)
        }
    }
}

private fun hitTest(data: MapRenderData, state: MapViewState, tap: Offset, radius: Float): Place? {
    val worldSize = TILE_PX * 2.0.pow(state.zoom.toDouble())
    val cx = MapRenderData.normX(state.centerLon) * worldSize
    val cy = MapRenderData.normY(state.centerLat) * worldSize
    val halfW = state.width / 2.0
    val halfH = state.height / 2.0
    var best: Place? = null
    var bestDist = radius
    for (station in data.stations) {
        val sx = (station.x * worldSize - cx + halfW).toFloat()
        val sy = (station.y * worldSize - cy + halfH).toFloat()
        val d = hypot(sx - tap.x, sy - tap.y)
        if (d < bestDist) {
            bestDist = d
            best = station.place
        }
    }
    return best
}

private fun DrawScope.drawMap(
    data: MapRenderData,
    state: MapViewState,
    tiles: TileCache,
    visibleTypes: Set<LineType>,
    route: Route?,
    textMeasurer: TextMeasurer,
    labelCache: HashMap<String, TextLayoutResult>,
) {
    val worldSize = TILE_PX * 2.0.pow(state.zoom.toDouble())
    val cx = MapRenderData.normX(state.centerLon) * worldSize
    val cy = MapRenderData.normY(state.centerLat) * worldSize
    val halfW = size.width / 2.0
    val halfH = size.height / 2.0

    fun screenX(nx: Float) = (nx * worldSize - cx + halfW).toFloat()
    fun screenY(ny: Float) = (ny * worldSize - cy + halfH).toFloat()

    // ---- подложка
    val tileZoom = floor(state.zoom).toInt().coerceIn(7, 17)
    val tileScale = 2.0.pow((state.zoom - tileZoom).toDouble()).toFloat()
    val tileSide = (TILE_PX * tileScale).toFloat()
    val worldAtTile = TILE_PX * 2.0.pow(tileZoom.toDouble())
    val left = cx / (worldSize / worldAtTile) - (halfW / tileScale)
    val top = cy / (worldSize / worldAtTile) - (halfH / tileScale)
    val minTx = floor(left / TILE_PX).toInt()
    val maxTx = floor((left + size.width / tileScale) / TILE_PX).toInt()
    val minTy = floor(top / TILE_PX).toInt()
    val maxTy = floor((top + size.height / tileScale) / TILE_PX).toInt()
    val maxIndex = (2.0.pow(tileZoom)).toInt()

    val wanted = ArrayList<String>(32)
    for (tx in minTx..maxTx) {
        for (ty in minTy..maxTy) {
            if (ty < 0 || ty >= maxIndex) continue
            val wrapped = ((tx % maxIndex) + maxIndex) % maxIndex
            val key = "$tileZoom/$wrapped/$ty"
            val bitmap = tiles.get(key)
            if (bitmap == null) {
                wanted.add(key)
                continue
            }
            val dx = (tx * TILE_PX - left) * tileScale
            val dy = (ty * TILE_PX - top) * tileScale
            drawImage(
                image = bitmap,
                dstOffset = androidx.compose.ui.unit.IntOffset(dx.roundToInt(), dy.roundToInt()),
                dstSize = androidx.compose.ui.unit.IntSize(tileSide.roundToInt() + 1, tileSide.roundToInt() + 1),
            )
        }
    }
    if (wanted.isNotEmpty()) tiles.request(wanted)

    val routeLines = route?.lineIds?.toSet() ?: emptySet()
    val lineScale = tileScale.coerceIn(0.85f, 1.5f)

    // ---- линии
    for (line in data.lines) {
        if (!visibleTypes.contains(line.type)) continue
        val dim = route != null && !routeLines.contains(line.id)
        val alpha = if (dim) 0.10f else 1f
        val baseWidth = when (line.type) {
            LineType.METRO -> 5.6f
            LineType.MCC -> 5.0f
            LineType.MCD -> 5.0f
            LineType.PROJECT -> 4.2f
        } * lineScale
        val path = Path()
        var started = false
        for (i in line.xs.indices) {
            val x = screenX(line.xs[i])
            val y = screenY(line.ys[i])
            if (x < -200 || y < -200 || x > size.width + 200 || y > size.height + 200) {
                // линию не обрываем: точка всё равно нужна для непрерывности
            }
            if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
        }
        if (line.ring && line.xs.isNotEmpty()) path.close()
        if (!started) continue
        drawPath(
            path,
            Color(0xFF05080D).copy(alpha = alpha * 0.85f),
            style = Stroke(baseWidth + 3.4f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawPath(
            path,
            line.color.copy(alpha = alpha),
            style = Stroke(baseWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }

    // ---- маршрут
    if (route != null && route.path.size > 1) {
        val pts = route.path.map {
            Offset(screenX(MapRenderData.normX(it.second)), screenY(MapRenderData.normY(it.first)))
        }
        val path = Path()
        pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
        drawPath(path, Color(0xFF05080D), style = Stroke(11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, Color.White, style = Stroke(4.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    // ---- станции
    val stationRadius = (4.6f * lineScale).coerceIn(3.6f, 6.0f)
    val transferRadius = (6.2f * lineScale).coerceIn(5.0f, 8.0f)
    for (station in data.stations) {
        if (station.types.none { visibleTypes.contains(it) }) continue
        val x = screenX(station.x)
        val y = screenY(station.y)
        if (x < -20 || y < -20 || x > size.width + 20 || y > size.height + 20) continue
        val dim = route != null && station.place.lines.none { routeLines.contains(it) }
        val a = if (dim) 0.14f else 1f
        val r = if (station.transfer) transferRadius else stationRadius
        val center = Offset(x, y)
        drawCircle(Color(0xFF0A0E15), radius = r + 1.6f, center = center, alpha = a)
        drawCircle(station.color, radius = r, center = center, alpha = a)
        if (station.transfer) {
            drawCircle(Color.White, radius = r, center = center, style = Stroke(1.7f), alpha = a)
        }
    }

    // ---- подписи
    drawLabels(data, state, textMeasurer, labelCache, ::screenX, ::screenY, route, visibleTypes)
}

private fun DrawScope.drawLabels(
    data: MapRenderData,
    state: MapViewState,
    textMeasurer: TextMeasurer,
    labelCache: HashMap<String, TextLayoutResult>,
    screenX: (Float) -> Float,
    screenY: (Float) -> Float,
    route: Route?,
    visibleTypes: Set<LineType>,
) {
    val zoom = state.zoom
    // сколько подписей показываем: на общем виде — только узлы, дальше всё больше
    val budget = when {
        zoom < 11f -> 28
        zoom < 12.5f -> 55
        zoom < 14f -> 110
        else -> 400
    }
    val showAll = zoom >= 13.2f
    val candidateLimit = if (showAll) 400 else budget

    val bucket = (zoom * 4).toInt()
    if (state.placementBucket != bucket) {
        state.placementBucket = bucket
        state.placements = computePlacements(
            data = data,
            state = state,
            textMeasurer = textMeasurer,
            labelCache = labelCache,
            screenX = screenX,
            screenY = screenY,
            limit = candidateLimit,
            showAll = showAll,
            visibleTypes = visibleTypes,
        )
    }

    val styleSize = if (zoom >= 13f) 11.5f else 10.5f
    for ((name, variant) in state.placements) {
        val station = data.stations.firstOrNull { it.place.name == name } ?: continue
        val x = screenX(station.x)
        val y = screenY(station.y)
        if (x < -80 || y < -40 || x > size.width + 80 || y > size.height + 40) continue
        val layout = labelFor(name, textMeasurer, labelCache, styleSize)
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

private fun computePlacements(
    data: MapRenderData,
    state: MapViewState,
    textMeasurer: TextMeasurer,
    labelCache: HashMap<String, TextLayoutResult>,
    screenX: (Float) -> Float,
    screenY: (Float) -> Float,
    limit: Int,
    showAll: Boolean,
    visibleTypes: Set<LineType>,
): MutableMap<String, Int> {
    val result = HashMap<String, Int>(limit * 2)
    val placed = ArrayList<Rect>(limit)
    val styleSize = if (state.zoom >= 13f) 11.5f else 10.5f

    var count = 0
    for (station in data.stations) {
        if (count >= limit) break
        if (station.types.none { visibleTypes.contains(it) }) continue
        // приоритет: пересадочные узлы, конечные, затем остальные
        if (!showAll && !station.transfer && !station.terminal) continue
        val x = screenX(station.x)
        val y = screenY(station.y)
        if (x < -40 || y < -40 || x > state.width + 40 || y > state.height + 40) continue

        val layout = labelFor(station.place.name, textMeasurer, labelCache, styleSize)
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
        if (chosen >= 0) {
            result[station.place.name] = chosen
            count++
        }
    }
    return result
}
