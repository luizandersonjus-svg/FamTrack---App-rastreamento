package com.famtrack.app.ui.geofence

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.work.*
import com.famtrack.app.data.model.Geofence
import com.famtrack.app.data.remote.FamilyRepository
import com.famtrack.app.data.remote.GeofenceRepository
import com.famtrack.app.data.remote.SupabaseClient
import com.famtrack.app.service.GeofenceWorker
import com.famtrack.app.service.LocationService
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeofenceScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val geofenceRepository = remember { GeofenceRepository() }
    val familyRepository = remember { FamilyRepository() }

    var geofences by remember { mutableStateOf<List<Geofence>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showCreateFamilyDialog by remember { mutableStateOf(false) }
    var showJoinFamilyDialog by remember { mutableStateOf(false) }
    var familyId by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var hasFamily by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        try {
            val client = SupabaseClient.getInstance()
            val user = client.auth.currentUserOrNull()
            if (user != null) {
                try {
                    val member = familyRepository.getUserFamily(user.id)
                    if (member != null) {
                        familyId = member.family_id
                        hasFamily = true
                        geofences = geofenceRepository.getFamilyGeofences(member.family_id)
                    } else {
                        hasFamily = false
                    }
                } catch (e: Exception) {
                    hasFamily = false
                    android.util.Log.e("GeofenceScreen", "Error getting family", e)
                }
            }
        } catch (e: Exception) {
            errorMessage = e.message
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Geofences") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Voltar",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            if (hasFamily) {
                FloatingActionButton(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(
                                context, Manifest.permission.ACCESS_FINE_LOCATION
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        }
                        showCreateDialog = true
                    },
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Adicionar Geofence")
                }
            }
        }
    ) { paddingValues ->
        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            !hasFamily -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(
                            Icons.Default.FamilyRestroom,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Voce ainda nao tem uma familia",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Crie uma familia para poder usar geofences",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { showCreateFamilyDialog = true }
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Criar Familia")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { showJoinFamilyDialog = true }
                        ) {
                            Icon(Icons.Default.GroupAdd, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                                                       Text("Entrar em Familia")
                        }
                        if (errorMessage != null) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = errorMessage ?: "",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
            errorMessage != null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Erro ao carregar geofences",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMessage ?: "Erro desconhecido",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            geofences.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.LocationOff,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Nenhum geofence configurado",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Toque no + para adicionar uma area protegida",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    items(geofences) { geofence ->
                        GeofenceItem(
                            geofence = geofence,
                            onToggle = { active ->
                                scope.launch {
                                    geofenceRepository.toggleGeofence(geofence.id!!, active)
                                    geofences = geofences.map {
                                        if (it.id == geofence.id) it.copy(active = active) else it
                                    }
                                    scheduleGeofenceCheck(context)
                                }
                            },
                            onDelete = {
                                scope.launch {
                                    geofenceRepository.deleteGeofence(geofence.id!!)
                                    geofences = geofences.filter { it.id != geofence.id }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog && familyId != null) {
        CreateGeofenceDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, lat, lon, radius ->
                scope.launch {
                    try {
                        val newGeofence = geofenceRepository.createGeofence(
                            Geofence(
                                family_id = familyId!!,
                                name = name,
                                center_lat = lat,
                                center_lon = lon,
                                radius_meters = radius
                            )
                        )
                        geofences = geofences + newGeofence
                        showCreateDialog = false
                        scheduleGeofenceCheck(context)
                    } catch (e: Exception) {
                        errorMessage = e.message
                    }
                }
            }
        )
    }

    if (showCreateFamilyDialog) {
        CreateFamilyDialog(
            onDismiss = { showCreateFamilyDialog = false },
            onCreate = { familyName ->
                scope.launch {
                    try {
                        val user = SupabaseClient.getInstance().auth.currentUserOrNull()
                        if (user != null) {
                            val family = familyRepository.createFamily(
                                name = familyName
                            )
                            familyId = family.id
                            hasFamily = true
                            showCreateFamilyDialog = false
                            geofences = geofenceRepository.getFamilyGeofences(family.id)
                            // Inicia o compartilhamento de localização
                            if (ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.ACCESS_FINE_LOCATION
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                LocationService.startService(context, family.id, user.id)
                            }
                        }
                    } catch (e: Exception) {
                        errorMessage = "Erro ao criar familia: ${e.message}"
                        showCreateFamilyDialog = false
                    }
                }
            }
        )
    }

    if (showJoinFamilyDialog) {
        JoinFamilyDialog(
            onDismiss = { showJoinFamilyDialog = false },
            onJoin = { inviteCode ->
                scope.launch {
                    try {
                        val user = SupabaseClient.getInstance().auth.currentUserOrNull()
                        if (user != null) {
                            val family = familyRepository.joinFamilyByCode(inviteCode)
                            familyId = family.id
                            hasFamily = true
                            showJoinFamilyDialog = false
                            geofences = geofenceRepository.getFamilyGeofences(family.id)
                            if (ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.ACCESS_FINE_LOCATION
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                LocationService.startService(context, family.id, user.id)
                            }
                        }
                    } catch (e: Exception) {
                        errorMessage = "Erro ao entrar na familia: ${e.message}"
                        showJoinFamilyDialog = false
                    }
                }
            }
        )
    }
}

fun scheduleGeofenceCheck(context: android.content.Context) {
    val workRequest = PeriodicWorkRequestBuilder<GeofenceWorker>(
        15, TimeUnit.MINUTES
    ).setConstraints(
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    ).build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "geofence_check",
        ExistingPeriodicWorkPolicy.UPDATE,
        workRequest
    )
}

@Composable
fun GeofenceItem(
    geofence: Geofence,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.LocationOn,
                contentDescription = null,
                tint = if (geofence.active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = geofence.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Raio: ${geofence.radius_meters.toInt()}m",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = geofence.active,
                onCheckedChange = onToggle
            )
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Excluir",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun CreateGeofenceDialog(
    onDismiss: () -> Unit,
    onCreate: (String, Double, Double, Double) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var latitude by remember { mutableStateOf("") }
    var longitude by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf("100") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Novo Geofence") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = latitude,
                    onValueChange = { latitude = it },
                    label = { Text("Latitude") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = longitude,
                    onValueChange = { longitude = it },
                    label = { Text("Longitude") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = radius,
                    onValueChange = { radius = it },
                    label = { Text("Raio (metros)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val lat = latitude.toDoubleOrNull() ?: return@TextButton
                    val lon = longitude.toDoubleOrNull() ?: return@TextButton
                    val r = radius.toDoubleOrNull() ?: 100.0
                    onCreate(name, lat, lon, r)
                },
                enabled = name.isNotBlank() && latitude.isNotBlank() && longitude.isNotBlank()
            ) {
                Text("Criar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
fun CreateFamilyDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var familyName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Criar Familia") },
        text = {
            Column {
                Text("Crie uma familia para poder usar geofences e compartilhar localizacao.")
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = familyName,
                    onValueChange = { familyName = it },
                    label = { Text("Nome da Familia") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Depois de criar, um codigo de convite sera gerado. Compartilhe com quem " +
                        "voce quer incluir na familia.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(familyName) },
                enabled = familyName.isNotBlank()
            ) {
                Text("Criar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
fun JoinFamilyDialog(
    onDismiss: () -> Unit,
    onJoin: (String) -> Unit
) {
    var inviteCode by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Entrar em Familia") },
        text = {
            Column {
                Text("Digite o codigo de convite recebido de um membro da familia.")
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = inviteCode,
                    onValueChange = { inviteCode = it.uppercase() },
                    label = { Text("Codigo de Convite") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onJoin(inviteCode) },
                enabled = inviteCode.isNotBlank()
            ) {
                Text("Entrar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}
