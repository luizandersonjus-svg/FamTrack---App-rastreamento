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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.famtrack.app.R
import com.famtrack.app.data.remote.FamilyRepository
import com.famtrack.app.data.remote.SupabaseClient
import com.famtrack.app.feature.places.PlacesRepository
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayFormatter = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", Locale("pt", "BR"))

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
    val activityRepository = remember { ActivityRepository() }
    val familyRepository = remember { FamilyRepository() }
    val placesRepository = remember { PlacesRepository() }

    var resolvedFamilyId by remember { mutableStateOf(familyId) }
    var events by remember { mutableStateOf<List<Event>>(emptyList()) }
    var memberNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var placeNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
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
                placeNames = placesRepository.getFamilyPlaces(fid)
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

    val grouped = remember(events) {
        events.groupBy { it.created_at?.substring(0, 10) ?: "desconhecido" }
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
                                ActivityRow(
                                    event = event,
                                    displayName = memberNames[event.member_id] ?: "Membro",
                                    placeName = placeNames[event.place_id]
                                        ?: "${"%.5f".format(event.lat)}, ${"%.5f".format(event.lng)}",
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
    placeName: String,
    modifier: Modifier = Modifier
) {
    val (icon, tint) = eventIcon(event)
    val actionText = when (event.type) {
        "ENTER" -> stringResource(R.string.activity_enter, placeName)
        "EXIT" -> stringResource(R.string.activity_exit, placeName)
        "SOS" -> stringResource(R.string.activity_sos, placeName)
        "CHECKIN" -> stringResource(R.string.activity_checkin, placeName)
        "LOW_BATTERY" -> stringResource(R.string.activity_low_battery)
        else -> event.type
    }
    val timeLabel = try {
        event.created_at?.substring(11, 16) ?: ""
    } catch (e: Exception) {
        ""
    }

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