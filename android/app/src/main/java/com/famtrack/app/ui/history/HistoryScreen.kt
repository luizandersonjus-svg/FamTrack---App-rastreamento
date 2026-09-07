package com.famtrack.app.ui.history

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.famtrack.app.feature.places.Place
import com.famtrack.app.feature.places.PlacesRepository
import com.famtrack.app.util.isInsideGeofence
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Sessão de reprodução aberta ao tocar num card (ou na rota do dia). */
private data class PlayerSession(
    val points: List<RoutePoint>,
    val title: String,
    val memberName: String?
)

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
    var speedAlertKmh by remember { mutableStateOf(readSpeedAlertPref(context)) }
    var playerSession by remember { mutableStateOf<PlayerSession?>(null) }

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
            if (playerSession == null) {
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
        }
    ) { paddingValues ->
        val session = playerSession
        if (session != null) {
            TripPlayerScreen(
                points = session.points,
                title = session.title,
                memberName = session.memberName,
                onClose = { playerSession = null },
                modifier = Modifier.padding(paddingValues)
            )
        } else {
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
                                    modifier = Modifier.size(56.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = stringResource(R.string.history_no_points_any),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                    else -> {
                        val orderedPoints = remember(routePoints) {
                            routePoints.sortedBy {
                                parseTimestampMillis(it.recorded_at) ?: Long.MAX_VALUE
                            }
                        }
                        val totalDistance = computeTotalDistance(orderedPoints)
                        val tripsResult = remember(orderedPoints, speedAlertKmh) {
                            computeTrips(
                                points = orderedPoints,
                                speedLimitKmh = if (speedAlertKmh > 0) speedAlertKmh.toDouble() else null
                            )
                        }
                        val trips = tripsResult.trips
                        val memberName = selectedMemberId?.let { nameById[it] }

                        // Fatias dos pontos de cada percurso (para miniatura e player).
                        val tripSlices = remember(orderedPoints, trips) {
                            trips.map { orderedPoints.slice(it.pointStart..it.pointEnd) }
                        }

                        var tripLabels by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
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

                        val openPlayer: (List<RoutePoint>, String) -> Unit = { points, title ->
                            if (points.size < 2) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.history_insufficient_trip),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                playerSession = PlayerSession(points, title, memberName)
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp)
                        ) {
                            Spacer(modifier = Modifier.height(4.dp))
                            MemberTimelineHeader(
                                name = memberName,
                                dateLabel = formatLongDayHeader(selectedDate, zone),
                                tripCount = trips.size
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            DayTimelineSummary(orderedPoints, totalDistance, trips.size)
                            Spacer(modifier = Modifier.height(12.dp))

                            if (orderedPoints.size >= 2) {
                                DayRouteMapCard(
                                    orderedPoints = orderedPoints,
                                    geofences = geofences,
                                    onPlay = {
                                        openPlayer(
                                            orderedPoints,
                                            context.getString(R.string.history_player_title_day)
                                        )
                                    }
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                            }

                            TimelineControlsHeader(
                                speedAlertKmh = speedAlertKmh,
                                truncated = tripsResult.truncated,
                                onSpeedLimitChange = { value ->
                                    speedAlertKmh = value
                                    writeSpeedAlertPref(context, value)
                                },
                                onRefresh = { reloadToken++ }
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            if (trips.isEmpty()) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 20.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Route,
                                        contentDescription = null,
                                        modifier = Modifier.size(36.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = stringResource(R.string.history_no_trip_significant),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            } else {
                                trips.forEachIndexed { index, trip ->
                                    TripTimelineCard(
                                        trip = trip,
                                        points = tripSlices[index],
                                        origin = tripLabels.getOrNull(index)?.first
                                            ?: context.getString(R.string.history_local_approx),
                                        destination = tripLabels.getOrNull(index)?.second
                                            ?: context.getString(R.string.history_local_approx),
                                        memberName = memberName,
                                        avatarColor = MaterialTheme.colorScheme.primary,
                                        showThumbnail = index >= trips.size - 3,
                                        onClick = {
                                            val originLabel = tripLabels.getOrNull(index)?.first
                                            val destLabel = tripLabels.getOrNull(index)?.second
                                            openPlayer(
                                                tripSlices[index],
                                                tripPlayerTitle(context, originLabel, destLabel)
                                            )
                                        }
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            DaySummarySection(dayVisits)
                            Spacer(modifier = Modifier.height(16.dp))
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
}

/** Cabeçalho da timeline: avatar do membro + nome + data + nº de percursos. */
@Composable
private fun MemberTimelineHeader(
    name: String?,
    dateLabel: String,
    tripCount: Int
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(50),
            modifier = Modifier.size(44.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = initialsOf(name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name ?: "—",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = dateLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (tripCount > 0) {
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                shape = RoundedCornerShape(50)
            ) {
                Text(
                    text = stringResource(R.string.history_day_trips_count, tripCount),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

private fun initialsOf(name: String?): String =
    (name ?: "")
        .trim()
        .split(Regex("\\s+"))
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .take(2)
        .joinToString("")
        .ifEmpty { "?" }

/** Título do player de um percurso: "De X a Y" ou genérico. */
private fun tripPlayerTitle(context: Context, origin: String?, destination: String?): String {
    val o = origin ?: context.getString(R.string.history_local_approx)
    val d = destination ?: context.getString(R.string.history_local_approx)
    return if (o.isBlank() || d.isBlank() || o.equals(d, ignoreCase = true)) {
        context.getString(R.string.history_trip_generic_title)
    } else {
        context.getString(R.string.history_trip_from_to, o, d)
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
 * Rótulo curto do dia (ex.: "12 mai"), no fuso do aparelho.
 */
private fun formatDayHeader(date: LocalDate, zone: ZoneId): String {
    val fmt = SimpleDateFormat("d MMM", Locale("pt", "BR")).apply {
        timeZone = TimeZone.getTimeZone(zone)
    }
    return fmt.format(Date(date.atStartOfDay(zone).toInstant().toEpochMilli()))
}

/**
 * Rótulo longo do dia (ex.: "12 de maio"), usado no cabeçalho da timeline.
 */
private fun formatLongDayHeader(date: LocalDate, zone: ZoneId): String {
    val fmt = SimpleDateFormat("d 'de' MMMM", Locale("pt", "BR")).apply {
        timeZone = TimeZone.getTimeZone(zone)
    }
    return fmt.format(Date(date.atStartOfDay(zone).toInstant().toEpochMilli()))
}

/**
 * Distância total percorrida (soma dos deslocamentos consecutivos), em metros.
 */
private fun computeTotalDistance(points: List<RoutePoint>): Double? {
    if (points.size < 2) return null
    val result = FloatArray(1)
    var total = 0.0
    for (i in 1 until points.size) {
        android.location.Location.distanceBetween(
            points[i - 1].latitude, points[i - 1].longitude,
            points[i].latitude, points[i].longitude, result
        )
        if (result[0] >= 0f) total += result[0]
    }
    return total
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
                            fontWeight = FontWeight.Medium
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