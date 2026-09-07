package com.famtrack.app.ui.history

import android.content.Context
import android.location.Location
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.famtrack.app.R
import com.famtrack.app.data.model.Geofence
import com.famtrack.app.data.model.RoutePoint
import com.famtrack.app.feature.common.AddressOutcome
import com.famtrack.app.feature.common.AddressResolver
import com.famtrack.app.feature.places.Place
import com.famtrack.app.util.isInsideGeofence
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.maps.android.compose.CameraPositionState
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// ---------------------------------------------------------------------------
// Constantes e cálculo dos percursos (Parte D)
// ---------------------------------------------------------------------------

private const val STOP_RADIUS_METERS = 60.0
private const val STOP_MIN_DURATION_MILLIS = 5 * 60 * 1000L
private const val MIN_TRIP_DISTANCE_METERS = 50.0
private const val MIN_TRIP_DURATION_MILLIS = 60 * 1000L
internal const val MAX_TRIP_POINTS = 5000

// Saneamento de velocidade: ignora segmentos com intervalo < 1s (ruído GPS) e
// velocidades implausíveis (> 220 km/h) para o cálculo de velocidade máxima.
private const val MIN_SEGMENT_DT_MS = 1_000L
private const val MAX_PLAUSIBLE_KMH = 220.0

// Limiar de alerta "possível excesso": configurável (40–100 km/h ou desativado),
// persistido em SharedPreferences própria (não mexe em privacy_prefs).
private const val DEFAULT_SPEED_ALERT_KMH = 60
private const val SPEED_PREFS_NAME = "speed_alert_prefs"
private const val SPEED_PREFS_KEY = "trip_speed_alert_kmh"

internal fun readSpeedAlertPref(context: Context): Int =
    context.getSharedPreferences(SPEED_PREFS_NAME, Context.MODE_PRIVATE)
        .getInt(SPEED_PREFS_KEY, DEFAULT_SPEED_ALERT_KMH)

internal fun writeSpeedAlertPref(context: Context, value: Int) {
    context.getSharedPreferences(SPEED_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putInt(SPEED_PREFS_KEY, value)
        .apply()
}

/**
 * Resumo de um deslocamento entre duas paradas. [pointStart]/[pointEnd] são
 * índices na lista ordenada de pontos usada no cálculo.
 */
internal data class TripSummary(
    val pointStart: Int,
    val pointEnd: Int,
    val startMillis: Long,
    val endMillis: Long,
    val durationMillis: Long,
    val distanceMeters: Double,
    val avgSpeedKmh: Double?,
    val maxSpeedKmh: Double?,
    val possibleSpeeding: Boolean
)

private data class TimedPoint(val point: RoutePoint, val timeMillis: Long)

internal data class TripsResult(
    val trips: List<TripSummary>,
    val truncated: Boolean
)

/**
 * Divide o dia em trechos de deslocamento:
 * - "parada" = pessoa fica dentro de ~60m por >= 5 min (calculado no aparelho);
 * - trecho aceito desde que distância >= 50 m e duração >= 1 min;
 * - sem paradas detectadas (poucos pontos), cria percurso simples entre o
 *   primeiro e o último ponto se distância > 50 m;
 * - [speedLimitKmh] nulo desativa o alerta de velocidade.
 * Para dias com muitos pontos legados, limita a 5000 pontos e sinaliza
 * [TripsResult.truncated] para a UI avisar discretamente.
 */
internal fun computeTrips(points: List<RoutePoint>, speedLimitKmh: Double?): TripsResult {
    val truncated = points.size > MAX_TRIP_POINTS
    val capped = if (truncated) points.take(MAX_TRIP_POINTS) else points
    if (capped.size < 2) return TripsResult(emptyList(), truncated)
    val timed = capped.mapNotNull { p ->
        parseTimestampMillis(p.recorded_at)?.let { TimedPoint(p, it) }
    }
    if (timed.size < 2) return TripsResult(emptyList(), truncated)

    val n = timed.size
    val inStop = BooleanArray(n)

    var i = 0
    while (i < n) {
        if (inStop[i]) { i++; continue }
        val base = timed[i]
        var k = i
        while (k + 1 < n) {
            val next = timed[k + 1]
            val d = FloatArray(1)
            Location.distanceBetween(
                base.point.latitude, base.point.longitude,
                next.point.latitude, next.point.longitude, d
            )
            if (d[0] > STOP_RADIUS_METERS) break
            k++
        }
        if (k - i >= 2 && timed[k].timeMillis - base.timeMillis >= STOP_MIN_DURATION_MILLIS) {
            for (j in i..k) inStop[j] = true
            i = k + 1
        } else {
            i++
        }
    }

    val trips = mutableListOf<TripSummary>()
    var start = -1
    for (idx in 0 until n) {
        if (inStop[idx]) {
            if (start != -1) {
                flushTrip(timed, start, idx - 1, speedLimitKmh)?.let { trips += it }
                start = -1
            }
        } else {
            if (start == -1) start = idx
        }
    }
    if (start != -1) flushTrip(timed, start, n - 1, speedLimitKmh)?.let { trips += it }

    // Fallback: sem paradas claras (poucos pontos): percurso simples entre o
    // primeiro e o último ponto, desde que distância > 50 m e duração >= 1 min.
    if (trips.isEmpty()) {
        val first = timed.first()
        val last = timed.last()
        val d = FloatArray(1)
        Location.distanceBetween(
            first.point.latitude, first.point.longitude,
            last.point.latitude, last.point.longitude, d
        )
        if (d[0].toDouble() >= MIN_TRIP_DISTANCE_METERS &&
            (last.timeMillis - first.timeMillis) >= MIN_TRIP_DURATION_MILLIS
        ) {
            flushTrip(timed, 0, n - 1, speedLimitKmh)?.let { trips += it }
        }
    }
    return TripsResult(trips.sortedBy { it.startMillis }, truncated)
}

private fun flushTrip(
    timed: List<TimedPoint>,
    start: Int,
    end: Int,
    speedLimitKmh: Double?
): TripSummary? {
    if (end - start < 1) return null
    var distance = 0.0
    var maxSpeed: Double? = null
    var runSegments = 0
    var runDurationMs = 0L
    var speeding = false

    fun checkRun() {
        if (runSegments > 0 && (runDurationMs >= 10_000 || runSegments >= 2)) {
            speeding = true
        }
        runSegments = 0
        runDurationMs = 0
    }

    for (j in (start + 1)..end) {
        val a = timed[j - 1]
        val b = timed[j]
        val d = FloatArray(1)
        Location.distanceBetween(
            a.point.latitude, a.point.longitude,
            b.point.latitude, b.point.longitude, d
        )
        val dist = d[0].toDouble().coerceAtLeast(0.0)
        distance += dist
        val dtMs = b.timeMillis - a.timeMillis
        var kmh: Double? = b.point.speed?.let { it * 3.6 }
        if (kmh == null && dtMs >= MIN_SEGMENT_DT_MS) {
            kmh = (dist / (dtMs / 1000.0)) * 3.6
        }
        if (kmh != null) {
            if (kmh <= MAX_PLAUSIBLE_KMH) {
                if (maxSpeed == null || kmh > maxSpeed) maxSpeed = kmh
                if (speedLimitKmh != null) {
                    if (kmh > speedLimitKmh) {
                        runSegments++
                        runDurationMs += dtMs.coerceAtLeast(0L)
                    } else {
                        checkRun()
                    }
                }
            } else {
                checkRun()
            }
        } else if (speedLimitKmh != null) {
            // Segmento sem velocidade mensurável (delta inválido): fecha a
            // "corrida" anterior para não acumular tempo sem confirmação.
            checkRun()
        }
    }
    checkRun()

    val duration = timed[end].timeMillis - timed[start].timeMillis
    if (distance < MIN_TRIP_DISTANCE_METERS || duration < MIN_TRIP_DURATION_MILLIS) return null
    val avg = if (duration > 0) (distance / (duration / 1000.0)) * 3.6 else null
    return TripSummary(
        pointStart = start,
        pointEnd = end,
        startMillis = timed[start].timeMillis,
        endMillis = timed[end].timeMillis,
        durationMillis = duration,
        distanceMeters = distance,
        avgSpeedKmh = avg,
        maxSpeedKmh = maxSpeed,
        possibleSpeeding = speeding
    )
}

// ---------------------------------------------------------------------------
// Helpers compartilhados com HistoryScreen
// ---------------------------------------------------------------------------

/** Converte um timestamp do banco (ISO) em millis. Tolerante ao sufixo de fuso. */
internal fun parseTimestampMillis(timestamp: String?): Long? {
    if (timestamp == null) return null
    return try {
        val cleaned = timestamp.trim().substringBefore('.')
        val inputFormat = if (cleaned.endsWith("Z")) {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        } else {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
        }
        inputFormat.parse(cleaned)?.time
    } catch (e: Exception) {
        null
    }
}

/**
 * Centro e zoom para enquadrar a rota via LatLngBounds (com margem).
 * Ponto único: zoom fixo 17f. Rota vazia: fallback (15f).
 */
internal fun computeRouteCenterAndZoom(points: List<LatLng>): Triple<Double, Double, Float> {
    if (points.isEmpty()) return Triple(0.0, 0.0, 15f)
    if (points.size == 1) return Triple(points[0].latitude, points[0].longitude, 17f)
    val bounds = com.google.android.gms.maps.model.LatLngBounds.Builder()
        .apply { points.forEach { include(it) } }
        .build()
    val center = bounds.center
    val span = maxOf(
        bounds.northeast.latitude - bounds.southwest.latitude,
        bounds.northeast.longitude - bounds.southwest.longitude
    ) * 1.4
    val zoom = when {
        span <= 0.0015 -> 17f
        span <= 0.004 -> 16f
        span <= 0.01 -> 15f
        span <= 0.025 -> 14f
        span <= 0.06 -> 13f
        span <= 0.15 -> 12f
        span <= 0.35 -> 11f
        else -> 10f
    }
    return Triple(center.latitude, center.longitude, zoom)
}

/** Decimação simples (1 a cada k) para renderizar a Polyline — não altera os dados. */
private fun decimate(points: List<RoutePoint>, target: Int): List<RoutePoint> {
    if (points.size <= target) return points
    val step = points.size.toDouble() / target.toDouble()
    val result = mutableListOf<RoutePoint>()
    var idx = 0.0
    while (idx < points.size && result.size < target) {
        result += points[idx.toInt()]
        idx += step
    }
    if (result.lastOrNull() !== points.last()) result += points.last()
    return result
}

@Composable
private fun distanceText(totalDistance: Double?): String {
    if (totalDistance == null) return "—"
    return if (totalDistance >= 1000) {
        stringResource(
            R.string.history_distance_km,
            String.format(Locale.getDefault(), "%.1f", totalDistance / 1000.0)
        )
    } else {
        stringResource(
            R.string.history_distance_m,
            String.format(Locale.getDefault(), "%.0f", totalDistance)
        )
    }
}

@Composable
internal fun tripDurationText(durationMillis: Long): String {
    val totalMin = (durationMillis / 60_000L).toInt().coerceAtLeast(0)
    val hours = totalMin / 60
    val minutes = totalMin % 60
    return if (hours > 0) {
        stringResource(R.string.history_duration_h, "$hours", "$minutes")
    } else {
        stringResource(R.string.history_duration_min, "$minutes")
    }
}

internal fun speedText(v: Double?): String =
    if (v == null) "—" else String.format(Locale.getDefault(), "%.0f", v)

internal fun formatTripTime(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

/**
 * Rótulo de origem/destino de um trecho: nome do Place salvo (se dentro do
 * raio), senão endereço via Geocoder, senão "Local aproximado".
 */
internal suspend fun tripLocationLabel(
    context: Context,
    places: List<Place>,
    latitude: Double,
    longitude: Double
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
    if (inside != null) return inside.name
    return when (val outcome = AddressResolver.resolve(context, latitude, longitude)) {
        is AddressOutcome.Found -> outcome.text
        else -> context.getString(R.string.history_local_approx)
    }
}

// ---------------------------------------------------------------------------
// Resumo do dia (Parte B.3)
// ---------------------------------------------------------------------------

@Composable
internal fun DayTimelineSummary(
    points: List<RoutePoint>,
    totalDistance: Double?,
    tripCount: Int
) {
    val firstMillis = parseTimestampMillis(points.firstOrNull()?.recorded_at)
    val lastMillis = parseTimestampMillis(points.lastOrNull()?.recorded_at)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.history_summary_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = distanceText(totalDistance),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.history_day_points_count, points.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.history_day_trips_count, tripCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (firstMillis != null) {
                        stringResource(R.string.history_day_first_time, formatTripTime(firstMillis))
                    } else {
                        "-"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (lastMillis != null) {
                        stringResource(R.string.history_day_last_time, formatTripTime(lastMillis))
                    } else {
                        "-"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Header de controles da linha do tempo (velocidade + refresh)
// ---------------------------------------------------------------------------

@Composable
internal fun TimelineControlsHeader(
    speedAlertKmh: Int,
    truncated: Boolean,
    onSpeedLimitChange: (Int) -> Unit,
    onRefresh: () -> Unit
) {
    val speedOptions = listOf(40, 50, 60, 80, 100, 0)
    val speedLabelText: @Composable (Int) -> String = { opt ->
        if (opt == 0) {
            stringResource(R.string.history_speed_off)
        } else {
            stringResource(R.string.history_speed_label, opt)
        }
    }
    var showSpeedMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.history_trips_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onRefresh) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = stringResource(R.string.history_trips_refresh),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.history_speed_limit_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box {
            TextButton(onClick = { showSpeedMenu = true }) {
                Text(
                    text = speedLabelText(speedAlertKmh),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(
                expanded = showSpeedMenu,
                onDismissRequest = { showSpeedMenu = false }
            ) {
                speedOptions.forEach { opt ->
                    DropdownMenuItem(
                        text = { Text(speedLabelText(opt)) },
                        onClick = {
                            showSpeedMenu = false
                            onSpeedLimitChange(opt)
                        }
                    )
                }
            }
        }
    }
    if (truncated) {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.history_trip_truncated, MAX_TRIP_POINTS),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------------------------------------------------------------------
// Card de percurso (Parte C) — estilo FamTrack
// ---------------------------------------------------------------------------

@Composable
internal fun TripTimelineCard(
    trip: TripSummary,
    points: List<RoutePoint>,
    origin: String,
    destination: String,
    memberName: String?,
    avatarColor: Color,
    showThumbnail: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            val title = if (origin.isBlank() || destination.isBlank() ||
                origin.equals(destination, ignoreCase = true)
            ) {
                stringResource(R.string.history_trip_generic_title)
            } else {
                stringResource(R.string.history_trip_from_to, origin, destination)
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(
                    R.string.history_trip_subtitle,
                    formatTripTime(trip.startMillis),
                    formatTripTime(trip.endMillis),
                    tripDurationText(trip.durationMillis)
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = distanceText(trip.distanceMeters),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(
                        R.string.history_metric_avg,
                        speedText(trip.avgSpeedKmh)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(
                        R.string.history_metric_max,
                        speedText(trip.maxSpeedKmh)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            TripSpeedStatus(trip.possibleSpeeding)
            Spacer(modifier = Modifier.height(12.dp))
            TripRouteThumbnail(
                points = points,
                memberName = memberName,
                avatarColor = avatarColor,
                showThumbnail = showThumbnail
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.history_trip_play),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** Badge arredondado (âmbar/teal) + nota. Âmbar/laranja para "possível excesso". */
@Composable
private fun TripSpeedStatus(possibleSpeeding: Boolean) {
    if (possibleSpeeding) {
        val bg = Color(0xFFFFF3E0)
        val fg = Color(0xFFB45309)
        Surface(
            color = bg,
            shape = RoundedCornerShape(50)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = fg
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = stringResource(R.string.history_trip_speeding),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = fg
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.history_trip_speed_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        val bg = Color(0xFFE0F5F2)
        val fg = Color(0xFF0B6B60)
        Surface(
            color = bg,
            shape = RoundedCornerShape(50)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = fg
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = stringResource(R.string.history_trip_safe),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = fg
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Miniatura do percurso (Parte C.5)
// ---------------------------------------------------------------------------

@Composable
private fun TripRouteThumbnail(
    points: List<RoutePoint>,
    memberName: String?,
    avatarColor: Color,
    showThumbnail: Boolean
) {
    val latLngs = remember(points) {
        (if (points.size > 600) decimate(points, 600) else points)
            .map { LatLng(it.latitude, it.longitude) }
    }

    if (!showThumbnail || latLngs.size < 2) {
        FallbackTripThumbnail()
        return
    }

    val (thumbLat, thumbLon, thumbZoom) = computeRouteCenterAndZoom(latLngs)
    val camera = rememberCameraPositionState(
        key = "trip_thumbnail",
        init = {
            position = CameraPosition.fromLatLngZoom(LatLng(thumbLat, thumbLon), thumbZoom)
        }
    )
    LaunchedEffect(camera, latLngs) {
        val (clat, clon, zoom) = computeRouteCenterAndZoom(latLngs)
        camera.position = CameraPosition.fromLatLngZoom(LatLng(clat, clon), zoom)
    }
    val density = LocalDensity.current.density
    val avatarDescriptor = remember(memberName, avatarColor, density) {
        BitmapDescriptorFactory.fromBitmap(
            playbackAvatarBitmap(density, memberName, avatarColor.toArgb())
        )
    }

    GoogleMap(
        modifier = Modifier
            .fillMaxWidth()
            .height(130.dp)
            .clip(RoundedCornerShape(12.dp)),
        cameraPositionState = camera,
        properties = MapProperties(mapType = MapType.NORMAL),
        uiSettings = MapUiSettings(
            zoomControlsEnabled = false,
            scrollGesturesEnabled = false,
            zoomGesturesEnabled = false,
            rotationGesturesEnabled = false,
            tiltGesturesEnabled = false,
            mapToolbarEnabled = false,
            compassEnabled = false
        )
    ) {
        Polyline(
            points = latLngs,
            color = MaterialTheme.colorScheme.primary,
            width = 5f
        )
        val first = latLngs.first()
        val last = latLngs.last()
        Marker(
            state = MarkerState(position = first),
            title = stringResource(R.string.history_start_marker),
            icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)
        )
        Marker(
            state = MarkerState(position = last),
            title = stringResource(R.string.history_end_marker),
            icon = avatarDescriptor,
            anchor = Offset(0.5f, 0.5f),
            zIndex = 5f
        )
    }
}

/** Fallback visual simples para percursos além dos 3 mais recentes. */
@Composable
private fun FallbackTripThumbnail() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(130.dp)
            .clip(RoundedCornerShape(12.dp)),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Route,
                    contentDescription = stringResource(R.string.history_fallback_thumbnail),
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.history_fallback_thumbnail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Mapa da rota do dia (mantém a visão interativa do dia inteiro)
// ---------------------------------------------------------------------------

@Composable
internal fun DayRouteMapCard(
    orderedPoints: List<RoutePoint>,
    geofences: List<Geofence>,
    onPlay: () -> Unit
) {
    val display = remember(orderedPoints) {
        if (orderedPoints.size > 1200) decimate(orderedPoints, 1200) else orderedPoints
    }
    val latLngs = remember(display) { display.map { LatLng(it.latitude, it.longitude) } }
    val camera = rememberCameraPositionState()

    LaunchedEffect(orderedPoints) {
        if (latLngs.isNotEmpty()) {
            val (clat, clon, zoom) = computeRouteCenterAndZoom(latLngs)
            camera.animate(
                CameraUpdateFactory.newLatLngZoom(LatLng(clat, clon), zoom),
                700
            )
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.history_route_day_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onPlay) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.history_route_day_play))
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
            ) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = camera,
                    properties = MapProperties(mapType = MapType.NORMAL)
                ) {
                    if (latLngs.size >= 2) {
                        Polyline(
                            points = latLngs,
                            color = MaterialTheme.colorScheme.primary,
                            width = 6f
                        )
                    }
                    val first = orderedPoints.firstOrNull()
                    if (first != null) {
                        Marker(
                            state = MarkerState(position = LatLng(first.latitude, first.longitude)),
                            title = stringResource(R.string.history_start_marker),
                            icon = BitmapDescriptorFactory.defaultMarker(
                                BitmapDescriptorFactory.HUE_GREEN
                            )
                        )
                    }
                    if (orderedPoints.size > 1) {
                        val last = orderedPoints.lastOrNull()
                        if (last != null) {
                            Marker(
                                state = MarkerState(position = LatLng(last.latitude, last.longitude)),
                                title = stringResource(R.string.history_end_marker),
                                icon = BitmapDescriptorFactory.defaultMarker(
                                    BitmapDescriptorFactory.HUE_RED
                                )
                            )
                        }
                    }
                    geofences.forEach { gf ->
                        val center = LatLng(gf.center_lat, gf.center_lon)
                        val strokeColor = try {
                            Color(android.graphics.Color.parseColor(gf.color))
                        } catch (e: Exception) {
                            Color.Red
                        }
                        Circle(
                            center = center,
                            radius = gf.radius_meters,
                            strokeColor = strokeColor,
                            fillColor = strokeColor.copy(alpha = 0.25f),
                            strokeWidth = 3f
                        )
                        Marker(state = MarkerState(position = center), title = gf.name)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Player do percurso em tela cheia (Parte E)
// ---------------------------------------------------------------------------

/**
 * Player único de um percurso. Mapa ocupa a maior parte da tela; painel de
 * controles compacto (máx. ~96dp) na parte inferior. Fecha com o botão/X ou
 * gesto "voltar". Com < 2 pontos, não há o que reproduzir (mensagem própria).
 */
@Composable
internal fun TripPlayerScreen(
    points: List<RoutePoint>,
    title: String,
    memberName: String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val playPoints = remember(points) {
        if (points.size > 800) decimate(points, 800) else points
    }
    val latLngs = remember(playPoints) { playPoints.map { LatLng(it.latitude, it.longitude) } }
    val playback = rememberRoutePlayback(playPoints, latLngs, memberName, primaryColor)
    val camera = rememberCameraPositionState()

    LaunchedEffect(playPoints) {
        when {
            latLngs.size >= 2 -> {
                val (clat, clon, zoom) = computeRouteCenterAndZoom(latLngs)
                camera.animate(
                    CameraUpdateFactory.newLatLngZoom(LatLng(clat, clon), zoom),
                    500
                )
            }
            latLngs.size == 1 -> camera.animate(
                CameraUpdateFactory.newLatLngZoom(latLngs[0], 16f),
                500
            )
        }
    }

    BackHandler(onBack = onClose)

    Box(modifier = modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = camera,
            properties = MapProperties(mapType = MapType.NORMAL)
        ) {
            if (latLngs.size >= 2) {
                Polyline(
                    points = latLngs,
                    color = primaryColor.copy(alpha = 0.35f),
                    width = 6f
                )
            }
            if (playback.hasRoute) {
                PlaybackMapLayers(controller = playback, primaryColor = primaryColor)
            }
        }
        RoutePlaybackEngine(playback)
        RoutePlaybackCameraFollow(playback, camera)

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(10.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shape = RoundedCornerShape(50),
                tonalElevation = 3.dp
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 14.dp)
                ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.history_player_close_cd),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                }
            }
        }

        if (playback.hasRoute) {
            RoutePlaybackControls(
                controller = playback,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        } else {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.history_insufficient_trip),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
                )
            }
        }
    }
}