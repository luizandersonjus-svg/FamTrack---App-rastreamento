package com.famtrack.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.famtrack.app.R
import com.famtrack.app.data.model.RoutePoint
import com.famtrack.app.ui.history.haversineMeters
import com.famtrack.app.ui.history.parseTimestampMillis
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Período do trajeto no mapa principal (ETAPA 10D-3). */
enum class RoutePeriod { TODAY, LAST_24H }

data class MemberRouteOption(val userId: String, val displayName: String)

/**
 * Estado de carregamento da consulta de trajeto (TRAJ-2): estados explícitos
 * de Loading / Empty / Error (com 'Tentar novamente') / Loaded.
 */
enum class RouteUiState { IDLE, LOADING, EMPTY, ERROR, LOADED }

/** Intervalo [início, fim) em UTC para a consulta paginada de route_history. */
fun routePeriodBounds(period: RoutePeriod, zone: ZoneId): Pair<String, String> {
    val now = Instant.now()
    return when (period) {
        RoutePeriod.TODAY -> {
            val start = LocalDate.now(zone).atStartOfDay(zone).toInstant()
            start.toString() to now.toString()
        }
        RoutePeriod.LAST_24H -> {
            now.minusSeconds(24 * 60 * 60).toString() to now.toString()
        }
    }
}

/**
 * Detecta PERMANÊNCIAS (paradas) a partir dos pontos históricos: pontos
 * consecutivos dentro de [maxDriftMeters] do primeiro do grupo, por
 * [minDurationMillis] ou mais, viram uma parada no primeiro ponto — evitando
 * a "linha tremendo no mesmo lugar" no mapa.
 */
fun buildStopMarkers(
    points: List<RoutePoint>,
    maxDriftMeters: Double = 60.0,
    minDurationMillis: Long = 5 * 60_000L
): List<RoutePoint> {
    if (points.size < 2) return emptyList()
    val stops = mutableListOf<RoutePoint>()
    var i = 0
    while (i < points.size - 1) {
        val anchor = points[i]
        val anchorMillis = parseTimestampMillis(anchor.recorded_at)
        var j = i + 1
        while (j < points.size) {
            val p = points[j]
            val within = haversineMeters(
                anchor.latitude, anchor.longitude,
                p.latitude, p.longitude
            ) <= maxDriftMeters
            val pMillis = parseTimestampMillis(p.recorded_at)
            if (!within || pMillis == null) break
            j++
        }
        val last = points[j - 1]
        val lastMillis = parseTimestampMillis(last.recorded_at)
        if (anchorMillis != null && lastMillis != null &&
            lastMillis - anchorMillis >= minDurationMillis
        ) {
            stops += anchor
        }
        if (j == i + 1) {
            i++
        } else {
            i = j
        }
    }
    return stops
}

/**
 * Painel "Trajeto recente" (TRAJ-1): ModalBottomSheet compacto, no mesmo padrão
 * do painel "Camadas do mapa", com altura reduzida para manter o mapa visível e
 * mostrar a rota desenhada. Não muda nenhuma regra de consulta.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteLayerPanel(
    members: List<MemberRouteOption>,
    selectedMemberId: String?,
    period: RoutePeriod,
    uiState: RouteUiState,
    hasRoute: Boolean,
    onMemberChange: (String) -> Unit,
    onPeriodChange: (RoutePeriod) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
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
                .fillMaxHeight(0.38f)
                .padding(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 24.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Filled.Navigation,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.route_layer_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.route_layer_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))

            if (members.isEmpty()) {
                Text(
                    text = stringResource(R.string.route_layer_paused_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyRow(
                    contentPadding = PaddingValues(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(members) { m ->
                        FilterChip(
                            selected = selectedMemberId == m.userId,
                            onClick = { onMemberChange(m.userId) },
                            label = { Text(text = m.displayName, fontSize = 12.sp) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = period == RoutePeriod.TODAY,
                        onClick = { onPeriodChange(RoutePeriod.TODAY) },
                        label = { Text(stringResource(R.string.route_layer_today), fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = period == RoutePeriod.LAST_24H,
                        onClick = { onPeriodChange(RoutePeriod.LAST_24H) },
                        label = { Text(stringResource(R.string.route_layer_24h), fontSize = 12.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            when (uiState) {
                RouteUiState.LOADING -> {
                    Text(
                        text = stringResource(R.string.route_layer_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                RouteUiState.ERROR -> {
                    Text(
                        text = stringResource(R.string.route_layer_error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.route_retry))
                    }
                }
                RouteUiState.EMPTY -> {
                    Text(
                        text = stringResource(R.string.route_layer_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                RouteUiState.LOADED, RouteUiState.IDLE -> Unit
            }
            if (uiState == RouteUiState.LOADED && hasRoute) {
                Text(
                    text = stringResource(R.string.osrm_attribution),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}