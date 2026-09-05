package com.famtrack.app.ui.history

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.famtrack.app.data.model.FamilyMember
import com.famtrack.app.data.model.Geofence
import com.famtrack.app.data.model.RoutePoint
import com.famtrack.app.data.remote.FamilyRepository
import com.famtrack.app.data.remote.GeofenceRepository
import com.famtrack.app.data.remote.LocationRepository
import com.famtrack.app.data.remote.SupabaseClient
import com.famtrack.app.util.isInsideGeofence
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import io.github.jan.supabase.auth.auth
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onNavigateBack: () -> Unit
) {
    val locationRepository = remember { LocationRepository() }
    val familyRepository = remember { FamilyRepository() }
    val geofenceRepository = remember { GeofenceRepository() }

    var routePoints by remember { mutableStateOf<List<RoutePoint>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var familyId by remember { mutableStateOf<String?>(null) }
    var members by remember { mutableStateOf<List<FamilyMember>>(emptyList()) }
    var nameById by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var selectedMemberId by remember { mutableStateOf<String?>(null) }
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

    LaunchedEffect(selectedMemberId, familyId) {
        val fid = familyId ?: return@LaunchedEffect
        val mid = selectedMemberId ?: return@LaunchedEffect
        isLoading = true
        try {
            routePoints = locationRepository.getRouteHistory(fid, mid)
            // Resumo do dia de HOJE: blocos contínuos dentro de cada local conhecido
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val dayPoints = locationRepository.getRouteHistoryForDay(fid, mid, today)
            dayVisits = computeDayVisits(dayPoints, geofences)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isLoading = false
        }
    }

    // Carrega os locais conhecidos para calcular o resumo do dia
    LaunchedEffect(familyId) {
        val fid = familyId ?: return@LaunchedEffect
        try {
            geofences = geofenceRepository.getFamilyGeofences(fid)
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val mid = selectedMemberId ?: return@LaunchedEffect
            val dayPoints = locationRepository.getRouteHistoryForDay(fid, mid, today)
            dayVisits = computeDayVisits(dayPoints, geofences)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Historico de Rotas") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Voltar",
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
            when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
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
                            text = "Nenhum historico encontrado",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Os registros de localizacao aparecerao aqui",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            else -> {
                val cameraPositionState = rememberCameraPositionState()
                val orderedPoints = routePoints.sortedBy {
                    parseTimestampMillis(it.recorded_at) ?: Long.MAX_VALUE
                }
                // Move a câmera para caber a rota
                LaunchedEffect(routePoints) {
                    if (orderedPoints.isNotEmpty()) {
                        val (clat, clon, zoom) = computeRouteCenterAndZoom(
                            orderedPoints.map { LatLng(it.latitude, it.longitude) }
                        )
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
                            .fillMaxHeight(0.55f),
                        cameraPositionState = cameraPositionState,
                        properties = MapProperties(mapType = MapType.NORMAL)
                    ) {
                        if (orderedPoints.size >= 2) {
                            Polyline(
                                points = orderedPoints.map { LatLng(it.latitude, it.longitude) },
                                color = Color(0xFF1E88E5),
                                width = 6f
                            )
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
                        DaySummarySection(dayVisits)
                    }
                }
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
 * Centro e zoom aproximados para caber a rota (com um pouco de margem).
 */
private fun computeRouteCenterAndZoom(points: List<LatLng>): Triple<Double, Double, Float> {
    if (points.isEmpty()) return Triple(0.0, 0.0, 15f)
    var minLat = points[0].latitude
    var maxLat = points[0].latitude
    var minLon = points[0].longitude
    var maxLon = points[0].longitude
    points.forEach { p ->
        minLat = minOf(minLat, p.latitude)
        maxLat = maxOf(maxLat, p.latitude)
        minLon = minOf(minLon, p.longitude)
        maxLon = maxOf(maxLon, p.longitude)
    }
    val centerLat = (minLat + maxLat) / 2
    val centerLon = (minLon + maxLon) / 2
    val span = maxOf(maxLat - minLat, maxLon - minLon) * 1.4
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
    return Triple(centerLat, centerLon, zoom)
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
            text = "Resumo do dia",
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
