package com.famtrack.app.ui.home

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.famtrack.app.R

/**
 * Legenda simples das camadas do mapa (ETAPA 10D): localização atual, trajeto,
 * geofence, evento e dado stale.
 */
@Composable
fun MapLegend(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
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
                    text = stringResource(R.string.map_legend_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null)
                }
            }
            LegendRow(
                color = MaterialTheme.colorScheme.primary,
                dot = true,
                label = stringResource(R.string.map_legend_current)
            )
            LegendRow(
                color = MaterialTheme.colorScheme.tertiary,
                dot = false,
                label = stringResource(R.string.map_legend_route)
            )
            LegendRow(
                color = MaterialTheme.colorScheme.secondary,
                dot = false,
                label = stringResource(R.string.map_legend_geofence)
            )
            LegendRow(
                color = MaterialTheme.colorScheme.error,
                dot = true,
                label = stringResource(R.string.map_legend_event)
            )
            LegendRow(
                color = MaterialTheme.colorScheme.outline,
                dot = true,
                label = stringResource(R.string.map_legend_stale)
            )
        }
    }
}

@Composable
private fun LegendRow(color: Color, dot: Boolean, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        if (dot) {
            Icon(
                imageVector = Icons.Filled.LocationOn,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = color
            )
        } else {
            Spacer(
                modifier = Modifier
                    .padding(start = 2.dp)
                    .size(width = 16.dp, height = 4.dp)
                    .background(color, RoundedCornerShape(2.dp))
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}