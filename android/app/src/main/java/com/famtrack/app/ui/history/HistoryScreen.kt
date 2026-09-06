package com.famtrack.app.ui.history

import android.content.Context
import android.location.Location
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.famtrack.app.R
import com.famtrack.app.data.model.FamilyMember
import com.famtrack.app.data.model.Geofence
import com.famtrack.app.data.model.RoutePoint
import com.famtrack.app.data.remote.FamilyRepository
import com.famtrack.app.data.remote.GeofenceRepository
import com.famtrack.app.data.remote.LocationRepository
import com.famtrack.app.data.remote.SupabaseClient
import com.famtrack.app.feature.common.AddressOutcome
import com.famtrack.app.feature.common.AddressResolver
import com.famtrack.app.feature.places.Place
import com.famtrack.app.feature.places.PlacesRepository
import com.famtrack.app.util.isInsideGeofence
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.*
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onNavigateBack: () -> Unit
) {
    val locationRepository = remember { LocationRepository() }
    val familyRepository = remember { FamilyRepository() }
    val geofenceRepository = remember { GeofenceRepository() }

    val zone = remember { ZoneId.systemDefault() }
    val context = LocalContext.current
    val today = remember { LocalDate.now(zone) }
    val minDate = remember { today.minusDays(30) }

    var routePoints by remember { mutableStateOf<List<RoutePoint>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableStateOf(0) }
    var familyId by remember { mutableStateOf<String?>(null) }
    var members by remember { mutableStateOf<List<FamilyMember>>(emptyList()) }
    var nameById by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var selectedMemberId by remember { mutableStateOf<String?>(null) }
    var selectedDate by remember { mutableStateOf(today) }
    var showDatePicker by remember { mutableStateOf(false) }
    var dayVisits by remember { mutableStateOf<List<Visit>>(emptyList()) }
    var geofences by remember { mutableStateOf<List<Geofence>>(emptyList()) }
    var places by remember { mutableStateOf<List<Place>>(emptyList()) }
    var selectedTripIndex by remember { mutableStateOf(-1) }
    var tripLabels by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var speedAlertKmh by remember { mutableStateOf(readSpeedAlertPref(context)) }

    LaunchedEffect(Unit) {
        try {
            val client = SupabaseClient.getInstance()
            val user = client.auth.currentUserOrNull()
            if (user != null) {
                val member = familyRepository.getUserFamily(user.id)
                if (member != null) {
                    familyId = member.family_id
                    selectedMemberId = user.id
                    members = familyRepository.getFamilyMembers(member.family_id)
                    nameById = familyRepository.getFamilyMemberDisplay(member.family_id)
                        .associate { it.user_id to it.display_name }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Carrega os locais conhecidos para calcular o resumo do dia
    LaunchedEffect(familyId) {
        val fid = familyId ?: return@LaunchedEffect
        try {
            geofences = geofenceRepository.getFamilyGeofences(fid)
            places = PlacesRepository().getFamilyPlaces(fid)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Limites do dia no fuso do aparelho (00:00 -> 24:00), convertidos para UTC.
    val dayBounds = remember(selectedDate, zone) {
        val startUtc = selectedDate.atStartOfDay(zone).toInstant().toString()
        val endUtc = selectedDate.plusDays(1).atStartOfDay(zone).toInstant().toString()
        startUtc to endUtc
    }

    // Consulta o único dia selecionado no servidor (paginação em LocationRepository).
    LaunchedEffect(selectedMemberId, familyId, selectedDate, reloadToken, geofences) {
        val fid = familyId ?: return@LaunchedEffect
        val mid = selectedMemberId ?: return@LaunchedEffect
        isLoading = true
        loadError = false
        try {
            val dayPoints = locationRepository.getRouteHistoryForDay(
                fid, mid, dayBounds.first, dayBounds.second
            )
            routePoints = dayPoints
            dayVisits = computeDayVisits(dayPoints, geofences)
        } catch (e: Exception) {
            loadError = true
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.history_back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (members.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(members) { m ->
                        FilterChip(
                            selected = selectedMemberId == m.user_id,
                            onClick = { selectedMemberId = m.user_id },
                            label = { Text(nameById[m.user_id] ?: "Membro", fontSize = 12.sp) }
                        )
                    }
                }
            }

            // Navegação por data: ‹ [data] ›
            val headerLabel = if (selectedDate == today) {
                stringResource(R.string.history_date_today_format, formatDayHeader(selectedDate, zone))
            } else {
                formatDayHeader(selectedDate, zone)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        selectedDate = selectedDate.minusDays(1)
                            .let { if (it.isBefore(minDate)) minDate else it }
                    },
                    enabled = selectedDate.isAfter(minDate),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("‹", style = MaterialTheme.typography.titleLarge)
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(2f)
                ) {
                    TextButton(
                        onClick = { showDatePicker = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(headerLabel, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    }
                    if (selectedDate != today) {
                        TextButton(
                            onClick = { selectedDate = today },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text(
                                stringResource(R.string.history_today),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
                TextButton(
                    onClick = {
                        selectedDate = if (selectedDate.plusDays(1).isAfter(today)) today else selectedDate.plusDays(1)
                    },
                    enabled = selectedDate.isBefore(today),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("›", style = MaterialTheme.typography.titleLarge)
                }
            }
            if (selectedDate == minDate && selectedDate.isBefore(today)) {
                Text(
                    text = stringResource(R.string.history_limit_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    textAlign = TextAlign.Center
                )
            }

            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                loadError -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.history_error),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            TextButton(onClick = { reloadToken++ }) {
                                Text(stringResource(R.string.history_retry))
                            }
                        }
                    }
                }
                routePoints.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Route,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(
                                    R.string.history_empty_day,
                                    formatLongDayHeader(selectedDate, zone)
                                ),
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                else -> {
                    val cameraPositionState = rememberCameraPositionState()
                    val orderedPoints = routePoints.sortedBy {
                        parseTimestampMillis(it.recorded_at) ?: Long.MAX_VALUE
                    }
                    // Trechos de deslocamento do dia (calculado no aparelho, sem SQL).
                    val tripsResult = remember(routePoints, speedAlertKmh) {
                        computeTrips(
                            points = routePoints.sortedBy {
                                parseTimestampMillis(it.recorded_at) ?: Long.MAX_VALUE
                            },
                            speedLimitKmh = if (speedAlertKmh > 0) speedAlertKmh.toDouble() else null
                        )
                    }
                    val trips = tripsResult.trips
                    // Decimação somente para renderizar a Polyline em dias com dados legados densos.
                    val displayPoints = if (orderedPoints.size > MAX_DISPLAY_POINTS) {
                        decimate(orderedPoints, MAX_DISPLAY_POINTS)
                    } else orderedPoints

                    val totalDistance = computeTotalDistance(orderedPoints)

                    val displayLatLngs = remember(displayPoints) {
                        displayPoints.map { LatLng(it.latitude, it.longitude) }
                    }
                    val playback = rememberRoutePlayback(
                        points = displayPoints,
                        displayLatLngs = displayLatLngs,
                        memberName = selectedMemberId?.let { nameById[it] },
                        avatarColor = MaterialTheme.colorScheme.primary
                    )
                    var showPlayback by remember { mutableStateOf(false) }

                    // Enquadra a rota do dia (LatLngBounds); ponto único vira zoom fixo.
                    LaunchedEffect(orderedPoints) {
                        if (orderedPoints.isNotEmpty()) {
                            val latLngs = orderedPoints.map { LatLng(it.latitude, it.longitude) }
                            val (clat, clon, zoom) = computeRouteCenterAndZoom(latLngs)
                            cameraPositionState.animate(
                                CameraUpdateFactory.newLatLngZoom(LatLng(clat, clon), zoom),
                                1000
                            )
                        }
                    }

                    // Nomeia origem/destino de cada percurso (place salvo → endereço → "Local aproximado").
                    LaunchedEffect(trips, orderedPoints, places) {
                        if (trips.isEmpty() || orderedPoints.isEmpty()) {
                            tripLabels = emptyList()
                            return@LaunchedEffect
                        }
                        tripLabels = withContext(Dispatchers.IO) {
                            trips.map { trip ->
                                val origin = orderedPoints[trip.pointStart]
                                val dest = orderedPoints[trip.pointEnd]
                                tripLocationLabel(context, places, origin.latitude, origin.longitude) to
                                    tripLocationLabel(context, places, dest.latitude, dest.longitude)
                            }
                        }
                    }

                    // Destaca no mapa o trecho do percurso selecionado (por janela de tempo).
                    val selectedTrip = trips.getOrNull(selectedTripIndex)
                    val selectedTripLatLngs = remember(selectedTripIndex, displayPoints) {
                        val t = selectedTrip ?: return@remember emptyList<LatLng>()
                        displayPoints.mapNotNull { p ->
                            val millis = parseTimestampMillis(p.recorded_at)
                            if (millis != null && millis in t.startMillis..t.endMillis) {
                                LatLng(p.latitude, p.longitude)
                            } else null
                        }
                    }
                    LaunchedEffect(selectedTripIndex, trips) {
                        val t = selectedTrip ?: return@LaunchedEffect
                        if (selectedTripLatLngs.size >= 2) {
                            val (clat, clon, zoom) = computeRouteCenterAndZoom(selectedTripLatLngs)
                            cameraPositionState.animate(
                                CameraUpdateFactory.newLatLngZoom(LatLng(clat, clon), zoom),
                                900
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        DayTripsSection(
                            trips = trips,
                            labels = tripLabels,
                            selectedIndex = selectedTripIndex,
                            speedAlertKmh = speedAlertKmh,
                            onSpeedLimitChange = { value ->
                                speedAlertKmh = value
                                writeSpeedAlertPref(context, value)
                            },
                            truncated = tripsResult.truncated,
                            onRefresh = { reloadToken++ },
                            onSelect = { index ->
                                selectedTripIndex = if (selectedTripIndex == index) -1 else index
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(0.24f)
                                .padding(horizontal = 16.dp)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(0.46f)
                        ) {
                            GoogleMap(
                                modifier = Modifier.fillMaxSize(),
                                cameraPositionState = cameraPositionState,
                                properties = MapProperties(mapType = MapType.NORMAL)
                            ) {
                                if (displayLatLngs.size >= 2) {
                                    Polyline(
                                        points = displayLatLngs,
                                        color = MaterialTheme.colorScheme.primary.copy(
                                            alpha = if (showPlayback) 0.35f else 1f
                                        ),
                                        width = 6f
                                    )
                                }
                                val hl = selectedTrip
                                if (hl != null && selectedTripLatLngs.size >= 2) {
                                    Polyline(
                                        points = selectedTripLatLngs,
                                        color = if (hl.possibleSpeeding) {
                                            Color(0xFFFF6D00)
                                        } else {
                                            MaterialTheme.colorScheme.tertiary
                                        },
                                        width = 8f
                                    )
                                }
                                orderedPoints.firstOrNull()?.let { first ->
                                    Marker(
                                        state = MarkerState(position = LatLng(first.latitude, first.longitude)),
                                        title = stringResource(R.string.history_start_marker),
                                        icon = BitmapDescriptorFactory.defaultMarker(
                                            BitmapDescriptorFactory.HUE_GREEN
                                        )
                                    )
                                }
                                if (orderedPoints.size > 1) {
                                    orderedPoints.lastOrNull()?.let { last ->
                                        Marker(
                                            state = MarkerState(position = LatLng(last.latitude, last.longitude)),
                                            title = stringResource(R.string.history_end_marker),
                                            icon = BitmapDescriptorFactory.defaultMarker(
                                                BitmapDescriptorFactory.HUE_RED
                                            )
                                        )
                                    }
                                }
                                geofences.forEach { gf ->
                                    val center = LatLng(gf.center_lat, gf.center_lon)
                                    val strokeColor = try {
                                        Color(android.graphics.Color.parseColor(gf.color))
                                    } catch (e: Exception) {
                                        Color.Red
                                    }
                                    Circle(
                                        center = center,
                                        radius = gf.radius_meters,
                                        strokeColor = strokeColor,
                                        fillColor = strokeColor.copy(alpha = 0.25f),
                                        strokeWidth = 3f
                                    )
                                    Marker(
                                        state = MarkerState(position = center),
                                        title = gf.name
                                    )
                                }
                                if (showPlayback && playback.hasRoute) {
                                    PlaybackMapLayers(
                                        controller = playback,
                                        primaryColor = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            if (showPlayback && playback.hasRoute) {
                                RoutePlaybackEngine(playback)
                                RoutePlaybackCameraFollow(playback, cameraPositionState)
                            }
                            if (showPlayback) {
                                RoutePlaybackControls(
                                    controller = playback,
                                    modifier = Modifier.align(Alignment.BottomCenter)
                                )
                            } else {
                                Button(
                                    onClick = {
                                        showPlayback = true
                                        playback.togglePlayPause()
                                    },
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(12.dp)
                                ) {
                                    Text(stringResource(R.string.playback_button))
                                }
                            }
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(0.30f)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp)
                        ) {
                            DayPeriodSummary(orderedPoints, totalDistance)
                            DaySummarySection(dayVisits)
                        }
                    }
                }
            }

            if (showDatePicker) {
                val selectableDates = remember(zone, minDate, today) {
                    object : SelectableDates {
                        override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                            val d = Instant.ofEpochMilli(utcTimeMillis).atZone(zone).toLocalDate()
                            return !d.isBefore(minDate) && !d.isAfter(today)
                        }
                    }
                }
                val pickerState = rememberDatePickerState(
                    initialSelectedDateMillis = selectedDate.atStartOfDay(zone).toInstant().toEpochMilli(),
                    selectableDates = selectableDates
                )
                DatePickerDialog(
                    onDismissRequest = { showDatePicker = false },
                    confirmButton = {
                        TextButton(onClick = {
                            pickerState.selectedDateMillis?.let { millis ->
                                val picked = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
                                if (!picked.isBefore(minDate) && !picked.isAfter(today)) {
                                    selectedDate = picked
                                }
                            }
                            showDatePicker = false
                        }) {
                            Text(stringResource(R.string.history_picker_ok))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDatePicker = false }) {
                            Text(stringResource(R.string.history_cancel))
                        }
                    }
                ) {
                    DatePicker(
                        state = pickerState,
                        showModeToggle = false
                    )
                }
            }
        }
    }
}

/**
 * Um período em que a pessoa esteve dentro de um local conhecido.
 */
data class Visit(
    val localName: String,
    val inicio: Long,
    val fim: Long
)

/**
 * Detecta "visitas": blocos contínuos (>= 5 min) em que os pontos do dia
 * estão dentro de uma geofence. Calculado no aparelho, sem SQL novo.
 */
private fun computeDayVisits(
    points: List<RoutePoint>,
    geofences: List<Geofence>
): List<Visit> {
    val ordered = points
        .mapNotNull { p ->
            val millis = parseTimestampMillis(p.recorded_at)
            if (millis != null) p to millis else null
        }
        .sortedBy { it.second }
    if (ordered.isEmpty() || geofences.isEmpty()) return emptyList()

    val minVisitMillis = 5 * 60 * 1000L
    val visits = mutableListOf<Visit>()

    for (gf in geofences) {
        var startTime: Long? = null
        var lastInsideTime: Long? = null
        for ((point, time) in ordered) {
            val inside = isInsideGeofence(
                point.latitude,
                point.longitude,
                gf.center_lat,
                gf.center_lon,
                gf.radius_meters
            )
            if (inside) {
                if (startTime == null) startTime = time
                lastInsideTime = time
            } else {
                if (startTime != null && lastInsideTime != null) {
                    if (lastInsideTime - startTime >= minVisitMillis) {
                        visits += Visit(gf.name, startTime, lastInsideTime)
                    }
                    startTime = null
                    lastInsideTime = null
                }
            }
        }
        if (startTime != null && lastInsideTime != null &&
            lastInsideTime - startTime >= minVisitMillis
        ) {
            visits += Visit(gf.name, startTime, lastInsideTime)
        }
    }

    return visits.sortedBy { it.inicio }
}

/**
 * Converte um timestamp do banco (ISO) em millis. Tolerante ao sufixo de fuso.
 */
private fun parseTimestampMillis(timestamp: String?): Long? {
    if (timestamp == null) return null
    return try {
        val cleaned = timestamp.trim().substringBefore('.')
        val inputFormat = if (cleaned.endsWith("Z")) {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        } else {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
        }
        inputFormat.parse(cleaned)?.time
    } catch (e: Exception) {
        null
    }
}

/**
 * Rótulo curto do dia (ex.: "12 mai"), no fuso do aparelho.
 */
private fun formatDayHeader(date: LocalDate, zone: ZoneId): String {
    val fmt = SimpleDateFormat("d MMM", Locale("pt", "BR")).apply {
        timeZone = TimeZone.getTimeZone(zone)
    }
    return fmt.format(Date(date.atStartOfDay(zone).toInstant().toEpochMilli()))
}

/**
 * Rótulo longo do dia (ex.: "12 de maio"), usado no estado vazio.
 */
private fun formatLongDayHeader(date: LocalDate, zone: ZoneId): String {
    val fmt = SimpleDateFormat("d 'de' MMMM", Locale("pt", "BR")).apply {
        timeZone = TimeZone.getTimeZone(zone)
    }
    return fmt.format(Date(date.atStartOfDay(zone).toInstant().toEpochMilli()))
}

/**
 * Centro e zoom para enquadrar a rota via LatLngBounds (com margem).
 * Ponto único: zoom fixo 17f. Rota vazia: fallback (15f).
 */
private fun computeRouteCenterAndZoom(points: List<LatLng>): Triple<Double, Double, Float> {
    if (points.isEmpty()) return Triple(0.0, 0.0, 15f)
    if (points.size == 1) return Triple(points[0].latitude, points[0].longitude, 17f)
    val bounds = LatLngBounds.Builder().apply { points.forEach { include(it) } }.build()
    val center = bounds.center
    val span = maxOf(
        bounds.northeast.latitude - bounds.southwest.latitude,
        bounds.northeast.longitude - bounds.southwest.longitude
    ) * 1.4
    val zoom = when {
        span <= 0.0015 -> 17f
        span <= 0.004 -> 16f
        span <= 0.01 -> 15f
        span <= 0.025 -> 14f
        span <= 0.06 -> 13f
        span <= 0.15 -> 12f
        span <= 0.35 -> 11f
        else -> 10f
    }
    return Triple(center.latitude, center.longitude, zoom)
}

/**
 * Distância total percorrida (soma dos deslocamentos consecutivos), em metros.
 */
private fun computeTotalDistance(points: List<RoutePoint>): Double? {
    if (points.size < 2) return null
    val result = FloatArray(1)
    var total = 0.0
    for (i in 1 until points.size) {
        Location.distanceBetween(
            points[i - 1].latitude, points[i - 1].longitude,
            points[i].latitude, points[i].longitude, result
        )
        if (result[0] >= 0f) total += result[0]
    }
    return total
}

/**
 * Decimação simples (1 a cada k) para renderizar a Polyline — não altera os dados.
 */
private const val MAX_DISPLAY_POINTS = 5000

private fun decimate(points: List<RoutePoint>, target: Int): List<RoutePoint> {
    if (points.size <= target) return points
    val step = points.size.toDouble() / target.toDouble()
    val result = mutableListOf<RoutePoint>()
    var idx = 0.0
    while (idx < points.size && result.size < target) {
        result += points[idx.toInt()]
        idx += step
    }
    if (result.lastOrNull() !== points.last()) result += points.last()
    return result
}

/**
 * Faixa com distância total e período do dia selecionado.
 */
@Composable
private fun DayPeriodSummary(points: List<RoutePoint>, totalDistance: Double?) {
    val firstMillis = parseTimestampMillis(points.firstOrNull()?.recorded_at)
    val lastMillis = parseTimestampMillis(points.lastOrNull()?.recorded_at)
    if (totalDistance == null && (firstMillis == null || lastMillis == null)) return

    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 12.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = stringResource(R.string.history_summary_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = distanceText(totalDistance),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            if (firstMillis != null && lastMillis != null) {
                Text(
                    text = "${timeFormat.format(Date(firstMillis))}" +
                        stringResource(R.string.history_period_sep) +
                        "${timeFormat.format(Date(lastMillis))}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun distanceText(totalDistance: Double?): String {
    if (totalDistance == null) return "—"
    return if (totalDistance >= 1000) {
        stringResource(
            R.string.history_distance_km,
            String.format(Locale.getDefault(), "%.1f", totalDistance / 1000.0)
        )
    } else {
        stringResource(
            R.string.history_distance_m,
            String.format(Locale.getDefault(), "%.0f", totalDistance)
        )
    }
}

/**
 * Cards simples com o resumo do dia (locais por onde a pessoa esteve).
 */
@Composable
fun DaySummarySection(visits: List<Visit>) {
    if (visits.isEmpty()) return
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
    ) {
        Text(
            text = stringResource(R.string.history_summary_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        visits.forEach { visit ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Place,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = visit.localName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                        )
                        Text(
                            text = "${timeFormat.format(Date(visit.inicio))} - " +
                                "${timeFormat.format(Date(visit.fim))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Trechos de deslocamento (Partes B e C)
// ---------------------------------------------------------------------------

private const val STOP_RADIUS_METERS = 60.0
private const val STOP_MIN_DURATION_MILLIS = 5 * 60 * 1000L
private const val MIN_TRIP_DISTANCE_METERS = 100.0
private const val MIN_TRIP_DURATION_MILLIS = 2 * 60 * 1000L
private const val MAX_TRIP_POINTS = 5000

// Limiar de alerta "possível excesso": configurável (40–100 km/h ou desativado),
// persistido em SharedPreferences própria (não mexe em privacy_prefs).
private const val DEFAULT_SPEED_ALERT_KMH = 60
private const val SPEED_PREFS_NAME = "speed_alert_prefs"
private const val SPEED_PREFS_KEY = "trip_speed_alert_kmh"

private fun readSpeedAlertPref(context: Context): Int =
    context.getSharedPreferences(SPEED_PREFS_NAME, Context.MODE_PRIVATE)
        .getInt(SPEED_PREFS_KEY, DEFAULT_SPEED_ALERT_KMH)

private fun writeSpeedAlertPref(context: Context, value: Int) {
    context.getSharedPreferences(SPEED_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putInt(SPEED_PREFS_KEY, value)
        .apply()
}

/**
 * Resumo de um deslocamento entre duas paradas. [pointStart]/[pointEnd] são
 * índices na lista ordenada de pontos usada no cálculo.
 */
data class TripSummary(
    val pointStart: Int,
    val pointEnd: Int,
    val startMillis: Long,
    val endMillis: Long,
    val durationMillis: Long,
    val distanceMeters: Double,
    val avgSpeedKmh: Double?,
    val maxSpeedKmh: Double?,
    val possibleSpeeding: Boolean
)

private data class TimedPoint(val point: RoutePoint, val timeMillis: Long)

private data class TripsResult(
    val trips: List<TripSummary>,
    val truncated: Boolean
)

/**
 * Divide o dia em trechos de deslocamento:
 * - "parada" = pessoa fica dentro de ~60m por >= 5 min (calculado no aparelho);
 * - trecho muito curto (< 100 m ou < 2 min) é descartado;
 * - [speedLimitKmh] nulo desativa o alerta de velocidade.
 * Para dias com muitos pontos legados, limita a 5000 pontos e sinaliza
 * [TripsResult.truncated] para a UI avisar discretamente.
 */
private fun computeTrips(points: List<RoutePoint>, speedLimitKmh: Double?): TripsResult {
    val truncated = points.size > MAX_TRIP_POINTS
    val capped = if (truncated) points.take(MAX_TRIP_POINTS) else points
    if (capped.size < 2) return TripsResult(emptyList(), truncated)
    val timed = capped.mapNotNull { p ->
        parseTimestampMillis(p.recorded_at)?.let { TimedPoint(p, it) }
    }
    if (timed.size < 2) return TripsResult(emptyList(), truncated)

    val n = timed.size
    val inStop = BooleanArray(n)

    var i = 0
    while (i < n) {
        if (inStop[i]) { i++; continue }
        val base = timed[i]
        var k = i
        while (k + 1 < n) {
            val next = timed[k + 1]
            val d = FloatArray(1)
            Location.distanceBetween(
                base.point.latitude, base.point.longitude,
                next.point.latitude, next.point.longitude, d
            )
            if (d[0] > STOP_RADIUS_METERS) break
            k++
        }
        if (k - i >= 2 && timed[k].timeMillis - base.timeMillis >= STOP_MIN_DURATION_MILLIS) {
            for (j in i..k) inStop[j] = true
            i = k + 1
        } else {
            i++
        }
    }

    val trips = mutableListOf<TripSummary>()
    var start = -1
    for (idx in 0 until n) {
        if (inStop[idx]) {
            if (start != -1) {
                flushTrip(timed, start, idx - 1, speedLimitKmh)?.let { trips += it }
                start = -1
            }
        } else {
            if (start == -1) start = idx
        }
    }
    if (start != -1) flushTrip(timed, start, n - 1, speedLimitKmh)?.let { trips += it }
    return TripsResult(trips.sortedBy { it.startMillis }, truncated)
}

private fun flushTrip(
    timed: List<TimedPoint>,
    start: Int,
    end: Int,
    speedLimitKmh: Double?
): TripSummary? {
    if (end - start < 1) return null
    var distance = 0.0
    var maxSpeed: Double? = null
    var runSegments = 0
    var runDurationMs = 0L
    var speeding = false

    fun checkRun() {
        if (runSegments > 0 && (runDurationMs >= 10_000 || runSegments >= 2)) {
            speeding = true
        }
        runSegments = 0
        runDurationMs = 0
    }

    for (j in (start + 1)..end) {
        val a = timed[j - 1]
        val b = timed[j]
        val d = FloatArray(1)
        Location.distanceBetween(
            a.point.latitude, a.point.longitude,
            b.point.latitude, b.point.longitude, d
        )
        val dist = d[0].toDouble().coerceAtLeast(0.0)
        distance += dist
        val dtMs = (b.timeMillis - a.timeMillis).coerceAtLeast(0L)
        val kmh = b.point.speed?.let { it * 3.6 }
            ?: if (dtMs > 0) (dist / (dtMs / 1000.0)) * 3.6 else null
        if (kmh != null) {
            if (maxSpeed == null || kmh > maxSpeed) maxSpeed = kmh
            // Alerta avaliado só quando um limite foi configurado (null = desativado).
            if (speedLimitKmh != null) {
                if (kmh > speedLimitKmh) {
                    runSegments++
                    runDurationMs += dtMs
                } else {
                    checkRun()
                }
            }
        }
        // velocidade desconhecida (sem campo e dt=0): não quebra nem acumula
    }
    checkRun()

    val duration = timed[end].timeMillis - timed[start].timeMillis
    if (distance < MIN_TRIP_DISTANCE_METERS || duration < MIN_TRIP_DURATION_MILLIS) return null
    val avg = if (duration > 0) (distance / (duration / 1000.0)) * 3.6 else null
    return TripSummary(
        pointStart = start,
        pointEnd = end,
        startMillis = timed[start].timeMillis,
        endMillis = timed[end].timeMillis,
        durationMillis = duration,
        distanceMeters = distance,
        avgSpeedKmh = avg,
        maxSpeedKmh = maxSpeed,
        possibleSpeeding = speeding
    )
}

/**
 * Rótulo de origem/destino de um trecho: nome do Place salvo (se dentro do
 * raio), senão endereço via Geocoder, senão "Local aproximado".
 */
private suspend fun tripLocationLabel(
    context: Context,
    places: List<Place>,
    latitude: Double,
    longitude: Double
): String {
    val inside = places.firstOrNull {
        isInsideGeofence(
            latitude,
            longitude,
            it.center_lat,
            it.center_lon,
            it.radius_meters.toDouble()
        )
    }
    if (inside != null) return inside.name
    return when (val outcome = AddressResolver.resolve(context, latitude, longitude)) {
        is AddressOutcome.Found -> outcome.text
        else -> context.getString(R.string.history_local_approx)
    }
}

/**
 * Seção "Percursos do dia": fica no TOPO do histórico, com lista compacta de
 * trechos clicáveis, seletor do limite de velocidade do alerta, botão de
 * atualização e aviso discreto quando o dia foi truncado em 5000 pontos.
 */
@Composable
private fun DayTripsSection(
    trips: List<TripSummary>,
    labels: List<Pair<String, String>>,
    selectedIndex: Int,
    speedAlertKmh: Int,
    onSpeedLimitChange: (Int) -> Unit,
    truncated: Boolean,
    onRefresh: () -> Unit,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val speedOptions = listOf(40, 50, 60, 80, 100, 0)
    val speedLabelText: @Composable (Int) -> String = { opt ->
        if (opt == 0) {
            stringResource(R.string.history_speed_off)
        } else {
            stringResource(R.string.history_speed_label, opt)
        }
    }
    var showSpeedMenu by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.history_trips_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRefresh) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.history_trips_refresh),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.history_speed_limit_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box {
                TextButton(onClick = { showSpeedMenu = true }) {
                    Text(
                        text = speedLabelText(speedAlertKmh),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = null
                    )
                }
                DropdownMenu(
                    expanded = showSpeedMenu,
                    onDismissRequest = { showSpeedMenu = false }
                ) {
                    speedOptions.forEach { opt ->
                        DropdownMenuItem(
                            text = { Text(speedLabelText(opt)) },
                            onClick = {
                                showSpeedMenu = false
                                onSpeedLimitChange(opt)
                            }
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        if (truncated) {
            Text(
                text = stringResource(R.string.history_trip_truncated, MAX_TRIP_POINTS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        if (trips.isEmpty()) {
            Text(
                text = stringResource(R.string.history_trip_no_trips),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.history_trips_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
        } else {
            trips.forEachIndexed { index, trip ->
                val origin = labels.getOrNull(index)?.first
                    ?: stringResource(R.string.history_local_approx)
                val dest = labels.getOrNull(index)?.second
                    ?: stringResource(R.string.history_local_approx)
                val selected = selectedIndex == index
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clickable { onSelect(index) },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.history_trip_from_to, origin, dest),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(
                                R.string.history_trip_meta,
                                tripDurationText(trip.durationMillis),
                                distanceText(trip.distanceMeters),
                                speedText(trip.avgSpeedKmh),
                                speedText(trip.maxSpeedKmh)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (trip.possibleSpeeding) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.history_trip_speeding),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.history_trip_speed_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun tripDurationText(durationMillis: Long): String {
    val totalMin = (durationMillis / 60_000L).toInt().coerceAtLeast(0)
    val hours = totalMin / 60
    val minutes = totalMin % 60
    return if (hours > 0) {
        stringResource(R.string.history_duration_h, "$hours", "$minutes")
    } else {
        stringResource(R.string.history_duration_min, "$minutes")
    }
}

private fun speedText(v: Double?): String =
    if (v == null) "—" else String.format(Locale.getDefault(), "%.0f", v)