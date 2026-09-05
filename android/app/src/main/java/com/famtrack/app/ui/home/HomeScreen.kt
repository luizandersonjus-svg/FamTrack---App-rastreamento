package com.famtrack.app.ui.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
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
import com.famtrack.app.feature.sos.CheckInRepository
import com.famtrack.app.feature.sos.RealtimeAlertListener
import com.famtrack.app.feature.sos.SosButton
import com.famtrack.app.feature.sos.SosConfirmationDialog
import com.famtrack.app.service.LocationService
import io.github.jan.supabase.auth.auth
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.maps.android.compose.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var showSosConfirm by remember { mutableStateOf(false) }
    var sosMessage by remember { mutableStateOf<String?>(null) }
    var places by remember { mutableStateOf<List<Place>>(emptyList()) }
    var flagByMember by remember { mutableStateOf<Map<String, MemberFlags>>(emptyMap()) }
    var memberStatuses by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var selectedMember by remember { mutableStateOf<FamLocation?>(null) }
    var checkoutBusy by remember { mutableStateOf(false) }
    var onboardingPending by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        onboardingPending = !OnboardingPrefs.isDone(context)
    }

    if (onboardingPending) {
        OnboardingScreen(
            onFinished = {
                scope.launch { onboardingPending = false }
            }
        )
        return
    }

    val tabs = listOf("Mapa", "Historico", "Alertas", "Config")

    val cameraPositionState = rememberCameraPositionState()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        hasLocationPermission = fineGranted || coarseGranted
        if (hasLocationPermission) {
            startLocationUpdates(context) { location ->
                currentLocation = Pair(location.latitude, location.longitude)
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
                currentLocation = Pair(location.latitude, location.longitude)
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
    // Mantém o mapa atualizado: busca as posições dos membros a cada 10 segundos
    LaunchedEffect(familyId) {
        val fid = familyId ?: return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(3_000)
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
                            circularAvatarBitmap(android.graphics.Bitmap.createScaledBitmap(it, 120, 120, true))
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
        val today = java.time.LocalDate.now().toString()
        val prefs = context.getSharedPreferences("low_battery_log", Context.MODE_PRIVATE)
        familyLocations.forEach { loc ->
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

    // Notificação em tempo real de SOS dos outros membros (F5, sem FCM)
    LaunchedEffect(familyId, userId) {
        val fid = familyId ?: return@LaunchedEffect
        val uid = userId ?: return@LaunchedEffect
        try {
            RealtimeAlertListener.collectSosAlerts(
                SupabaseClient.getInstance(),
                fid,
                uid
            ) { alert ->
                AlertNotifier.showSosNotification(
                    context,
                    memberInfos[alert.user_id]?.display_name
                        ?: context.getString(R.string.sos_notif_fallback),
                    alert.latitude,
                    alert.longitude
                )
                activeSosAlerts = listOf(alert) + activeSosAlerts.filter { it.id != alert.id }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    if (showSosConfirm) {
        SosConfirmationDialog(
            onConfirm = {
                showSosConfirm = false
                scope.launch {
                    val fId = familyId ?: return@launch
                    val uId = userId ?: return@launch
                    val (lat, lon) = currentLocation ?: run {
                        sosMessage = context.getString(R.string.sos_no_location)
                        return@launch
                    }
                    try {
                        sosRepository.triggerSos(fId, uId, lat, lon)
                        ActivityRepository().recordEvent(
                            Event(
                                family_id = fId,
                                type = "SOS",
                                member_id = uId,
                                lat = lat,
                                lng = lon
                            )
                        )
                        sosMessage = context.getString(R.string.sos_sent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        sosMessage = context.getString(R.string.sos_send_fail)
                    }
                }
            },
            onDismiss = { showSosConfirm = false }
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
                    IconButton(onClick = onNavigateToPlaces) {
                        Icon(
                            Icons.Filled.Place,
                            contentDescription = stringResource(R.string.home_action_places),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onNavigateToActivity) {
                        Icon(
                            Icons.Filled.Timeline,
                            contentDescription = stringResource(R.string.home_action_activity),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onNavigateToInvite) {
                        Icon(
                            Icons.Filled.GroupAdd,
                            contentDescription = stringResource(R.string.home_action_invite),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onNavigateToPrivacy) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = stringResource(R.string.home_action_privacy),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = {
                        scope.launch {
                            SupabaseClient.getInstance().auth.signOut()
                            onLogout()
                        }
                    }) {
                        Icon(
                            Icons.Default.Logout,
                            contentDescription = stringResource(R.string.logout),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FloatingActionButton(
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
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.MyLocation,
                        contentDescription = "Minha localizacao",
                        modifier = Modifier.size(24.dp)
                    )
                }

                FloatingActionButton(
                    onClick = onNavigateToGeofence,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = "Geofences",
                        modifier = Modifier.size(24.dp)
                    )
                }

                FloatingActionButton(
                    onClick = { showCreatePlaceDialog = true },
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.AddLocation,
                        contentDescription = "Cadastrar local",
                        modifier = Modifier.size(24.dp)
                    )
                }

                FloatingActionButton(
                    onClick = {
                        if (!checkoutBusy) {
                            scope.launch {
                                checkoutBusy = true
                                val ok = CheckInRepository().checkIn(context)
                                sosMessage = if (ok) {
                                    context.getString(R.string.checkin_done)
                                } else {
                                    context.getString(R.string.checkin_fail)
                                }
                                checkoutBusy = false
                            }
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = stringResource(R.string.checkin_cd),
                        modifier = Modifier.size(24.dp)
                    )
                }

                FloatingActionButton(
                    onClick = { showSosConfirm = true },
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(64.dp)
                        .shadow(8.dp, CircleShape)
                ) {
                    Text(
                        "SOS",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
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
                familyLocations.forEach { location ->
                    val info = memberInfos[location.user_id]
                    val avatar = memberAvatars[location.user_id]
                    val isSelf = location.user_id == userId
                    val live = if (isSelf) currentLocation else null
                    val pos = com.google.android.gms.maps.model.LatLng(
                        live?.first ?: location.latitude,
                        live?.second ?: location.longitude
                    )
                    Marker(
                        state = MarkerState(position = pos),
                        title = info?.display_name ?: "Membro",
                        icon = if (avatar != null) {
                            com.google.android.gms.maps.model.BitmapDescriptorFactory.fromBitmap(avatar)
                        } else {
                            com.google.android.gms.maps.model.BitmapDescriptorFactory.defaultMarker(
                                com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_AZURE
                            )
                        },
                        anchor = if (avatar != null) {
                            androidx.compose.ui.geometry.Offset(0.5f, 0.5f)
                        } else {
                            androidx.compose.ui.geometry.Offset(0.5f, 1f)
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

            if (currentLocation == null) {
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
                                "Obtendo localizacao...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
                        append(familyName ?: "Familia")
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
                        .align(Alignment.BottomStart)
                        .padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "ALERTA SOS ATIVO",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        activeSosAlerts.forEach { alert ->
                            Text(
                                text = alert.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            // Cards dos membros (F2/F3/F7) no rodapé do mapa
            if (familyLocations.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 92.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(familyLocations, key = { it.user_id }) { loc ->
                        val info = memberInfos[loc.user_id]
                        MemberCard(
                            info = MemberCardInfo(
                                memberId = loc.user_id,
                                displayName = info?.display_name ?: "Membro",
                                avatarUrl = info?.avatar_url,
                                statusText = memberStatuses[loc.user_id] ?: "",
                                batteryLevel = loc.batteryLevel,
                                lastUpdatedMillis = loc.lastUpdatedAt,
                                sharingPaused = flagByMember[loc.user_id]?.sharing_paused == true
                            ),
                            onClick = { selectedMember = loc },
                            modifier = Modifier.width(240.dp)
                        )
                    }
                }
            }

            // Botão de SOS com pressionar e segurar (F5)
            SosButton(
                onTrigger = { showSosConfirm = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 80.dp)
                    .padding(bottom = 8.dp)
            )

            sosMessage?.let { msg ->
                LaunchedEffect(msg) {
                    kotlinx.coroutines.delay(3000)
                    sosMessage = null
                }
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
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

private fun circularAvatarBitmap(source: android.graphics.Bitmap): android.graphics.Bitmap {
    val size = minOf(source.width, source.height)
    val out = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val bounds = android.graphics.RectF(0f, 0f, size.toFloat(), size.toFloat())
    val path = android.graphics.Path().apply {
        addOval(bounds, android.graphics.Path.Direction.CW)
    }
    canvas.save()
    canvas.clipPath(path)
    canvas.drawBitmap(source, null, android.graphics.Rect(0, 0, size, size), paint)
    canvas.restore()
    val border = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        color = android.graphics.Color.WHITE
        strokeWidth = (size * 0.06f).coerceAtLeast(2f)
    }
    canvas.drawOval(bounds, border)
    return out
}
