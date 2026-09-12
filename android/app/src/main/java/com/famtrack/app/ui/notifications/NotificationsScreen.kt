package com.famtrack.app.ui.notifications

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.famtrack.app.data.model.Notification
import com.famtrack.app.data.remote.NotificationRepository
import com.famtrack.app.data.remote.SupabaseClient
import com.famtrack.app.util.parseIsoInstantMillis
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    onNavigateBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val notificationRepository = remember { NotificationRepository() }

    var notifications by remember { mutableStateOf<List<Notification>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try {
            val client = SupabaseClient.getInstance()
            val user = client.auth.currentUserOrNull()
            if (user != null) {
                notifications = notificationRepository.getUserNotifications(user.id)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notificacoes") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Voltar",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    if (notifications.isNotEmpty()) {
                        IconButton(onClick = {
                            scope.launch {
                                try {
                                    val client = SupabaseClient.getInstance()
                                    val user = client.auth.currentUserOrNull()
                                    if (user != null) {
                                        notificationRepository.markAllAsRead(user.id)
                                        notifications = notifications.map { it.copy(read = true) }
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        }) {
                            Icon(
                                Icons.Default.DoneAll,
                                contentDescription = "Marcar todas como lidas",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            )
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
            notifications.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(
                            Icons.Default.NotificationsOff,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Nenhuma notificacao",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Notificacoes de SOS e geofences aparecerao aqui",
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
                    items(notifications) { notification ->
                        NotificationItem(
                            notification = notification,
                            onMarkAsRead = {
                                scope.launch {
                                    try {
                                        notificationRepository.markAsRead(notification.id!!)
                                        notifications = notifications.map {
                                            if (it.id == notification.id) it.copy(read = true) else it
                                        }
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun NotificationItem(
    notification: Notification,
    onMarkAsRead: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (notification.read)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                when (notification.type) {
                    "sos" -> Icons.Default.Warning
                    "geofence" -> Icons.Default.LocationOn
                    else -> Icons.Default.Info
                },
                contentDescription = null,
                tint = when (notification.type) {
                    "sos" -> MaterialTheme.colorScheme.error
                    "geofence" -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = notification.title,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = notification.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatTimestamp(notification.created_at),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!notification.read) {
                IconButton(onClick = onMarkAsRead) {
                    Icon(
                        Icons.Default.MarkEmailRead,
                        contentDescription = "Marcar como lida"
                    )
                }
            }
        }
    }
}

private fun formatTimestamp(timestamp: String?): String {
    val millis = parseIsoInstantMillis(timestamp) ?: return timestamp.orEmpty()
    val outputFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    return outputFormat.format(Date(millis))
}
