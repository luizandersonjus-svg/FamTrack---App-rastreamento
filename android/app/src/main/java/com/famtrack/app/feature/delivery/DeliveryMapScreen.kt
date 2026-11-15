package com.famtrack.app.feature.delivery

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.famtrack.app.feature.common.ErrorMessages
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Intervalo de atualização: mostra as entregas marcadas pelo outro aparelho. */
private const val REFRESH_INTERVAL_MS = 15_000L

/**
 * Mapa das entregas: uma bandeira por pacote, com o número do pacote.
 * Quadriculada = pendente; inteiramente verde = entregue. Tocar na bandeira
 * abre o painel com o endereço e os botões Navegar / Marcar como entregue.
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
    val repository = remember { DeliveryRepository() }
    val density = LocalDensity.current.density

    var familyId by remember { mutableStateOf<String?>(null) }
    var deliveries by remember { mutableStateOf<List<Delivery>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var mapLoaded by remember { mutableStateOf(false) }
    var cameraFitted by remember { mutableStateOf(false) }

    val cameraPositionState = rememberCameraPositionState()

    val hasFineLocation = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

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

    val routeOrdered = deliveries.inRouteOrder()
    val total = routeOrdered.size
    val doneCount = routeOrdered.count { it.delivered }
    val nextPending = routeOrdered.firstOrNull { !it.delivered }
    val selected = routeOrdered.firstOrNull { it.id == selectedId } ?: nextPending ?: routeOrdered.lastOrNull()

    // Enquadra todas as bandeiras na primeira vez que há mapa e dados.
    LaunchedEffect(mapLoaded, total) {
        if (mapLoaded && !cameraFitted && total > 0) {
            try {
                if (total == 1) {
                    val only = routeOrdered.first()
                    cameraPositionState.move(
                        CameraUpdateFactory.newLatLngZoom(LatLng(only.latitude, only.longitude), 16f)
                    )
                } else {
                    val bounds = LatLngBounds.Builder()
                    routeOrdered.forEach { bounds.include(LatLng(it.latitude, it.longitude)) }
                    cameraPositionState.move(
                        CameraUpdateFactory.newLatLngBounds(bounds.build(), (72 * density).toInt())
                    )
                }
                cameraFitted = true
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
                // Depois de entregar, o painel passa para a próxima pendente.
                if (newValue) selectedId = null
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
                        myLocationButtonEnabled = hasFineLocation,
                        mapToolbarEnabled = false
                    ),
                    properties = MapProperties(
                        mapType = MapType.NORMAL,
                        isMyLocationEnabled = hasFineLocation
                    ),
                    onMapLoaded = { mapLoaded = true }
                ) {
                    routeOrdered.filter { it.id != null }.forEach { delivery ->
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
                                        selectedId = id
                                        true
                                    }
                                )
                            }
                        }
                    }
                }

                if (total > 0) {
                    FlagLegend(modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
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
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when {
                        selected != null -> {
                            ProgressHeader(
                                doneCount = doneCount,
                                total = total,
                                stopNumber = routeOrdered.indexOfFirst { it.id == selected.id } + 1
                            )
                            SelectedDeliveryInfo(selected)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        if (!openDeliveryNavigation(context, selected)) {
                                            Toast.makeText(
                                                context,
                                                "Nenhum app de mapas encontrado.",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    },
                                    modifier = Modifier.height(48.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.NearMe,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Navegar")
                                }
                                if (selected.delivered) {
                                    OutlinedButton(
                                        onClick = { toggleDelivered(selected) },
                                        enabled = !isSaving,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                    ) {
                                        Text("Desfazer entrega")
                                    }
                                } else {
                                    Button(
                                        onClick = { toggleDelivered(selected) },
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

                        isLoading -> {
                            Text(
                                "Carregando entregas…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        loadFailed -> {
                            Text(
                                "Não foi possível carregar as entregas. Verifique a internet; " +
                                    "a tela tenta de novo sozinha.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        familyId == null -> {
                            Text(
                                "Entre em uma família para usar as entregas.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        else -> {
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

@Composable
private fun ProgressHeader(doneCount: Int, total: Int, stopNumber: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "$doneCount de $total entregues",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${stopNumber}ª parada",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End
            )
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

@Composable
private fun SelectedDeliveryInfo(delivery: Delivery) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DeliveryFlagChip(number = delivery.package_number, delivered = delivery.delivered)
            Column {
                Text(
                    text = "Pacote ${delivery.package_number}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = delivery.placeTypeLabel + " · " +
                        (if (delivery.delivered) "Entregue" else "Pendente"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(text = delivery.addressLine, style = MaterialTheme.typography.bodyLarge)
        val note = delivery.note?.trim().orEmpty()
        if (note.isNotEmpty()) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FlagLegend(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        shadowElevation = 3.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DeliveryFlagSwatch(delivered = false)
            Text("Pendente", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(4.dp))
            DeliveryFlagSwatch(delivered = true)
            Text("Entregue", style = MaterialTheme.typography.bodySmall)
        }
    }
}
