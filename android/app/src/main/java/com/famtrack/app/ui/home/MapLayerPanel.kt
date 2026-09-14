package com.famtrack.app.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * Controle de camadas do mapa (redesenho visual): bottom sheet com handle,
 * cabeçalho "Camadas do mapa" e conteúdo em duas colunas — membros online e
 * opções (Mapa-base com radio + Camadas com switches). Não muda nenhuma regra:
 * as mesmas preferências são persistidas por MapLayerPrefs pelo chamador.
 */
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
    members: List<LayerMember> = emptyList(),
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    val onlineCount = members.count { it.id.isNotBlank() }

    if (!open) {
        Card(
            modifier = modifier,
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            IconButton(onClick = { open = true }) {
                Icon(Icons.Filled.Layers, contentDescription = stringResource(R.string.map_layers_panel))
            }
        }
        return
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 20.dp, bottomEnd = 20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .heightIn(max = 430.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            // Alça (handle) do bottom sheet.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f))
            )
            Spacer(modifier = Modifier.height(10.dp))

            // Cabeçalho: título + fechar.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.map_layers_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(32.dp)
                ) {
                    IconButton(
                        onClick = { open = false },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Coluna esquerda: membros online.
                Column(modifier = Modifier.weight(1f)) {
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
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = stringResource(R.string.map_layers_members_online, onlineCount),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                members.take(5).forEach { member ->
                                    MemberAvatar(
                                        avatarUrl = member.avatarUrl,
                                        name = member.name,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = members.take(5).joinToString(", ") { it.name },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Icon(
                                    Icons.Filled.ExpandMore,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Coluna direita: Mapa-base + Camadas.
                Column(modifier = Modifier.weight(1f)) {
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
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Text(stringResource(R.string.map_legend_toggle))
                    }
                }
            }
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