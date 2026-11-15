package com.famtrack.app.ui.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.famtrack.app.R
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.RoundCap
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.CancellationException

// ---------------------------------------------------------------------------
// HIST-UX — componentes da tela de Histórico:
//  - seletor de membro (avatar + nome, cor do membro) e de dia;
//  - mapa do dia: visão geral (trajetos numerados) OU um único trajeto
//    (linha larga com contorno, setas de direção, marcadores A e B);
//  - linha do tempo "Parada / Trajeto" e painel do trajeto selecionado.
// ---------------------------------------------------------------------------

/** Membro como exibido no seletor. */
internal data class HistoryMemberUi(
    val userId: String,
    val name: String,
    val color: Color
)

private val DIMMED_TRIP_COLOR = Color(0xFF8A9099)
private val SPEED_WARNING_BG = Color(0xFFFFF3E0)
private val SPEED_WARNING_FG = Color(0xFF9A4A06)

// ---------------------------------------------------------------------------
// Seletores
// ---------------------------------------------------------------------------

@Composable
internal fun MemberSelectorRow(
    members: List<HistoryMemberUi>,
    selectedId: String?,
    onSelect: (String) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(members, key = { it.userId }) { m ->
            MemberChip(member = m, isSelected = m.userId == selectedId, onClick = { onSelect(m.userId) })
        }
    }
}

@Composable
private fun MemberChip(
    member: HistoryMemberUi,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (isSelected) {
            member.color.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        },
        border = if (isSelected) BorderStroke(2.dp, member.color) else null,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .semantics { selected = isSelected }
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(member.color),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = memberInitials(member.name),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = member.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (isSelected) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = member.color
                )
            }
        }
    }
}

@Composable
internal fun DaySelectorRow(
    label: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    showTodayShortcut: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPick: () -> Unit,
    onToday: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrev, enabled = canGoBack) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.history_prev_day)
            )
        }
        Surface(
            onClick = onPick,
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.CalendarMonth,
                    contentDescription = stringResource(R.string.history_pick_date_cd),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            }
        }
        if (showTodayShortcut) {
            TextButton(onClick = onToday) {
                Text(stringResource(R.string.history_today))
            }
        }
        IconButton(onClick = onNext, enabled = canGoForward) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.history_next_day)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Mapa do dia
// ---------------------------------------------------------------------------

/**
 * Mapa do histórico.
 * - Sem seleção: todos os trajetos do dia na cor do membro, cada um com o
 *   seu NÚMERO (o mesmo da lista). Tocar no número seleciona o trajeto.
 * - Com seleção: só o trajeto escolhido em destaque (linha larga com
 *   contorno, setas de direção, A = saída, B = chegada); os demais ficam
 *   cinza e finos. A câmera enquadra o trajeto (LatLngBounds + margem).
 * Trechos sem sinal de GPS (gaps) aparecem tracejados.
 */
@Composable
internal fun HistoryDayMap(
    tripSegments: List<List<List<LatLng>>>,
    selectedTrip: Int?,
    selectedMatched: List<List<LatLng>>?,
    stayPosition: LatLng?,
    stayLabel: String?,
    startLabel: String?,
    endLabel: String?,
    memberColor: Color,
    onSelectTrip: (Int) -> Unit,
    onShowAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current.density
    val paddingPx = with(LocalDensity.current) { 48.dp.roundToPx() }
    val outlineColor = remember(memberColor) { lerp(memberColor, Color.Black, 0.35f) }

    val selectedRaw: List<List<LatLng>> =
        selectedTrip?.let { tripSegments.getOrNull(it) } ?: emptyList()
    // Geometria desenhada do trajeto selecionado: encaixada nas ruas se o
    // OSRM respondeu; senão, o GPS bruto (simplificado).
    val selectedDrawn: List<List<LatLng>> =
        if (selectedTrip != null) selectedMatched ?: selectedRaw else emptyList()

    // Enquadramento: só muda ao trocar de trajeto/dia (não ao chegar o OSRM).
    val focusPoints = remember(tripSegments, selectedTrip, stayPosition) {
        when {
            selectedRaw.isNotEmpty() -> selectedRaw.flatten()
            tripSegments.isNotEmpty() -> tripSegments.flatten().flatten()
            stayPosition != null -> listOf(stayPosition)
            else -> emptyList()
        }
    }
    val camera = rememberCameraPositionState {
        val (lat, lon, zoom) = computeRouteCenterAndZoom(focusPoints)
        position = CameraPosition.fromLatLngZoom(LatLng(lat, lon), zoom)
    }
    var mapLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(mapLoaded, focusPoints) {
        if (mapLoaded) fitCameraTo(camera, focusPoints, paddingPx)
    }

    val badgePositions = remember(tripSegments) {
        tripSegments.map { segs -> pathMidpoint(segs.maxByOrNull { it.size } ?: emptyList()) }
    }
    val arrows = remember(selectedDrawn) {
        selectedDrawn.flatMap { directionArrows(it, maxArrows = 5) }.take(12)
    }
    val gapConnectors = remember(selectedDrawn) {
        selectedDrawn.zipWithNext { a, b ->
            val from = a.lastOrNull()
            val to = b.firstOrNull()
            if (from != null && to != null) listOf(from, to) else null
        }.filterNotNull()
    }
    val mapProperties = remember { MapProperties(mapType = MapType.NORMAL) }
    val uiSettings = remember {
        MapUiSettings(
            zoomControlsEnabled = false,
            mapToolbarEnabled = false,
            myLocationButtonEnabled = false,
            tiltGesturesEnabled = false
        )
    }
    val startTitle = stringResource(R.string.history_marker_start_title)
    val endTitle = stringResource(R.string.history_marker_end_title)
    val badgeDescriptions = tripSegments.indices.map { i ->
        stringResource(R.string.history_trip_number, i + 1)
    }

    Box(modifier = modifier) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = camera,
            properties = mapProperties,
            uiSettings = uiSettings,
            onMapLoaded = { mapLoaded = true }
        ) {
            // Bitmaps criados DENTRO do mapa (Maps SDK já inicializado).
            val numberIcons = remember(tripSegments.size, memberColor, density) {
                (1..tripSegments.size).map { n ->
                    BitmapDescriptorFactory.fromBitmap(
                        labeledCircleBitmap(density, "$n", memberColor.toArgb(), 28f, 2.5f)
                    )
                }
            }
            val startIcon = remember(density) {
                BitmapDescriptorFactory.fromBitmap(
                    labeledCircleBitmap(density, "A", START_MARKER_COLOR.toArgb())
                )
            }
            val endIcon = remember(density) {
                BitmapDescriptorFactory.fromBitmap(
                    labeledCircleBitmap(density, "B", END_MARKER_COLOR.toArgb())
                )
            }
            val arrowIcon = remember(outlineColor, density) {
                BitmapDescriptorFactory.fromBitmap(directionArrowBitmap(density, outlineColor.toArgb()))
            }

            val mainWidth = 9f * density
            val outlineWidth = 13f * density

            tripSegments.forEachIndexed { k, segs ->
                key(k) {
                    val isSelected = k == selectedTrip
                    val dimmed = selectedTrip != null && !isSelected
                    if (!isSelected) {
                        segs.forEach { seg ->
                            if (dimmed) {
                                Polyline(
                                    points = seg,
                                    color = DIMMED_TRIP_COLOR.copy(alpha = 0.55f),
                                    width = 5f * density,
                                    startCap = RoundCap(),
                                    endCap = RoundCap(),
                                    jointType = JointType.ROUND,
                                    zIndex = 0.5f
                                )
                            } else {
                                Polyline(
                                    points = seg,
                                    color = outlineColor,
                                    width = 10f * density,
                                    startCap = RoundCap(),
                                    endCap = RoundCap(),
                                    jointType = JointType.ROUND,
                                    zIndex = 1f
                                )
                                Polyline(
                                    points = seg,
                                    color = memberColor,
                                    width = 6.5f * density,
                                    startCap = RoundCap(),
                                    endCap = RoundCap(),
                                    jointType = JointType.ROUND,
                                    zIndex = 2f
                                )
                            }
                        }
                        val badge = badgePositions.getOrNull(k)
                        val icon = numberIcons.getOrNull(k)
                        if (badge != null && icon != null) {
                            val badgeState = remember(badge) { MarkerState(position = badge) }
                            Marker(
                                state = badgeState,
                                icon = icon,
                                anchor = Offset(0.5f, 0.5f),
                                alpha = if (dimmed) 0.6f else 1f,
                                contentDescription = badgeDescriptions.getOrNull(k),
                                zIndex = 5f,
                                onClick = {
                                    onSelectTrip(k)
                                    true
                                }
                            )
                        }
                    }
                }
            }

            if (selectedTrip != null && selectedDrawn.isNotEmpty()) {
                // Trechos sem sinal: tracejado fino (não inventa o caminho).
                gapConnectors.forEach { pts ->
                    Polyline(
                        points = pts,
                        color = outlineColor.copy(alpha = 0.8f),
                        width = 4f * density,
                        pattern = listOf(Dash(18f * density / 2f), Gap(12f * density / 2f)),
                        zIndex = 2.5f
                    )
                }
                selectedDrawn.forEach { seg ->
                    if (seg.size >= 2) {
                        Polyline(
                            points = seg,
                            color = outlineColor,
                            width = outlineWidth,
                            startCap = RoundCap(),
                            endCap = RoundCap(),
                            jointType = JointType.ROUND,
                            zIndex = 3f
                        )
                        Polyline(
                            points = seg,
                            color = memberColor,
                            width = mainWidth,
                            startCap = RoundCap(),
                            endCap = RoundCap(),
                            jointType = JointType.ROUND,
                            zIndex = 4f
                        )
                    }
                }
                arrows.forEach { arrow ->
                    key(arrow.position.latitude, arrow.position.longitude) {
                        val arrowState = remember(arrow.position) { MarkerState(position = arrow.position) }
                        Marker(
                            state = arrowState,
                            icon = arrowIcon,
                            anchor = Offset(0.5f, 0.5f),
                            flat = true,
                            rotation = arrow.bearing,
                            zIndex = 6f,
                            onClick = { true }
                        )
                    }
                }
                val startPos = selectedDrawn.firstOrNull { it.isNotEmpty() }?.firstOrNull()
                val endPos = selectedDrawn.lastOrNull { it.isNotEmpty() }?.lastOrNull()
                if (startPos != null) {
                    val startState = remember(startPos) { MarkerState(position = startPos) }
                    Marker(
                        state = startState,
                        icon = startIcon,
                        anchor = Offset(0.5f, 0.5f),
                        title = startTitle,
                        snippet = startLabel,
                        contentDescription = startTitle,
                        zIndex = 8f
                    )
                }
                if (endPos != null) {
                    val endState = remember(endPos) { MarkerState(position = endPos) }
                    Marker(
                        state = endState,
                        icon = endIcon,
                        anchor = Offset(0.5f, 0.5f),
                        title = endTitle,
                        snippet = endLabel,
                        contentDescription = endTitle,
                        zIndex = 9f
                    )
                }
            }

            if (tripSegments.isEmpty() && stayPosition != null) {
                val stayState = remember(stayPosition) { MarkerState(position = stayPosition) }
                Marker(state = stayState, title = stayLabel)
            }
        }

        if (selectedTrip != null) {
            Surface(
                onClick = onShowAll,
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .heightIn(min = 48.dp)
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.history_show_all_trips),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

/**
 * Enquadra os pontos com margem (LatLngBounds). Trajeto minúsculo ou ponto
 * único: zoom fixo de rua. Se o mapa ainda não tiver tamanho (ou a margem não
 * couber), cai para centro + zoom estimado.
 */
private suspend fun fitCameraTo(
    camera: CameraPositionState,
    points: List<LatLng>,
    paddingPx: Int
) {
    val bounds = boundsOf(points) ?: return
    val tiny = (bounds.northeast.latitude - bounds.southwest.latitude) < 0.0008 &&
        (bounds.northeast.longitude - bounds.southwest.longitude) < 0.0008
    val update = if (tiny) {
        CameraUpdateFactory.newLatLngZoom(bounds.center, 16.5f)
    } else {
        CameraUpdateFactory.newLatLngBounds(bounds, paddingPx)
    }
    try {
        camera.animate(update, 650)
    } catch (e: CancellationException) {
        // Gesto do usuário interrompeu a animação (ou efeito cancelado).
        throw e
    } catch (e: Exception) {
        val (lat, lon, zoom) = computeRouteCenterAndZoom(points)
        try {
            camera.move(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), zoom))
        } catch (ignored: Exception) {
            // Mapa ainda não pronto; o próximo enquadramento resolve.
        }
    }
}

// ---------------------------------------------------------------------------
// Resumo do dia + linha do tempo
// ---------------------------------------------------------------------------

@Composable
internal fun DaySummaryHeader(
    tripCount: Int,
    distanceMeters: Double,
    movingMillis: Long,
    truncated: Boolean,
    onPlayDay: (() -> Unit)?
) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 10.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                val tripsText = if (tripCount == 1) {
                    stringResource(R.string.history_summary_trips_one)
                } else {
                    stringResource(R.string.history_summary_trips_many, tripCount)
                }
                Text(
                    text = if (tripCount > 0) "$tripsText • ${distanceText(distanceMeters)}" else tripsText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (tripCount > 0) {
                    Text(
                        text = stringResource(R.string.history_summary_moving, tripDurationText(movingMillis)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (onPlayDay != null) {
                TextButton(onClick = onPlayDay) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.history_play_day))
                }
            }
        }
        if (tripCount > 0) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.history_tap_trip_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (truncated) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.history_trip_truncated, MAX_TRIP_POINTS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Trilho vertical da linha do tempo (na cor do membro). */
@Composable
private fun RailSegment(visible: Boolean, color: Color, modifier: Modifier) {
    Box(
        modifier = modifier
            .width(4.dp)
            .background(if (visible) color else Color.Transparent)
    )
}

/** Item "Parada": local, horário de chegada/saída e quanto tempo ficou. */
@Composable
internal fun TimelineStayRow(
    entry: DayTimelineEntry.Stay,
    label: String?,
    memberColor: Color,
    railAbove: Boolean,
    railBelow: Boolean
) {
    val durationMs = entry.toMillis - entry.fromMillis
    val overline = when {
        entry.isDayStart && entry.isDayEnd -> stringResource(R.string.history_stay_whole_day)
        entry.isDayStart -> stringResource(R.string.history_stay_first)
        entry.isDayEnd -> stringResource(R.string.history_stay_last)
        else -> stringResource(R.string.history_stay_label)
    }
    val timeText = when {
        durationMs < 60_000L && entry.isDayStart && !entry.isDayEnd ->
            stringResource(R.string.history_stay_left_at, formatTripTime(entry.toMillis))
        durationMs < 60_000L ->
            stringResource(R.string.history_stay_arrived_at, formatTripTime(entry.fromMillis))
        else -> stringResource(
            R.string.history_trip_subtitle,
            formatTripTime(entry.fromMillis),
            formatTripTime(entry.toMillis),
            tripDurationText(durationMs)
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .semantics(mergeDescendants = true) {}
    ) {
        Column(
            modifier = Modifier
                .width(48.dp)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            RailSegment(railAbove, memberColor, Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(2.dp, memberColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Place,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = memberColor
                )
            }
            RailSegment(railBelow, memberColor, Modifier.weight(1f))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp, top = 12.dp, bottom = 12.dp)
        ) {
            Text(
                text = overline,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = label ?: stringResource(R.string.history_place_loading),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = timeText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun tripRouteTitle(origin: String?, destination: String?): String {
    val o = origin ?: stringResource(R.string.history_place_loading)
    val d = destination ?: stringResource(R.string.history_place_loading)
    return if (origin != null && destination != null && origin.equals(destination, ignoreCase = true)) {
        stringResource(R.string.history_trip_round, o)
    } else {
        stringResource(R.string.history_trip_from_to_short, o, d)
    }
}

@Composable
private fun tripModeIcon(mode: TripMode): ImageVector = when (mode) {
    TripMode.WALK -> Icons.AutoMirrored.Filled.DirectionsWalk
    TripMode.VEHICLE -> Icons.Default.DirectionsCar
    TripMode.UNKNOWN -> Icons.Default.Route
}

@Composable
private fun tripModeText(mode: TripMode): String? = when (mode) {
    TripMode.WALK -> stringResource(R.string.history_mode_walk)
    TripMode.VEHICLE -> stringResource(R.string.history_mode_vehicle)
    TripMode.UNKNOWN -> null
}

/** Item "Trajeto": número (o mesmo do mapa), origem -> destino, horário, distância. */
@Composable
internal fun TimelineMoveRow(
    number: Int,
    trip: TripSummary,
    origin: String?,
    destination: String?,
    memberColor: Color,
    onClick: () -> Unit
) {
    val mode = tripModeOf(trip)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        Box(
            modifier = Modifier
                .width(48.dp)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(memberColor)
            )
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(memberColor)
                    .border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$number",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = memberColor.copy(alpha = 0.08f),
            border = BorderStroke(1.dp, memberColor.copy(alpha = 0.35f)),
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp, top = 4.dp, bottom = 4.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable(
                    onClickLabel = stringResource(R.string.history_open_on_map),
                    role = Role.Button,
                    onClick = onClick
                )
        ) {
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            tripModeIcon(mode),
                            contentDescription = tripModeText(mode),
                            modifier = Modifier.size(18.dp),
                            tint = memberColor
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.history_trip_number, number),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = tripRouteTitle(origin, destination),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(
                            R.string.history_trip_line,
                            formatTripTime(trip.startMillis),
                            formatTripTime(trip.endMillis),
                            tripDurationText(trip.durationMillis),
                            distanceText(trip.distanceMeters)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (trip.possibleSpeeding) {
                        Spacer(modifier = Modifier.height(4.dp))
                        SpeedWarningChip()
                    }
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SpeedWarningChip() {
    Surface(color = SPEED_WARNING_BG, shape = RoundedCornerShape(50)) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = SPEED_WARNING_FG
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.history_trip_speeding),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = SPEED_WARNING_FG
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Painel do trajeto selecionado
// ---------------------------------------------------------------------------

@Composable
private fun EndpointRow(letter: String, letterColor: Color, label: String, caption: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(letterColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = letter,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Detalhe do trajeto escolhido (abaixo do mapa): A -> B com horários,
 * duração/distância/velocidade, aviso de velocidade, navegação entre
 * trajetos do dia e o botão de reprodução.
 */
@Composable
internal fun TripDetailPanel(
    number: Int,
    total: Int,
    trip: TripSummary,
    origin: String?,
    destination: String?,
    memberColor: Color,
    hasGaps: Boolean,
    onPrev: (() -> Unit)?,
    onNext: (() -> Unit)?,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    val mode = tripModeOf(trip)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onPrev?.invoke() }, enabled = onPrev != null) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.history_prev_trip)
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(memberColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.history_trip_of_total, number, total),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    tripModeText(mode)?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = { onNext?.invoke() }, enabled = onNext != null) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.history_next_trip)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                EndpointRow(
                    letter = "A",
                    letterColor = START_MARKER_COLOR,
                    label = origin ?: stringResource(R.string.history_place_loading),
                    caption = stringResource(R.string.history_stay_left_at, formatTripTime(trip.startMillis))
                )
                Box(
                    modifier = Modifier
                        .padding(start = 13.dp)
                        .width(4.dp)
                        .height(16.dp)
                        .background(memberColor)
                )
                EndpointRow(
                    letter = "B",
                    letterColor = END_MARKER_COLOR,
                    label = destination ?: stringResource(R.string.history_place_loading),
                    caption = stringResource(R.string.history_stay_arrived_at, formatTripTime(trip.endMillis))
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    StatCell(
                        value = tripDurationText(trip.durationMillis),
                        label = stringResource(R.string.history_stat_duration),
                        modifier = Modifier.weight(1f)
                    )
                    StatCell(
                        value = distanceText(trip.distanceMeters),
                        label = stringResource(R.string.history_stat_distance),
                        modifier = Modifier.weight(1f)
                    )
                    StatCell(
                        value = stringResource(R.string.history_speed_value, speedText(trip.avgSpeedKmh)),
                        label = stringResource(R.string.history_stat_avg_speed),
                        modifier = Modifier.weight(1f)
                    )
                }
                if (trip.possibleSpeeding) {
                    Spacer(modifier = Modifier.height(10.dp))
                    SpeedWarningChip()
                    Text(
                        text = stringResource(
                            R.string.history_trip_speeding_detail,
                            speedText(trip.maxSpeedKmh)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (hasGaps) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.history_gap_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onPlay,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.history_play_trip))
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Estados: carregando / erro / vazio
// ---------------------------------------------------------------------------

@Composable
internal fun HistoryMessageState(
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    loading: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            if (loading) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
            } else if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            if (body != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(actionLabel)
                }
            }
        }
    }
}
