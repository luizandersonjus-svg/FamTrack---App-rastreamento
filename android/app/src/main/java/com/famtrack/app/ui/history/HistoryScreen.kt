package com.famtrack.app.ui.history

import android.location.Location
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
import androidx.compose.ui.res.stringResource
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
import com.famtrack.app.util.isInsideGeofence
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.*
import io.github.jan.supabase.auth.auth
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
                    // Decimação somente para renderizar a Polyline em dias com dados legados densos.
                    val displayPoints = if (orderedPoints.size > MAX_DISPLAY_POINTS) {
                        decimate(orderedPoints, MAX_DISPLAY_POINTS)
                    } else orderedPoints

                    val totalDistance = computeTotalDistance(orderedPoints)

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
                    Column(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        GoogleMap(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(0.5f),
                            cameraPositionState = cameraPositionState,
                            properties = MapProperties(mapType = MapType.NORMAL)
                        ) {
                            val displayLatLngs = displayPoints.map { LatLng(it.latitude, it.longitude) }
                            if (displayLatLngs.size >= 2) {
                                Polyline(
                                    points = displayLatLngs,
                                    color = MaterialTheme.colorScheme.primary,
                                    width = 6f
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
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
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