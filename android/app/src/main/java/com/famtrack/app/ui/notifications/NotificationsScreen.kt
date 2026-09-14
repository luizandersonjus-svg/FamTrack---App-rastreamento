package com.famtrack.app.ui.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.famtrack.app.R
import com.famtrack.app.data.model.Notification
import com.famtrack.app.data.remote.NotificationRepository
import com.famtrack.app.data.remote.SupabaseClient
import com.famtrack.app.util.parseIsoInstantMillis
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.*

// Auditoria visual L1: filtros separados por grupo (TIPO x PERÍODO), cartão
// compacto de uma linha, contador de não-lidas conforme o filtro ativo,
// ações em massa via menu (marcar todas lidas / limpar lidas / selecionar) e
// exclusão apenas após sucesso no servidor (nunca otimista).

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    onNavigateBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val notificationRepository = remember { NotificationRepository() }

    var notifications by remember { mutableStateOf<List<Notification>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var currentUserId by remember { mutableStateOf<String?>(null) }

    var typeFilter by remember { mutableStateOf("todas") }
    var timeFilter by remember { mutableStateOf("tudo") }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var bulkMenuOpen by remember { mutableStateOf(false) }
    var deleteTargetIds by remember { mutableStateOf<List<String>?>(null) }

    LaunchedEffect(Unit) {
        try {
            val client = SupabaseClient.getInstance()
            val user = client.auth.currentUserOrNull()
            if (user != null) {
                currentUserId = user.id
                notifications = notificationRepository.getUserNotifications(user.id)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isLoading = false
        }
    }

    val filtered = remember(notifications, typeFilter, timeFilter) {
        val zone = ZoneId.systemDefault()
        val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val weekStart = todayStart - (7L * 24L * 60L * 60L * 1000L)
        notifications.filter { n ->
            val typeOk = typeFilter == "todas" || n.type == typeFilter
            val millis = parseIsoInstantMillis(n.created_at) ?: Long.MAX_VALUE
            val timeOk = when (timeFilter) {
                "hoje" -> millis >= todayStart
                "7dias" -> millis >= weekStart
                else -> true
            }
            typeOk && timeOk
        }
    }

    // Contador de não-lidas SEMPRE do conjunto exibido (filtrado), não global.
    val unreadInFilter = filtered.count { !it.read }

    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    fun markRead(ids: List<String>) {
        val uid = currentUserId ?: return
        scope.launch {
            try {
                ids.forEach { id ->
                    notificationRepository.markAsRead(id)
                    notifications = notifications.map {
                        if (it.id == id) it.copy(read = true) else it
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun markAllAsRead() {
        val uid = currentUserId ?: return
        scope.launch {
            try {
                notificationRepository.markAllAsRead(uid)
                notifications = notifications.map { it.copy(read = true) }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun clearRead() {
        val uid = currentUserId ?: return
        scope.launch {
            try {
                notificationRepository.deleteReadNotifications(uid)
                notifications = notifications.filterNot { it.read }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectionMode) {
                            val count = selectedIds.size
                            if (count == 1) "1 selecionada" else "$count selecionadas"
                        } else {
                            stringResource(R.string.notifications_title)
                        }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                navigationIcon = {
                    if (selectionMode) {
                        IconButton(onClick = ::exitSelection) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.notifications_exit_selection),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    } else {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                },
                actions = {
                    when {
                        selectionMode -> {
                            IconButton(
                                enabled = selectedIds.isNotEmpty(),
                                onClick = { markRead(selectedIds.toList()) }
                            ) {
                                Icon(
                                    Icons.Default.MarkEmailRead,
                                    contentDescription = stringResource(R.string.notifications_mark_selected_read),
                                    tint = if (selectedIds.isNotEmpty()) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            IconButton(
                                enabled = selectedIds.isNotEmpty(),
                                onClick = {
                                    if (selectedIds.isNotEmpty()) {
                                        deleteTargetIds = selectedIds.toList()
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.notifications_delete_selected),
                                    tint = if (selectedIds.isNotEmpty()) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                        notifications.isNotEmpty() -> {
                            Box {
                                IconButton(onClick = { bulkMenuOpen = true }) {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        contentDescription = stringResource(R.string.notifications_more_actions),
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                DropdownMenu(
                                    expanded = bulkMenuOpen,
                                    onDismissRequest = { bulkMenuOpen = false }
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(stringResource(R.string.notifications_menu_mark_all_read))
                                        },
                                        onClick = {
                                            bulkMenuOpen = false
                                            if (notifications.any { !it.read }) markAllAsRead()
                                        },
                                        enabled = notifications.any { !it.read }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(stringResource(R.string.notifications_menu_clear_read))
                                        },
                                        onClick = {
                                            bulkMenuOpen = false
                                            clearRead()
                                        },
                                        enabled = notifications.any { it.read }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(stringResource(R.string.notifications_menu_select))
                                        },
                                        onClick = {
                                            bulkMenuOpen = false
                                            selectionMode = true
                                            selectedIds = emptySet()
                                        },
                                        enabled = notifications.isNotEmpty()
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(R.string.notifications_menu_clear_all),
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        },
                                        onClick = {
                                            bulkMenuOpen = false
                                            deleteTargetIds = notifications.mapNotNull { it.id }
                                        },
                                        enabled = notifications.isNotEmpty()
                                    )
                                }
                            }
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
                            text = stringResource(R.string.notifications_empty_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.notifications_empty_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            else -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(paddingValues)
                ) {
                    Text(
                        text = stringResource(R.string.notifications_filter_type),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 4.dp)
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            FilterChip(
                                selected = typeFilter == "todas",
                                onClick = { typeFilter = "todas" },
                                label = { Text(stringResource(R.string.notifications_type_all)) }
                            )
                        }
                        item {
                            FilterChip(
                                selected = typeFilter == "sos",
                                onClick = { typeFilter = "sos" },
                                label = { Text(stringResource(R.string.notifications_type_sos)) }
                            )
                        }
                        item {
                            FilterChip(
                                selected = typeFilter == "geofence",
                                onClick = { typeFilter = "geofence" },
                                label = { Text(stringResource(R.string.notifications_type_geofence)) }
                            )
                        }
                        item {
                            FilterChip(
                                selected = typeFilter == "system",
                                onClick = { typeFilter = "system" },
                                label = { Text(stringResource(R.string.notifications_type_system)) }
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.notifications_filter_period),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 4.dp)
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            FilterChip(
                                selected = timeFilter == "tudo",
                                onClick = { timeFilter = "tudo" },
                                label = { Text(stringResource(R.string.notifications_period_all)) }
                            )
                        }
                        item {
                            FilterChip(
                                selected = timeFilter == "hoje",
                                onClick = { timeFilter = "hoje" },
                                label = { Text(stringResource(R.string.notifications_period_today)) }
                            )
                        }
                        item {
                            FilterChip(
                                selected = timeFilter == "7dias",
                                onClick = { timeFilter = "7dias" },
                                label = { Text(stringResource(R.string.notifications_period_week)) }
                            )
                        }
                    }
                    if (unreadInFilter > 0) {
                        Text(
                            text = if (unreadInFilter == 1) {
                                stringResource(R.string.notifications_unread_one)
                            } else {
                                stringResource(R.string.notifications_unread_many, unreadInFilter)
                            },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (filtered.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.notifications_filtered_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp)
                        ) {
                            items(filtered, key = { it.id ?: it.hashCode() }) { notification ->
                                NotificationItem(
                                    notification = notification,
                                    selectionMode = selectionMode,
                                    selected = selectedIds.contains(notification.id),
                                    onClick = {
                                        val id = notification.id
                                        if (selectionMode) {
                                            if (id != null) {
                                                selectedIds = if (id in selectedIds) {
                                                    selectedIds - id
                                                } else {
                                                    selectedIds + id
                                                }
                                            }
                                        } else if (!notification.read) {
                                            markRead(listOf(id ?: return@NotificationItem))
                                        }
                                    },
                                    onLongClick = {
                                        val id = notification.id ?: return@NotificationItem
                                        if (!selectionMode) {
                                            selectionMode = true
                                            selectedIds = emptySet()
                                        }
                                        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Exclusão individual/em lote sempre confirmada; remove da lista apenas
    // após sucesso no servidor (nunca otimista).
    deleteTargetIds?.let { ids ->
        if (ids.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { deleteTargetIds = null },
                title = {
                    Text(
                        if (ids.size == 1) {
                            stringResource(R.string.notifications_delete_one_title)
                        } else {
                            stringResource(R.string.notifications_delete_many_title, ids.size)
                        }
                    )
                },
                text = {
                    Text(stringResource(R.string.notifications_delete_message))
                },
                confirmButton = {
                    TextButton(onClick = {
                        deleteTargetIds = null
                        exitSelection()
                        scope.launch {
                            ids.forEach { id ->
                                try {
                                    notificationRepository.deleteNotification(id)
                                    notifications = notifications.filter { it.id != id }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        }
                    }) {
                        Text(
                            stringResource(R.string.notifications_delete_confirm),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTargetIds = null }) {
                        Text(stringResource(R.string.notifications_delete_cancel))
                    }
                }
            )
        }
    }
}

@Composable
fun NotificationItem(
    notification: Notification,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
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
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                when (notification.type) {
                    "sos" -> Icons.Default.Warning
                    "geofence" -> Icons.Default.LocationOn
                    else -> Icons.Default.Info
                },
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = when (notification.type) {
                    "sos" -> MaterialTheme.colorScheme.error
                    "geofence" -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = notificationMainText(notification),
                    style = if (notification.read) {
                        MaterialTheme.typography.bodyMedium
                    } else {
                        MaterialTheme.typography.titleSmall
                    },
                    fontWeight = if (notification.read) FontWeight.Normal else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = relativeTime(notification.created_at),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onClick() }
                )
            } else if (!notification.read) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

/** Linha única do cartão: evita repetição tipo "Casa / Leidy chegou em Casa". */
private fun notificationMainText(notification: Notification): String {
    val title = notification.title.trim()
    val message = notification.message.trim()
    return when (notification.type) {
        "sos" -> message.ifBlank { title }
        "geofence" -> {
            if (message.isNotBlank() &&
                (title.isBlank() || message.contains(title, ignoreCase = true))
            ) {
                message
            } else {
                listOf(title, message).filter { it.isNotBlank() }
                    .joinToString(" — ")
            }
        }
        else -> message.ifBlank { title }
    }
}

private fun relativeTime(timestamp: String?): String {
    val millis = parseIsoInstantMillis(timestamp) ?: return timestamp.orEmpty()
    val diff = System.currentTimeMillis() - millis
    return when {
        diff < 60_000L -> "agora"
        diff < 3_600_000L -> "há ${diff / 60_000L} min"
        diff < 86_400_000L -> "há ${diff / 3_600_000L} h"
        else -> SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(millis))
    }
}