package com.famtrack.app.feature.delivery

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.famtrack.app.feature.common.ErrorMessages
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Cadastro de pacote: número do pacote (vai na bandeira), rua, número da
 * casa/comércio, tipo de local e observação. "Localizar no mapa" acha o
 * endereço; tocar no mapa ajusta a posição da bandeira. Depois de salvar,
 * a tela fica pronta para o próximo pacote.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeliveryEditScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { DeliveryRepository() }
    val density = LocalDensity.current.density

    var packageNumber by rememberSaveable { mutableStateOf("") }
    var street by rememberSaveable { mutableStateOf("") }
    var houseNumber by rememberSaveable { mutableStateOf("") }
    var city by rememberSaveable { mutableStateOf(loadDeliveryCity(context)) }
    var placeType by rememberSaveable { mutableStateOf(PLACE_RESIDENCIA) }
    var note by rememberSaveable { mutableStateOf("") }

    var latitude by rememberSaveable { mutableStateOf<Double?>(null) }
    var longitude by rememberSaveable { mutableStateOf<Double?>(null) }
    // Endereço que gerou a posição atual: mudou o texto, pede nova busca.
    var locatedFor by rememberSaveable { mutableStateOf("") }

    var isSearching by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var searchMessage by remember { mutableStateOf<String?>(null) }
    var addedCount by rememberSaveable { mutableStateOf(0) }

    val cameraPositionState = rememberCameraPositionState()
    val position = latitude?.let { lat -> longitude?.let { lon -> LatLng(lat, lon) } }

    fun addressQuery(): String = listOf(
        listOf(street.trim(), houseNumber.trim()).filter { it.isNotEmpty() }.joinToString(", "),
        city.trim()
    ).filter { it.isNotEmpty() }.joinToString(", ")

    val addressChanged = position != null && locatedFor != addressQuery()
    val canSearch = street.isNotBlank() && !isSearching
    val canSave = packageNumber.isNotBlank() && street.isNotBlank() && position != null && !isSaving

    // Mantém a câmera na bandeira sempre que a posição muda.
    LaunchedEffect(latitude, longitude) {
        val target = position ?: return@LaunchedEffect
        try {
            // Atribuição direta: funciona mesmo antes de o mapa desta tela existir.
            cameraPositionState.position = CameraPosition.fromLatLngZoom(target, 17f)
        } catch (e: Exception) {
            ErrorMessages.logSafe(e, "mover câmera do cadastro")
        }
    }

    fun locate() {
        if (!canSearch) return
        val query = addressQuery()
        isSearching = true
        searchMessage = null
        scope.launch {
            try {
                when (val result = geocodeDeliveryAddress(context, query)) {
                    is GeocodeResult.Found -> {
                        latitude = result.position.latitude
                        longitude = result.position.longitude
                        locatedFor = query
                        saveDeliveryCity(context, city)
                        searchMessage = "Endereço encontrado. Toque no mapa para ajustar a bandeira."
                    }
                    GeocodeResult.NotFound ->
                        searchMessage = "Endereço não encontrado. Confira a rua e a cidade."
                    GeocodeResult.Error ->
                        searchMessage = "Busca indisponível agora. Verifique a internet e tente de novo."
                }
            } finally {
                isSearching = false
            }
        }
    }

    fun save() {
        val target = position ?: return
        if (!canSave) return
        isSaving = true
        scope.launch {
            try {
                val familyId = repository.currentFamilyId()
                if (familyId == null) {
                    Toast.makeText(context, "Entre em uma família para cadastrar pacotes.", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val existing = repository.list(familyId)
                val nextOrder = (existing.maxOfOrNull { it.route_order } ?: 0) + 1
                repository.add(
                    Delivery(
                        family_id = familyId,
                        package_number = packageNumber,
                        street = street,
                        house_number = houseNumber,
                        place_type = placeType,
                        note = note,
                        latitude = target.latitude,
                        longitude = target.longitude
                    ),
                    nextOrder
                )
                saveDeliveryCity(context, city)
                Toast.makeText(context, "Pacote ${packageNumber.trim()} adicionado à rota.", Toast.LENGTH_SHORT).show()
                addedCount += 1
                // Pronto para o próximo pacote: mantém cidade e tipo.
                packageNumber = ""
                street = ""
                houseNumber = ""
                note = ""
                latitude = null
                longitude = null
                locatedFor = ""
                searchMessage = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorMessages.logSafe(e, "salvar entrega")
                Toast.makeText(context, "Não foi possível salvar. Verifique a internet.", Toast.LENGTH_SHORT).show()
            } finally {
                isSaving = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Adicionar pacote") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar ao mapa")
                    }
                },
                actions = {
                    TextButton(onClick = onNavigateBack) { Text("Ver mapa") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = packageNumber,
                    onValueChange = { value -> packageNumber = value.take(20) },
                    label = { Text("Número do pacote") },
                    supportingText = { Text("Aparece na bandeira do mapa.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next
                    ),
                    textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f)
                )
                DeliveryFlagChip(
                    number = packageNumber.trim().ifEmpty { "?" }.take(4),
                    delivered = false
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = street,
                    onValueChange = { value -> street = value.take(200) },
                    label = { Text("Rua") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = houseNumber,
                    onValueChange = { value -> houseNumber = value.take(20) },
                    label = { Text("Nº") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier.width(104.dp)
                )
            }

            OutlinedTextField(
                value = city,
                onValueChange = { value -> city = value.take(80) },
                label = { Text("Cidade") },
                supportingText = { Text("Usada para achar a rua certa. Fica salva para os próximos.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Tipo de local",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = placeType == PLACE_RESIDENCIA,
                        onClick = { placeType = PLACE_RESIDENCIA },
                        label = { Text("Residência") },
                        leadingIcon = {
                            Icon(Icons.Filled.Home, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    )
                    FilterChip(
                        selected = placeType == PLACE_COMERCIO,
                        onClick = { placeType = PLACE_COMERCIO },
                        label = { Text("Comércio") },
                        leadingIcon = {
                            Icon(Icons.Filled.Storefront, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    )
                }
            }

            OutlinedButton(
                onClick = { locate() },
                enabled = canSearch,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                if (isSearching) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(if (position == null) "Localizar no mapa" else "Localizar de novo")
            }

            val message = when {
                addressChanged -> "O endereço mudou. Toque em \"Localizar de novo\" ou ajuste a bandeira no mapa."
                else -> searchMessage
            }
            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (position != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(12.dp))
                ) {
                    GoogleMap(
                        modifier = Modifier.fillMaxSize(),
                        cameraPositionState = cameraPositionState,
                        uiSettings = MapUiSettings(
                            zoomControlsEnabled = false,
                            myLocationButtonEnabled = false,
                            mapToolbarEnabled = false
                        ),
                        properties = MapProperties(mapType = MapType.NORMAL),
                        onMapClick = { point ->
                            latitude = point.latitude
                            longitude = point.longitude
                            // Posição escolhida à mão vale para o endereço atual.
                            locatedFor = addressQuery()
                        }
                    ) {
                        val label = if (houseNumber.isBlank()) "" else
                            (if (placeType == PLACE_COMERCIO) "comércio " else "casa ") + houseNumber.trim()
                        val icon = rememberDeliveryFlagIcon(
                            number = packageNumber.trim().ifEmpty { "?" },
                            label = label,
                            delivered = false,
                            selected = false,
                            density = density
                        )
                        if (icon != null) {
                            Marker(
                                state = MarkerState(position = position),
                                icon = icon.descriptor,
                                anchor = icon.anchor
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = note,
                onValueChange = { value -> note = value.take(300) },
                label = { Text("Observação (opcional)") },
                placeholder = { Text("Ex.: deixar com o porteiro") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = { save() },
                enabled = canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(if (isSaving) "Salvando…" else "Adicionar à rota")
            }

            if (position == null) {
                Text(
                    "Para adicionar, primeiro localize o endereço no mapa.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (addedCount > 0) {
                Text(
                    text = if (addedCount == 1) "1 pacote adicionado nesta sessão."
                    else "$addedCount pacotes adicionados nesta sessão.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
