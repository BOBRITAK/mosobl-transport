package ru.podmoskovye.transport.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import ru.podmoskovye.transport.core.Line
import ru.podmoskovye.transport.core.LineType
import ru.podmoskovye.transport.core.Network
import ru.podmoskovye.transport.core.Place
import ru.podmoskovye.transport.core.Route
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

private const val TILE = 256.0
private const val MIN_ZOOM = 7f
private const val MAX_ZOOM = 16.5f

internal fun worldX(lon: Double, zoom: Int): Float {
    val size = TILE * 2.0.pow(zoom)
    return ((lon + 180.0) / 360.0 * size).toFloat()
}

internal fun worldY(lat: Double, zoom: Int): Float {
    val size = TILE * 2.0.pow(zoom)
    val s = sin(lat * PI / 180.0)
    return ((0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * size).toFloat()
}

internal fun latFromWorldY(y: Double, zoom: Int): Double {
    val size = TILE * 2.0.pow(zoom)
    val n = PI - 2.0 * PI * y / size
    return 180.0 / PI * atan(0.5 * (exp(n) - exp(-n)))
}

internal fun lonFromWorldX(x: Double, zoom: Int): Double {
    val size = TILE * 2.0.pow(zoom)
    return x / size * 360.0 - 180.0
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
    var zoom by remember { mutableFloatStateOf(10.2f) }
    var centerLat by remember { mutableStateOf(55.7512) }
    var centerLon by remember { mutableStateOf(37.6400) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val tapRadius = with(density) { 26.dp.toPx() }

    LaunchedEffect(focusPlace) {
        val place = focusPlace?.let { network.byName[it] } ?: return@LaunchedEffect
        centerLat = place.lat
        centerLon = place.lon
        if (zoom < 13f) zoom = 13f
    }

    val tileZoom = zoom.roundToInt().coerceIn(7, 17)
    val scale = 2.0.pow((zoom - tileZoom).toDouble()).toFloat()

    Box(modifier = modifier.fillMaxSize().background(Pal.Bg).onSizeChanged { size = it }) {
        if (size.width > 0) {
            val cx = worldX(centerLon, tileZoom)
            val cy = worldY(centerLat, tileZoom)
            val halfW = size.width / 2f
            val halfH = size.height / 2f
            val left = cx - halfW / scale
            val top = cy - halfH / scale
            val right = cx + halfW / scale
            val bottom = cy + halfH / scale

            val minTx = floor(left / TILE).toInt()
            val maxTx = floor(right / TILE).toInt()
            val minTy = floor(top / TILE).toInt()
            val maxTy = floor(bottom / TILE).toInt()
            val maxIndex = (2.0.pow(tileZoom)).toInt()
            val sidePx = (TILE * scale).toFloat()
            val sideDp = with(density) { sidePx.toDp() }

            for (tx in minTx..maxTx) {
                for (ty in minTy..maxTy) {
                    if (ty < 0 || ty >= maxIndex) continue
                    val wrapped = ((tx % maxIndex) + maxIndex) % maxIndex
                    val x = ((tx * TILE - left) * scale).toFloat()
                    val y = ((ty * TILE - top) * scale).toFloat()
                    AsyncImage(
                        model = "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/$tileZoom/$ty/$wrapped",
                        contentDescription = null,
                        modifier = Modifier
                            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                            .size(sideDp),
                    )
                }
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(tileZoom, scale) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        if (size.width == 0) return@detectTransformGestures
                        val cx = worldX(centerLon, tileZoom).toDouble()
                        val cy = worldY(centerLat, tileZoom).toDouble()
                        val nx = cx - pan.x / scale
                        val ny = cy - pan.y / scale
                        centerLon = lonFromWorldX(nx, tileZoom)
                        centerLat = latFromWorldY(ny, tileZoom)
                        zoom = (zoom * gestureZoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    }
                }
                .pointerInput(tileZoom, scale, network) {
                    detectTapGestures { tap ->
                        val cx = worldX(centerLon, tileZoom)
                        val cy = worldY(centerLat, tileZoom)
                        val left = cx - size.width / 2f / scale
                        val top = cy - size.height / 2f / scale
                        var best: Place? = null
                        var bestD = Float.MAX_VALUE
                        for (place in network.places) {
                            val sx = (worldX(place.lon, tileZoom) - left) * scale
                            val sy = (worldY(place.lat, tileZoom) - top) * scale
                            val d = hypot(sx - tap.x, sy - tap.y)
                            if (d < bestD) { bestD = d; best = place }
                        }
                        val found = best
                        if (found != null && bestD <= tapRadius) onStationClick(found)
                    }
                },
        ) {
            drawTransport(
                network = network,
                visibleTypes = visibleTypes,
                route = route,
                centerLat = centerLat,
                centerLon = centerLon,
                tileZoom = tileZoom,
                scale = scale,
                textMeasurer = textMeasurer,
            )
        }
    }
}

private fun DrawScope.drawTransport(
    network: Network,
    visibleTypes: Set<LineType>,
    route: Route?,
    centerLat: Double,
    centerLon: Double,
    tileZoom: Int,
    scale: Float,
    textMeasurer: TextMeasurer,
) {
    val cx = worldX(centerLon, tileZoom)
    val cy = worldY(centerLat, tileZoom)
    val halfW = size.width / 2f
    val halfH = size.height / 2f

    fun toScreen(lat: Double, lon: Double): Offset = Offset(
        (worldX(lon, tileZoom) - cx) * scale + halfW,
        (worldY(lat, tileZoom) - cy) * scale + halfH,
    )

    val routeLineIds = route?.lineIds?.toSet() ?: emptySet()

    for (line in network.lines) {
        if (!visibleTypes.contains(line.type)) continue
        val dim = route != null && !routeLineIds.contains(line.id)
        val color = lineColor(line.color).copy(alpha = if (dim) 0.10f else 1f)
        drawLinePath(line, ::toScreen, color, if (line.type == LineType.PROJECT) 3f else 5.5f)
    }

    if (route != null && route.path.size > 1) {
        val pts = route.path.map { toScreen(it.first, it.second) }
        val path = Path().apply {
            moveTo(pts.first().x, pts.first().y)
            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
        }
        drawPath(path, Color(0xFF05080D), style = Stroke(width = 11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, Color.White, style = Stroke(width = 4.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    for (place in network.places) {
        if (place.lines.none { id -> network.lineById[id]?.let { visibleTypes.contains(it.type) } == true }) continue
        val dim = route != null && place.lines.none { routeLineIds.contains(it) }
        val p = toScreen(place.lat, place.lon)
        if (p.x < -30 || p.y < -30 || p.x > size.width + 30 || p.y > size.height + 30) continue
        val color = place.lines.firstNotNullOfOrNull { id -> network.lineById[id]?.let { lineColor(it.color) } } ?: Pal.Muted
        val r = if (place.isTransfer) 5.5f else 4.2f
        val a = if (dim) 0.15f else 1f
        drawCircle(Color(0xFF0A0E15), radius = r + 1.7f, center = p, alpha = a)
        drawCircle(color, radius = r, center = p, alpha = a)
        if (place.isTransfer) {
            drawCircle(Color.White, radius = r, center = p, style = Stroke(width = 1.7f), alpha = a)
        }
    }

    // подписи важных станций с защитой от наложения
    val placed = mutableListOf<Rect>()
    val candidates = network.places.filter { it.isTransfer || it.isTerminal }
    for (place in candidates) {
        val p = toScreen(place.lat, place.lon)
        if (p.x < 0 || p.y < 0 || p.x > size.width || p.y > size.height) continue
        val layout = textMeasurer.measure(
            text = place.name,
            style = TextStyle(color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
            maxLines = 1,
        )
        val rect = Rect(
            left = p.x + 7f,
            top = p.y - layout.size.height / 2f,
            right = p.x + 9f + layout.size.width,
            bottom = p.y + layout.size.height / 2f,
        )
        if (placed.any { it.overlaps(rect) }) continue
        placed.add(rect)
        drawText(layout, topLeft = Offset(rect.left, rect.top))
    }
}

private fun DrawScope.drawLinePath(
    line: Line,
    toScreen: (Double, Double) -> Offset,
    color: Color,
    width: Float,
) {
    val pts = line.stations.map { toScreen(it.lat, it.lon) }
    if (pts.size < 2) return
    val path = Path().apply {
        moveTo(pts.first().x, pts.first().y)
        for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
    }
    drawPath(path, Color(0xFF05080D).copy(alpha = color.alpha * 0.8f), style = Stroke(width + 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
