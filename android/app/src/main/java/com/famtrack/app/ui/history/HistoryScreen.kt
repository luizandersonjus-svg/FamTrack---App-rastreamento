package com.famtrack.app.ui.history

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import com.google.android.gms.maps.model.LatLng
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Sessão de reprodução aberta a partir de um trajeto (ou do dia inteiro). */
private data class PlayerSession(
    val points: List<RoutePoint>,
    val title: String,
    val memberName: String?,
    val color: Color
)

/**
 * HIST-UX — Histórico de rotas, no formato "linha do tempo do dia":
 *  1. escolhe o MEMBRO (avatar com a cor dele) e o DIA;
 *  2. o mapa mostra os trajetos do dia numerados (1, 2, 3...) na cor do
 *     membro, e a lista abaixo conta o dia em ordem: Parada -> Trajeto ->
 *     Parada...;
 *  3. tocar num trajeto (na lista ou no número do mapa) mostra SÓ aquele
 *     caminho, enquadrado, com A (saída), B (chegada) e setas de direção;
 *     o painel embaixo traz horários, distância e "Reproduzir trajeto".
 */
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
    var currentUserId by remember { mutableStateOf<String?>(null) }
    var members by remember { mutableStateOf<List<FamilyMember>>(emptyList()) }
    var nameById by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var selectedMemberId by remember { mutableStateOf<String?>(null) }
    var selectedDate by remember { mutableStateOf(today) }
    var showDatePicker by remember { mutableStateOf(false) }
    var geofences by remember { mutableStateOf<List<Geofence>>(emptyList()) }
    var places by remember { mutableStateOf<List<Place>>(emptyList()) }
    val speedFlow = remember(context) { speedAlertFlow(context) }
    val initialSpeedAlert = remember(context) { readSpeedAlertPref(context) }
    val speedAlertKmh by speedFlow.collectAsState(initial = initialSpeedAlert)
    var playerSession by remember { mutableStateOf<PlayerSession?>(null) }
    var selectedTrip by remember { mutableStateOf<Int?>(null) }
    val listState = rememberLazyListState()

    // Família e membros. Refeito pelo "Tentar novamente" se a 1ª carga falhar.
    LaunchedEffect(reloadToken) {
        if (familyId != null) return@LaunchedEffect
        isLoading = true
        loadError = false
        try {
            val client = SupabaseClient.getInstance()
            val user = client.auth.currentUserOrNull()
            if (user != null) {
                currentUserId = user.id
                val member = familyRepository.getUserFamily(user.id)
                if (member != null) {
                    members = familyRepository.getFamilyMembers(member.family_id)
                    nameById = try {
                        familyRepository.getFamilyMemberDisplay(member.family_id)
                            .associate { it.user_id to it.display_name }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyMap()
                    }
                    if (selectedMemberId == null) selectedMemberId = user.id
                    // Por último: dispara a carga do dia (efeito abaixo).
                    familyId = member.family_id
                } else {
                    isLoading = false
                }
            } else {
                isLoading = false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            isLoading = false
            loadError = true
        }
    }

    // Locais conhecidos (nomes de "Casa", "Escola"... nas paradas).
    LaunchedEffect(familyId) {
        val fid = familyId ?: return@LaunchedEffect
        try {
            geofences = geofenceRepository.getFamilyGeofences(fid)
            places = PlacesRepository().getFamilyPlaces(fid)
        } catch (e: CancellationException) {
            throw e
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

    // Consulta o dia selecionado no servidor (paginação em LocationRepository).
    LaunchedEffect(selectedMemberId, familyId, selectedDate, reloadToken) {
        val fid = familyId ?: return@LaunchedEffect
        val mid = selectedMemberId ?: return@LaunchedEffect
        isLoading = true
        loadError = false
        selectedTrip = null
        try {
            routePoints = locationRepository.getRouteHistoryForDay(
                fid, mid, dayBounds.first, dayBounds.second
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            routePoints = emptyList()
            loadError = true
        } finally {
            isLoading = false
        }
    }

    // ---- Modelo do dia (derivado; sem rede) ----
    val memberFallback = stringResource(R.string.history_member_fallback)
    val memberUis = remember(members, nameById, currentUserId) {
        members
            .sortedByDescending { it.user_id == currentUserId }
            .map { m ->
                HistoryMemberUi(
                    userId = m.user_id,
                    name = nameById[m.user_id] ?: m.nickname ?: memberFallback,
                    color = memberColorFor(m.user_id)
                )
            }
    }
    val memberName = selectedMemberId?.let { id -> memberUis.firstOrNull { it.userId == id }?.name }
    val memberColor = memberColorFor(selectedMemberId)

    val orderedPoints = remember(routePoints) {
        routePoints.sortedBy { parseTimestampMillis(it.recorded_at) ?: Long.MAX_VALUE }
    }
    val tripsResult = remember(orderedPoints, speedAlertKmh) {
        computeTrips(
            points = orderedPoints,
            speedLimitKmh = if (speedAlertKmh > 0) speedAlertKmh.toDouble() else null
        )
    }
    val trips = tripsResult.trips
    val tripSlices = remember(orderedPoints, trips) {
        trips.map { t ->
            val from = t.pointStart.coerceIn(0, orderedPoints.size)
            val to = (t.pointEnd + 1).coerceIn(from, orderedPoints.size)
            orderedPoints.subList(from, to).toList()
        }
    }
    val tripSegments = remember(tripSlices) { tripSlices.map { tripDisplaySegments(it) } }
    val anchors = remember(orderedPoints, trips) { dayAnchorPositions(orderedPoints, trips) }
    val timeline = remember(orderedPoints, trips, anchors) { buildDayTimeline(orderedPoints, trips, anchors) }
    val sel = selectedTrip?.takeIf { it in trips.indices }

    // Nomes dos locais (local salvo > cerca > endereço), resolvidos aos poucos.
    var anchorLabels by remember { mutableStateOf<List<String?>>(emptyList()) }
    LaunchedEffect(anchors, places, geofences) {
        val resolved = MutableList<String?>(anchors.size) { null }
        anchorLabels = resolved.toList()
        anchors.forEachIndexed { i, a ->
            resolved[i] = resolveAnchorLabel(context, a, places, geofences)
            anchorLabels = resolved.toList()
        }
    }

    // Trajeto selecionado encaixado nas ruas (OSRM, com cache); sem resposta,
    // o mapa continua com o GPS bruto.
    var selectedMatched by remember { mutableStateOf<List<List<LatLng>>?>(null) }
    LaunchedEffect(sel, tripSlices) {
        selectedMatched = null
        val idx = sel ?: return@LaunchedEffect
        val slice = tripSlices.getOrNull(idx) ?: return@LaunchedEffect
        if (slice.size < 2) return@LaunchedEffect
        val result = try {
            fetchMatchedRoute(slice)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (result != null && result.segments.any { it.status == RouteMatchStatus.MATCHED }) {
            selectedMatched = result.segments.filter { it.points.size >= 2 }.map { it.points }
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
            playerSession = PlayerSession(points, title, memberName, memberColor)
        }
    }

    // "Voltar" com um trajeto aberto volta para o dia inteiro.
    BackHandler(enabled = playerSession == null && sel != null) { selectedTrip = null }

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
                    },
                    actions = {
                        IconButton(onClick = { reloadToken++ }) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.history_trips_refresh),
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
                modifier = Modifier.padding(paddingValues),
                routeColor = session.color
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                if (memberUis.isNotEmpty()) {
                    MemberSelectorRow(
                        members = memberUis,
                        selectedId = selectedMemberId,
                        onSelect = { id ->
                            if (id != selectedMemberId) {
                                selectedTrip = null
                                selectedMemberId = id
                            }
                        }
                    )
                }
                val headerLabel = when (selectedDate) {
                    today -> stringResource(
                        R.string.history_date_today_format,
                        formatDayHeader(selectedDate, zone, withWeekday = false)
                    )
                    today.minusDays(1) -> stringResource(
                        R.string.history_date_yesterday_format,
                        formatDayHeader(selectedDate, zone, withWeekday = false)
                    )
                    else -> formatDayHeader(selectedDate, zone, withWeekday = true)
                }
                DaySelectorRow(
                    label = headerLabel,
                    canGoBack = selectedDate.isAfter(minDate),
                    canGoForward = selectedDate.isBefore(today),
                    showTodayShortcut = selectedDate != today,
                    onPrev = {
                        selectedTrip = null
                        selectedDate = selectedDate.minusDays(1).let { if (it.isBefore(minDate)) minDate else it }
                    },
                    onNext = {
                        selectedTrip = null
                        selectedDate = selectedDate.plusDays(1).let { if (it.isAfter(today)) today else it }
                    },
                    onPick = { showDatePicker = true },
                    onToday = {
                        selectedTrip = null
                        selectedDate = today
                    }
                )
                if (selectedDate == minDate && selectedDate.isBefore(today)) {
                    Text(
                        text = stringResource(R.string.history_limit_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(top = 4.dp))

                val stateModifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                when {
                    isLoading -> HistoryMessageState(
                        title = stringResource(
                            R.string.history_loading_member,
                            memberName ?: memberFallback
                        ),
                        body = null,
                        loading = true,
                        modifier = stateModifier
                    )
                    loadError -> HistoryMessageState(
                        title = stringResource(R.string.history_error),
                        body = stringResource(R.string.history_error_friendly),
                        icon = Icons.Default.CloudOff,
                        actionLabel = stringResource(R.string.history_retry),
                        onAction = { reloadToken++ },
                        modifier = stateModifier
                    )
                    orderedPoints.isEmpty() -> HistoryMessageState(
                        title = stringResource(
                            R.string.history_empty_member_day,
                            memberName ?: memberFallback
                        ),
                        body = stringResource(R.string.history_empty_hint),
                        icon = Icons.Default.Route,
                        modifier = stateModifier
                    )
                    else -> {
                        HistoryDayMap(
                            tripSegments = tripSegments,
                            selectedTrip = sel,
                            selectedMatched = selectedMatched,
                            stayPosition = anchors.firstOrNull(),
                            stayLabel = anchorLabels.firstOrNull(),
                            startLabel = sel?.let { anchorLabels.getOrNull(it) },
                            endLabel = sel?.let { anchorLabels.getOrNull(it + 1) },
                            memberColor = memberColor,
                            onSelectTrip = { selectedTrip = it },
                            onShowAll = { selectedTrip = null },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                        val selTrip = sel?.let { trips.getOrNull(it) }
                        if (sel != null && selTrip != null) {
                            val origin = anchorLabels.getOrNull(sel)
                            val destination = anchorLabels.getOrNull(sel + 1)
                            TripDetailPanel(
                                number = sel + 1,
                                total = trips.size,
                                trip = selTrip,
                                origin = origin,
                                destination = destination,
                                memberColor = memberColor,
                                hasGaps = (tripSegments.getOrNull(sel)?.size ?: 0) > 1,
                                onPrev = if (sel > 0) ({ selectedTrip = sel - 1 }) else null,
                                onNext = if (sel < trips.lastIndex) ({ selectedTrip = sel + 1 }) else null,
                                onPlay = {
                                    openPlayer(
                                        tripSlices.getOrElse(sel) { emptyList() },
                                        tripPlayerTitle(context, sel + 1, origin, destination)
                                    )
                                }
                            )
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1.15f),
                                contentPadding = PaddingValues(start = 4.dp, end = 16.dp, bottom = 24.dp)
                            ) {
                                val playDay: (() -> Unit)? =
                                    if (trips.isNotEmpty() && orderedPoints.size >= 2) {
                                        ({
                                            openPlayer(
                                                orderedPoints,
                                                context.getString(R.string.history_player_title_day)
                                            )
                                        })
                                    } else {
                                        null
                                    }
                                item(key = "summary") {
                                    DaySummaryHeader(
                                        tripCount = trips.size,
                                        distanceMeters = trips.sumOf { it.distanceMeters },
                                        movingMillis = trips.sumOf { it.durationMillis },
                                        truncated = tripsResult.truncated,
                                        onPlayDay = playDay
                                    )
                                }
                                items(timeline.size) { i ->
                                    when (val entry = timeline[i]) {
                                        is DayTimelineEntry.Stay -> TimelineStayRow(
                                            entry = entry,
                                            label = anchorLabels.getOrNull(entry.anchorIndex),
                                            memberColor = memberColor,
                                            railAbove = i > 0,
                                            railBelow = i < timeline.lastIndex
                                        )
                                        is DayTimelineEntry.Move -> {
                                            val trip = trips[entry.tripIndex]
                                            TimelineMoveRow(
                                                number = entry.tripIndex + 1,
                                                trip = trip,
                                                origin = anchorLabels.getOrNull(entry.tripIndex),
                                                destination = anchorLabels.getOrNull(entry.tripIndex + 1),
                                                memberColor = memberColor,
                                                onClick = { selectedTrip = entry.tripIndex }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (showDatePicker) {
                    val selectableDates = remember(zone, minDate, today) {
                        object : SelectableDates {
                            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                                val d = Instant.ofEpochMilli(utcTimeMillis).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                                return !d.isBefore(minDate) && !d.isAfter(today)
                            }
                        }
                    }
                    val pickerState = rememberDatePickerState(
                        initialSelectedDateMillis = selectedDate.atStartOfDay(java.time.ZoneOffset.UTC)
                            .toInstant().toEpochMilli(),
                        selectableDates = selectableDates
                    )
                    DatePickerDialog(
                        onDismissRequest = { showDatePicker = false },
                        confirmButton = {
                            TextButton(onClick = {
                                pickerState.selectedDateMillis?.let { millis ->
                                    // O DatePicker trabalha em meia-noite UTC.
                                    val picked = Instant.ofEpochMilli(millis)
                                        .atZone(java.time.ZoneOffset.UTC).toLocalDate()
                                    if (!picked.isBefore(minDate) && !picked.isAfter(today)) {
                                        selectedTrip = null
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

/**
 * Nome de um local do dia: Local salvo (Places) > cerca (geofence) >
 * endereço curto (Geocoder, com cache) > "Local aproximado".
 */
private suspend fun resolveAnchorLabel(
    context: Context,
    position: LatLng,
    places: List<Place>,
    geofences: List<Geofence>
): String {
    val place = places.firstOrNull {
        isInsideGeofence(
            position.latitude, position.longitude,
            it.center_lat, it.center_lon, it.radius_meters.toDouble()
        )
    }
    if (place != null) return place.name
    val fence = geofences.firstOrNull {
        isInsideGeofence(
            position.latitude, position.longitude,
            it.center_lat, it.center_lon, it.radius_meters
        )
    }
    if (fence != null) return fence.name
    return withContext(Dispatchers.IO) {
        tripLocationLabel(context, places, position.latitude, position.longitude)
    }
}

/** Título do player: "Trajeto 2: Casa → Escola" (ou só "Trajeto 2"). */
private fun tripPlayerTitle(context: Context, number: Int, origin: String?, destination: String?): String {
    val base = context.getString(R.string.history_trip_number, number)
    if (origin.isNullOrBlank() || destination.isNullOrBlank()) return base
    val route = if (origin.equals(destination, ignoreCase = true)) {
        context.getString(R.string.history_trip_round, origin)
    } else {
        context.getString(R.string.history_trip_from_to_short, origin, destination)
    }
    return "$base: $route"
}

/**
 * Rótulo curto do dia (ex.: "12 mai" ou "sex., 12 mai"), no fuso do aparelho.
 */
private fun formatDayHeader(date: LocalDate, zone: ZoneId, withWeekday: Boolean): String {
    val pattern = if (withWeekday) "EEE, d MMM" else "d MMM"
    val fmt = SimpleDateFormat(pattern, Locale("pt", "BR")).apply {
        timeZone = TimeZone.getTimeZone(zone)
    }
    return fmt.format(Date(date.atStartOfDay(zone).toInstant().toEpochMilli()))
}
