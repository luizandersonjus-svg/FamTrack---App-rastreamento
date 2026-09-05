package com.famtrack.app.feature.privacy

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.famtrack.app.R
import com.famtrack.app.data.remote.FamilyRepository
import com.famtrack.app.data.remote.SupabaseClient
import com.famtrack.app.feature.healthsnap.HealthSnapRepository
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.launch

/**
 * Preferências de privacidade (F7): pausa do compartilhamento de localização
 * e compartilhamento de batimentos (padrão: desligado).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val privacyRepository = remember { PrivacyRepository() }
    val familyRepository = remember { FamilyRepository() }

    var sharingPaused by remember { mutableStateOf(false) }
    var shareHealth by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var userId by remember { mutableStateOf<String?>(null) }
    var familyId by remember { mutableStateOf<String?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        isLoading = true
        try {
            val user = SupabaseClient.getInstance().auth.currentUserOrNull()
            if (user != null) {
                userId = user.id
                val member = familyRepository.getUserFamily(user.id)
                familyId = member?.family_id
                val flags = privacyRepository.getMyFlags(user.id)
                sharingPaused = flags?.sharing_paused ?: PrivacyPrefs.isSharingPaused(context)
                shareHealth = flags?.share_health ?: PrivacyPrefs.isSharingHealth(context)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isLoading = false
        }
    }

    fun applySharing(newValue: Boolean) {
        val uid = userId ?: return
        sharingPaused = newValue
        PrivacyPrefs.setSharingPaused(context, newValue)
        scope.launch {
            saving = true
            try {
                privacyRepository.setSharingPaused(uid, newValue)
                snackbarHostState.showSnackbar(context.getString(R.string.privacy_saved))
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                saving = false
            }
        }
    }

    fun applyHealth(newValue: Boolean) {
        val uid = userId ?: return
        val fid = familyId
        shareHealth = newValue
        PrivacyPrefs.setShareHealth(context, newValue)
        scope.launch {
            saving = true
            try {
                privacyRepository.setShareHealth(uid, newValue)
                if (newValue && fid != null) {
                    // Torna os batimentos de hoje visíveis imediatamente.
                    HealthSnapRepository(context).syncOwnToday(fid, uid)
                }
                snackbarHostState.showSnackbar(context.getString(R.string.privacy_saved))
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                saving = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.privacy_title)) },
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
        snackbarHost = { SnackbarHost(snackbarHostState) }
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
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.Shield,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.privacy_sharing_title),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                    )
                                    Text(
                                        text = stringResource(R.string.privacy_sharing_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = sharingPaused,
                                    onCheckedChange = { applySharing(it) },
                                    enabled = !saving && userId != null
                                )
                            }
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.HealthAndSafety,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.privacy_health_title),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                    )
                                    Text(
                                        text = stringResource(R.string.privacy_health_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = shareHealth,
                                    onCheckedChange = { applyHealth(it) },
                                    enabled = !saving && userId != null
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}