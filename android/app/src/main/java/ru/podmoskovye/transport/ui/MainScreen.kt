package ru.podmoskovye.transport.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.podmoskovye.transport.core.Instruction
import ru.podmoskovye.transport.core.LineType
import ru.podmoskovye.transport.core.Model
import ru.podmoskovye.transport.core.Network
import ru.podmoskovye.transport.core.Place
import ru.podmoskovye.transport.core.Route
import ru.podmoskovye.transport.core.Router
import ru.podmoskovye.transport.data.DataSource
import ru.podmoskovye.transport.data.Prefs
import ru.podmoskovye.transport.data.Wikipedia

enum class Mode { MAP, SCHEME }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(context: Context, prefs: Prefs) {
    var network by remember { mutableStateOf<Network?>(null) }
    var scheme by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }



    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                network = DataSource.loadNetwork(context)
                scheme = DataSource.loadScheme(context)
            } catch (e: Exception) {
                error = e.message ?: "Не удалось загрузить данные"
            }
        }
    }

    val net = network
    if (net == null) {
        Box(Modifier.fillMaxSize().background(Pal.Bg), contentAlignment = Alignment.Center) {
            if (error != null) Text("Ошибка данных: $error", color = Pal.Accent)
            else CircularProgressIndicator(color = Pal.Accent)
        }
        return
    }
    MainContent(net, scheme, prefs, context)
}

/** Содержимое экрана без загрузки данных — так его можно тестировать и рендерить. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainContent(
    net: Network,
    scheme: JSONObject?,
    prefs: Prefs,
    context: Context,
    initialMode: Mode = Mode.MAP,
    initialPlace: String? = null,
) {
    var mode by remember { mutableStateOf(initialMode) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(initialPlace?.let { net.byName[it] }) }

    var layers by remember { mutableStateOf(prefs.layers) }
    var favorites by remember { mutableStateOf(prefs.favorites) }
    var history by remember { mutableStateOf(prefs.history) }

    var routeResult by remember { mutableStateOf<List<Route>>(emptyList()) }
    var routeError by remember { mutableStateOf<String?>(null) }
    var variantIndex by remember { mutableStateOf(0) }
    var showRouteSheet by remember { mutableStateOf(false) }
    var fromName by remember { mutableStateOf("") }
    var toName by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

    val router = remember(net) { Router(net) }
    val types = remember(layers) {
        layers.mapNotNull {
            when (it) {
                "metro" -> LineType.METRO
                "mcc" -> LineType.MCC
                "mcd" -> LineType.MCD
                "project" -> LineType.PROJECT
                else -> null
            }
        }.toSet().ifEmpty { setOf(LineType.METRO) }
    }

    val results = remember(query, net) {
        if (query.isBlank()) emptyList()
        else net.places.filter { it.searchText.contains(query.trim().lowercase()) }
            .sortedWith(compareBy({ if (it.name.lowercase().startsWith(query.trim().lowercase())) 0 else 1 }, { it.name }))
            .take(30)
    }

    val activeRoute = routeResult.getOrNull(variantIndex)

    Box(Modifier.fillMaxSize().background(Pal.Bg)) {
        if (mode == Mode.MAP) {
            MapCanvas(
                network = net,
                visibleTypes = types,
                route = activeRoute,
                focusPlace = selected?.name,
                onStationClick = { place ->
                    selected = place
                    if (!history.contains(place.name)) history = listOf(place.name) + history.take(11)
                    else history = listOf(place.name) + history.filter { it != place.name }
                    prefs.history = history
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            SchemeCanvas(
                network = net,
                schemeJson = scheme,
                visibleTypes = types,
                route = activeRoute,
                focusPlace = selected?.name,
                onStationClick = { place ->
                    selected = place
                    history = (listOf(place.name) + history.filter { it != place.name }).take(12)
                    prefs.history = history
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // верхняя панель: компактный поиск и переключатель режимов
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // компактное поле поиска: 44dp вместо 56dp
                Row(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Pal.Panel)
                        .border(1.dp, if (query.isNotEmpty()) Pal.Accent else Pal.Line, RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("⌕", color = Pal.Muted2, fontSize = 16.sp)
                    Spacer(Modifier.width(6.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Pal.Text, fontSize = 14.sp),
                        cursorBrush = SolidColor(Pal.Accent),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            if (query.isEmpty()) {
                                Text("Станция, линия, город", color = Pal.Muted2, fontSize = 13.sp)
                            }
                            inner()
                        },
                    )
                    if (query.isNotEmpty()) {
                        Text(
                            "✕",
                            color = Pal.Muted2,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { query = "" }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                // переключатель «Карта / Схема» — сегментированный, компактный
                Row(
                    Modifier
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Pal.Panel)
                        .padding(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ModeTab("Карта", mode == Mode.MAP) { mode = Mode.MAP }
                    ModeTab("Схема", mode == Mode.SCHEME) { mode = Mode.SCHEME }
                }
            }

            if (query.isNotBlank()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Pal.Panel),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).heightIn(max = 300.dp),
                ) {
                    LazyColumn {
                        items(results) { place ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { selected = place; query = "" }
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier.size(9.dp).clip(CircleShape)
                                        .background(lineColor(place.lines.firstOrNull()?.let { net.lineById[it]?.color } ?: "#8B95A7"))
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(place.name, color = Pal.Text, fontSize = 14.sp, maxLines = 1)
                                    val lineNames = place.lines.mapNotNull { net.lineById[it]?.shortName }.joinToString(", ")
                                    Text(
                                        (place.city ?: "") + " · " + lineNames,
                                        color = Pal.Muted2, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        if (results.isEmpty()) {
                            item { Text("Ничего не найдено", color = Pal.Muted2, modifier = Modifier.padding(14.dp)) }
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                layerChip("Метро", "metro", layers) { layers = toggle(layers, "metro") }
                layerChip("МЦК", "mcc", layers) { layers = toggle(layers, "mcc") }
                layerChip("МЦД", "mcd", layers) { layers = toggle(layers, "mcd") }
                layerChip("Проект", "project", layers) { layers = toggle(layers, "project") }
            }
        }

        // нижняя панель действий
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)) {
            if (selected == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            fromName = history.firstOrNull() ?: ""
                            showRouteSheet = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Pal.Accent),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f).height(50.dp),
                    ) { Text("Построить маршрут", fontSize = 14.sp) }

                    Button(
                        onClick = {
                            val fav = favorites.toMutableList()
                            val pick = history.firstOrNull()
                            if (pick != null) {
                                if (!fav.contains(pick)) fav.add(pick) else fav.remove(pick)
                                favorites = fav
                                prefs.favorites = fav
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Pal.PanelHigh),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.height(50.dp),
                    ) { Text("★ ${favorites.size}", fontSize = 14.sp) }
                }
            }
        }

        selected?.let { place ->
            StationCard(
                network = net,
                place = place,
                favorites = favorites,
                onToggleFavorite = {
                    val fav = favorites.toMutableList()
                    if (fav.contains(place.name)) fav.remove(place.name) else fav.add(place.name)
                    favorites = fav
                    prefs.favorites = fav
                },
                onClose = { selected = null },
                onFrom = { fromName = place.name; showRouteSheet = true },
                onTo = { toName = place.name; showRouteSheet = true },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (showRouteSheet) {
        RouteSheet(
            network = net,
            router = router,
            types = types,
            initialFrom = fromName,
            initialTo = toName,
            result = routeResult,
            error = routeError,
            variantIndex = variantIndex,
            onVariant = { variantIndex = it },
            onBuild = { f, t ->
                fromName = f
                toName = t
                val res = router.findRoutes(f, t, types)
                routeResult = res.variants
                routeError = if (res.ok) null else res.reason
                variantIndex = 0
                if (res.ok) selected = null
            },
            onDismiss = { showRouteSheet = false },
        )
    }
}

private fun toggle(set: Set<String>, key: String): Set<String> =
    if (set.contains(key)) (set - key).ifEmpty { setOf(key) } else set + key

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun layerChip(label: String, key: String, layers: Set<String>, onClick: () -> Unit) {
    FilterChip(
        selected = layers.contains(key),
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Pal.Accent,
            selectedLabelColor = Color.White,
            containerColor = Pal.Panel,
            labelColor = Pal.Muted,
        ),
    )
}

@Composable
private fun StationCard(
    network: Network,
    place: Place,
    favorites: List<String>,
    onToggleFavorite: () -> Unit,
    onClose: () -> Unit,
    onFrom: () -> Unit,
    onTo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var info by remember(place.name) { mutableStateOf<Wikipedia.Info?>(null) }
    var loading by remember(place.name) { mutableStateOf(true) }

    LaunchedEffect(place.name) {
        loading = true
        info = Wikipedia.stationInfo(place.name)
        loading = false
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Pal.Panel),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        modifier = modifier.fillMaxWidth().heightIn(max = 420.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(place.name, color = Pal.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text(
                        listOfNotNull(
                            place.city?.let { if (place.cityApprox) "$it ≈" else it },
                            if (place.isTransfer) "пересадка" else null,
                            if (place.hasProject) "проект" else null,
                        ).joinToString(" · "),
                        color = Pal.Muted2, fontSize = 12.sp,
                    )
                }
                IconButton(onClick = onToggleFavorite) {
                    Text(
                        if (favorites.contains(place.name)) "★" else "☆",
                        color = if (favorites.contains(place.name)) Pal.Warn else Pal.Muted,
                        fontSize = 20.sp,
                    )
                }
                IconButton(onClick = onClose) { Text("✕", color = Pal.Muted, fontSize = 16.sp) }
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (id in place.lines) {
                    val line = network.lineById[id] ?: continue
                    Row(
                        Modifier.background(Pal.PanelHigh, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(lineColor(line.color)))
                        Spacer(Modifier.width(6.dp))
                        Text(line.shortName, color = Pal.Text, fontSize = 11.sp)
                    }
                }
            }

            if (loading) {
                Text("Загрузка описания…", color = Pal.Muted2, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            } else {                info?.photoUrl?.let { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = place.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .padding(top = 10.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                }
                info?.extract?.let { text ->
                    Text(
                        text,
                        color = Pal.Muted, fontSize = 12.5.sp, maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (info?.extract == null) {
                    Text(
                        "Описание недоступно без интернета. Данные о линиях и пересадках — из встроенной базы.",
                        color = Pal.Muted2, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            // Пересадки: другие линии на этой станции и пешие переходы рядом
            val neighbours = remember(place.name, network) {
                network.places
                    .asSequence()
                    .filter { it.name != place.name }
                    .map { it to ru.podmoskovye.transport.core.Model.haversineKm(place.lat, place.lon, it.lat, it.lon) }
                    .filter { it.second <= 0.6 }
                    .sortedBy { it.second }
                    .take(3)
                    .toList()
            }
            if (place.isTransfer || neighbours.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("ПЕРЕСАДКИ", color = Pal.Muted2, fontSize = 10.sp, letterSpacing = 1.sp)
                Spacer(Modifier.height(4.dp))
                if (place.isTransfer) {
                    val others = place.lines.mapNotNull { network.lineById[it]?.shortName }
                    Text(
                        "На этой станции: " + others.joinToString(", "),
                        color = Pal.Muted, fontSize = 12.sp,
                    )
                }
                neighbours.forEach { (other, km) ->
                    Text(
                        "Переход на «${other.name}» · ${Math.round(km * 1000)} м",
                        color = Pal.Muted, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = Pal.Line)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onFrom,
                    colors = ButtonDefaults.buttonColors(containerColor = Pal.PanelHigh),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                ) { Text("Отсюда", fontSize = 13.sp) }
                Button(
                    onClick = onTo,
                    colors = ButtonDefaults.buttonColors(containerColor = Pal.Accent),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                ) { Text("Сюда", fontSize = 13.sp) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteSheet(
    network: Network,
    router: Router,
    types: Set<LineType>,
    initialFrom: String,
    initialTo: String,
    result: List<Route>,
    error: String?,
    variantIndex: Int,
    onVariant: (Int) -> Unit,
    onBuild: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var from by remember { mutableStateOf(initialFrom) }
    var to by remember { mutableStateOf(initialTo) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Pal.Panel,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Маршрут", color = Pal.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = from, onValueChange = { from = it },
                label = { Text("Откуда", fontSize = 12.sp) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Pal.PanelHigh, unfocusedContainerColor = Pal.PanelHigh,
                    focusedTextColor = Pal.Text, unfocusedTextColor = Pal.Text, focusedBorderColor = Pal.Accent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = to, onValueChange = { to = it },
                label = { Text("Куда", fontSize = 12.sp) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Pal.PanelHigh, unfocusedContainerColor = Pal.PanelHigh,
                    focusedTextColor = Pal.Text, unfocusedTextColor = Pal.Text, focusedBorderColor = Pal.Accent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { onBuild(from.trim(), to.trim()) },
                colors = ButtonDefaults.buttonColors(containerColor = Pal.Accent),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(46.dp),
            ) { Text("Построить") }

            error?.let {
                Text(it, color = Pal.AccentSoft, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            }

            if (result.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Варианты", color = Pal.Muted, fontSize = 11.sp, letterSpacing = 1.sp)
                Spacer(Modifier.height(6.dp))
                result.forEachIndexed { index, route ->
                    val active = index == variantIndex
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (active) Pal.Accent.copy(alpha = 0.16f) else Pal.PanelHigh
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(10.dp).clickableNoRipple { onVariant(index) }
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(formatDuration(route.minutes), color = Pal.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(8.dp))
                                Text(route.label.uppercase(), color = if (active) Pal.AccentSoft else Pal.Muted, fontSize = 10.sp)
                            }
                            Text(
                                "${route.transfers} " + plural(route.transfers, "пересадка", "пересадки", "пересадок") +
                                    " · ${route.stationCount} " + plural(route.stationCount, "станция", "станции", "станций"),
                                color = Pal.Muted2, fontSize = 11.5.sp,
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                route.lineIds.forEach { id ->
                                    val line = network.lineById[id] ?: return@forEach
                                    Row(
                                        Modifier.background(Pal.Bg, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(Modifier.size(6.dp).clip(CircleShape).background(lineColor(line.color)))
                                        Spacer(Modifier.width(4.dp))
                                        Text(line.shortName, color = Pal.Text, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                val route = result.getOrNull(variantIndex)
                if (route != null) {
                    Spacer(Modifier.height(6.dp))
                    Text("Этапы", color = Pal.Muted, fontSize = 11.sp, letterSpacing = 1.sp)
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                        items(route.legs) { leg ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Box(
                                    Modifier.width(4.dp).height(30.dp)
                                        .background(lineColor(leg.color), RoundedCornerShape(2.dp))
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        "${leg.lineShortName} · " + if (leg.direction.ring) leg.direction.label
                                        else "в направлении ${leg.direction.terminal}",
                                        color = Pal.Text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        "${leg.from} → ${leg.to} · ${formatDuration(leg.minutes)} · ${leg.stationCount} ст.",
                                        color = Pal.Muted2, fontSize = 11.sp,
                                    )
                                }
                            }
                        }
                    }

                    val instructions: List<Instruction> = router.buildInstructions(route)
                    Spacer(Modifier.height(8.dp))
                    Text("Инструкция", color = Pal.Muted, fontSize = 11.sp, letterSpacing = 1.sp)
                    Spacer(Modifier.height(4.dp))
                    instructions.forEachIndexed { i, step ->
                        Text(
                            "${i + 1}. ${step.text}",
                            color = Pal.Muted, fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun String.uppercaseRu(): String = this

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

fun formatDuration(minutes: Int): String {
    val m = minutes.coerceAtLeast(1)
    return if (m < 60) "$m мин"
    else {
        val h = m / 60
        val rest = m % 60
        if (rest > 0) "$h ч $rest мин" else "$h ч"
    }
}

fun plural(n: Int, one: String, few: String, many: String): String {
    val mod10 = n % 10
    val mod100 = n % 100
    return when {
        mod10 == 1 && mod100 != 11 -> one
        mod10 in 2..4 && (mod100 < 10 || mod100 >= 20) -> few
        else -> many
    }
}

/** Компактная вкладка переключения «Карта / Схема». */
@Composable
private fun ModeTab(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (active) Pal.PanelHigh else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (active) Pal.Text else Pal.Muted2, fontSize = 12.sp)
    }
}
