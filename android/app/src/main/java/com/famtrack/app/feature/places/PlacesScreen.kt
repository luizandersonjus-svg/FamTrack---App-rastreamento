package com.famtrack.app.feature.places

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Church
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.LocalMall
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Work
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
import androidx.compose.ui.unit.sp
import com.famtrack.app.R
import com.famtrack.app.data.remote.SupabaseClient
import com.famtrack.app.feature.common.ErrorMessages
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.launch

@Composable
fun placeTypeLabel(type: String): String = stringResource(
    when (type) {
        "CASA" -> R.string.place_type_casa
        "ESCOLA" -> R.string.place_type_escola
        "TRABALHO" -> R.string.place_type_trabalho
        "IGRJA" -> R.string.place_type_igreja
        "MERCADO" -> R.string.place_type_mercado
        "RESTAURANTE" -> R.string.place_type_restaurante
        "FARMACIA" -> R.string.place_type_farmacia
        "ACADEMIA" -> R.string.place_type_academia
        "SHOPPING" -> R.string.place_type_shopping
        else -> R.string.place_type_outro
    }
)

@Composable
fun placeTypeIcon(type: String): ImageVector = when (type) {
    "CASA" -> Icons.Filled.Home
    "ESCOLA" -> Icons.Filled.School
    "TRABALHO" -> Icons.Filled.Work
    "IGRJA" -> Icons.Filled.Church
    "MERCADO" -> Icons.Filled.LocalGroceryStore
    "RESTAURANTE" -> Icons.Filled.Restaurant
    "FARMACIA" -> Icons.Filled.LocalPharmacy
    "ACADEMIA" -> Icons.Filled.FitnessCenter
    "SHOPPING" -> Icons.Filled.LocalMall
    else -> Icons.Filled.Place
}

/**
 * Lista de locais salvos da família, com estado vazio ilustrado.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacesScreen(
    onNavigateBack: () -> Unit,
    onNavigateToCreate: () -> Unit,
    onNavigateToEdit: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val repository = remember { PlacesRepository() }
    val familyRepository = remember { com.famtrack.app.data.remote.FamilyRepository() }
    val context = LocalContext.current

    var places by remember { mutableStateOf<List<Place>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var familyId by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            isLoading = true
            error = null
            try {
                val user = SupabaseClient.getInstance().auth.currentUserOrNull()
                if (user != null) {
                    val member = familyRepository.getUserFamily(user.id)
                    if (member != null) {
                        familyId = member.family_id
                        places = repository.getFamilyPlaces(member.family_id)
                    }
                }
            } catch (e: Exception) {
                error = ErrorMessages.friendly(context, e, R.string.place_err_load)
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.places_title)) },
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
        },
        floatingActionButton = {
            if (!isLoading && error == null) {
                FloatingActionButton(
                    onClick = onNavigateToCreate,
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.place_new))
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                error != null -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Filled.Place,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.places_load_error),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = error ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { load() }) {
                            Text(stringResource(R.string.places_retry))
                        }
                    }
                }
                places.isEmpty() -> {
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
                            text = stringResource(R.string.places_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.places_empty_text),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(onClick = onNavigateToCreate) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.place_new))
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(places, key = { it.id ?: it.name }) { place ->
                            Card(
                                onClick = { onNavigateToEdit(place.id ?: "") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        placeTypeIcon(place.type),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = place.name,
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Text(
                                            text = stringResource(
                                                R.string.place_item_detail,
                                                placeTypeLabel(place.type),
                                                place.radius_meters
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                try {
                                                    repository.deletePlace(place)
                                                    places = places.filter { it.id != place.id }
                                                } catch (e: Exception) {
                                                    ErrorMessages.logSafe(e, "excluir place da lista")
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = stringResource(R.string.place_delete_cd),
                                            tint = MaterialTheme.colorScheme.error
                                        )
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