package com.famtrack.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.famtrack.app.R

/**
 * Controle de camadas do mapa principal (ETAPA 10D-1).
 * Familiares fica sempre ativa; as demais são opt-in e persistidas localmente
 * (MapLayerPrefs) pelo chamador.
 */
@Composable
fun MapLayerPanel(
    geofencesOn: Boolean,
    eventsOn: Boolean,
    routesOn: Boolean,
    weatherOn: Boolean,
    onGeofencesChange: (Boolean) -> Unit,
    onEventsChange: (Boolean) -> Unit,
    onRoutesChange: (Boolean) -> Unit,
    onWeatherChange: (Boolean) -> Unit,
    onShowLegend: () -> Unit,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        if (!open) {
            IconButton(onClick = { open = true }) {
                Icon(Icons.Filled.Layers, contentDescription = stringResource(R.string.map_layers_panel))
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.map_layers_title),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { open = false },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = null)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
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
            }
        }
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
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}