package com.famtrack.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import coil.compose.AsyncImage
import com.famtrack.app.data.remote.FamilyRepository
import com.famtrack.app.data.remote.HealthConnectRepository
import com.famtrack.app.data.remote.HeartRateSummary
import com.famtrack.app.data.remote.SupabaseClient
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onLogout: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var showProfileDialog by remember { mutableStateOf(false) }
    var showFamilyDialog by remember { mutableStateOf(false) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var showDevicesDialog by remember { mutableStateOf(false) }

    if (showProfileDialog) {
        ProfileDialog(onDismiss = { showProfileDialog = false })
    }
    if (showFamilyDialog) {
        FamilyDialog(onDismiss = { showFamilyDialog = false })
    }
    if (showPrivacyDialog) {
        PrivacyDialog(onDismiss = { showPrivacyDialog = false })
    }
    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }
    if (showHelpDialog) {
        HelpDialog(onDismiss = { showHelpDialog = false })
    }
    if (showDevicesDialog) {
        DevicesDialog(onDismiss = { showDevicesDialog = false })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configuracoes") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Voltar",
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
        ) {
            Text(
                text = "Conta",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column {
                    SettingsItem(
                        icon = Icons.Default.Person,
                        title = "Perfil",
                        subtitle = "Gerenciar suas informacoes",
                        onClick = { showProfileDialog = true }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsItem(
                        icon = Icons.Default.FamilyRestroom,
                        title = "Familia",
                        subtitle = "Gerenciar membros da familia",
                        onClick = { showFamilyDialog = true }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsItem(
                        icon = Icons.Default.Security,
                        title = "Privacidade",
                        subtitle = "Configuracoes de privacidade",
                        onClick = { showPrivacyDialog = true }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Dispositivos",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                SettingsItem(
                    icon = Icons.Default.Watch,
                    title = "Conectar dispositivos",
                    subtitle = "Parear relogios e wearables usados pela familia",
                    onClick = { showDevicesDialog = true }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Notificacoes",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column {
                    var notificationsEnabled by remember { mutableStateOf(true) }
                    SettingsSwitchItem(
                        icon = Icons.Default.Notifications,
                        title = "Notificacoes push",
                        subtitle = "Receber alertas de SOS e geofences",
                        checked = notificationsEnabled,
                        onCheckedChange = { notificationsEnabled = it }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    var locationSharing by remember { mutableStateOf(true) }
                    SettingsSwitchItem(
                        icon = Icons.Default.LocationOn,
                        title = "Compartilhar localizacao",
                        subtitle = "Permitir que membros vejam sua posicao",
                        checked = locationSharing,
                        onCheckedChange = { locationSharing = it }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Sobre",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column {
                    SettingsItem(
                        icon = Icons.Default.Info,
                        title = "Sobre o FamTrack",
                        subtitle = "Versao 1.0.0",
                        onClick = { showAboutDialog = true }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsItem(
                        icon = Icons.AutoMirrored.Filled.Help,
                        title = "Ajuda",
                        subtitle = "Duvidas e suporte",
                        onClick = { showHelpDialog = true }
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    scope.launch {
                        SupabaseClient.getInstance().auth.signOut()
                        onLogout()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Logout,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text("Sair da conta", fontSize = 16.sp)
            }
        }
    }
}

/**
 * Diálogo de PERFIL: mostra o e-mail e permite EDITAR o nome
 * (o "nickname" que aparece no pino do mapa).
 */
@Composable
fun ProfileDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val familyRepository = remember { FamilyRepository() }
    val user = SupabaseClient.getInstance().auth.currentUserOrNull()

    // Campo do nome; começa vazio e tenta carregar o nickname atual
    var nameField by remember { mutableStateOf("") }
    var loadedName by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    // Foto do perfil atual (vem do Google em userMetadata, ou de family_members)
    var avatarUrl by remember { mutableStateOf<String?>(null) }

    // Ao abrir, carrega o nickname e o avatar atual do usuário (se existir)
    LaunchedEffect(user?.id) {
        val uid = user?.id ?: return@LaunchedEffect
        try {
            val current = familyRepository.getMyNickname(uid)
            if (current != null) {
                nameField = current
            } else {
                // Se ainda não tem nickname, sugere o nome do Google (se houver)
                nameField = user.userMetadata?.get("full_name")?.toString() ?: ""
            }
        } catch (e: Exception) {
            // ignora — deixa o campo vazio
        }
        // Avatar: primeiro tenta o do Google, depois o salvo na família
        avatarUrl = user.userMetadata?.get("avatar_url")?.toString()
            ?: user.userMetadata?.get("picture")?.toString()
        if (avatarUrl == null) {
            try {
                avatarUrl = familyRepository.getUserFamily(uid)?.avatar_url
            } catch (e: Exception) {
                // ignora — fica sem foto
            }
        }
        loadedName = true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Perfil") },
        text = {
            Column {
                Text("Email: ${user?.email ?: "Nao disponivel"}")
                Spacer(modifier = Modifier.height(12.dp))
                val avatar = avatarUrl
                if (avatar != null) {
                    AsyncImage(
                        model = avatar,
                        contentDescription = "Foto do perfil",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                } else {
                    androidx.compose.material3.Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Person,
                        contentDescription = "Sem foto",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(80.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = nameField,
                    onValueChange = { nameField = it },
                    label = { Text("Seu nome") },
                    placeholder = { Text("Como seus familiares vao te ver") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    if (nameField.isBlank()) {
                        Toast.makeText(context, "Digite um nome", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    saving = true
                    scope.launch {
                        try {
                            familyRepository.updateMyNickname(nameField.trim())
                            Toast.makeText(context, "Nome atualizado!", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        } catch (e: Exception) {
                            Toast.makeText(context, "Erro ao salvar: ${e.message}", Toast.LENGTH_LONG).show()
                            saving = false
                        }
                    }
                }
            ) {
                Text(if (saving) "Salvando..." else "Salvar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

/**
 * Diálogo de FAMÍLIA: mostra o código de convite (com botão copiar)
 * e permite renomear a família (se você for o criador).
 */
@Composable
fun FamilyDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val familyRepository = remember { FamilyRepository() }

    val user = SupabaseClient.getInstance().auth.currentUserOrNull()
    var familyName by remember { mutableStateOf<String?>(null) }
    var inviteCode by remember { mutableStateOf<String?>(null) }
    var familyId by remember { mutableStateOf<String?>(null) }
    var isCreator by remember { mutableStateOf(false) }
    var nameField by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    // Ao abrir, carrega a família do usuário
    LaunchedEffect(user?.id) {
        val uid = user?.id ?: return@LaunchedEffect
        try {
            val member = familyRepository.getUserFamily(uid)
            if (member != null) {
                familyId = member.family_id
                val fam = familyRepository.getFamilyById(member.family_id)
                familyName = fam?.name
                inviteCode = fam?.invite_code
                isCreator = fam?.creator_id == uid
                nameField = fam?.name ?: ""
            }
        } catch (e: Exception) {
            // ignora
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Familia") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                if (familyName != null) {
                    Text(
                        text = "Familia: $familyName",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (inviteCode != null) {
                    Text("Codigo de convite:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = inviteCode!!,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(inviteCode!!))
                            Toast.makeText(context, "Codigo copiado!", Toast.LENGTH_SHORT).show()
                        }) {
                            Text("Copiar")
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Compartilhe este codigo com quem voce quer na familia.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text("Voce ainda nao esta em uma familia.")
                }

                if (isCreator && familyId != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Renomear familia",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = nameField,
                        onValueChange = { nameField = it },
                        label = { Text("Novo nome da familia") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (!isCreator && familyId != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Apenas quem criou a familia pode renomea-la.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (isCreator && familyId != null) {
                TextButton(
                    enabled = !saving,
                    onClick = {
                        if (nameField.isBlank()) return@TextButton
                        saving = true
                        scope.launch {
                            val ok = familyRepository.renameFamily(familyId!!, nameField.trim())
                            if (ok) {
                                Toast.makeText(context, "Familia renomeada!", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            } else {
                                Toast.makeText(context, "Sem permissao para renomear.", Toast.LENGTH_LONG).show()
                                saving = false
                            }
                        }
                    }
                ) {
                    Text(if (saving) "Salvando..." else "Salvar nome")
                }
            } else {
                TextButton(onClick = onDismiss) { Text("Fechar") }
            }
        },
        dismissButton = {
            if (isCreator && familyId != null) {
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        }
    )
}

@Composable
fun PrivacyDialog(onDismiss: () -> Unit) {
    var locationSharing by remember { mutableStateOf(true) }
    var showOnline by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Privacidade") },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Compartilhar localizacao", fontWeight = FontWeight.Medium)
                        Text(
                            "Permitir que membros vejam sua posicao",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = locationSharing, onCheckedChange = { locationSharing = it })
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Mostrar status online", fontWeight = FontWeight.Medium)
                        Text(
                            "Outros membros veem quando voce esta online",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = showOnline, onCheckedChange = { showOnline = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Fechar") }
        }
    )
}

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sobre o FamTrack") },
        text = {
            Column {
                Text("Versao 1.0.0")
                Spacer(modifier = Modifier.height(8.dp))
                Text("App de rastreamento familiar.")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Fechar") }
        }
    )
}

@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajuda") },
        text = {
            Text("Em breve: central de ajuda.")
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Fechar") }
        }
    )
}

@Composable
fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SettingsSwitchItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
fun DevicesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { HealthConnectRepository(context) }

    var sdkAvailable by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(true) }
    var hasPermission by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<HeartRateSummary?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            checking = true
            reading = false
            summary = null
            message = null
            try {
                sdkAvailable = repo.isAvailable()
                if (sdkAvailable) {
                    hasPermission = repo.hasHeartRatePermission()
                    if (hasPermission) {
                        reading = true
                        summary = repo.readTodayHeartRate()
                        reading = false
                        if (summary == null) {
                            message = "Sem registros de batimentos hoje. Conecte um relogio/banda que grave batimentos (ex.: Samsung Health, Mi Fitness, Google Fit), sincronize com o Health Connect e toque em Atualizar."
                        }
                    }
                } else {
                    message = "O app Health Connect do Google nao esta instalado/atualizado neste aparelho. Instale-o para poder ler os batimentos do relogio/banda."
                }
            } catch (e: Exception) {
                message = "Erro ao consultar o Health Connect."
            } finally {
                checking = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        hasPermission = granted.contains(
            HealthPermission.getReadPermission(HeartRateRecord::class)
        )
        if (hasPermission) {
            load()
        } else {
            message = "Acesso aos batimentos negado. Permita no Health Connect e toque em Atualizar."
        }
    }

    LaunchedEffect(Unit) { load() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dispositivos e saude") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "Os batimentos vem do Health Connect (Google), que recebe os dados do relogio ou banda que voce conectar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                when {
                    checking -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp
                        )
                    }
                    !sdkAvailable -> {
                        Text(message ?: "Health Connect indisponivel.")
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = {
                            try {
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse("market://details?id=com.google.android.apps.healthdata")
                                    )
                                )
                            } catch (e: Exception) {
                                try {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")
                                        )
                                    )
                                } catch (e2: Exception) {
                                    // nada
                                }
                            }
                        }) {
                            Text("Abrir loja para instalar")
                        }
                    }
                    !hasPermission -> {
                        Text("Para ler os batimentos, o FamTrack precisa da sua permissao.")
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = {
                            permissionLauncher.launch(
                                setOf(HealthPermission.getReadPermission(HeartRateRecord::class))
                            )
                        }) {
                            Text("Permitir acesso aos batimentos")
                        }
                    }
                    reading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp
                        )
                    }
                    summary != null -> {
                        Text(
                            "Batimentos de hoje",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Media: ${summary!!.media} bpm")
                        Text("Minimo: ${summary!!.min} bpm")
                        Text("Maximo: ${summary!!.max} bpm")
                        Text(
                            "(${summary!!.amostras} leituras)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> {
                        Text(message ?: "Sem dados disponiveis.")
                    }
                }
                if (!checking && summary != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    TextButton(onClick = { load() }) { Text("Atualizar") }
                }
                if (!checking && message != null && sdkAvailable) {
                    Spacer(modifier = Modifier.height(4.dp))
                    TextButton(onClick = { load() }) { Text("Atualizar") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Fechar") }
        }
    )
}