package com.famtrack.app.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.famtrack.app.R
import com.famtrack.app.data.local.MapBaseType
import com.google.maps.android.compose.MapType

/** Membro exibido no painel "membros online" (redesenho visual). */
data class LayerMember(
    val id: String,
    val avatarUrl: String?,
    val name: String
)

/**
 * Controle de camadas do mapa (redesenho visual): ModalBottomSheet que abre de
 * baixo para cima, com handle, cabeçalho "Camadas do mapa" + botão fechar e
 * conteúdo rolável em uma coluna — cartão compacto de membros online, seção
 * Mapa-base (radio) e seção Camadas (switches). Não muda nenhuma regra: as
 * mesmas preferências são persistidas por MapLayerPrefs pelo chamador.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapLayerPanel(
    baseType: MapBaseType,
    onBaseTypeChange: (MapBaseType) -> Unit,
    geofencesOn: Boolean,
    eventsOn: Boolean,
    routesOn: Boolean,
    weatherOn: Boolean,
    onGeofencesChange: (Boolean) -> Unit,
    onEventsChange: (Boolean) -> Unit,
    onRoutesChange: (Boolean) -> Unit,
    onWeatherChange: (Boolean) -> Unit,
    onShowLegend: () -> Unit,
    onDismiss: () -> Unit,
    members: List<LayerMember> = emptyList()
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.62f)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 24.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.map_layers_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.map_layers_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))

            MembersCard(members)

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.map_base_section),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            MapBaseRow(
                label = stringResource(R.string.map_base_normal),
                selected = baseType == MapBaseType.NORMAL,
                onClick = { if (baseType != MapBaseType.NORMAL) onBaseTypeChange(MapBaseType.NORMAL) }
            )
            MapBaseRow(
                label = stringResource(R.string.map_base_satellite),
                selected = baseType == MapBaseType.SATELLITE,
                onClick = { if (baseType != MapBaseType.SATELLITE) onBaseTypeChange(MapBaseType.SATELLITE) }
            )
            MapBaseRow(
                label = stringResource(R.string.map_base_hybrid),
                selected = baseType == MapBaseType.HYBRID,
                onClick = { if (baseType != MapBaseType.HYBRID) onBaseTypeChange(MapBaseType.HYBRID) }
            )
            MapBaseRow(
                label = stringResource(R.string.map_base_terrain),
                selected = baseType == MapBaseType.TERRAIN,
                onClick = { if (baseType != MapBaseType.TERRAIN) onBaseTypeChange(MapBaseType.TERRAIN) }
            )

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.map_layers_section),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            LayerRow(
                icon = Icons.Filled.Check,
                label = stringResource(R.string.map_layer_family),
                checked = true,
                enabled = false
            ) {}
            LayerRow(
                icon = Icons.Filled.Place,
                label = stringResource(R.string.map_layer_geofences),
                checked = geofencesOn,
                enabled = true,
                onCheckedChange = onGeofencesChange
            )
            LayerRow(
                icon = Icons.Filled.Info,
                label = stringResource(R.string.map_layer_events),
                checked = eventsOn,
                enabled = true,
                onCheckedChange = onEventsChange
            )
            LayerRow(
                icon = Icons.Filled.Navigation,
                label = stringResource(R.string.map_layer_routes),
                checked = routesOn,
                enabled = true,
                onCheckedChange = onRoutesChange
            )
            LayerRow(
                icon = Icons.Filled.WbSunny,
                label = stringResource(R.string.map_layer_weather),
                checked = weatherOn,
                enabled = true,
                onCheckedChange = onWeatherChange
            )
            TextButton(
                onClick = onShowLegend,
                modifier = Modifier.align(Alignment.Start)
            ) {
                Text(stringResource(R.string.map_legend_toggle))
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/** Cartão compacto "N membros online" com avatares e chevron (redesenho visual). */
@Composable
private fun MembersCard(members: List<LayerMember>) {
    val onlineCount = members.count { it.id.isNotBlank() }
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                members.take(5).forEach { member ->
                    MemberAvatar(
                        avatarUrl = member.avatarUrl,
                        name = member.name,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.map_layers_members_online, onlineCount),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = members.take(5).joinToString(", ") { it.name },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MemberAvatar(
    avatarUrl: String?,
    name: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (avatarUrl != null) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(28.dp)
            )
        } else {
            Text(
                text = name.take(1).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun MapBaseRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp)
        )
    }
}

@Composable
private fun LayerRow(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}

/** Conversão para o tipo de tile do Google Maps (redesenho visual). */
fun MapBaseType.toGoogleMapType(): MapType = when (this) {
    MapBaseType.SATELLITE -> MapType.SATELLITE
    MapBaseType.HYBRID -> MapType.HYBRID
    MapBaseType.TERRAIN -> MapType.TERRAIN
    MapBaseType.NORMAL -> MapType.NORMAL
}