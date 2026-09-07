package com.famtrack.app.feature.routes

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.famtrack.app.R
import com.famtrack.app.data.remote.FamilyRepository
import com.famtrack.app.data.remote.SupabaseClient
import com.famtrack.app.feature.places.Place
import com.famtrack.app.feature.places.PlacesRepository
import com.google.android.gms.location.LocationServices
import io.github.jan.supabase.auth.auth

private const val TAG = "FamTrackRoutes"
private const val MAPS_PACKAGE_NAME = "com.google.android.apps.maps"

/**
 * Pesquisa simples de rotas: monta o link do Google Maps e abre no app de
 * mapas (ou no navegador). Não usa Directions/Routes/Places API.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteSearchScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var useCurrentLocation by remember { mutableStateOf(true) }
    var originText by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf("") }
    var places by remember { mutableStateOf<List<Place>>(emptyList()) }
    var currentLocation by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    // Usa a última localização conhecida apenas se o app já tiver permissão.
    val hasFineLocation = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    LaunchedEffect(Unit) {
        if (hasFineLocation) {
            LocationServices.getFusedLocationProviderClient(context)
                .lastLocation
                .addOnSuccessListener { location ->
                    if (location != null) {
                        currentLocation = location.latitude to location.longitude
                    }
                }
        }
        try {
            val user = SupabaseClient.getInstance().auth.currentUserOrNull() ?: return@LaunchedEffect
            val member = FamilyRepository().getUserFamily(user.id) ?: return@LaunchedEffect
            places = PlacesRepository().getFamilyPlaces(member.family_id)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun openMaps() {
        if (destination.isBlank()) {
            toast(context.getString(R.string.routes_dest_required))
            return
        }
        val originParam: String? = if (useCurrentLocation) {
            val ll = currentLocation
            if (ll == null) {
                toast(context.getString(R.string.routes_no_current_location))
                return
            }
            String.format(java.util.Locale.US, "%.6f,%.6f", ll.first, ll.second)
        } else {
            if (originText.isBlank()) {
                toast(context.getString(R.string.routes_origin_required))
                return
            }
            originText.trim()
        }
        // Origem/destino codificados explicitamente (espaços, acentos, vírgulas).
        val destinationEncoded = Uri.encode(destination.trim())
        val originEncoded = originParam?.let { Uri.encode(it) }
        val url = if (originEncoded != null) {
            "https://www.google.com/maps/dir/?api=1&origin=$originEncoded" +
                "&destination=$destinationEncoded&travelmode=driving"
        } else {
            // Origem vazia: o Maps usa a localização atual do próprio app.
            "https://www.google.com/maps/dir/?api=1&destination=$destinationEncoded" +
                "&travelmode=driving"
        }
        Log.d(TAG, "Google Maps URL: $url")
        val mapsUri = Uri.parse(url)

        // Cascata de tentativas (sem pré-verificação: package visibility filtra
        // getPackageInfo/resolveActivity no Android 11+):
        // 1) Google Maps explícito  2) intent implícita  3) navegador
        // 4) esquema geo.
        val targets = listOf(
            Triple(
                Intent(Intent.ACTION_VIEW, mapsUri).apply {
                    setPackage(MAPS_PACKAGE_NAME)
                },
                R.string.routes_opening_google_maps,
                "Google Maps"
            ),
            Triple(
                Intent(Intent.ACTION_VIEW, mapsUri),
                R.string.routes_opening_google_maps,
                "Intent implícita"
            ),
            Triple(
                Intent(Intent.ACTION_VIEW, mapsUri).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                },
                R.string.routes_opening_browser,
                "Navegador"
            ),
            Triple(
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$destinationEncoded")),
                R.string.routes_opening_google_maps,
                "geo"
            )
        )
        var started = false
        targets.forEachIndexed { index, (intent, successRes, label) ->
            if (!started) {
                try {
                    context.startActivity(intent)
                    started = true
                    toast(context.getString(successRes))
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG, "Tentativa $index ($label): ActivityNotFoundException", e)
                } catch (e: SecurityException) {
                    Log.w(TAG, "Tentativa $index ($label): SecurityException", e)
                } catch (e: Exception) {
                    Log.w(TAG, "Tentativa $index ($label): Exception", e)
                }
            }
        }
        if (!started) {
            toast(context.getString(R.string.routes_err_no_map_app))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.routes_title)) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(R.string.routes_origin_label),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.MyLocation,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.routes_origin_current),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = when {
                                !useCurrentLocation -> stringResource(R.string.routes_no_current_location)
                                currentLocation != null -> stringResource(R.string.routes_current_ready)
                                hasFineLocation -> stringResource(R.string.routes_origin_acquiring)
                                else -> stringResource(R.string.routes_no_current_location)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = useCurrentLocation,
                        onCheckedChange = { useCurrentLocation = it }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = originText,
                onValueChange = { originText = it },
                enabled = !useCurrentLocation,
                label = { Text(stringResource(R.string.routes_origin_address)) },
                placeholder = { Text(stringResource(R.string.routes_origin_address)) },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.routes_destination_label),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            OutlinedTextField(
                value = destination,
                onValueChange = { destination = it },
                label = { Text(stringResource(R.string.routes_dest_place_hint)) },
                modifier = Modifier.fillMaxWidth()
            )

            if (places.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.routes_dest_tip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (places.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.routes_dest_place_pick),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    places.forEach { place ->
                        FilterChip(
                            selected = destination == "${place.center_lat},${place.center_lon}",
                            onClick = { destination = "${place.center_lat},${place.center_lon}" },
                            label = { Text(place.name, maxLines = 1) },
                            leadingIcon = {
                                Icon(Icons.Default.Place, contentDescription = null)
                            },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { openMaps() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    Icons.Default.NearMe,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(stringResource(R.string.routes_open_maps), fontSize = 16.sp)
            }
        }
    }
}