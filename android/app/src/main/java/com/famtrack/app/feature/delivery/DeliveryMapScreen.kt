package com.famtrack.app.feature.delivery

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.famtrack.app.feature.common.ErrorMessages
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLngBounds
import com.famtrack.app.ui.history.osrmDrivingPath
import com.google.maps.android.compose.CameraMoveStartedReason
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Intervalo de atualização: mostra as entregas marcadas pelo outro aparelho. */
private const val REFRESH_INTERVAL_MS = 15_000L

/**
 * Zoom de entrega: perto o bastante para ver as casas do quarteirão e o
 * nome das ruas (o mapa da Home usa 17.5; aqui o entregador precisa de mais).
 */
private const val DELIVERY_ZOOM = 18.5f

/** Intervalo do GPS enquanto a tela está aberta (o ponto azul e a câmera). */
private const val GPS_INTERVAL_MS = 2_000L
private const val GPS_MIN_INTERVAL_MS = 1_000L

/**
 * Arrastar o mapa mais que isso (em dp) para longe da própria posição solta
 * o "seguir". Pinçar para dar zoom ou arrastar pouco continua seguindo.
 */
private const val FOLLOW_RELEASE_DP = 80f

/** Distância na tela entre uma seta e a próxima (constante em qualquer zoom). */
private const val ARROW_SPACING_DP = 64f

/** Fora do caminho além disso (m), pede um caminho novo a partir de onde está. */
private const val OFF_ROUTE_M = 40.0

/** Intervalo mínimo entre pedidos de caminho ao servidor. */
private const val REROUTE_MIN_INTERVAL_MS = 10_000L

/** Depois de uma falha do servidor, nova tentativa após este tempo. */
private const val ROUTE_RETRY_MS = 30_000L

private fun hasLocationPermission(context: Context): Boolean =
    listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

/**
 * Mapa das entregas: uma bandeira por pacote, com o número do pacote.
 * Quadriculada = pendente; inteiramente verde = entregue.
 *
 * - A câmera segue o entregador (zoom de rua). Arrastar o mapa solta; o
 *   botão de localização volta a seguir.
 * - O painel de baixo rola na horizontal entre os pacotes, na ordem da rota.
 * - Ao chegar perto de um endereço pendente, o painel rola sozinho até o
 *   pacote daquele lugar (com vibração curta).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeliveryMapScreen(
    onNavigateBack: () -> Unit,
    onOpenList: () -> Unit,
    onAddPackage: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val repository = remember { DeliveryRepository() }
    val density = LocalDensity.current.density

    var familyId by remember { mutableStateOf<String?>(null) }
    var deliveries by remember { mutableStateOf<List<Delivery>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var mapLoaded by remember { mutableStateOf(false) }
    var cameraPlaced by remember { mutableStateOf(false) }
    var pagerPlaced by remember { mutableStateOf(false) }

    // Localização ao vivo (só com a tela visível).
    var hasLocation by remember { mutableStateOf(hasLocationPermission(context)) }
    var myPosition by remember { mutableStateOf<LatLng?>(null) }
    // Veículo no lugar do ponto azul: para onde aponta e a precisão do GPS.
    var myHeading by remember { mutableStateOf<Float?>(null) }
    var myAccuracy by remember { mutableStateOf<Float?>(null) }
    var vehicleType by remember { mutableStateOf(loadVehicleType(context)) }
    var vehicleMenuOpen by remember { mutableStateOf(false) }
    // Seguir: a câmera acompanha o entregador.
    var following by remember { mutableStateOf(true) }
    var followZoomed by remember { mutableStateOf(false) }
    var gestureActive by remember { mutableStateOf(false) }
    // Pacote ao qual o entregador acabou de chegar (selo no cartão).
    var arrivedId by remember { mutableStateOf<String?>(null) }
    val arrivalTracker = remember { ArrivalTracker() }

    val cameraPositionState = rememberCameraPositionState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasLocation = result.values.any { it } || hasLocationPermission(context)
        if (hasLocation) {
            following = true
            followZoomed = false
        } else {
            Toast.makeText(
                context,
                "Sem permissão de localização, o mapa não acompanha você.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // GPS da tela: liga ao aparecer, desliga em segundo plano. Também
    // relê a permissão ao voltar (o usuário pode tê-la dado nas Configurações).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, hasLocation) {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    val to = GeoPt(loc.latitude, loc.longitude)
                    myHeading = nextHeading(
                        previous = myHeading,
                        gpsBearing = if (loc.hasBearing()) loc.bearing else null,
                        speedMps = if (loc.hasSpeed()) loc.speed else null,
                        from = myPosition?.let { GeoPt(it.latitude, it.longitude) },
                        to = to
                    )
                    myAccuracy = if (loc.hasAccuracy()) loc.accuracy else null
                    myPosition = LatLng(loc.latitude, loc.longitude)
                }
            }
        }
        var active = false
        fun start() {
            if (active || !hasLocation) return
            try {
                val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, GPS_INTERVAL_MS)
                    .setMinUpdateIntervalMillis(GPS_MIN_INTERVAL_MS)
                    .build()
                client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                active = true
            } catch (e: SecurityException) {
                hasLocation = false
            }
        }
        fun stop() {
            if (active) {
                client.removeLocationUpdates(callback)
                active = false
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    val granted = hasLocationPermission(context)
                    if (granted != hasLocation) hasLocation = granted else start()
                }
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            stop()
        }
    }

    // Carrega ao entrar (e ao voltar do cadastro) e atualiza periodicamente.
    LaunchedEffect(Unit) {
        while (true) {
            try {
                val fid = familyId ?: repository.currentFamilyId()
                familyId = fid
                if (fid != null) {
                    deliveries = repository.list(fid)
                    loadFailed = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorMessages.logSafe(e, "carregar entregas")
                if (deliveries.isEmpty()) loadFailed = true
            } finally {
                isLoading = false
            }
            delay(REFRESH_INTERVAL_MS)
        }
    }

    val routeOrdered = deliveries.inRouteOrder().filter { it.id != null }
    val total = routeOrdered.size
    val doneCount = routeOrdered.count { it.delivered }
    val nextPendingIndex = routeOrdered.indexOfFirst { !it.delivered }

    val pagerState = rememberPagerState { routeOrdered.size }
    val selectedIndex = pagerState.currentPage.coerceIn(0, (total - 1).coerceAtLeast(0))
    val selected = routeOrdered.getOrNull(selectedIndex)

    // --- Setas de direção ------------------------------------------------
    var arrowMode by remember { mutableStateOf(loadArrowMode(context)) }
    var arrowMenuOpen by remember { mutableStateOf(false) }
    var routePath by remember { mutableStateOf<List<GeoPt>>(emptyList()) }
    var routeFailed by remember { mutableStateOf(false) }
    var lastRouteRequestMs by remember { mutableLongStateOf(0L) }
    var routeJob by remember { mutableStateOf<Job?>(null) }
    // Cartão em que o painel parou (não cada página durante o deslize).
    val arrowTarget = routeOrdered.getOrNull(pagerState.settledPage)
    val arrowStopsNow = arrowStops(arrowMode, routeOrdered, arrowTarget)
    val arrowKey = arrowMode.name + ":" + arrowStopsNow.joinToString(",") { it.id.orEmpty() }

    fun setArrowMode(mode: ArrowMode) {
        arrowMode = mode
        saveArrowMode(context, mode)
    }

    /** Pede ao OSRM o caminho de carro: posição atual -> paradas. */
    fun requestRoute() {
        val stops = arrowStops(arrowMode, routeOrdered, arrowTarget)
        routeJob?.cancel()
        if (stops.isEmpty()) {
            routePath = emptyList()
            routeFailed = false
            return
        }
        val origin = myPosition
        // Até um pacote só, sem saber onde o entregador está, não há caminho.
        if (origin == null && stops.size < 2) {
            routePath = emptyList()
            routeFailed = false
            return
        }
        val coords = listOfNotNull(origin) + stops.map { LatLng(it.latitude, it.longitude) }
        lastRouteRequestMs = System.currentTimeMillis()
        routeJob = scope.launch {
            val path = try {
                osrmDrivingPath(coords)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorMessages.logSafe(e, "caminho das setas")
                null
            }
            routeFailed = path == null
            routePath = path.orEmpty().map { GeoPt(it.latitude, it.longitude) }
        }
    }

    // Caminho novo quando mudam o modo ou as paradas, ou quando chega o 1º fix.
    LaunchedEffect(arrowKey, myPosition != null) {
        requestRoute()
    }

    // Saiu do caminho (errou a entrada, desvio): recalcula a partir de onde está.
    LaunchedEffect(myPosition) {
        val pos = myPosition ?: return@LaunchedEffect
        val elapsed = System.currentTimeMillis() - lastRouteRequestMs
        // Servidor falhou: tenta de novo de vez em quando.
        if (routePath.size < 2) {
            if (routeFailed && elapsed >= ROUTE_RETRY_MS) requestRoute()
            return@LaunchedEffect
        }
        if (elapsed < REROUTE_MIN_INTERVAL_MS) return@LaunchedEffect
        val off = projectOnPath(routePath, GeoPt(pos.latitude, pos.longitude))?.distanceM
            ?: return@LaunchedEffect
        if (off > OFF_ROUTE_M) requestRoute()
    }

    // O que falta do caminho (o trecho já percorrido some).
    val remaining = remember(routePath, myPosition) {
        remainingPath(routePath, myPosition?.let { GeoPt(it.latitude, it.longitude) })
    }
    // Zoom em degraus de 0,5 e região visível só com a câmera parada: as
    // setas não são recalculadas a cada quadro da animação.
    val zoomStep by remember {
        derivedStateOf { (cameraPositionState.position.zoom * 2).toInt() / 2f }
    }
    var visibleBounds by remember { mutableStateOf<LatLngBounds?>(null) }
    LaunchedEffect(cameraPositionState.isMoving, mapLoaded) {
        if (!cameraPositionState.isMoving) {
            visibleBounds = cameraPositionState.projection?.visibleRegion?.latLngBounds
        }
    }
    val arrows = remember(remaining, zoomStep, visibleBounds) {
        val ref = remaining.firstOrNull()
        if (ref == null) {
            emptyList()
        } else {
            val spacing = ARROW_SPACING_DP * density * metersPerPixel(zoomStep, ref.lat)
            val all = arrowsAlong(remaining, spacing, firstAtM = spacing * 0.6, maxArrows = 400)
            val bounds = visibleBounds
            if (bounds == null) {
                all.take(60)
            } else {
                // Só o que aparece na tela (com folga), para não encher o mapa de marcadores.
                val latPad = (bounds.northeast.latitude - bounds.southwest.latitude) * 0.3
                val lonPad = (bounds.northeast.longitude - bounds.southwest.longitude) * 0.3
                all.filter {
                    it.lat in (bounds.southwest.latitude - latPad)..(bounds.northeast.latitude + latPad) &&
                        it.lon in (bounds.southwest.longitude - lonPad)..(bounds.northeast.longitude + lonPad)
                }.take(120)
            }
        }
    }

    // Primeira vez com dados: o painel abre na próxima entrega pendente.
    LaunchedEffect(total) {
        if (!pagerPlaced && total > 0) {
            pagerPlaced = true
            if (nextPendingIndex > 0) pagerState.scrollToPage(nextPendingIndex)
        }
    }

    // Posição inicial da câmera: perto, na posição do entregador; sem ela,
    // na próxima bandeira pendente.
    LaunchedEffect(mapLoaded, total, hasLocation) {
        if (!mapLoaded || cameraPlaced) return@LaunchedEffect
        val seed = myPosition ?: if (hasLocation) lastKnownPosition(context) else null
        val target = seed
            ?: routeOrdered.getOrNull(nextPendingIndex.coerceAtLeast(0))
                ?.let { LatLng(it.latitude, it.longitude) }
            ?: return@LaunchedEffect
        try {
            cameraPositionState.move(CameraUpdateFactory.newLatLngZoom(target, DELIVERY_ZOOM))
            cameraPlaced = true
            if (seed != null) followZoomed = true
            if (!hasLocation) following = false
        } catch (e: Exception) {
            ErrorMessages.logSafe(e, "posicionar mapa de entregas")
        }
    }

    // Seguir o entregador.
    LaunchedEffect(myPosition, following, mapLoaded) {
        val pos = myPosition ?: return@LaunchedEffect
        if (!following || !mapLoaded || gestureActive) return@LaunchedEffect
        val update = if (!followZoomed) {
            CameraUpdateFactory.newLatLngZoom(
                pos, maxOf(cameraPositionState.position.zoom, DELIVERY_ZOOM)
            )
        } else {
            CameraUpdateFactory.newLatLng(pos)
        }
        cameraPositionState.animate(update, 700)
        followZoomed = true
        cameraPlaced = true
    }

    // Gestos: arrastar para longe solta o "seguir"; pinçar não.
    LaunchedEffect(cameraPositionState.isMoving, cameraPositionState.cameraMoveStartedReason) {
        val byGesture =
            cameraPositionState.cameraMoveStartedReason == CameraMoveStartedReason.GESTURE
        if (cameraPositionState.isMoving) {
            if (byGesture && following) gestureActive = true
        } else if (gestureActive) {
            gestureActive = false
            val pos = myPosition
            val projection = cameraPositionState.projection
            if (pos == null || projection == null) {
                following = false
            } else {
                val p = projection.toScreenLocation(pos)
                val c = projection.toScreenLocation(cameraPositionState.position.target)
                val distPx = Math.hypot((p.x - c.x).toDouble(), (p.y - c.y).toDouble())
                if (distPx > FOLLOW_RELEASE_DP * density) following = false
            }
        }
    }

    // Chegou perto de um endereço pendente: rola o painel até o pacote.
    LaunchedEffect(myPosition) {
        val pos = myPosition ?: return@LaunchedEffect
        val id = arrivalTracker.update(pos.latitude, pos.longitude, routeOrdered)
        arrivedId = arrivedId?.takeIf { current ->
            routeOrdered.firstOrNull { it.id == current }
                ?.let { !it.delivered && it.distanceTo(pos.latitude, pos.longitude) <= ARRIVAL_EXIT_RADIUS_M }
                ?: false
        }
        if (id == null) return@LaunchedEffect
        val index = routeOrdered.indexOfFirst { it.id == id }
        if (index < 0) return@LaunchedEffect
        arrivedId = id
        // Não briga com o dedo: se o entregador está rolando, só marca o selo.
        if (pagerState.isScrollInProgress) return@LaunchedEffect
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        pagerState.animateScrollToPage(index)
    }

    // Sem seguir, escolher outro pacote no painel mostra a bandeira dele.
    LaunchedEffect(pagerState.settledPage) {
        if (following || !cameraPlaced) return@LaunchedEffect
        val d = routeOrdered.getOrNull(pagerState.settledPage) ?: return@LaunchedEffect
        cameraPositionState.animate(
            CameraUpdateFactory.newLatLng(LatLng(d.latitude, d.longitude)), 600
        )
    }

    fun followMe() {
        if (!hasLocation) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }
        followZoomed = false
        following = true
        if (myPosition == null) {
            Toast.makeText(context, "Procurando sua localização…", Toast.LENGTH_SHORT).show()
        }
    }

    fun showAllFlags() {
        if (total == 0) return
        following = false
        val points = routeOrdered.map { LatLng(it.latitude, it.longitude) } + listOfNotNull(myPosition)
        scope.launch {
            try {
                if (points.size == 1) {
                    cameraPositionState.animate(
                        CameraUpdateFactory.newLatLngZoom(points.first(), DELIVERY_ZOOM), 600
                    )
                } else {
                    val bounds = LatLngBounds.Builder().apply { points.forEach { include(it) } }.build()
                    cameraPositionState.animate(
                        CameraUpdateFactory.newLatLngBounds(bounds, (64 * density).toInt()), 600
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorMessages.logSafe(e, "enquadrar entregas")
            }
        }
    }

    fun toggleDelivered(delivery: Delivery) {
        val id = delivery.id ?: return
        if (isSaving) return
        val newValue = !delivery.delivered
        isSaving = true
        scope.launch {
            try {
                repository.setDelivered(id, newValue)
                deliveries = deliveries.map { if (it.id == id) it.copy(delivered = newValue) else it }
                if (newValue) {
                    if (arrivedId == id) arrivedId = null
                    // Passa para a próxima pendente da rota (depois desta; senão, a primeira).
                    val ordered = deliveries.inRouteOrder().filter { it.id != null }
                    val here = ordered.indexOfFirst { it.id == id }
                    val next = ordered.withIndex()
                        .firstOrNull { it.index > here && !it.value.delivered }
                        ?: ordered.withIndex().firstOrNull { !it.value.delivered }
                    if (next != null) pagerState.animateScrollToPage(next.index)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorMessages.logSafe(e, "marcar entrega")
                Toast.makeText(context, "Não foi possível salvar. Verifique a internet.", Toast.LENGTH_SHORT).show()
            } finally {
                isSaving = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Entregas", style = MaterialTheme.typography.titleLarge)
                        if (total > 0) {
                            Text(
                                text = if (total == 1) "1 pacote na rota" else "$total pacotes na rota",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
                actions = {
                    if (total > 0) {
                        IconButton(onClick = { showAllFlags() }) {
                            Icon(Icons.Filled.ZoomOutMap, contentDescription = "Ver todas as bandeiras")
                        }
                    }
                    IconButton(onClick = onOpenList) {
                        Icon(
                            Icons.AutoMirrored.Filled.List,
                            contentDescription = "Ver lista da rota"
                        )
                    }
                    IconButton(onClick = onAddPackage) {
                        Icon(Icons.Filled.Add, contentDescription = "Adicionar pacote")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraPositionState,
                    uiSettings = MapUiSettings(
                        zoomControlsEnabled = false,
                        // Botão próprio ("seguir"), abaixo.
                        myLocationButtonEnabled = false,
                        mapToolbarEnabled = false
                    ),
                    properties = MapProperties(
                        mapType = MapType.NORMAL,
                        // O veículo (abaixo) substitui o ponto azul padrão.
                        isMyLocationEnabled = false
                    ),
                    onMapLoaded = { mapLoaded = true }
                ) {
                    // Entregador: carro ou moto apontando para onde está indo.
                    myPosition?.let { pos ->
                        val accuracy = myAccuracy
                        if (accuracy != null && accuracy > 12f) {
                            Circle(
                                center = pos,
                                radius = accuracy.toDouble(),
                                fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                                strokeColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                strokeWidth = 1f * density,
                                zIndex = 0.4f
                            )
                        }
                        val vehicleIcon = remember(vehicleType, density) {
                            try {
                                BitmapDescriptorFactory.fromBitmap(buildVehicleBitmap(vehicleType, density))
                            } catch (e: Exception) {
                                null
                            }
                        }
                        if (vehicleIcon != null) {
                            Marker(
                                state = MarkerState(position = pos),
                                icon = vehicleIcon,
                                anchor = Offset(0.5f, 0.5f),
                                rotation = myHeading ?: 0f,
                                flat = true,
                                zIndex = 20f,
                                title = "Você",
                                onClick = { true }
                            )
                        }
                    }
                    // Caminho: linha fina e clara + setas no sentido do trânsito.
                    if (remaining.size >= 2) {
                        Polyline(
                            points = remaining.map { LatLng(it.lat, it.lon) },
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                            width = 4f * density,
                            zIndex = 0.5f
                        )
                        val arrowIcon = remember(density) {
                            try {
                                BitmapDescriptorFactory.fromBitmap(buildArrowBitmap(density))
                            } catch (e: Exception) {
                                null
                            }
                        }
                        if (arrowIcon != null) {
                            arrows.forEach { arrow ->
                                Marker(
                                    state = MarkerState(position = LatLng(arrow.lat, arrow.lon)),
                                    icon = arrowIcon,
                                    anchor = Offset(0.5f, 0.5f),
                                    rotation = arrow.bearing,
                                    flat = true,
                                    zIndex = 0.6f,
                                    onClick = { true }
                                )
                            }
                        }
                    }
                    routeOrdered.forEachIndexed { index, delivery ->
                        val id = delivery.id.orEmpty()
                        key(id) {
                            val isSelected = id == selected?.id
                            val icon = rememberDeliveryFlagIcon(
                                number = delivery.package_number,
                                label = delivery.numberLabel,
                                delivered = delivery.delivered,
                                selected = isSelected,
                                density = density
                            )
                            if (icon != null) {
                                Marker(
                                    state = MarkerState(
                                        position = LatLng(delivery.latitude, delivery.longitude)
                                    ),
                                    title = "Pacote ${delivery.package_number}",
                                    icon = icon.descriptor,
                                    anchor = icon.anchor,
                                    zIndex = if (isSelected) 10f else if (delivery.delivered) 1f else 5f,
                                    onClick = {
                                        // Bandeira tocada: as setas passam a levar até ela.
                                        if (arrowMode == ArrowMode.ROUTE || arrowMode == ArrowMode.NUMBER) {
                                            setArrowMode(ArrowMode.SELECTED)
                                            Toast.makeText(
                                                context,
                                                "Setas levando até o pacote ${delivery.package_number}",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                        scope.launch { pagerState.animateScrollToPage(index) }
                                        true
                                    }
                                )
                            }
                        }
                    }
                }

                if (total > 0) {
                    Box(modifier = Modifier.align(Alignment.TopStart).padding(12.dp)) {
                        ArrowModeChip(
                            mode = arrowMode,
                            failed = routeFailed && arrowMode != ArrowMode.OFF,
                            onClick = { arrowMenuOpen = true }
                        )
                        DropdownMenu(expanded = arrowMenuOpen, onDismissRequest = { arrowMenuOpen = false }) {
                            ArrowMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.label) },
                                    leadingIcon = {
                                        if (mode == arrowMode) Icon(Icons.Filled.Check, contentDescription = null)
                                    },
                                    onClick = {
                                        arrowMenuOpen = false
                                        setArrowMode(mode)
                                    }
                                )
                            }
                        }
                    }
                    FlagLegend(modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = 64.dp)
                ) {
                    SmallFloatingActionButton(
                        onClick = { vehicleMenuOpen = true },
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary
                    ) {
                        Icon(
                            if (vehicleType == VehicleType.MOTO) Icons.Filled.TwoWheeler else Icons.Filled.DirectionsCar,
                            contentDescription = "Veículo no mapa: ${vehicleType.label}"
                        )
                    }
                    DropdownMenu(expanded = vehicleMenuOpen, onDismissRequest = { vehicleMenuOpen = false }) {
                        VehicleType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.label) },
                                leadingIcon = {
                                    Icon(
                                        if (type == VehicleType.MOTO) Icons.Filled.TwoWheeler else Icons.Filled.DirectionsCar,
                                        contentDescription = null
                                    )
                                },
                                trailingIcon = {
                                    if (type == vehicleType) Icon(Icons.Filled.Check, contentDescription = null)
                                },
                                onClick = {
                                    vehicleMenuOpen = false
                                    vehicleType = type
                                    saveVehicleType(context, type)
                                }
                            )
                        }
                    }
                }
                SmallFloatingActionButton(
                    onClick = { followMe() },
                    containerColor = if (following && hasLocation) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    contentColor = if (following && hasLocation) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    elevation = FloatingActionButtonDefaults.elevation(),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                ) {
                    Icon(
                        if (following && hasLocation) Icons.Filled.GpsFixed else Icons.Filled.GpsNotFixed,
                        contentDescription = if (following) "Seguindo sua posição" else "Seguir minha posição"
                    )
                }
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }

            Surface(
                tonalElevation = 2.dp,
                shadowElevation = 8.dp,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    when {
                        total > 0 -> {
                            ProgressHeader(
                                doneCount = doneCount,
                                total = total,
                                modifier = Modifier.padding(horizontal = 20.dp)
                            )
                            HorizontalPager(
                                state = pagerState,
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                pageSpacing = 10.dp,
                                key = { routeOrdered.getOrNull(it)?.id ?: it }
                            ) { page ->
                                val delivery = routeOrdered.getOrNull(page) ?: return@HorizontalPager
                                DeliveryCard(
                                    delivery = delivery,
                                    stopNumber = page + 1,
                                    total = total,
                                    isNext = page == nextPendingIndex,
                                    arrived = delivery.id == arrivedId && !delivery.delivered,
                                    distanceMeters = myPosition?.let {
                                        delivery.distanceTo(it.latitude, it.longitude)
                                    },
                                    isSaving = isSaving,
                                    onNavigate = {
                                        if (!openDeliveryNavigation(context, delivery)) {
                                            Toast.makeText(
                                                context,
                                                "Nenhum app de mapas encontrado.",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    },
                                    onToggleDelivered = { toggleDelivered(delivery) }
                                )
                            }
                        }

                        isLoading -> PanelMessage("Carregando entregas…")

                        loadFailed -> PanelMessage(
                            "Não foi possível carregar as entregas. Verifique a internet; " +
                                "a tela tenta de novo sozinha."
                        )

                        familyId == null -> PanelMessage("Entre em uma família para usar as entregas.")

                        else -> {
                            Column(
                                modifier = Modifier.padding(horizontal = 20.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    "Nenhum pacote na rota",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Cadastre os pacotes com o número e o endereço. Cada um vira " +
                                        "uma bandeira no mapa.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(
                                    onClick = onAddPackage,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                ) {
                                    Text("Adicionar pacote")
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
private fun PanelMessage(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp)
    )
}

@Composable
private fun ProgressHeader(doneCount: Int, total: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "$doneCount de $total entregues",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (total > 1) {
                Text(
                    text = "Deslize para ver outros pacotes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        LinearProgressIndicator(
            progress = { if (total == 0) 0f else doneCount.toFloat() / total },
            color = DeliveredGreen,
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
        )
    }
}

/** Cartão de um pacote no painel deslizante. */
@Composable
private fun DeliveryCard(
    delivery: Delivery,
    stopNumber: Int,
    total: Int,
    isNext: Boolean,
    arrived: Boolean,
    distanceMeters: Double?,
    isSaving: Boolean,
    onNavigate: () -> Unit,
    onToggleDelivered: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (arrived) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val status = when {
                    arrived -> "Você está no endereço"
                    delivery.delivered -> "Entregue"
                    isNext -> "Próxima entrega"
                    else -> "Pendente"
                }
                Text(
                    text = "${stopNumber}ª de $total · $status",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (arrived) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        arrived -> MaterialTheme.colorScheme.onPrimaryContainer
                        delivery.delivered -> DeliveredGreen
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f)
                )
                if (distanceMeters != null && !delivery.delivered) {
                    Text(
                        text = formatDistance(distanceMeters),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DeliveryFlagChip(number = delivery.package_number, delivered = delivery.delivered)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Pacote ${delivery.package_number}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = delivery.addressLine,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = delivery.placeTypeLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            val note = delivery.note?.trim().orEmpty()
            if (note.isNotEmpty()) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onNavigate, modifier = Modifier.height(48.dp)) {
                    Icon(Icons.Filled.NearMe, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Navegar")
                }
                if (delivery.delivered) {
                    OutlinedButton(
                        onClick = onToggleDelivered,
                        enabled = !isSaving,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text("Desfazer entrega")
                    }
                } else {
                    Button(
                        onClick = onToggleDelivered,
                        enabled = !isSaving,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text("Marcar como entregue")
                    }
                }
            }
        }
    }
}

@Composable
private fun ArrowModeChip(mode: ArrowMode, failed: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        shadowElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = if (failed) "Setas: sem rota agora" else "Setas: ${mode.short}",
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun FlagLegend(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        shadowElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DeliveryFlagSwatch(delivered = false)
            Text("Pendente", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(4.dp))
            DeliveryFlagSwatch(delivered = true)
            Text("Entregue", style = MaterialTheme.typography.bodySmall)
        }
    }
}
