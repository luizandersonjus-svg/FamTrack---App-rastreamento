package com.famtrack.app.feature.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Sos
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.content.Context
import com.famtrack.app.R
import com.famtrack.app.data.remote.FamilyRepository
import com.famtrack.app.data.remote.SupabaseClient
import com.famtrack.app.feature.common.AddressOutcome
import com.famtrack.app.feature.common.AddressResolver
import com.famtrack.app.feature.places.Place
import com.famtrack.app.feature.places.PlacesRepository
import com.famtrack.app.util.isInsideGeofence
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayFormatter = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", Locale("pt", "BR"))
private val eventTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Converte o created_at (UTC do Postgres) para o fuso do aparelho. */
private fun toDeviceTime(iso: String?, zone: ZoneId): String? = try {
    if (iso == null) null else OffsetDateTime.parse(iso).atZoneSameInstant(zone).format(eventTimeFormatter)
} catch (e: Exception) {
    null
}

/** Data local (fuso do aparelho) do evento. */
private fun toDeviceDate(iso: String?, zone: ZoneId): String? = try {
    if (iso == null) null else OffsetDateTime.parse(iso).atZoneSameInstant(zone).toLocalDate().toString()
} catch (e: Exception) {
    null
}

/**
 * Histórico de atividade agrupado por dia (F6). Quando memberId é informado,
 * mostra apenas os eventos daquele membro.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    familyId: String,
    memberId: String? = null,
    onNavigateBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activityRepository = remember { ActivityRepository() }
    val familyRepository = remember { FamilyRepository() }
    val placesRepository = remember { PlacesRepository() }

    var resolvedFamilyId by remember { mutableStateOf(familyId) }
    var events by remember { mutableStateOf<List<Event>>(emptyList()) }
    var memberNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var placeNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var places by remember { mutableStateOf<List<Place>>(emptyList()) }
    var eventLocations by remember {
        mutableStateOf<Map<String, EventLocation>>(emptyMap())
    }
    var isLoading by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var hasMore by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        if (resolvedFamilyId.isBlank()) {
            try {
                val user = SupabaseClient.getInstance().auth.currentUserOrNull()
                if (user != null) {
                    resolvedFamilyId = familyRepository.getUserFamily(user.id)?.family_id ?: ""
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun loadFirstPage() {
        val fid = resolvedFamilyId
        if (fid.isBlank()) return
        scope.launch {
            isLoading = true
            error = null
            try {
                val page = if (memberId != null) {
                    activityRepository.getMemberEvents(fid, memberId).take(50)
                } else {
                    activityRepository.getFamilyEvents(fid, limit = 50, offset = 0)
                }
                events = page
                hasMore = memberId == null && page.size == 50
                memberNames = familyRepository.getFamilyMemberDisplay(fid)
                    .associate { it.user_id to it.display_name }
                val familyPlaces = placesRepository.getFamilyPlaces(fid)
                places = familyPlaces
                placeNames = familyPlaces
                    .associate { (it.id ?: "") to it.name }
            } catch (e: Exception) {
                e.printStackTrace()
                error = e.message
            } finally {
                isLoading = false
            }
        }
    }

    fun loadMore() {
        val fid = resolvedFamilyId
        if (fid.isBlank()) return
        scope.launch {
            isLoadingMore = true
            try {
                val offset = events.size.toLong()
                val page = activityRepository.getFamilyEvents(fid, limit = 50, offset = offset)
                events = events + page
                hasMore = page.size == 50
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isLoadingMore = false
            }
        }
    }

    LaunchedEffect(familyId, memberId, resolvedFamilyId) { loadFirstPage() }

    // Resolve endereço/place de cada evento com coordenadas, uma única vez por
    // evento (chave estável). O Geocoder roda fora da recomposition e tem cache.
    LaunchedEffect(events, places) {
        val current = eventLocations
        val missing = events.filter { current.containsKey(eventKey(it)).not() }
        if (missing.isEmpty()) return@LaunchedEffect
        val additions = withContext(Dispatchers.IO) {
            missing.map { event -> eventKey(event) to resolveEventLocation(context, event, places) }
                .toMap()
        }
        eventLocations = current + additions
    }

    val zone = remember { ZonedDateTime.now().zone }

    val grouped = remember(events) {
        events.groupBy { toDeviceDate(it.created_at, zone) ?: "desconhecido" }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.activity_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.app_back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                isLoading -> CircularProgressIndicator(
                    Modifier.align(Alignment.Center)
                )
                error != null -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.activity_error),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { loadFirstPage() }) {
                            Text(stringResource(R.string.places_retry))
                        }
                    }
                }
                events.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Filled.Place,
                            contentDescription = null,
                            modifier = Modifier.size(72.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.activity_empty),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.activity_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp)
                    ) {
                        grouped.forEach { (day, dayEvents) ->
                            val label = try {
                                LocalDate.parse(day).format(dayFormatter)
                                    .replaceFirstChar { it.titlecase(Locale("pt", "BR")) }
                            } catch (e: Exception) {
                                day
                            }
                            item(key = "header_$day") {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                            items(dayEvents, key = { it.id ?: "${it.created_at}_${it.member_id}" }) { event ->
                                val loc = eventLocations[eventKey(event)]
                                ActivityRow(
                                    event = event,
                                    displayName = memberNames[event.member_id] ?: "Membro",
                                    placeName = loc?.messagePlace,
                                    locationSubtitle = loc?.subtitle,
                                    zone = zone,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        if (hasMore) {
                            item(key = "load_more") {
                                OutlinedButton(
                                    onClick = { loadMore() },
                                    enabled = !isLoadingMore,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp)
                                ) {
                                    if (isLoadingMore) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Text(stringResource(R.string.activity_load_more))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(
    event: Event,
    displayName: String,
    placeName: String?,
    locationSubtitle: String?,
    zone: ZoneId,
    modifier: Modifier = Modifier
) {
    val (icon, tint) = eventIcon(event)
    val actionText = when (event.type) {
        "ENTER" -> when {
            placeName != null -> stringResource(R.string.activity_enter, placeName)
            else -> stringResource(R.string.activity_enter_no_loc)
        }
        "EXIT" -> when {
            placeName != null -> stringResource(R.string.activity_exit, placeName)
            else -> stringResource(R.string.activity_exit_no_loc)
        }
        "SOS" -> when {
            placeName != null -> stringResource(R.string.activity_sos, placeName)
            else -> stringResource(R.string.activity_sos_no_loc)
        }
        "CHECKIN" -> when {
            placeName != null -> stringResource(R.string.activity_checkin, placeName)
            else -> stringResource(R.string.activity_checkin_no_loc)
        }
        "LOW_BATTERY" -> stringResource(R.string.activity_low_battery)
        else -> event.type
    }
    val timeLabel = toDeviceTime(event.created_at, zone) ?: ""

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(tint.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = tint)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$displayName $actionText",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (locationSubtitle != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = locationSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (timeLabel.isNotBlank()) {
                Text(
                    text = timeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Localização legível de um evento: o nome a usar na mensagem (mensagem) e o
 * endereço/indício exibido como subtítulo. Coordenadas só como último recurso.
 */
private data class EventLocation(
    val messagePlace: String?,
    val subtitle: String?
)

private fun eventKey(event: Event): String =
    event.id ?: "${event.created_at}_${event.member_id}"

private fun eventHasCoords(event: Event): Boolean =
    event.lat != 0.0 || event.lng != 0.0

private suspend fun resolveEventLocation(
    context: Context,
    event: Event,
    places: List<Place>
): EventLocation {
    if (!eventHasCoords(event)) return EventLocation(null, null)

    val direct = places.firstOrNull { it.id != null && it.id == event.place_id }
    val exact = places.firstOrNull {
        isInsideGeofence(event.lat, event.lng, it.center_lat, it.center_lon, it.radius_meters.toDouble())
    }
    val near = places.firstOrNull {
        isInsideGeofence(
            event.lat, event.lng, it.center_lat, it.center_lon,
            it.radius_meters.toDouble() * 1.5
        )
    }

    // ETAPA 11-C3: check-in usa o RÓTULO ÚNICO (10A) — só o lugar cujo raio
    // real contém a coordenada (nunca "mais próxima" nem o heurístico "perto").
    val isCheckin = event.type == "CHECKIN"
    val messagePlace = if (isCheckin) {
        direct?.name ?: exact?.name
    } else {
        direct?.name ?: exact?.name ?: near?.name
    }

    var subtitle: String? = null
    if (isCheckin && messagePlace == null) {
        subtitle = AddressResolver.resolveForDisplay(context, event.lat, event.lng, places)
    } else if (messagePlace == null) {
        subtitle = when (val outcome = AddressResolver.resolve(context, event.lat, event.lng)) {
            is AddressOutcome.Found -> outcome.text
            AddressOutcome.NotFound -> context.getString(R.string.address_not_found)
            AddressOutcome.Unavailable -> context.getString(
                R.string.address_approx_coords,
                "%.5f".format(event.lat),
                "%.5f".format(event.lng)
            )
        }
    } else if (near != null && exact == null) {
        subtitle = context.getString(R.string.address_near_place, near.name)
    }
    return EventLocation(messagePlace, subtitle)
}

@Composable
private fun eventIcon(event: Event): Pair<ImageVector, androidx.compose.ui.graphics.Color> {
    return when (event.type) {
        "EXIT" -> Icons.AutoMirrored.Filled.ExitToApp to MaterialTheme.colorScheme.tertiary
        "SOS" -> Icons.Filled.Sos to MaterialTheme.colorScheme.error
        "CHECKIN" -> Icons.Filled.CheckCircle to MaterialTheme.colorScheme.primary
        "LOW_BATTERY" -> Icons.Filled.BatteryAlert to MaterialTheme.colorScheme.error
        else -> Icons.Filled.Place to MaterialTheme.colorScheme.primary
    }
}