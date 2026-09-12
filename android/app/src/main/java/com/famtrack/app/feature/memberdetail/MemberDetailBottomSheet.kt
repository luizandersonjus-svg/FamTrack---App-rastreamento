package com.famtrack.app.feature.memberdetail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.famtrack.app.R
import com.famtrack.app.data.model.Location
import com.famtrack.app.data.remote.HealthConnectRepository
import com.famtrack.app.feature.healthsnap.HealthSnapRepository
import com.famtrack.app.feature.places.Place
import com.famtrack.app.feature.privacy.MemberFlags
import com.famtrack.app.util.parseIsoInstantMillis

/**
 * Bottom sheet de detalhe de um membro: status, bateria, saude, ligar,
 * rota até aqui e acesso ao histórico de atividade.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberDetailBottomSheet(
    member: Location,
    displayName: String,
    avatarUrl: String?,
    places: List<Place>,
    memberFlags: MemberFlags?,
    familyId: String,
    isSelf: Boolean,
    onDismiss: () -> Unit,
    onNavigateToHistory: (String) -> Unit
) {
    val context = LocalContext.current

    var statusText by remember { mutableStateOf("") }
    var loadingStatus by remember { mutableStateOf(true) }

    var bpmLoading by remember { mutableStateOf(true) }
    var bpmSummary by remember { mutableStateOf<String?>(null) }
    var healthUnavailable by remember { mutableStateOf(false) }
    var healthNotShared by remember { mutableStateOf(false) }

    LaunchedEffect(member.user_id) {
        loadingStatus = true
        statusText = resolveStatusText(
            context,
            member.latitude,
            member.longitude,
            places
        )
        loadingStatus = false
    }

    LaunchedEffect(member.user_id, isSelf, memberFlags?.share_health) {
        bpmLoading = true
        bpmSummary = null
        healthUnavailable = false
        healthNotShared = false
        try {
            if (isSelf) {
                val repo = HealthConnectRepository(context)
                if (repo.isAvailable()) {
                    val summary = repo.readTodayHeartRate()
                    bpmSummary = summary?.let {
                        context.getString(
                            R.string.member_health_summary,
                            it.media, it.min, it.max, it.amostras
                        )
                    }
                } else {
                    healthUnavailable = true
                }
            } else {
                val shared = memberFlags?.share_health == true
                if (shared) {
                    val snap = HealthSnapRepository(context)
                        .getByUserToday(familyId, member.user_id)
                    bpmSummary = snap?.let {
                        context.getString(
                            R.string.member_health_summary,
                            it.bpm_media, it.bpm_min, it.bpm_max, it.samples
                        )
                    }
                } else {
                    healthNotShared = true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            bpmLoading = false
        }
    }

    val phone = member.user?.phone
    val lastUpdatedMillis = member.lastUpdatedAt ?: parseIsoInstantMillis(member.created_at)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MemberInitialsAvatar(
                    name = displayName,
                    avatarUrl = avatarUrl,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        if (isSelf) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = stringResource(R.string.member_you_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    if (loadingStatus) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = if (memberFlags?.sharing_paused == true) {
                                stringResource(R.string.member_sharing_paused)
                            } else {
                                statusText
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (memberFlags?.sharing_paused == true) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    Text(
                        text = stringResource(
                            R.string.member_updated_ago,
                            lastUpdateLabel(context, lastUpdatedMillis)
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            member.batteryLevel?.let { level ->
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = batteryIcon(level),
                        contentDescription = null,
                        tint = if (level < 20) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.member_battery_label, level),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (level < 20) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.PhoneAndroid,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.member_health_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        when {
                            bpmLoading -> {
                                Spacer(modifier = Modifier.height(6.dp))
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                            bpmSummary != null -> {
                                Text(
                                    text = bpmSummary!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            healthNotShared || healthUnavailable -> {
                                Text(
                                    text = if (healthUnavailable) {
                                        stringResource(R.string.member_health_unavailable)
                                    } else {
                                        stringResource(R.string.member_health_none)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            else -> {
                                Text(
                                    text = stringResource(R.string.member_health_none),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (phone != null) {
                    Button(
                        onClick = {
                            try {
                                context.startActivity(
                                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                                )
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.Call, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.member_btn_call))
                    }
                }

                Button(
                    onClick = {
                        val uri = "geo:0,0?q=${member.latitude},${member.longitude}"
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                            )
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.Directions, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.member_btn_route))
                }

                OutlinedButton(
                    onClick = { onNavigateToHistory(member.user_id) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.History, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.member_btn_history))
                }
            }
        }
    }
}