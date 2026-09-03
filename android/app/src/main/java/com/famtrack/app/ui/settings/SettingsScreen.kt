package com.famtrack.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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

@Composable
fun ProfileDialog(onDismiss: () -> Unit) {
    val user = SupabaseClient.getInstance().auth.currentUserOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Perfil") },
        text = {
            Column {
                Text("Email: ${user?.email ?: "Nao disponivel"}")
                Spacer(modifier = Modifier.height(8.dp))
                Text("ID: ${user?.id?.take(8) ?: "N/A"}...")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Fechar") }
        }
    )
}

@Composable
fun FamilyDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Familia") },
        text = {
            Column {
                Text("Para gerenciar membros da familia:")
                Spacer(modifier = Modifier.height(8.dp))
                Text("1. Compartilhe o codigo da sua familia com seus familiares")
                Spacer(modifier = Modifier.height(4.dp))
                Text("2. Eles podem entrar e se juntar a familia")
                Spacer(modifier = Modifier.height(4.dp))
                Text("3. Todos os membros aparecerao no mapa")
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Em breve: tela de gerenciamento de familia",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Fechar") }
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
                Text("FamTrack", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Versao: 1.0.0")
                Spacer(modifier = Modifier.height(4.dp))
                Text("Um app de rastreamento familiar em tempo real.")
                Spacer(modifier = Modifier.height(4.dp))
                Text("Funcionalidades:")
                Spacer(modifier = Modifier.height(4.dp))
                Text("- Mapa com localizacao em tempo real", style = MaterialTheme.typography.bodySmall)
                Text("- Alerta SOS", style = MaterialTheme.typography.bodySmall)
                Text("- Geofences", style = MaterialTheme.typography.bodySmall)
                Text("- Historico de rotas", style = MaterialTheme.typography.bodySmall)
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
            Column {
                Text("Como usar o FamTrack:", fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(8.dp))
                Text("1. Crie uma conta ou faca login", style = MaterialTheme.typography.bodySmall)
                Text("2. Crie ou entre em uma familia", style = MaterialTheme.typography.bodySmall)
                Text("3. Compartilhe o codigo da familia com seus familiares", style = MaterialTheme.typography.bodySmall)
                Text("4. Todos aparecerao no mapa em tempo real", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(12.dp))
                Text("SOS:", fontWeight = FontWeight.Medium)
                Text("Clique no botao SOS para enviar um alerta com sua localizacao para todos os membros.", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Geofences:", fontWeight = FontWeight.Medium)
                Text("Crie areas protegidas. O app avisa quando alguem entrar ou sair dessas areas.", style = MaterialTheme.typography.bodySmall)
            }
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
