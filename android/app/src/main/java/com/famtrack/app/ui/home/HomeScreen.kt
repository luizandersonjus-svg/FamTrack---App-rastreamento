package com.famtrack.app.ui.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.famtrack.app.R
import com.famtrack.app.data.model.Geofence
import com.famtrack.app.data.model.Location as FamLocation
import com.famtrack.app.data.remote.FamilyMemberDisplay
import com.famtrack.app.data.remote.FamilyRepository
import com.famtrack.app.data.remote.GeofenceRepository
import com.famtrack.app.data.remote.LocationRepository
import com.famtrack.app.data.remote.SosRepository
import com.famtrack.app.data.remote.SupabaseClient
import com.famtrack.app.feature.activity.ActivityRepository
import com.famtrack.app.feature.activity.Event
import com.famtrack.app.feature.memberdetail.MemberCard
import com.famtrack.app.feature.memberdetail.MemberCardInfo
import com.famtrack.app.feature.memberdetail.MemberDetailBottomSheet
import com.famtrack.app.feature.memberdetail.resolveStatusText
import com.famtrack.app.feature.onboarding.OnboardingPrefs
import com.famtrack.app.feature.onboarding.OnboardingScreen
import com.famtrack.app.feature.places.Place
import com.famtrack.app.feature.places.PlacesMapOverlay
import com.famtrack.app.feature.places.PlacesRepository
import com.famtrack.app.feature.privacy.MemberFlags
import com.famtrack.app.feature.privacy.PrivacyRepository
import com.famtrack.app.feature.sos.AlertNotifier
import com.famtrack.app.feature.sos.CheckInOutcome
import com.famtrack.app.feature.sos.CheckInRepository
import com.famtrack.app.feature.sos.RealtimeAlertListener
import com.famtrack.app.feature.sos.SosButton
import com.famtrack.app.feature.sos.SosCancelWindowDialog
import com.famtrack.app.feature.sos.SosDeepLink
import com.famtrack.app.feature.sos.SosFix
import com.famtrack.app.feature.sos.LocationFixStore
import com.famtrack.app.service.GeofenceWorker
import com.famtrack.app.service.LocationService
import io.github.jan.supabase.auth.auth
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.maps.android.compose.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

private const val MAX_SOS_ACCURACY_METERS = 250.0
private const val MAX_SOS_FIX_AGE_MILLIS = 5 * 60_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToGeofence: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToPlaces: () -> Unit,
    onNavigateToActivity: () -> Unit,
    onNavigateToInvite: () -> Unit,
    onNavigateToPrivacy: () -> Unit,
    onNavigateToRoutes: () -> Unit,
    onNavigateToMemberHistory: (String) -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val locationRepository = remember { LocationRepository() }
    val familyRepository = remember { FamilyRepository() }
    val sosRepository = remember { SosRepository() }
    val geofenceRepository = remember { GeofenceRepository() }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var currentLocation by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var currentFix by remember { mutableStateOf<SosFix?>(null) }
    var familyLocations by remember { mutableStateOf<List<FamLocation>>(emptyList()) }
    var activeSosAlerts by remember { mutableStateOf<List<com.famtrack.app.data.model.SosAlert>>(emptyList()) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var userId by remember { mutableStateOf<String?>(null) }
    var familyId by remember { mutableStateOf<String?>(null) }
    var familyName by remember { mutableStateOf<String?>(null) }
    var memberInfos by remember { mutableStateOf<Map<String, FamilyMemberDisplay>>(emptyMap()) }
    var memberAvatars by remember { mutableStateOf<Map<String, android.graphics.Bitmap>>(emptyMap()) }
    var geofences by remember { mutableStateOf<List<Geofence>>(emptyList()) }
    var currentSteps by remember { mutableStateOf<Pair<String?, Int>?>(null) }
    var showCreatePlaceDialog by remember { mutableStateOf(false) }
    var sosInFlight by remember { mutableStateOf(false) }
    var showSosCancelWindow by remember { mutableStateOf(false) }
    var pendingSosId by remember { mutableStateOf<String?>(null) }
    var sosCancelling by remember { mutableStateOf(false) }
    var sosMessage by remember { mutableStateOf<String?>(null) }
    var places by remember { mutableStateOf<List<Place>>(emptyList()) }
    var flagByMember by remember { mutableStateOf<Map<String, MemberFlags>>(emptyMap()) }
    var memberStatuses by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var selectedMember by remember { mutableStateOf<FamLocation?>(null) }
    var checkoutBusy by remember { mutableStateOf(false) }
    var onboardingPending by remember { mutableStateOf(true) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var membersPanelExpanded by remember { mutableStateOf(false) }
    var skipNextCameraFollow by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        onboardingPending = !OnboardingPrefs.isDone(context)
    }

    // Agenda (de forma unica e permanente) a checagem periodica de geofences
    // assim que o usuario tem familia, mantendo o intervalo de 15 min da F6.
    LaunchedEffect(familyId) {
        if (familyId != null) {
            val workRequest = PeriodicWorkRequestBuilder<GeofenceWorker>(
                15, TimeUnit.MINUTES
            ).setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "geofence_check",
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }
    }

    if (onboardingPending) {
        OnboardingScreen(
            onFinished = {
                scope.launch { onboardingPending = false }
            }
        )
        return
    }

    val tabs = listOf("Mapa", "Histórico", "Alertas", "Configurações")

    val cameraPositionState = rememberCameraPositionState()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        hasLocationPermission = fineGranted || coarseGranted
        if (hasLocationPermission) {
            startLocationUpdates(context) { location ->
                val fix = SosFix(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy.takeIf { it > 0f }?.toDouble(),
                    fixAtMillis = location.time
                )
                currentLocation = Pair(fix.latitude, fix.longitude)
                currentFix = fix
                LocationFixStore.save(context, fix)
            }
            startSharingService(context, userId, familyId)
        }
    }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            startLocationUpdates(context) { location ->
                val fix = SosFix(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy.takeIf { it > 0f }?.toDouble(),
                    fixAtMillis = location.time
                )
                currentLocation = Pair(fix.latitude, fix.longitude)
                currentFix = fix
                LocationFixStore.save(context, fix)
            }
        }

        try {
            val client = SupabaseClient.getInstance()
            val user = client.auth.currentUserOrNull()
            if (user != null) {
                userId = user.id
                val member = familyRepository.getUserFamily(user.id)
                if (member != null) {
                    familyId = member.family_id
                    familyLocations = locationRepository.getFamilyLocations(member.family_id)
                    // Carrega o nome da família e o nome de cada membro
                    try {
                        familyName = familyRepository.getFamilyById(member.family_id)?.name
                        memberInfos = familyRepository.getFamilyMemberDisplay(member.family_id)
                            .associateBy { it.user_id }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    // Carrega as geofences (locais conhecidos) da família
                    try {
                        geofences = geofenceRepository.getFamilyGeofences(member.family_id)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    // Carrega locais salvos e flags de privacidade (F3/F7)
                    try {
                        places = PlacesRepository().getFamilyPlaces(member.family_id)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    try {
                        flagByMember = PrivacyRepository().getMemberFlags(member.family_id)
                            .associateBy { it.user_id }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    // Carrega alertas SOS ativos da família
                    try {
                        activeSosAlerts = sosRepository.getActiveSosAlerts(member.family_id)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    // Inicia o serviço de compartilhamento de localização em background
                    if (hasLocationPermission) {
                        startSharingService(context, user.id, member.family_id)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    LaunchedEffect(currentLocation) {
        if (skipNextCameraFollow) {
            skipNextCameraFollow = false
        } else {
            currentLocation?.let { (lat, lon) ->
                cameraPositionState.animate(
                    CameraUpdateFactory.newLatLngZoom(
                        com.google.android.gms.maps.model.LatLng(lat, lon),
                        15f
                    ),
                    1000
                )
            }
        }
    }
    // Clique na notificação de SOS: centraliza o mapa no alerta uma única vez.
    LaunchedEffect(Unit) {
        SosDeepLink.consume()?.let { (lat, lon) ->
            skipNextCameraFollow = true
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(
                    com.google.android.gms.maps.model.LatLng(lat, lon),
                    15f
                ),
                1000
            )
        }
    }
    // Localização em tempo real: o canal Realtime do Supabase empurra cada
    // mudança de posição (INSERT/UPDATE) e o mapa reage sem poll a cada 3s.
    // Cada evento é fusionado à lista em memória (uma linha por usuário).
    LaunchedEffect(familyId) {
        val fid = familyId ?: return@LaunchedEffect
        locationRepository.observeFamilyLocations(fid).collect { updated ->
            familyLocations = listOf(updated) +
                familyLocations.filterNot { it.user_id == updated.user_id }
        }
    }

    // Rede de segurança: se o Realtime cair silenciosamente, um refresh leve
    // (apenas locations, 1 query) a cada 30s corrige a tela. As demais tabelas
    // (geofences/places/flags) permanecem com a carga inicial; revalidação em
    // foreground (etapa posterior) as recarrega ao voltar do background.
    LaunchedEffect(familyId) {
        val fid = familyId ?: return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(30_000)
            try {
                familyLocations = locationRepository.getFamilyLocations(fid)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ETAPA 4 — stale/fresh: ao voltar do foreground (MainActivity.onResume),
    // recarrega as tabelas raras que não são realtime (geofences/places/flags)
    // e o próprio estado SOS/locations, sem polling contínuo.
    LaunchedEffect(familyId) {
        val fid = familyId ?: return@LaunchedEffect
        HomeDataRefresh.ticks.collect { _ ->
            try {
                familyLocations = locationRepository.getFamilyLocations(fid)
                activeSosAlerts = sosRepository.getActiveSosAlerts(fid)
                geofences = geofenceRepository.getFamilyGeofences(fid)
                places = PlacesRepository().getFamilyPlaces(fid)
                flagByMember = PrivacyRepository().getMemberFlags(fid)
                    .associateBy { it.user_id }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
                        
    LaunchedEffect(memberInfos) {
        val entries = memberInfos.values.mapNotNull { info ->
            info.avatar_url?.let { url -> info.user_id to url }
        }
        if (entries.isEmpty()) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            entries.mapNotNull { (id, url) ->
                val bmp = try {
                    java.net.URL(url).openStream().use { stream ->
                        android.graphics.BitmapFactory.decodeStream(stream)?.let {
                            android.graphics.Bitmap.createScaledBitmap(it, 120, 120, true)
                        }
                    }
                } catch (e: Exception) {
                    null
                }
                if (bmp != null) id to bmp else null
            }.toMap()
        }
        memberAvatars = loaded
    }

    // Acompanha os passos acumulados no local atual (dados do serviço do próprio aparelho)
    LaunchedEffect(Unit) {
        while (true) {
            currentSteps = LocationService.currentPlaceName?.let { name ->
                name to LocationService.currentPlaceSteps
            }
            kotlinx.coroutines.delay(2000)
        }
    }

    // Status humano (local/endereço) de cada membro para os cards (F2)
    LaunchedEffect(familyLocations, places) {
        val currentPlaces = places
        val statuses = mutableMapOf<String, String>()
        familyLocations.forEach { loc ->
            try {
                statuses[loc.user_id] = resolveStatusText(
                    context,
                    loc.latitude,
                    loc.longitude,
                    currentPlaces
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        memberStatuses = statuses
    }

    // Registra bateria fraca (<20%) uma vez por dia por membro (F6)
    LaunchedEffect(familyLocations) {
        val fid = familyId ?: return@LaunchedEffect
        val uid = userId ?: return@LaunchedEffect
        val today = java.time.LocalDate.now().toString()
        val prefs = context.getSharedPreferences("low_battery_log", Context.MODE_PRIVATE)
        familyLocations.forEach { loc ->
            // RLS: eventos apenas no nome do proprio usuario (member_id = auth.uid()).
            if (loc.user_id == uid) {
                val level = loc.batteryLevel
                if (level != null && level < 20) {
                    val key = "${loc.user_id}_$today"
                    if (!prefs.getBoolean(key, false)) {
                        ActivityRepository().recordEvent(
                            Event(
                                family_id = fid,
                                type = "LOW_BATTERY",
                                member_id = loc.user_id,
                                lat = loc.latitude,
                                lng = loc.longitude
                            )
                        )
                        prefs.edit().putBoolean(key, true).apply()
                    }
                }
            }
        }
    }

    // Notificação em tempo real de SOS dos outros membros (F5, sem FCM)
    LaunchedEffect(familyId, userId) {
        val fid = familyId ?: return@LaunchedEffect
        val uid = userId ?: return@LaunchedEffect
        try {
            RealtimeAlertListener.collectSosAlerts(
                SupabaseClient.getInstance(),
                fid,
                uid,
                onAlert = { alert ->
                    val alertId = alert.id
                    if (alertId != null) {
                        AlertNotifier.showSosNotification(
                            context,
                            memberInfos[alert.user_id]?.display_name
                                ?: context.getString(R.string.sos_notif_fallback),
                            alert.latitude,
                            alert.longitude,
                            alertId
                        )
                        activeSosAlerts = listOf(alert) + activeSosAlerts.filter { it.id != alert.id }
                    }
                },
                onResolved = { sosId ->
                    AlertNotifier.cancelSosNotification(context, sosId)
                    activeSosAlerts = activeSosAlerts.filter { it.id != sosId }
                }
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    val triggerSos: () -> Unit = {
        if (!sosInFlight) {
            sosInFlight = true
            scope.launch {
                try {
                    val fId = familyId ?: return@launch
                    val uId = userId ?: return@launch
                    // Usa o fix em memória; se não houver, recorre ao último
                    // fix persistido (app reaberto depois de kill/reboot).
                    val fix = currentFix ?: LocationFixStore.load(context)
                    if (fix == null) {
                        sosMessage = context.getString(R.string.sos_no_location)
                        return@launch
                    }
                    val ageMillis = System.currentTimeMillis() - fix.fixAtMillis
                    val approx = fix.accuracy == null ||
                        fix.accuracy > MAX_SOS_ACCURACY_METERS ||
                        ageMillis > MAX_SOS_FIX_AGE_MILLIS
                    try {
                        val alert = sosRepository.triggerSos(
                            fId, uId, fix.latitude, fix.longitude,
                            accuracy = fix.accuracy,
                            fixAt = fix.fixAtMillis
                        )
                        try {
                            ActivityRepository().recordEvent(
                                Event(
                                    family_id = fId,
                                    type = "SOS",
                                    member_id = uId,
                                    lat = fix.latitude,
                                    lng = fix.longitude
                                )
                            )
                        } catch (e: Exception) {
                            // Auxiliar; não invalida o SOS em si.
                            Log.e("HomeScreen", "Falha ao registrar evento SOS", e)
                        }
                        pendingSosId = alert.id
                        showSosCancelWindow = true
                        sosMessage = context.getString(
                            if (approx) R.string.sos_approx else R.string.sos_sent
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                        sosMessage = context.getString(R.string.sos_send_fail)
                    }
                } finally {
                    sosInFlight = false
                }
            }
        }
    }

    if (showSosCancelWindow) {
        SosCancelWindowDialog(
            onCancel = {
                val alertId = pendingSosId ?: return@SosCancelWindowDialog
                if (!sosCancelling) {
                    scope.launch {
                        sosCancelling = true
                        try {
                            sosRepository.resolveSos(alertId)
                            pendingSosId = null
                            showSosCancelWindow = false
                        } catch (e: Exception) {
                            e.printStackTrace()
                            sosMessage = context.getString(R.string.sos_cancel_fail)
                        } finally {
                            sosCancelling = false
                        }
                    }
                }
            },
            onWindowEnded = {
                pendingSosId = null
                showSosCancelWindow = false
            }
        )
    }

    val createLat = currentLocation?.first
    val createLon = currentLocation?.second
    val createFid = familyId
    if (showCreatePlaceDialog && createLat != null && createLon != null && createFid != null) {
        CreatePlaceDialog(
            familyId = createFid,
            lat = createLat,
            lon = createLon,
            onDismiss = { showCreatePlaceDialog = false },
            onCreated = {
                scope.launch {
                    try {
                        geofences = geofenceRepository.getFamilyGeofences(createFid)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "FamTrack",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.home_action_settings),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.home_action_more),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.home_action_places)) },
                                leadingIcon = { Icon(Icons.Filled.Place, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onNavigateToPlaces()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.home_action_activity)) },
                                leadingIcon = { Icon(Icons.Filled.Timeline, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onNavigateToActivity()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.home_action_invite)) },
                                leadingIcon = { Icon(Icons.Filled.GroupAdd, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onNavigateToInvite()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.home_action_privacy)) },
                                leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onNavigateToPrivacy()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.home_action_routes)) },
                                leadingIcon = { Icon(Icons.Filled.Directions, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onNavigateToRoutes()
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.logout)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    showLogoutDialog = true
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                tabs.forEachIndexed { index, title ->
                    NavigationBarItem(
                        icon = {
                            Icon(
                                when (index) {
                                    0 -> Icons.Filled.Map
                                    1 -> Icons.Outlined.History
                                    2 -> Icons.Outlined.Notifications
                                    else -> Icons.Outlined.Settings
                                },
                                contentDescription = title
                            )
                        },
                        label = { Text(title, fontSize = 12.sp) },
                        selected = selectedTab == index,
                        onClick = {
                            selectedTab = index
                            when (index) {
                                1 -> onNavigateToHistory()
                                2 -> onNavigateToNotifications()
                                3 -> onNavigateToSettings()
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                        )
                    )
                }
            }
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (showLogoutDialog) {
                AlertDialog(
                    onDismissRequest = { showLogoutDialog = false },
                    title = { Text(stringResource(R.string.settings_logout_title)) },
                    text = { Text(stringResource(R.string.settings_logout_message)) },
                    confirmButton = {
                        TextButton(onClick = {
                            showLogoutDialog = false
                            scope.launch {
                                SupabaseClient.getInstance().auth.signOut()
                                onLogout()
                            }
                        }) {
                            Text(stringResource(R.string.settings_logout_confirm))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showLogoutDialog = false }) {
                            Text(stringResource(R.string.settings_logout_cancel))
                        }
                    }
                )
            }
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                uiSettings = MapUiSettings(
                    myLocationButtonEnabled = false,
                    zoomControlsEnabled = false,
                    compassEnabled = false,
                    mapToolbarEnabled = false
                ),
                properties = MapProperties(
                    isMyLocationEnabled = hasLocationPermission,
                    mapType = MapType.NORMAL
                )
            ) {
                val placedPositions = mutableListOf<android.graphics.Point>()
                val markerDensity = context.resources.displayMetrics.density
                val markerPinColor = MaterialTheme.colorScheme.primary.toArgb()
                familyLocations.forEachIndexed { index, location ->
                    val info = memberInfos[location.user_id]
                    val photo = memberAvatars[location.user_id]
                    val isSelf = location.user_id == userId
                    val live = if (isSelf) currentLocation else null
                    val basePos = com.google.android.gms.maps.model.LatLng(
                        live?.first ?: location.latitude,
                        live?.second ?: location.longitude
                    )
                    // Deslocamento visual mínimo (estável) só quando coordenadas estão quase iguais.
                    val baseLatU = (basePos.latitude * 1e6).toInt()
                    val baseLonU = (basePos.longitude * 1e6).toInt()
                    val clash = placedPositions.any { p ->
                        kotlin.math.abs(p.x - baseLatU) < 50 &&
                            kotlin.math.abs(p.y - baseLonU) < 50
                    }
                    placedPositions += android.graphics.Point(baseLatU, baseLonU)
                    val pos = if (clash) {
                        val d = 0.00004
                        com.google.android.gms.maps.model.LatLng(
                            basePos.latitude + d * ((index % 3) - 1),
                            basePos.longitude + d * ((index % 2) * 2 - 1)
                        )
                    } else {
                        basePos
                    }
                    Marker(
                        state = MarkerState(position = pos),
                        title = info?.display_name ?: "Membro",
                        icon = com.google.android.gms.maps.model.BitmapDescriptorFactory
                            .fromBitmap(
                                memberMarkerBitmap(
                                    density = markerDensity,
                                    name = info?.display_name,
                                    photo = photo,
                                    pinColor = markerPinColor
                                )
                            ),
                        anchor = androidx.compose.ui.geometry.Offset(0.5f, 1f),
                        zIndex = 1f + index,
                        onClick = {
                            selectedMember = location
                            true
                        }
                    )
                }
                activeSosAlerts.forEach { alert ->
                    Marker(
                        state = MarkerState(
                            position = com.google.android.gms.maps.model.LatLng(
                                alert.latitude,
                                alert.longitude
                            )
                        ),
                        title = "ALERTA SOS! ${alert.message}",
                        icon = com.google.android.gms.maps.model.BitmapDescriptorFactory
                            .defaultMarker(com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_RED),
                        zIndex = 10f
                    )
                }
                geofences.forEach { geofence ->
                    val center = com.google.android.gms.maps.model.LatLng(
                        geofence.center_lat,
                        geofence.center_lon
                    )
                    val strokeColor = try {
                        Color(android.graphics.Color.parseColor(geofence.color))
                    } catch (e: Exception) {
                        Color.Red
                    }
                    val fillColor = strokeColor.copy(alpha = 0.3f)
                    Circle(
                        center = center,
                        radius = geofence.radius_meters,
                        strokeColor = strokeColor,
                        fillColor = fillColor,
                        strokeWidth = 3f
                    )
                    Marker(
                        state = MarkerState(position = center),
                        title = geofence.name
                    )
                }
                PlacesMapOverlay(places)
            }

            when {
                !hasLocationPermission -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Filled.LocationOn,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = stringResource(R.string.home_location_perm_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.home_location_perm_text),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = {
                                        permissionLauncher.launch(
                                            arrayOf(
                                                Manifest.permission.ACCESS_FINE_LOCATION,
                                                Manifest.permission.ACCESS_COARSE_LOCATION
                                            )
                                        )
                                    }
                                ) {
                                    Text(stringResource(R.string.home_location_perm_action))
                                }
                            }
                        }
                    }
                }
                currentLocation == null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(40.dp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    stringResource(R.string.home_loading_location),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                familyId != null && familyLocations.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Filled.GroupAdd,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = stringResource(R.string.home_empty_members_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.home_empty_members_text),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(onClick = onNavigateToInvite) {
                                    Text(stringResource(R.string.home_empty_members_action))
                                }
                            }
                        }
                    }
                }
            }

            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Text(
                    text = buildString {
                        append(familyName ?: "Família")
                        append(" • ")
                        append(familyLocations.size)
                        append(if (familyLocations.size == 1) " membro" else " membros")
                        val steps = currentSteps
                        if (steps != null && steps.first != null) {
                            append(" • ${steps.first}: ${steps.second} passos")
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (activeSosAlerts.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 16.dp, top = 64.dp, end = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.home_sos_active_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        activeSosAlerts.forEach { alert ->
                            val name = memberInfos[alert.user_id]?.display_name
                                ?: alert.user?.name
                                ?: context.getString(R.string.sos_notif_fallback)
                            val time = formatSosTime(alert.created_at)
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            if (time != null) {
                                Text(
                                    text = context.getString(R.string.home_sos_time_label) + " " + time,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White
                                )
                            }
                            memberStatuses[alert.user_id]?.takeIf { it.isNotBlank() }?.let { address ->
                                Text(
                                    text = context.getString(R.string.home_sos_location_label) + ": " + address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White
                                )
                            }
                            if (alert.message.isNotBlank()) {
                                Text(
                                    text = alert.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White
                                )
                            }
                            if (alert.id != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                TextButton(
                                    onClick = {
                                        val alertId = alert.id
                                        scope.launch {
                                            try {
                                                sosRepository.resolveSos(alertId)
                                                activeSosAlerts = activeSosAlerts.filter { it.id != alertId }
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                                sosMessage = context.getString(R.string.sos_resolve_fail)
                                            }
                                        }
                                    }
                                ) {
                                    Text(
                                        text = stringResource(
                                            if (alert.user_id == userId) {
                                                R.string.home_sos_end
                                            } else {
                                                R.string.home_sos_resolve
                                            }
                                        ),
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Painel inferior de membros (F2/F3/F7): barra compacta recolhida por padrão;
            // ao tocar, expande o LazyRow com os MemberCards. O usuário atual
            // aparece primeiro, com o selo "Você" no card.
            if (familyLocations.isNotEmpty()) {
                val carouselOrder = remember(familyLocations, userId) {
                    familyLocations.sortedBy { it.user_id != userId }
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (membersPanelExpanded) {
                        LazyRow(
                            modifier = Modifier.padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(carouselOrder, key = { it.user_id }) { loc ->
                                val info = memberInfos[loc.user_id]
                                MemberCard(
                                    info = MemberCardInfo(
                                        memberId = loc.user_id,
                                        displayName = info?.display_name ?: "Membro",
                                        avatarUrl = info?.avatar_url,
                                        statusText = memberStatuses[loc.user_id] ?: "",
                                        batteryLevel = loc.batteryLevel,
                                        lastUpdatedMillis = loc.lastUpdatedAt,
                                        sharingPaused = flagByMember[loc.user_id]?.sharing_paused == true,
                                        isSelf = loc.user_id == userId
                                    ),
                                    onClick = { selectedMember = loc },
                                    modifier = Modifier.width(240.dp)
                                )
                            }
                        }
                    }
                    Surface(
                        onClick = { membersPanelExpanded = !membersPanelExpanded },
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 3.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = buildString {
                                        append(familyName ?: context.getString(R.string.home_default_family))
                                        append(" • ")
                                        append(carouselOrder.size)
                                        append(
                                            if (carouselOrder.size == 1) " membro" else " membros"
                                        )
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                val selId = selectedMember?.user_id
                                val selName = selId?.let { memberInfos[it]?.display_name }
                                if (selName != null) {
                                    Text(
                                        text = context.getString(
                                            R.string.home_panel_member_format,
                                            selName,
                                            memberStatuses[selId] ?: ""
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                } else {
                                    Text(
                                        text = stringResource(R.string.home_panel_placeholder),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = if (membersPanelExpanded) {
                                    Icons.Filled.ExpandLess
                                } else {
                                    Icons.Filled.ExpandMore
                                },
                                contentDescription = stringResource(
                                    if (membersPanelExpanded) {
                                        R.string.home_panel_collapse_cd
                                    } else {
                                        R.string.home_panel_expand_cd
                                    }
                                ),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Coluna de controles do mapa: utilitárias (Minha localização e Check-in)
            // agrupadas num bloco; SOS separado, dominante e em cor de
            // emergência. Sobe quando o painel de membros está expandido,
            // para nunca ficar sobreposto a ele.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = 16.dp,
                        bottom = if (membersPanelExpanded) 236.dp else 88.dp
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 6.dp,
                    tonalElevation = 2.dp
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 6.dp,
                            modifier = Modifier.size(48.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    currentLocation?.let { (lat, lon) ->
                                        scope.launch {
                                            cameraPositionState.animate(
                                                CameraUpdateFactory.newLatLngZoom(
                                                    com.google.android.gms.maps.model.LatLng(lat, lon),
                                                    15f
                                                ),
                                                500
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    Icons.Default.MyLocation,
                                    contentDescription = stringResource(R.string.home_action_center),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 6.dp,
                            modifier = Modifier.size(48.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    if (!checkoutBusy) {
                                        scope.launch {
                                            checkoutBusy = true
                                            try {
                                                val outcome = CheckInRepository().checkIn(context)
                                                sosMessage = when (outcome) {
                                                    CheckInOutcome.SUCCESS ->
                                                        context.getString(R.string.checkin_done)
                                                    CheckInOutcome.NO_PERMISSION ->
                                                        context.getString(R.string.checkin_no_perm)
                                                    CheckInOutcome.NO_LOCATION ->
                                                        context.getString(R.string.checkin_no_location)
                                                    CheckInOutcome.TIMEOUT ->
                                                        context.getString(R.string.checkin_timeout)
                                                    CheckInOutcome.ERROR ->
                                                        context.getString(R.string.checkin_fail)
                                                }
                                            } finally {
                                                checkoutBusy = false
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = stringResource(R.string.checkin_cd),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
                SosButton(
                    onTrigger = triggerSos,
                    onTap = {
                        sosMessage = context.getString(R.string.sos_hold_hint_tap)
                    },
                    modifier = Modifier
                        .size(64.dp)
                        .shadow(8.dp, CircleShape)
                )
            }

            sosMessage?.let { msg ->
                LaunchedEffect(msg) {
                    kotlinx.coroutines.delay(3000)
                    sosMessage = null
                }
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .padding(bottom = if (membersPanelExpanded) 264.dp else 0.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(msg)
                }
            }
        }
    }

    selectedMember?.let { member ->
        val info = memberInfos[member.user_id]
        MemberDetailBottomSheet(
            member = member,
            displayName = info?.display_name ?: "Membro",
            avatarUrl = info?.avatar_url,
            places = places,
            memberFlags = flagByMember[member.user_id],
            familyId = familyId ?: "",
            isSelf = member.user_id == userId,
            onDismiss = { selectedMember = null },
            onNavigateToHistory = { uid ->
                selectedMember = null
                onNavigateToMemberHistory(uid)
            }
        )
    }
}

/** Formata o instante ISO do alerta SOS como HH:mm; null se indisponível. */
private fun formatSosTime(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    val formatter = DateTimeFormatter.ofPattern("HH:mm")
    return try {
        OffsetDateTime.parse(iso).format(formatter)
    } catch (_: Exception) {
        try {
            LocalDateTime.parse(iso).format(formatter)
        } catch (_: Exception) {
            null
        }
    }
}

private fun startSharingService(
    context: Context,
    userId: String?,
    familyId: String?
) {
    if (userId == null || familyId == null) return

    if (ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    ) {
        LocationService.startService(context, familyId, userId)
    }
}

private fun startLocationUpdates(
    context: Context,
    onLocationReceived: (Location) -> Unit
) {
    val locationClient = LocationServices.getFusedLocationProviderClient(context)

    val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY,
        3000L
    ).apply {
        setMinUpdateDistanceMeters(5f)
        setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
        setWaitForAccurateLocation(true)
    }.build()

    val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { location ->
                onLocationReceived(location)
            }
        }
    }

    if (ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    ) {
        locationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatePlaceDialog(
    familyId: String,
    lat: Double,
    lon: Double,
    onDismiss: () -> Unit,
    onCreated: () -> Unit
) {
    val geofenceRepository = remember { GeofenceRepository() }
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var radiusText by remember { mutableStateOf("100") }
    var colorHex by remember { mutableStateOf("#2E7D32") }
    var saving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val canSubmit = name.trim().isNotBlank() && radiusText.toDoubleOrNull() != null && !saving

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Cadastrar local") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "O local sera criado na sua posicao atual.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome do local") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = radiusText,
                    onValueChange = { radiusText = it },
                    label = { Text("Raio (metros)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Cor",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val palette = listOf(
                        "#2E7D32", "#1E88E5", "#F4511E", "#6A1B9A",
                        "#00897B", "#FDD835", "#C62828", "#37474F"
                    )
                    palette.forEach { hex ->
                        val isSelected = colorHex == hex
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(android.graphics.Color.parseColor(hex)))
                                .clickable { colorHex = hex }
                                .then(
                                    if (isSelected) {
                                        Modifier.border(
                                            3.dp,
                                            MaterialTheme.colorScheme.onSurface,
                                            CircleShape
                                        )
                                    } else {
                                        Modifier
                                    }
                                )
                        )
                    }
                }
                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = errorMessage.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val radius = radiusText.toDoubleOrNull() ?: return@TextButton
                    val trimmedName = name.trim()
                    if (trimmedName.isEmpty()) return@TextButton
                    saving = true
                    errorMessage = null
                    scope.launch {
                        try {
                            geofenceRepository.createGeofence(
                                Geofence(
                                    family_id = familyId,
                                    name = trimmedName,
                                    center_lat = lat,
                                    center_lon = lon,
                                    radius_meters = radius,
                                    color = colorHex
                                )
                            )
                            onCreated()
                            onDismiss()
                        } catch (e: Exception) {
                            e.printStackTrace()
                            errorMessage = "Erro ao cadastrar o local. Tente novamente."
                            saving = false
                        }
                    }
                },
                enabled = canSubmit
            ) {
                Text(if (saving) "Salvando..." else "Salvar")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !saving
            ) {
                Text("Cancelar")
            }
        }
    )
}

private fun memberInitials(name: String?): String =
    (name ?: "")
        .trim()
        .split(Regex("\\s+"))
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .take(2)
        .joinToString("")
        .ifEmpty { "?" }

/**
 * Bitmap do marcador de membro: pino/ponta na PARTE INFERIOR, círculo de
 * fundo, foto de perfil (ou iniciais) por cima e borda branca — o rosto
 * nunca é coberto pelo pino. anchor do Marker = (0.5f, 1.0f) aponta para a
 * ponta do pino no lat/lng.
 * Ordem das camadas (de baixo para cima):
 *   1. sombra suave do pino
 *   2. ponta/pin triangular na parte inferior
 *   3. círculo de fundo branco
 *   4. foto de perfil recortada em círculo (ou iniciais se não houver foto)
 *   5. borda branca (3.5dp) ao redor do círculo
 */
private fun memberMarkerBitmap(
    density: Float,
    name: String?,
    photo: android.graphics.Bitmap?,
    pinColor: Int
): android.graphics.Bitmap {
    val avatarPx = (48f * density).toInt().coerceAtLeast(1)
    val tailPx = (14f * density).toInt()
    val marginPx = (4f * density).toInt()
    val borderPx = (3.5f * density).coerceAtLeast(2f)

    val width = avatarPx + marginPx * 2
    val height = marginPx + avatarPx + tailPx
    val cx = width / 2f
    val avatarCenterY = marginPx + avatarPx / 2f
    val avatarR = avatarPx / 2f
    val tipY = height - 1f

    val out = android.graphics.Bitmap.createBitmap(
        width, height, android.graphics.Bitmap.Config.ARGB_8888
    )
    val canvas = android.graphics.Canvas(out)

    // 1 + 2: sombra suave + ponta/pin triangular (fica atrás do círculo).
    val pinPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = pinColor
        setShadowLayer((marginPx * 1.2f).coerceAtLeast(2f), 0f, marginPx * 0.6f, 0x66000000)
    }
    val tailPath = android.graphics.Path().apply {
        moveTo(cx, tipY)
        quadTo(
            cx - avatarR * 0.62f, avatarCenterY + avatarR * 0.85f,
            cx - avatarR * 0.80f, avatarCenterY + avatarR * 0.55f
        )
        quadTo(
            cx + avatarR * 0.80f, avatarCenterY + avatarR * 0.55f,
            cx + avatarR * 0.62f, avatarCenterY + avatarR * 0.85f
        )
        close()
    }
    canvas.drawPath(tailPath, pinPaint)

    // 3: círculo de fundo branco sobre a base do pino.
    val whiteFill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
    }
    canvas.drawCircle(cx, avatarCenterY, avatarR, whiteFill)

    // 4: foto recortada em círculo (ou iniciais como placeholder).
    if (photo != null) {
        val clip = android.graphics.Path().apply {
            addCircle(cx, avatarCenterY, avatarR - borderPx, android.graphics.Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(clip)
        val size = avatarPx - borderPx.toInt() * 2
        canvas.drawBitmap(
            photo,
            null,
            android.graphics.Rect(
                (cx - size / 2f).toInt(),
                (avatarCenterY - size / 2f).toInt(),
                (cx + size / 2f).toInt(),
                (avatarCenterY + size / 2f).toInt()
            ),
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        )
        canvas.restore()
    } else {
        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = pinColor
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD
            )
            textSize = avatarPx * 0.46f
        }
        val fm = textPaint.fontMetrics
        canvas.drawText(
            memberInitials(name),
            cx,
            avatarCenterY - (fm.ascent + fm.descent) / 2f,
            textPaint
        )
    }

    // 5: borda branca ao redor do círculo.
    val borderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        color = android.graphics.Color.WHITE
        strokeWidth = borderPx
    }
    canvas.drawCircle(cx, avatarCenterY, avatarR - borderPx / 2f, borderPaint)

    return out
}
