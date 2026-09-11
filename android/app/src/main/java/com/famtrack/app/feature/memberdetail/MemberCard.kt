package com.famtrack.app.feature.memberdetail

import android.content.Context
import android.location.Geocoder
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.famtrack.app.R
import com.famtrack.app.feature.places.Place
import com.famtrack.app.util.isInsideGeofence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Informações exibidas no card de um membro.
 */
data class MemberCardInfo(
    val memberId: String,
    val displayName: String,
    val avatarUrl: String?,
    val statusText: String,
    val batteryLevel: Int?,
    val lastUpdatedMillis: Long?,
    val sharingPaused: Boolean,
    val isSelf: Boolean = false,
    val signalLost: Boolean = false
)

/** Tempo desde a última atualização em texto humano ("há X min"). */
fun lastUpdateLabel(context: Context, lastUpdatedMillis: Long?): String {
    val millis = lastUpdatedMillis ?: return context.getString(R.string.updated_unknown)
    val elapsed = System.currentTimeMillis() - millis
    val minutes = elapsed / 60_000L
    return when {
        minutes < 1 -> context.getString(R.string.updated_just_now)
        minutes < 60 -> context.getString(R.string.updated_minutes_ago, minutes)
        minutes < 24 * 60 -> context.getString(R.string.updated_hours_ago, minutes / 60)
        else -> context.getString(R.string.updated_days_ago, minutes / (24 * 60))
    }
}

@Composable
fun batteryIcon(level: Int): ImageVector {
    return when {
        level >= 50 -> Icons.Filled.BatteryFull
        level >= 20 -> Icons.Filled.BatteryStd
        else -> Icons.Filled.BatteryAlert
    }
}

/**
 * Card de membro com foto (ou iniciais), status humano, tempo da última
 * atualização e bateria (alerta abaixo de 20%). Todos os cards aparecem com
 * opacidade total, independentemente do tempo da última atualização.
 * Sem última atualização, mostra "Aguardando primeira atualização".
 */
@Composable
fun MemberCard(
    info: MemberCardInfo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val waitingFirstUpdate = !info.sharingPaused && info.lastUpdatedMillis == null

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MemberInitialsAvatar(
                name = info.displayName,
                avatarUrl = info.avatarUrl,
                modifier = Modifier.size(44.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = info.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (info.isSelf) {
                        Spacer(modifier = Modifier.width(6.dp))
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
                if (info.sharingPaused) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Pause,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.member_sharing_paused),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    Text(
                        text = info.statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (waitingFirstUpdate) {
                        Text(
                            text = stringResource(R.string.member_waiting_first_update),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (info.signalLost) {
                        // ETAPA 7 — observabilidade: o serviço de quem compartilha
                        // parou de enviar; alerta o tempo sem sinal em destaque.
                        Text(
                            text = stringResource(R.string.member_signal_lost) +
                                " • " + lastUpdateLabel(context, info.lastUpdatedMillis),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Text(
                            text = lastUpdateLabel(context, info.lastUpdatedMillis),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            info.batteryLevel?.let { level ->
                Column(horizontalAlignment = Alignment.End) {
                    Icon(
                        imageVector = batteryIcon(level),
                        contentDescription = stringResource(R.string.member_battery_cd),
                        tint = if (level < 20) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Text(
                        text = "$level%",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (level < 20) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun MemberInitialsAvatar(
    name: String,
    avatarUrl: String?,
    modifier: Modifier = Modifier
) {
    val initials = name.trim()
        .split(Regex("\\s+"))
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .take(2)
        .joinToString("")

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (avatarUrl != null) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .clip(CircleShape)
            )
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initials.ifEmpty { "?" },
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Status humano: nome do local se dentro de uma Place ("Em casa" para CASA),
 * senão endereço via Geocoder, senão as coordenadas.
 */
suspend fun resolveStatusText(
    context: Context,
    latitude: Double,
    longitude: Double,
    places: List<Place>
): String {
    val inside = places.firstOrNull {
        isInsideGeofence(
            latitude,
            longitude,
            it.center_lat,
            it.center_lon,
            it.radius_meters.toDouble()
        )
    }
    if (inside != null) {
        return if (inside.type == "CASA") {
            context.getString(R.string.member_status_casa)
        } else {
            inside.name
        }
    }

    return withContext(Dispatchers.IO) {
        try {
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses = geocoder.getFromLocation(latitude, longitude, 1)
            val address = addresses?.firstOrNull()
            val parts = listOfNotNull(
                address?.thoroughfare,
                address?.subThoroughfare,
                address?.locality
            ).take(3).joinToString(", ")
                .ifBlank { address?.getAddressLine(0) }
            if (!parts.isNullOrBlank()) parts else "${"%.5f".format(latitude)}, ${"%.5f".format(longitude)}"
        } catch (e: Exception) {
            "${"%.5f".format(latitude)}, ${"%.5f".format(longitude)}"
        }
    }
}