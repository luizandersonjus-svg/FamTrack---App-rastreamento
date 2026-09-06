package com.famtrack.app.feature.places

import android.Manifest
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.famtrack.app.R
import com.famtrack.app.data.remote.FamilyRepository
import com.famtrack.app.data.remote.SupabaseClient
import com.famtrack.app.feature.common.ErrorMessages
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import io.github.jan.supabase.auth.auth
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Criação e edição de um local (F3). O raio pode ser ajustado entre 100 e
 * 1000 m (padrão 150 m) com o círculo atualizado no mapa. Salvar registra a
 * geofence reutilizando a implementação JÁ EXISTENTE.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceEditScreen(
    placeId: String?,
    onNavigateBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { PlacesRepository() }
    val familyRepository = remember { FamilyRepository() }

    var familyId by remember { mutableStateOf<String?>(null) }
    var existing by remember { mutableStateOf<Place?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var addressQuery by remember { mutableStateOf("") }
    var searchingAddress by remember { mutableStateOf(false) }

    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("CASA") }
    var lat by remember { mutableStateOf(-3.74512) }
    var lng by remember { mutableStateOf(-38.52314) }
    var radius by remember { mutableStateOf(150) }
    var alertOnEnter by remember { mutableStateOf(true) }
    var alertOnExit by remember { mutableStateOf(true) }

    val cameraPositionState = rememberCameraPositionState()
    var cameraTarget by remember { mutableStateOf<LatLng?>(null) }

    val locationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    fun useMyLocation() {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            errorMessage = context.getString(R.string.place_err_permission)
            return
        }
        locationClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                lat = loc.latitude
                lng = loc.longitude
                cameraTarget = LatLng(loc.latitude, loc.longitude)
            } else {
                errorMessage = context.getString(R.string.place_err_location_now)
            }
        }.addOnFailureListener {
            errorMessage = context.getString(R.string.place_err_location_exc)
        }
    }

    fun searchAddressByQuery() {
        val query = addressQuery.trim()
        if (query.isEmpty()) return
        if (searchingAddress) return
        scope.launch {
            searchingAddress = true
            errorMessage = null
            when (val result = searchAddress(context, query)) {
                is AddressSearchResult.Success -> {
                    lat = result.address.latitude
                    lng = result.address.longitude
                    cameraTarget = LatLng(lat, lng)
                }
                AddressSearchResult.NotFound ->
                    errorMessage = context.getString(R.string.place_err_search_not_found)
                AddressSearchResult.Error ->
                    errorMessage = context.getString(R.string.place_err_search_network)
            }
            searchingAddress = false
        }
    }

    LaunchedEffect(placeId) {
        isLoading = true
        try {
            val user = SupabaseClient.getInstance().auth.currentUserOrNull()
            if (user == null) {
                errorMessage = context.getString(R.string.place_err_login)
                return@LaunchedEffect
            }
            val member = familyRepository.getUserFamily(user.id)
            if (member != null) {
                familyId = member.family_id
                val places = repository.getFamilyPlaces(member.family_id)
                if (placeId != null && placeId.isNotBlank()) {
                    val found = places.firstOrNull { it.id == placeId }
                    if (found != null) {
                        existing = found
                        name = found.name
                        type = found.type
                        lat = found.center_lat
                        lng = found.center_lon
                        radius = found.radius_meters
                        alertOnEnter = found.alert_on_enter ?: true
                        alertOnExit = found.alert_on_exit ?: true
                    }
                }
            }
            if (placeId.isNullOrBlank() || existing == null) {
                useMyLocation()
            } else {
                cameraTarget = LatLng(lat, lng)
            }
        } catch (e: Exception) {
            errorMessage = ErrorMessages.friendly(context, e, R.string.place_err_load)
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(cameraTarget) {
        val target = cameraTarget ?: return@LaunchedEffect
        cameraPositionState.animate(
            CameraUpdateFactory.newLatLngZoom(target, 15f)
        )
    }

    fun save() {
        val fId = familyId
        if (name.isBlank()) {
            errorMessage = context.getString(R.string.place_err_name)
            return
        }
        if (fId == null) {
            errorMessage = context.getString(R.string.place_err_family)
            return
        }
        scope.launch {
            saving = true
            errorMessage = null
            try {
                val uid = SupabaseClient.getInstance().auth.currentUserOrNull()?.id
                if (existing != null) {
                    repository.updatePlace(
                        existing!!.copy(
                            name = name.trim(),
                            type = type,
                            center_lat = lat,
                            center_lon = lng,
                            radius_meters = radius,
                            alert_on_enter = alertOnEnter,
                            alert_on_exit = alertOnExit
                        )
                    )
                } else {
                    val created = repository.createPlace(
                        Place(
                            family_id = fId,
                            name = name.trim(),
                            type = type,
                            center_lat = lat,
                            center_lon = lng,
                            radius_meters = radius,
                            alert_on_enter = alertOnEnter,
                            alert_on_exit = alertOnExit,
                            created_by = uid ?: ""
                        )
                    )
                    if (created == null) {
                        errorMessage = context.getString(R.string.place_err_geofence)
                        saving = false
                        return@launch
                    }
                }
                onSaved()
            } catch (e: Exception) {
                errorMessage = ErrorMessages.friendly(context, e, R.string.place_err_save)
            } finally {
                saving = false
            }
        }
    }

    fun delete() {
        val place = existing ?: return
        scope.launch {
            saving = true
            try {
                repository.deletePlace(place)
                onSaved()
            } catch (e: Exception) {
                errorMessage = ErrorMessages.friendly(context, e, R.string.place_err_delete)
            } finally {
                saving = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (existing != null) R.string.place_edit_title else R.string.place_new_title
                        )
                    )
                },
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
            if (isLoading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.place_field_name),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = { Text(stringResource(R.string.place_name_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.place_field_type),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "CASA", "ESCOLA", "TRABALHO", "IGRJA", "MERCADO",
                            "RESTAURANTE", "FARMACIA", "ACADEMIA", "SHOPPING", "OUTRO"
                        ).forEach { t ->
                            FilterChip(
                                selected = type == t,
                                onClick = { type = t },
                                label = { Text(placeTypeLabel(t)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = placeTypeIcon(t),
                                        contentDescription = stringResource(
                                            R.string.place_type_icon_cd,
                                            placeTypeLabel(t)
                                        ),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.place_field_address),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = addressQuery,
                        onValueChange = { addressQuery = it },
                        placeholder = { Text(stringResource(R.string.place_search_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { searchAddressByQuery() }),
                        trailingIcon = {
                            if (searchingAddress) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                IconButton(onClick = { searchAddressByQuery() }) {
                                    Icon(
                                        Icons.Filled.Search,
                                        contentDescription = stringResource(R.string.place_search_action)
                                    )
                                }
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.place_field_map),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .clip(RoundedCornerShape(16.dp))
                    ) {
                        GoogleMap(
                            modifier = Modifier.fillMaxSize(),
                            cameraPositionState = cameraPositionState,
                            uiSettings = MapUiSettings(
                                myLocationButtonEnabled = true,
                                zoomControlsEnabled = true,
                                compassEnabled = true,
                                mapToolbarEnabled = false
                            ),
                            properties = MapProperties(mapType = MapType.NORMAL),
                            onMapClick = { point ->
                                lat = point.latitude
                                lng = point.longitude
                            }
                        ) {
                            val center = LatLng(lat, lng)
                            Circle(
                                center = center,
                                radius = radius.toDouble(),
                                strokeColor = MaterialTheme.colorScheme.primary,
                                fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                strokeWidth = 3f
                            )
                            val markerIcon = placeIconDescriptor(
                                name = name,
                                type = type,
                                tint = MaterialTheme.colorScheme.primary.toArgb(),
                                densityFloat = LocalDensity.current.density
                            )
                            Marker(
                                state = MarkerState(position = center),
                                title = stringResource(R.string.place_marker_hint),
                                icon = markerIcon
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { useMyLocation() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.GpsFixed, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.place_use_my_location))
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.place_radius_alert),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = stringResource(R.string.place_radius_value, radius),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = radius.toFloat(),
                        onValueChange = { radius = it.toInt() },
                        valueRange = 100f..1000f,
                        steps = 17
                    )
                    Text(
                        text = stringResource(R.string.place_radius_range),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.place_alerts),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.place_alert_enter),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(
                            checked = alertOnEnter,
                            onCheckedChange = { alertOnEnter = it }
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.place_alert_exit),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(
                            checked = alertOnExit,
                            onCheckedChange = { alertOnExit = it }
                        )
                    }

                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMessage ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = { save() },
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.Filled.Place, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                stringResource(
                                    if (existing != null) R.string.place_save_changes else R.string.place_save
                                )
                            )
                        }
                    }

                    if (existing != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { delete() },
                            enabled = !saving,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.place_delete))
                        }
                    }
                }
            }
        }
    }
}

private sealed class AddressSearchResult {
    data class Success(val address: android.location.Address) : AddressSearchResult()
    data object NotFound : AddressSearchResult()
    data object Error : AddressSearchResult()
}

/**
 * Busca de endereço via Geocoder do Android (mesmo padrão de MemberCard).
 * API 33+ (TIRAMISU): versão assíncrona com GeocodeListener.
 * Abaixo de 33: versão síncrona deprecada dentro de Dispatchers.IO.
 */
private suspend fun searchAddress(
    context: android.content.Context,
    query: String
): AddressSearchResult {
    val geocoder = Geocoder(context, Locale.getDefault())
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        var errored = false
        var resumed = false
        val address: android.location.Address? = try {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocationName(
                    query.trim(), 1,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: List<android.location.Address>) {
                            if (!resumed) {
                                resumed = true
                                if (cont.isActive) cont.resume(addresses.firstOrNull())
                            }
                        }

                        override fun onError(errorMessage: String?) {
                            if (!resumed) {
                                resumed = true
                                errored = true
                                if (cont.isActive) cont.resume(null)
                            }
                        }
                    }
                )
            }
        } catch (e: Exception) {
            errored = true
            null
        }
        when {
            address != null -> AddressSearchResult.Success(address)
            errored -> AddressSearchResult.Error
            else -> AddressSearchResult.NotFound
        }
    } else {
        withContext(Dispatchers.IO) {
            try {
                @Suppress("DEPRECATION")
                val address = geocoder.getFromLocationName(query.trim(), 1)?.firstOrNull()
                if (address != null) AddressSearchResult.Success(address)
                else AddressSearchResult.NotFound
            } catch (e: Exception) {
                AddressSearchResult.Error
            }
        }
    }
}